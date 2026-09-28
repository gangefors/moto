// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Which saved sections the rider has ridden, and when last, from the
//! recorded and imported rides: for listing favourites not ridden for a
//! while, and later for finding new roads.
//!
//! A section counts as ridden on a ride when most of it lies along the
//! ride's line: at least [`RIDDEN_SHARE`] of points spread every
//! [`SAMPLE_M`] along the section lie within [`NEAR_M`] of it. Either
//! direction counts. The ride's line is filled in between its fixes, but
//! not across gaps longer than [`MAX_GAP_M`] (lost GPS, a ferry): nothing
//! there counts as ridden, and a damaged or hostile file can't make the
//! work grow without bound.

use std::collections::HashMap;

use crate::LatLon;
use crate::geo::{densify, haversine_m};
use crate::section::Section;

/// Share of a section a ride must follow to have ridden it.
pub const RIDDEN_SHARE: f64 = 0.8;
/// How far a ride may be from the section and still be on it: GPS error
/// and the width of the road.
pub const NEAR_M: f64 = 35.0;
/// Points along a section, one per this many metres.
pub const SAMPLE_M: f64 = 100.0;
/// A gap between two fixes longer than this is not filled in.
pub const MAX_GAP_M: f64 = 1_000.0;
/// The ride's line is filled in to points this far apart.
const FILL_M: f64 = 15.0;

/// How often a section was ridden, and when last (the start of the latest
/// ride on it, seconds since the Unix epoch).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ridden {
    pub section_id: i64,
    pub times: u32,
    pub last_at: Option<i64>,
}

/// A ride: when it started and its line (the fixes in order).
#[derive(Debug, Clone, Copy)]
pub struct RideLine<'a> {
    pub started_at: i64,
    pub line: &'a [LatLon],
}

/// For each of `sections` (in order), how often it was ridden on `rides`
/// and when last.
pub fn ridden(sections: &[Section], rides: &[RideLine<'_>]) -> Vec<Ridden> {
    let samples: Vec<(Vec<LatLon>, Bounds)> = sections
        .iter()
        .map(|s| {
            let pts = sample(&s.geometry);
            let b = Bounds::of(&pts);
            (pts, b)
        })
        .collect();
    let mut out: Vec<Ridden> = sections
        .iter()
        .map(|s| Ridden {
            section_id: s.id,
            times: 0,
            last_at: None,
        })
        .collect();
    for ride in rides {
        let near = NearGrid::of(ride.line);
        let Some(bounds) = near.bounds else { continue };
        for ((pts, b), stats) in samples.iter().zip(out.iter_mut()) {
            if pts.is_empty() || !bounds.overlaps(b) {
                continue;
            }
            let on = pts.iter().filter(|&&p| near.has_near(p)).count();
            if on as f64 >= pts.len() as f64 * RIDDEN_SHARE - 1e-9 {
                stats.times += 1;
                stats.last_at = Some(
                    stats
                        .last_at
                        .map_or(ride.started_at, |t| t.max(ride.started_at)),
                );
            }
        }
    }
    out
}

/// Points every [`SAMPLE_M`] along `line`, its ends included.
fn sample(line: &[LatLon]) -> Vec<LatLon> {
    if line.len() < 2 {
        return line.to_vec();
    }
    let dense = densify(line, SAMPLE_M);
    // Densify gives at least one point per segment; thin dense
    // geometries (a point every few metres) to about one per SAMPLE_M.
    let mut out = vec![dense[0]];
    for &p in &dense[1..] {
        if haversine_m(*out.last().expect("not empty"), p) >= SAMPLE_M * 0.9 {
            out.push(p);
        }
    }
    let last = *dense.last().expect("not empty");
    if out.last() != Some(&last) {
        out.push(last);
    }
    out
}

/// A bounding box with a [`NEAR_M`] margin.
#[derive(Debug, Clone, Copy)]
struct Bounds {
    min_lat: f64,
    min_lon: f64,
    max_lat: f64,
    max_lon: f64,
}

impl Bounds {
    fn of(points: &[LatLon]) -> Self {
        let mut b = Bounds {
            min_lat: f64::INFINITY,
            min_lon: f64::INFINITY,
            max_lat: f64::NEG_INFINITY,
            max_lon: f64::NEG_INFINITY,
        };
        for p in points {
            b.min_lat = b.min_lat.min(p.lat);
            b.min_lon = b.min_lon.min(p.lon);
            b.max_lat = b.max_lat.max(p.lat);
            b.max_lon = b.max_lon.max(p.lon);
        }
        // About NEAR_M in degrees (longitude at 70° N, the widest).
        let m = NEAR_M / 111_000.0 * 3.0;
        Bounds {
            min_lat: b.min_lat - m,
            min_lon: b.min_lon - m,
            max_lat: b.max_lat + m,
            max_lon: b.max_lon + m,
        }
    }

    fn overlaps(&self, o: &Bounds) -> bool {
        self.min_lat <= o.max_lat
            && o.min_lat <= self.max_lat
            && self.min_lon <= o.max_lon
            && o.min_lon <= self.max_lon
    }
}

/// A ride's filled-in line in cells of about [`NEAR_M`], to ask quickly
/// whether a point is near it.
struct NearGrid {
    cells: HashMap<(i64, i64), Vec<LatLon>>,
    bounds: Option<Bounds>,
}

/// Cell size in degrees of latitude (and of longitude: cells are narrower
/// east–west in the north, which only makes them more).
const CELL_DEG: f64 = NEAR_M / 111_000.0;

fn cell(p: LatLon) -> (i64, i64) {
    (
        (p.lat / CELL_DEG).floor() as i64,
        (p.lon / CELL_DEG).floor() as i64,
    )
}

impl NearGrid {
    fn of(line: &[LatLon]) -> Self {
        let valid: Vec<LatLon> = line
            .iter()
            .copied()
            .filter(|p| p.validate().is_ok())
            .collect();
        let mut cells: HashMap<(i64, i64), Vec<LatLon>> = HashMap::new();
        let mut add = |p: LatLon| cells.entry(cell(p)).or_default().push(p);
        for w in valid.windows(2) {
            if haversine_m(w[0], w[1]) <= MAX_GAP_M {
                for p in densify(w, FILL_M) {
                    add(p);
                }
            } else {
                add(w[0]);
                add(w[1]);
            }
        }
        if let [only] = valid.as_slice() {
            add(*only);
        }
        let bounds = (!valid.is_empty()).then(|| Bounds::of(&valid));
        Self { cells, bounds }
    }

    fn has_near(&self, p: LatLon) -> bool {
        let (r, c) = cell(p);
        // A cell is NEAR_M tall: the rows either side hold every point
        // within NEAR_M north–south. East–west it is narrower by the
        // cosine of the latitude, so look as many cells further.
        let k = (1.0 / p.lat.to_radians().cos().max(0.2)).ceil() as i64;
        (-1..=1).any(|dr| {
            (-k..=k).any(|dc| {
                self.cells
                    .get(&(r + dr, c + dc))
                    .is_some_and(|pts| pts.iter().any(|&q| haversine_m(p, q) <= NEAR_M))
            })
        })
    }
}

#[cfg(test)]
mod tests;
