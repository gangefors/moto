// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Words for a stretch of road the rider can recognise (ADR-0005, format
//! 1.2): which roads it runs on, by number or name, and which places it
//! runs between. Saved sections, routes and rides have no name of their
//! own worth reading; this gives them one ("13 · Höör → Sjöbo").

use crate::geo::haversine_m;
use crate::region::Region;
use crate::region::format::{COORD_SCALE, NO_NAME, Place, PlaceKind};
use crate::section::MAX_SECTION_POINTS;
use crate::{CoreError, LatLon};

/// A road a line runs on: its number and name, either or both.
#[derive(Debug, Clone, PartialEq)]
pub struct RoadLabel {
    /// As signed: `13`, `E22`, `1177` (see [`signed_ref`]).
    pub road_ref: Option<String>,
    /// E.g. `Kvärnbyvägen`: the name of the longest named part.
    pub name: Option<String>,
    /// How much of the line runs on it, 0–1.
    pub share: f64,
}

/// A place near a point of the line.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaceName {
    pub name: String,
    pub kind: PlaceKind,
    /// From the point, metres.
    pub distance_m: f64,
}

/// What a line is, in words. Everything is optional: a region file
/// without names (before format 1.2), or country without named roads or
/// places, gives an empty description.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Description {
    /// The roads it runs on most, the most first; at most [`MAX_ROADS`],
    /// each on at least [`MIN_ROAD_SHARE`] of it.
    pub roads: Vec<RoadLabel>,
    /// The places nearest its start and its end (the same place for a
    /// short line or a loop).
    pub start: Option<PlaceName>,
    pub end: Option<PlaceName>,
    /// How curvy the roads under it are, 0–1, as a route's `curvy_share`
    /// counts it. Known on every region file, names or not.
    pub curvy_share: f64,
}

/// Most roads named for one line.
pub const MAX_ROADS: usize = 2;
/// Least share of a line a road needs to be named.
pub const MIN_ROAD_SHARE: f64 = 0.2;
/// Samples along the line, one per this many metres (within the bounds
/// below): the roads are found by snapping them.
const SAMPLE_STEP_M: f64 = 150.0;
const MIN_SAMPLES: usize = 4;
const MAX_SAMPLES: usize = 80;
/// A sample further than this from any road counts as off the roads.
const SNAP_M: f64 = 40.0;

/// How far a place of each kind may be and still name a point, metres,
/// and how much nearer a smaller place must be to win over a bigger one.
/// A hamlet names only a point right by it: the map labels it there, so
/// it is what the rider sees at that end (a section ending in a hamlet
/// is that hamlet's, not a town's a few kilometres away).
fn reach(kind: PlaceKind) -> (f64, f64) {
    match kind {
        PlaceKind::City => (20_000.0, 3.0),
        PlaceKind::Town => (12_000.0, 2.0),
        PlaceKind::Village => (6_000.0, 1.3),
        PlaceKind::Hamlet => (1_500.0, 1.0),
    }
}

/// Describes `line` (2 to [`MAX_SECTION_POINTS`] valid points) on `region`.
pub(crate) fn describe(region: &Region, line: &[LatLon]) -> Result<Description, CoreError> {
    if line.len() < 2 || line.len() > MAX_SECTION_POINTS {
        return Err(CoreError::InvalidArgument(format!(
            "a line to describe needs 2–{MAX_SECTION_POINTS} points, got {}",
            line.len()
        )));
    }
    for p in line {
        p.validate()?;
    }
    let under = roads_under(region, line);
    let curvy_share = curvy_share(region, &under);
    if !region.has_names() {
        return Ok(Description {
            curvy_share,
            ..Description::default()
        });
    }
    Ok(Description {
        roads: roads(region, &under),
        start: nearest_place(region, line[0]),
        end: nearest_place(region, line[line.len() - 1]),
        curvy_share,
    })
}

/// The edge under each sample of `line` (`None` where it is off the
/// roads).
fn roads_under(region: &Region, line: &[LatLon]) -> Vec<Option<u32>> {
    samples(line)
        .into_iter()
        .map(|p| {
            crate::snap::snap(region, p, SNAP_M)
                .ok()
                .map(|at| at.edge)
                .filter(|&e| (e as usize) < region.edge_count())
        })
        .collect()
}

/// The curviness of the edges under the samples, averaged over all of
/// them (a sample off the roads counts as straight).
fn curvy_share(region: &Region, under: &[Option<u32>]) -> f64 {
    if under.is_empty() {
        return 0.0;
    }
    let sum: f64 = under
        .iter()
        .flatten()
        .filter_map(|&id| {
            let e = region.edges().get(id as usize)?;
            let m = region.curvature().get(id as usize)?;
            let length_m = f64::from(e.length_dm) / 10.0;
            Some(crate::scoring::PARAMS.curviness(m, e.class, e.speed_kmh, e.flags, length_m))
        })
        .sum();
    (sum / under.len() as f64).clamp(0.0, 1.0)
}

