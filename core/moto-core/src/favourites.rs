// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The rider's favourite sections as seen by the router (PRD R6, M2a): a
//! bonus per region edge, built from the sections' OSM way spans. It is an
//! overlay applied at query time; the region graph is never rebuilt when
//! favourites change. Build a new one whenever the sections or the region
//! change.

use std::collections::HashMap;

use crate::geo::haversine_m;
use crate::region::format::WayRef;
use crate::region::format::edge_flags::REVERSED;
use crate::route::{edge_line, is_unpaved};
use crate::scoring::PARAMS;
use crate::section::{Direction, Rating, Section, Status};
use crate::{CoreError, Engine, LatLon};

/// Favourite edges of one region.
#[derive(Debug, Clone, Default)]
pub struct Favourites {
    /// The region the edge ids belong to (see `rematch::region_key`);
    /// empty for [`Favourites::none`], which fits any region.
    region_key: String,
    /// One bit per edge id, set for the edges in [`Self::weights`]
    /// (empty when there are none): the search tests the bit for every
    /// road and looks in the map only for the few favourite ones. A
    /// dense array of both values took 8 bytes for every road of every
    /// region (82 MB for four countries) for a few hundred favourites.
    marked: Vec<u64>,
    /// Of each edge on a section: its bonus at full pull (see
    /// `ScoringParams::bonus`, 0 when it is ridden the wrong way) and the
    /// share of its length on a section, whichever way the section may
    /// be ridden (what avoiding favourites keeps off).
    weights: HashMap<u32, (f32, f32)>,
    /// How many edges the region it was built for has.
    edge_count: usize,
    /// The stretch of each favourite edge that lies on a section, as
    /// fractions of its length in travel order, the section's rating
    /// weight (see `ScoringParams::favourite_weight`) and its rating.
    coverage: HashMap<u32, (f32, f32, f32, Rating)>,
    /// The largest bonus of any edge.
    max_bonus: f64,
    /// The highest speed of any favourite edge, km/h (0 with none): the
    /// fastest a road worth that much can be ridden (see the route
    /// search's estimate).
    max_speed_kmh: f64,
    /// The middle of each matched section and its rating weight: places
    /// a round trip may go through (ADR-0007).
    anchors: Vec<(LatLon, f64)>,
    /// The gravel stretches of each matched section that has any.
    gravel: Vec<SectionGravel>,
}

/// Where a favourite section runs on gravel or other unpaved road, for
/// drawing it (and hiding the section while gravel is avoided).
#[derive(Debug, Clone, PartialEq)]
pub struct SectionGravel {
    pub section_id: i64,
    /// Metres of the section on unpaved road, and its whole length.
    pub unpaved_m: f64,
    pub length_m: f64,
    /// The unpaved stretches, each at least two points.
    pub parts: Vec<Vec<LatLon>>,
}

/// A way span of a section, as the build looks it up.
struct Span {
    /// Index of the section in the build's input.
    section: usize,
    lo: u32,
    hi: u32,
    /// `Some(true)` if the section may only be ridden along the OSM way,
    /// `Some(false)` only against it, `None` both ways.
    along_way: Option<bool>,
    bonus: f64,
    rating: Rating,
}

impl Favourites {
    /// No favourites: plain fastest routes, on any region.
    pub fn none() -> Self {
        Self::default()
    }

