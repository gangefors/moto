// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! When one section makes another redundant (imports and new sections).
//!
//! A section is dropped only when another one says at least as much about
//! the same road: it covers it (every point within 15 m), applies in every
//! direction the other does (a two-way section covers either one-way; a
//! one-way section only one-way in the same direction), and is rated at
//! least as high. Otherwise both are kept: opposite one-ways, a one-way
//! rated higher than a two-way over it, or a short epic stretch inside a
//! longer good section all carry information. Routing takes the best bonus
//! per road and direction, so keeping both never makes a route worse.

use crate::geo::{densify, distance_to_line, haversine_m};
use crate::section::{Direction, NewSection, Rating, Section};
use crate::store::Store;
use crate::{CoreError, LatLon};

/// A section covers another when every point of the other lies within
/// this distance of it.
const COVER_TOLERANCE_M: f64 = 15.0;
/// Points checked along the covered section, this far apart.
const COVER_STEP_M: f64 = 10.0;

/// A section's shape for overlap checks.
pub(crate) struct Shape {
    line: Vec<LatLon>,
    direction: Direction,
    rating: Rating,
    length_m: f64,
    /// South-west and north-east corners, widened by the tolerance.
    bbox: (LatLon, LatLon),
}

impl Shape {
    pub(crate) fn of_section(s: &Section) -> Self {
        Self::new(&s.geometry, s.direction, s.rating)
    }

    pub(crate) fn of_new(s: &NewSection) -> Self {
        Self::new(&s.geometry, s.direction, s.rating)
    }

    fn new(line: &[LatLon], direction: Direction, rating: Rating) -> Self {
        let pad_lat = COVER_TOLERANCE_M / 111_195.0;
        let mut sw = LatLon {
            lat: 90.0,
            lon: 180.0,
        };
        let mut ne = LatLon {
            lat: -90.0,
            lon: -180.0,
        };
        for p in line {
            sw = LatLon {
                lat: sw.lat.min(p.lat),
                lon: sw.lon.min(p.lon),
            };
            ne = LatLon {
                lat: ne.lat.max(p.lat),
                lon: ne.lon.max(p.lon),
            };
        }
        let pad_lon = pad_lat / sw.lat.to_radians().cos().max(0.01);
        Self {
            line: line.to_vec(),
            direction,
            rating,
            length_m: line.windows(2).map(|w| haversine_m(w[0], w[1])).sum(),
            bbox: (
                LatLon {
                    lat: sw.lat - pad_lat,
                    lon: sw.lon - pad_lon,
                },
                LatLon {
                    lat: ne.lat + pad_lat,
                    lon: ne.lon + pad_lon,
                },
            ),
        }
    }

    fn bbox_overlaps(&self, other: &Shape) -> bool {
        self.bbox.0.lat <= other.bbox.1.lat
            && other.bbox.0.lat <= self.bbox.1.lat
            && self.bbox.0.lon <= other.bbox.1.lon
            && other.bbox.0.lon <= self.bbox.1.lon
    }

    /// Whether this section says everything `other` does: it lies along
    /// all of `other`, in every direction `other` applies to, rated at
    /// least as high.
    fn covers(&self, other: &Shape) -> bool {
        if self.rating < other.rating || !self.bbox_overlaps(other) {
            return false;
        }
        let same_way = match (self.direction, other.direction) {
            (Direction::Both, _) => true,
            (Direction::Forward, Direction::Both) => false,
            (Direction::Forward, Direction::Forward) => true, // checked below
        };
        if !same_way {
            return false;
        }
        let points = densify(&other.line, COVER_STEP_M);
        if !points
            .iter()
            .all(|&p| distance_to_line(p, &self.line) <= COVER_TOLERANCE_M)
        {
            return false;
        }
        if self.direction == Direction::Forward {
            let (Some(&a), Some(&b)) = (other.line.first(), other.line.last()) else {
                return false;
            };
            return position_along(&self.line, a) <= position_along(&self.line, b);
        }
        true
    }

    /// Whether this section makes `other` redundant: it covers it, and
    /// if they cover each other (the same road, direction and rating,
    /// give or take the tolerance) it is at least as long.
    pub(crate) fn beats(&self, other: &Shape) -> bool {
        self.covers(other) && (self.length_m >= other.length_m || !other.covers(self))
    }
}

/// Metres along `line` to the point nearest to `p`.
fn position_along(line: &[LatLon], p: LatLon) -> f64 {
    let mut best = (f64::INFINITY, 0.0);
    let mut walked = 0.0;
    for w in line.windows(2) {
        let seg = haversine_m(w[0], w[1]);
        let d = distance_to_line(p, w);
        if d < best.0 {
            // Along this segment: the part of the segment before p.
            let a = haversine_m(w[0], p);
            let along = (a * a - d * d).max(0.0).sqrt().min(seg);
            best = (d, walked + along);
        }
        walked += seg;
    }
    best.1
}

/// What saving a new section did.
#[derive(Debug, Clone, PartialEq)]
pub enum Added {
    /// Saved; `replaced` are the ids of saved sections it made redundant
    /// (removed in the same transaction).
    Saved {
        section: Section,
        replaced: Vec<i64>,
    },
    /// Not saved: the saved section `by` already says as much.
    Covered { by: i64 },
}

/// Saves a new section under the overlap rules: skipped if a saved one
/// already covers it (in its directions, rated as high), otherwise saved,
/// removing the saved sections it makes redundant. `now` is seconds since
/// the Unix epoch.
pub fn add_section(store: &mut Store, s: &NewSection, now: i64) -> Result<Added, CoreError> {
    s.validate()?;
    let shape = Shape::of_new(s);
    let saved = store.list_sections(None)?;
    if let Some(by) = saved
        .iter()
        .find(|o| Shape::of_section(o).beats(&shape))
        .map(|o| o.id)
    {
        return Ok(Added::Covered { by });
    }
    let replaced: Vec<i64> = saved
        .iter()
        .filter(|o| shape.beats(&Shape::of_section(o)))
        .map(|o| o.id)
        .collect();
    let section = store.add_section_replacing(s, &replaced, now)?;
    Ok(Added::Saved { section, replaced })
}

#[cfg(test)]
mod tests;