/// Points spread evenly along `line`, each standing for the same length.
fn samples(line: &[LatLon]) -> Vec<LatLon> {
    let lengths: Vec<f64> = line.windows(2).map(|w| haversine_m(w[0], w[1])).collect();
    let total: f64 = lengths.iter().sum();
    let n = ((total / SAMPLE_STEP_M).ceil() as usize).clamp(MIN_SAMPLES, MAX_SAMPLES);
    let mut out = Vec::with_capacity(n);
    let (mut seg, mut before) = (0usize, 0.0f64);
    for i in 0..n {
        let at = total * (i as f64 + 0.5) / n as f64;
        while seg + 1 < lengths.len() && before + lengths[seg] < at {
            before += lengths[seg];
            seg += 1;
        }
        let (a, b) = (line[seg], line[seg + 1]);
        let t = if lengths[seg] > 0.0 {
            ((at - before) / lengths[seg]).clamp(0.0, 1.0)
        } else {
            0.0
        };
        out.push(LatLon {
            lat: a.lat + (b.lat - a.lat) * t,
            lon: a.lon + (b.lon - a.lon) * t,
        });
    }
    out
}

/// A road found under the samples: its number and name ids (the key),
/// its share of the line, and the shares of the names along it.
type Found = (u32, u32, f64, Vec<(u32, f64)>);

/// The roads the samples lie on most (`under`, see [`roads_under`]). A
/// numbered road is one road whatever its streets are called along the
/// way (a road through a town).
fn roads(region: &Region, under: &[Option<u32>]) -> Vec<RoadLabel> {
    if under.is_empty() {
        return Vec::new();
    }
    let each = 1.0 / under.len() as f64;
    // Per road (by number, else by name): its share, and its names' shares.
    let mut found: Vec<Found> = Vec::new();
    for &id in under.iter().flatten() {
        let Some(edge) = region.edges().get(id as usize) else {
            continue;
        };
        let g = region.geometry_name(edge.geometry);
        if g.road_ref == NO_NAME && g.name == NO_NAME {
            continue;
        }
        // By number when it has one, else by name.
        let key = if g.road_ref != NO_NAME {
            (g.road_ref, NO_NAME)
        } else {
            (NO_NAME, g.name)
        };
        let road = match found.iter_mut().position(|r| (r.0, r.1) == key) {
            Some(i) => &mut found[i],
            None => {
                found.push((key.0, key.1, 0.0, Vec::new()));
                found.last_mut().expect("just pushed")
            }
        };
        road.2 += each;
        if g.name != NO_NAME {
            match road.3.iter_mut().find(|(n, _)| *n == g.name) {
                Some(n) => n.1 += each,
                None => road.3.push((g.name, each)),
            }
        }
    }
    // Most first; equal shares by string id, so the result is stable.
    found.sort_by(|a, b| b.2.total_cmp(&a.2).then((a.0, a.1).cmp(&(b.0, b.1))));
    found
        .into_iter()
        .filter(|r| r.2 >= MIN_ROAD_SHARE - 1e-9)
        .take(MAX_ROADS)
        .map(|(road_ref, _, share, names)| {
            let name = names
                .iter()
                .max_by(|a, b| a.1.total_cmp(&b.1).then(b.0.cmp(&a.0)))
                .and_then(|&(n, _)| region.string(n))
                .map(str::to_owned);
            RoadLabel {
                road_ref: region.string(road_ref).map(signed_ref),
                name,
                share: share.min(1.0),
            }
        })
        .collect()
}

/// A road number as it is signed: OSM writes Swedish county roads with
/// their county's letter (`M 1121`) and European roads with a space
/// (`E 22`); the signs say `1121` and `E22`. Other numbers stay as they
/// are.
pub fn signed_ref(r: &str) -> String {
    match r.split_once(' ') {
        Some((pre, num))
            if !pre.is_empty()
                && pre.len() <= 2
                && pre.chars().all(|c| c.is_ascii_uppercase())
                && !num.is_empty()
                && num.chars().all(|c| c.is_ascii_digit()) =>
        {
            if pre == "E" {
                format!("E{num}")
            } else {
                num.to_owned()
            }
        }
        _ => r.to_owned(),
    }
}

/// The place that best names `p`: the nearest, but a bigger place wins
/// over a smaller one unless the smaller one is clearly nearer (see
/// [`reach`]). None within reach gives `None`.
fn nearest_place(region: &Region, p: LatLon) -> Option<PlaceName> {
    let places = region.places();
    // Places are sorted by latitude: look only within the widest reach.
    let widest = reach(PlaceKind::City).0;
    let span = (widest / 111_000.0 * COORD_SCALE).ceil() as i64;
    let lat = (p.lat * COORD_SCALE).round() as i64;
    let lo = places.partition_point(|q| i64::from(q.pos.lat) < lat - span);
    let hi = places.partition_point(|q| i64::from(q.pos.lat) <= lat + span);
    let mut best: Option<(f64, &Place, PlaceKind, f64)> = None;
    for q in places.get(lo..hi).unwrap_or(&[]) {
        let Some(kind) = PlaceKind::from_u8(q.kind) else {
            continue;
        };
        let (max_m, weight) = reach(kind);
        let d = haversine_m(
            p,
            LatLon {
                lat: f64::from(q.pos.lat) / COORD_SCALE,
                lon: f64::from(q.pos.lon) / COORD_SCALE,
            },
        );
        if d > max_m {
            continue;
        }
        let score = d / weight;
        if best.is_none_or(|b| score < b.0) {
            best = Some((score, q, kind, d));
        }
    }
    let (_, q, kind, distance_m) = best?;
    Some(PlaceName {
        name: region.string(q.name)?.to_owned(),
        kind,
        distance_m,
    })
}

#[cfg(test)]
mod tests;