    /// The favourite edges of `engine`'s region for `sections`. Only
    /// sections matched to this region count: those waiting for a re-match
    /// or that no longer fit are left out. A section rated one way only
    /// gives its bonus in its direction; where sections overlap, the best
    /// bonus wins.
    pub fn build(engine: &Engine, sections: &[Section]) -> Self {
        let mut by_way: HashMap<i64, Vec<Span>> = HashMap::new();
        let mut anchors = Vec::new();
        for (i, s) in sections
            .iter()
            .enumerate()
            .filter(|(_, s)| s.status == Status::Ok)
        {
            let bonus = PARAMS.bonus(s.rating);
            if let Some(mid) = crate::geo::polyline_slice(&s.geometry, 0.5, 0.5).first() {
                anchors.push((*mid, bonus / PARAMS.max_pull));
            }
            for w in &s.ways {
                let (lo, hi) = (w.from_idx.min(w.to_idx), w.from_idx.max(w.to_idx));
                if lo == hi {
                    continue;
                }
                let along_way = match s.direction {
                    Direction::Both => None,
                    Direction::Forward => Some(w.to_idx > w.from_idx),
                };
                by_way.entry(w.way_id).or_default().push(Span {
                    section: i,
                    lo,
                    hi,
                    along_way,
                    bonus,
                    rating: s.rating,
                });
            }
        }
        let mut favourites = Self {
            region_key: crate::rematch::region_key(engine),
            anchors,
            ..Self::default()
        };
        if by_way.is_empty() {
            return favourites;
        }

        let region = engine.net();
        let mut weights: HashMap<u32, (f32, f32)> = HashMap::new();
        let mut gravel: HashMap<usize, SectionGravel> = HashMap::new();
        for id in 0..region.edge_count() as u32 {
            let r = &region.way_ref(id);
            let Some(spans) = by_way.get(&r.way_id) else {
                continue;
            };
            let (lo, hi) = (r.from_idx.min(r.to_idx), r.from_idx.max(r.to_idx));
            if lo == hi {
                continue;
            }
            let along_way = r.to_idx > r.from_idx;
            // (bonus, stretch, weight, rating)
            let mut best = (0.0f64, (0.0f64, 0.0f64), 0.0f64, Rating::Good);
            let mut on_any = 0.0f64;
            for s in spans {
                let (from, to) = (lo.max(s.lo), hi.min(s.hi));
                if from >= to {
                    continue; // apart, or touching at one node
                }
                let stretch = if from == lo && to == hi {
                    (0.0, 1.0)
                } else {
                    covered(engine, id, r, from, to)
                };
                on_any = on_any.max(stretch.1 - stretch.0);
                if s.along_way.is_some_and(|a| a != along_way) {
                    continue;
                }
                // Gravel is drawn once per road: from the edge along the
                // geometry, or from the one direction a one-way section
                // takes.
                let e = &region.edge(id);
                let once = s.along_way.is_some() || e.flags & REVERSED == 0;
                if once && is_unpaved(e) && stretch.1 > stretch.0 {
                    let part =
                        crate::geo::polyline_slice(&edge_line(region, e), stretch.0, stretch.1);
                    let g = gravel.entry(s.section).or_insert_with(|| SectionGravel {
                        section_id: sections[s.section].id,
                        unpaved_m: 0.0,
                        length_m: crate::geo::polyline_length_m(&sections[s.section].geometry),
                        parts: Vec::new(),
                    });
                    g.unpaved_m += (stretch.1 - stretch.0) * f64::from(e.length_dm) / 10.0;
                    if part.len() >= 2 {
                        g.parts.push(part);
                    }
                }
                let b = (stretch.1 - stretch.0) * s.bonus;
                if b > best.0 {
                    best = (b, stretch, s.bonus / PARAMS.max_pull, s.rating);
                }
            }
            if on_any > 0.0 {
                weights.entry(id).or_default().1 = on_any as f32;
            }
            if best.0 > 0.0 {
                // Bonuses and fractions lie in 0–1, where an f32 is exact
                // enough.
                weights.entry(id).or_default().0 = best.0 as f32;
                favourites.coverage.insert(
                    id,
                    (best.1.0 as f32, best.1.1 as f32, best.2 as f32, best.3),
                );
                favourites.max_bonus = favourites.max_bonus.max(best.0);
                favourites.max_speed_kmh = favourites
                    .max_speed_kmh
                    .max(f64::from(engine.net().edge(id).speed_kmh));
            }
        }
        if !weights.is_empty() {
            let mut marked = vec![0u64; region.edge_count().div_ceil(64)];
            for &id in weights.keys() {
                marked[(id >> 6) as usize] |= 1 << (id & 63);
            }
            favourites.marked = marked;
            favourites.weights = weights;
            favourites.edge_count = region.edge_count();
        }
        let mut gravel: Vec<SectionGravel> = gravel.into_values().collect();
        gravel.sort_by_key(|g| g.section_id);
        favourites.gravel = gravel;
        favourites
    }

    /// Whether no edge is a favourite.
    pub fn is_empty(&self) -> bool {
        self.coverage.is_empty()
    }

    /// How many edges (in either direction) are favourites.
    pub fn edge_count(&self) -> usize {
        self.coverage.len()
    }

    /// Refuses favourites built for another region: their edge ids would
    /// point at the wrong roads.
    pub(crate) fn check(&self, engine: &Engine) -> Result<(), CoreError> {
        if self.region_key.is_empty() && self.is_empty() {
            return Ok(());
        }
        if self.region_key != crate::rematch::region_key(engine)
            || (!self.weights.is_empty() && self.edge_count != engine.net().edge_count())
        {
            return Err(CoreError::InvalidArgument(
                "favourites were built for another region; build them again".into(),
            ));
        }
        Ok(())
    }

