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
use crate::route::edge_line;
use crate::scoring::PARAMS;
use crate::section::{Direction, Section, Status};
use crate::{CoreError, Engine};

/// Favourite edges of one region.
#[derive(Debug, Clone, Default)]
pub struct Favourites {
    /// The region the edge ids belong to (see `rematch::region_key`);
    /// empty for [`Favourites::none`], which fits any region.
    region_key: String,
    /// Bonus of every edge (see `ScoringParams::favourite_bonus`), indexed
    /// by edge id; empty when no edge is a favourite.
    bonus: Vec<f32>,
    /// The stretch of each favourite edge that lies on a section, as
    /// fractions of its length in travel order.
    coverage: HashMap<u32, (f32, f32)>,
    /// The largest bonus of any edge.
    max_bonus: f64,
}

/// A way span of a section, as the build looks it up.
struct Span {
    lo: u32,
    hi: u32,
    /// `Some(true)` if the section may only be ridden along the OSM way,
    /// `Some(false)` only against it, `None` both ways.
    along_way: Option<bool>,
    bonus: f64,
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
        for s in sections.iter().filter(|s| s.status == Status::Ok) {
            let bonus = PARAMS.bonus(s.rating);
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
                    lo,
                    hi,
                    along_way,
                    bonus,
                });
            }
        }
        let mut favourites = Self {
            region_key: crate::rematch::region_key(engine),
            ..Self::default()
        };
        if by_way.is_empty() {
            return favourites;
        }

        let region = engine.region();
        let mut bonus = vec![0.0f32; region.edge_count()];
        for (id, r) in region.way_refs().iter().enumerate() {
            let Some(spans) = by_way.get(&r.way_id) else {
                continue;
            };
            let (lo, hi) = (r.from_idx.min(r.to_idx), r.from_idx.max(r.to_idx));
            if lo == hi {
                continue;
            }
            let along_way = r.to_idx > r.from_idx;
            let Ok(id) = u32::try_from(id) else {
                break; // the region format caps edge ids below this
            };
            let mut best = (0.0f64, (0.0f64, 0.0f64)); // (bonus, stretch)
            for s in spans {
                if s.along_way.is_some_and(|a| a != along_way) {
                    continue;
                }
                let (from, to) = (lo.max(s.lo), hi.min(s.hi));
                if from >= to {
                    continue; // apart, or touching at one node
                }
                let stretch = if from == lo && to == hi {
                    (0.0, 1.0)
                } else {
                    covered(engine, id, r, from, to)
                };
                let b = (stretch.1 - stretch.0) * s.bonus;
                if b > best.0 {
                    best = (b, stretch);
                }
            }
            if best.0 > 0.0 {
                // Bonuses and fractions lie in 0–1, where an f32 is exact
                // enough.
                bonus[id as usize] = best.0 as f32;
                favourites
                    .coverage
                    .insert(id, (best.1.0 as f32, best.1.1 as f32));
                favourites.max_bonus = favourites.max_bonus.max(best.0);
            }
        }
        if !favourites.coverage.is_empty() {
            favourites.bonus = bonus;
        }
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
            || (!self.bonus.is_empty() && self.bonus.len() != engine.region().edge_count())
        {
            return Err(CoreError::InvalidArgument(
                "favourites were built for another region; build them again".into(),
            ));
        }
        Ok(())
    }

    /// The bonus of edge `id` (0.0 when it is no favourite).
    pub(crate) fn bonus(&self, id: u32) -> f64 {
        self.bonus.get(id as usize).map_or(0.0, |&b| f64::from(b))
    }

    /// Share of edge `id`'s length on a favourite section.
    #[cfg(test)]
    pub(crate) fn coverage(&self, id: u32) -> f64 {
        self.covered_between(id, 0.0, 1.0)
    }

    /// Share of edge `id`'s length between fractions `from` and `to` (in
    /// travel order) that lies on a favourite section.
    pub(crate) fn covered_between(&self, id: u32, from: f64, to: f64) -> f64 {
        self.coverage.get(&id).map_or(0.0, |&(a, b)| {
            (to.min(f64::from(b)) - from.max(f64::from(a))).max(0.0)
        })
    }

    /// The largest bonus of any edge.
    pub(crate) fn max_bonus(&self) -> f64 {
        self.max_bonus
    }
}

/// The stretch of edge `id` between way nodes `from` and `to` (`from <
/// to`, both within the edge's way ref `r`), as fractions of its length in
/// travel order. Shape point `k` of the edge, in travel order, is way node
/// `r.from_idx ± k`.
fn covered(engine: &Engine, id: u32, r: &WayRef, from: u32, to: u32) -> (f64, f64) {
    let region = engine.region();
    let Some(e) = region.edges().get(id as usize) else {
        return (0.0, 0.0);
    };
    let line = edge_line(region, e);
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
