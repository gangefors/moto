// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Which stretches of a line run on the rider's favourite sections, worked
//! out from the sections as they are now (ADR-0011): for a saved route,
//! whose favourites may have changed since it was saved, when it is shown
//! or ridden. The router gives a new route's parts itself.
//!
//! The line is walked in short steps; a step is on a favourite when its
//! middle lies within [`ON_SECTION_M`] of the section and it heads along
//! it (either way for a two-way section, its own way for a one-way one),
//! so a road that only crosses a favourite doesn't count. Where favourites
//! overlap, the best rating wins, as in routing.

use crate::geo::{bearing_deg, densify, haversine_m};
use crate::handoff::MAX_LINE_POINTS;
use crate::section::{Direction, Rating, Section};
use crate::{CoreError, LatLon};

/// A step is on a section within this distance of it (as when one
/// section covers another, `overlap`).
pub const ON_SECTION_M: f64 = 15.0;
/// The line is walked in steps of at most this length.
const STEP_M: f64 = 10.0;
/// A step heads along a section when it turns less than this from it.
const ALONG_DEG: f64 = 45.0;
/// Shorter stretches (a junction, a brush past a favourite) are dropped.
pub const MIN_PART_M: f64 = 50.0;

/// A stretch of the line on favourite sections of one rating.
#[derive(Debug, Clone, PartialEq)]
pub struct FavouritePart {
    pub line: Vec<LatLon>,
    pub rating: Rating,
}

/// A section's shape, with a padded bounding box for a quick first test.
struct Shape<'a> {
    line: &'a [LatLon],
    direction: Direction,
    rating: Rating,
    sw: LatLon,
    ne: LatLon,
}

impl<'a> Shape<'a> {
    fn new(s: &'a Section) -> Option<Self> {
        if s.geometry.len() < 2 {
            return None;
        }
        let (mut sw, mut ne) = (s.geometry[0], s.geometry[0]);
        for p in &s.geometry {
            sw = LatLon {
                lat: sw.lat.min(p.lat),
                lon: sw.lon.min(p.lon),
            };
            ne = LatLon {
                lat: ne.lat.max(p.lat),
                lon: ne.lon.max(p.lon),
            };
        }
        let pad_lat = ON_SECTION_M / 111_195.0;
        let pad_lon = pad_lat / sw.lat.to_radians().cos().max(0.01);
        Some(Self {
            line: &s.geometry,
            direction: s.direction,
            rating: s.rating,
            sw: LatLon {
                lat: sw.lat - pad_lat,
                lon: sw.lon - pad_lon,
            },
            ne: LatLon {
                lat: ne.lat + pad_lat,
                lon: ne.lon + pad_lon,
            },
        })
    }

    /// Whether the step from `a` to `b` runs on this section.
    fn carries(&self, a: LatLon, b: LatLon) -> bool {
        let mid = LatLon {
            lat: (a.lat + b.lat) / 2.0,
            lon: (a.lon + b.lon) / 2.0,
        };
        if mid.lat < self.sw.lat
            || mid.lat > self.ne.lat
            || mid.lon < self.sw.lon
            || mid.lon > self.ne.lon
        {
            return false;
        }
        let Some((d, i, t)) = nearest_segment(mid, self.line) else {
            return false;
        };
        // Beyond either end of the section is not on it.
        let last = self.line.len() - 2;
        if d > ON_SECTION_M || a == b || (i == 0 && t <= 0.0) || (i == last && t >= 1.0) {
            return false;
        }
        let (p, q) = (self.line[i], self.line[i + 1]);
        if p == q {
            return false;
        }
        let turn = angle_between(bearing_deg(a, b), bearing_deg(p, q));
        match self.direction {
            Direction::Forward => turn < ALONG_DEG,
            Direction::Both => turn < ALONG_DEG || turn > 180.0 - ALONG_DEG,
        }
    }
}

/// The stretches of `line` on `sections`, in line order, each with the
/// best rating of the sections under it. `line` comes from the app (a
/// saved route's line) and is checked like any input.
pub fn favourite_parts_along(
    line: &[LatLon],
    sections: &[Section],
) -> Result<Vec<FavouritePart>, CoreError> {
    if line.len() < 2 || line.len() > MAX_LINE_POINTS {
        return Err(CoreError::InvalidArgument(format!(
            "a route line needs 2–{MAX_LINE_POINTS} points, got {}",
            line.len()
        )));
    }
    for p in line {
        p.validate()?;
    }
    let shapes: Vec<Shape<'_>> = sections.iter().filter_map(Shape::new).collect();
    let mut out: Vec<FavouritePart> = Vec::new();
    if shapes.is_empty() {
        return Ok(out);
    }
    let points = densify(line, STEP_M);
    let mut current: Option<FavouritePart> = None;
    for w in points.windows(2) {
        let rating = shapes
            .iter()
            .filter(|s| s.carries(w[0], w[1]))
            .map(|s| s.rating)
            .max();
        match (&mut current, rating) {
            (Some(part), Some(r)) if part.rating == r => part.line.push(w[1]),
            (_, r) => {
                if let Some(done) = current.take() {
                    keep(&mut out, done);
                }
                current = r.map(|rating| FavouritePart {
                    line: vec![w[0], w[1]],
                    rating,
                });
            }
        }
    }
    if let Some(done) = current {
        keep(&mut out, done);
    }
    Ok(out)
}

fn keep(out: &mut Vec<FavouritePart>, part: FavouritePart) {
    let length: f64 = part.line.windows(2).map(|w| haversine_m(w[0], w[1])).sum();
    if length >= MIN_PART_M {
        out.push(part);
    }
}

/// Distance from `p` to the nearest segment of `line`, its index, and how
/// far along it the nearest point is (0–1).
fn nearest_segment(p: LatLon, line: &[LatLon]) -> Option<(f64, usize, f64)> {
    let k = p.lat.to_radians().cos();
    let m = 111_195.0;
    let xy = |q: LatLon| ((q.lon - p.lon) * k * m, (q.lat - p.lat) * m);
    let mut best: Option<(f64, usize, f64)> = None;
    for (i, w) in line.windows(2).enumerate() {
        let (a, b) = (xy(w[0]), xy(w[1]));
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len2 = dx * dx + dy * dy;
        let t = if len2 > 0.0 {
            (-(a.0 * dx + a.1 * dy) / len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let d = (a.0 + t * dx).hypot(a.1 + t * dy);
        if best.is_none_or(|(bd, _, _)| d < bd) {
            best = Some((d, i, t));
        }
    }
    best
}

fn angle_between(a: f64, b: f64) -> f64 {
    let d = (a - b).rem_euclid(360.0);
    d.min(360.0 - d)
}

#[cfg(test)]
mod tests;