    /// The bonus of edge `id` (0.0 when it is no favourite).
    pub(crate) fn bonus(&self, id: u32) -> f64 {
        self.weight(id).map_or(0.0, |w| f64::from(w.0))
    }

    /// Share of edge `id`'s length on a section, whichever way the
    /// section may be ridden.
    pub(crate) fn share(&self, id: u32) -> f64 {
        self.weight(id).map_or(0.0, |w| f64::from(w.1))
    }

    /// Bonus and share of edge `id`, when it is on a section.
    #[inline]
    fn weight(&self, id: u32) -> Option<(f32, f32)> {
        let word = self.marked.get((id >> 6) as usize)?;
        if word >> (id & 63) & 1 == 0 {
            return None;
        }
        self.weights.get(&id).copied()
    }

    /// Share of edge `id`'s length on a favourite section.
    #[cfg(test)]
    pub(crate) fn coverage(&self, id: u32) -> f64 {
        self.covered_between(id, 0.0, 1.0)
    }

    /// Share of edge `id`'s length between fractions `from` and `to` (in
    /// travel order) that lies on a favourite section.
    pub(crate) fn covered_between(&self, id: u32, from: f64, to: f64) -> f64 {
        self.coverage.get(&id).map_or(0.0, |&(a, b, _, _)| {
            (to.min(f64::from(b)) - from.max(f64::from(a))).max(0.0)
        })
    }

    /// The part of the stretch between fractions `from` and `to` (in
    /// travel order) of edge `id` that lies on a favourite section, and
    /// the section's rating.
    pub(crate) fn covered_part(&self, id: u32, from: f64, to: f64) -> Option<(f64, f64, Rating)> {
        let &(a, b, _, rating) = self.coverage.get(&id)?;
        let (lo, hi) = (from.max(f64::from(a)), to.min(f64::from(b)));
        (hi > lo).then_some((lo, hi, rating))
    }

    /// As [`Self::covered_between`], weighted by the section's rating
    /// (epic 1): how much favourite riding the stretch is worth.
    pub(crate) fn value_between(&self, id: u32, from: f64, to: f64) -> f64 {
        let weight = self
            .coverage
            .get(&id)
            .map_or(0.0, |&(_, _, w, _)| f64::from(w));
        self.covered_between(id, from, to) * weight
    }

    /// The gravel stretches of the sections that run on any, by section
    /// id.
    pub fn gravel(&self) -> &[SectionGravel] {
        &self.gravel
    }

    /// The middle of each matched section and its rating weight (epic 1).
    pub(crate) fn anchors(&self) -> &[(LatLon, f64)] {
        &self.anchors
    }

    /// The largest bonus of any edge.
    pub(crate) fn max_bonus(&self) -> f64 {
        self.max_bonus
    }

    /// The highest speed of any favourite edge, km/h (0 with none).
    pub(crate) fn max_speed_kmh(&self) -> f64 {
        self.max_speed_kmh
    }
}

/// The stretch of edge `id` between way nodes `from` and `to` (`from <
/// to`, both within the edge's way ref `r`), as fractions of its length in
/// travel order. Shape point `k` of the edge, in travel order, is way node
/// `r.from_idx ± k`.
fn covered(engine: &Engine, id: u32, r: &WayRef, from: u32, to: u32) -> (f64, f64) {
    let region = engine.net();
    let Some(e) = region.get_edge(id) else {
        return (0.0, 0.0);
    };
    let line = edge_line(region, &e);
    if line.len() < 2 {
        return (0.0, 0.0);
    }
    let last = line.len() - 1;
    let k = |node: u32| (node.abs_diff(r.from_idx) as usize).min(last);
    let (a, b) = (k(from).min(k(to)), k(from).max(k(to)));
    let length = |i: usize, j: usize| -> f64 {
        line[i..=j]
            .windows(2)
            .map(|w| haversine_m(w[0], w[1]))
            .sum()
    };
    let total = length(0, last);
    if total <= 0.0 {
        return (0.0, 1.0);
    }
    let start = (length(0, a) / total).clamp(0.0, 1.0);
    (start, (start + length(a, b) / total).clamp(start, 1.0))
}

#[cfg(test)]
mod tests;
