// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The rider's favourite sections near where they are, while recording
//! without a route (the recording card): how far each is and the way to
//! it, or, on one, how much of it is left the way they are going.
//!
//! Distances are to the nearest point of each section, in a local flat
//! approximation (fine over a few kilometres).

use crate::geo::bearing_deg;
use crate::section::{Direction, Rating, Section};
use crate::{CoreError, LatLon};

/// Favourites are near within this, metres.
pub const NEAR_M: f64 = 5_000.0;
/// The rider is on a favourite within this of it, metres.
pub const ON_M: f64 = 30.0;
/// At most this many are listed.
pub const MAX_NEAR: usize = 2;

/// A favourite near the rider.
#[derive(Debug, Clone, PartialEq)]
pub struct NearFavourite {
    pub section_id: i64,
    pub rating: Rating,
    /// To its nearest point, metres.
    pub distance_m: f64,
    /// The way to that point, degrees from north (0–360).
    pub bearing_deg: f64,
    /// The rider is on it ([`ON_M`]).
    pub on: bool,
    /// On it: how much of it is left the way the rider is going (its own
    /// way for a one-way favourite, or without a heading).
    pub left_m: Option<f64>,
}

const M_PER_DEG: f64 = 111_195.0;

/// The favourites in `sections` within [`NEAR_M`] of `at`, nearest first,
/// at most [`MAX_NEAR`]. `heading` (degrees from north) says which way the
/// rider goes along one they are on; it is ignored unless finite.
pub fn near_favourites(
    sections: &[Section],
    at: LatLon,
    heading: Option<f64>,
) -> Result<Vec<NearFavourite>, CoreError> {
    at.validate()?;
    let heading = heading
        .filter(|h| h.is_finite())
        .map(|h| h.rem_euclid(360.0));
    let k = at.lat.to_radians().cos().max(0.01);
    let xy = |q: LatLon| {
        (
            (q.lon - at.lon) * k * M_PER_DEG,
            (q.lat - at.lat) * M_PER_DEG,
        )
    };
    let mut out = Vec::new();
    for s in sections {
        if s.geometry.len() < 2 || s.geometry.iter().any(|p| p.validate().is_err()) {
            continue;
        }
        // Nearest point: its distance, segment, share along it, and the
        // length of the section before that segment.
        let mut best: Option<(f64, usize, f64)> = None;
        for (i, w) in s.geometry.windows(2).enumerate() {
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
        let Some((d, i, t)) = best else { continue };
        if d.is_nan() || d > NEAR_M {
            continue;
        }
        let (p, q) = (s.geometry[i], s.geometry[i + 1]);
        let point = LatLon {
            lat: p.lat + t * (q.lat - p.lat),
            lon: p.lon + t * (q.lon - p.lon),
        };
        let on = d <= ON_M;
        let left_m = on.then(|| {
            let seg = |w: &[LatLon]| {
                let (a, b) = (xy(w[0]), xy(w[1]));
                (b.0 - a.0).hypot(b.1 - a.1)
            };
            let before: f64 =
                s.geometry[..=i].windows(2).map(seg).sum::<f64>() + t * seg(&s.geometry[i..i + 2]);
            let total: f64 = s.geometry.windows(2).map(seg).sum();
            let ahead = total - before;
            let backwards = match (s.direction, heading) {
                (Direction::Both, Some(h)) if p != q => {
                    let turn = (h - bearing_deg(p, q)).rem_euclid(360.0);
                    turn > 90.0 && turn < 270.0
                }
                _ => false,
            };
            if backwards { before } else { ahead }
        });
        out.push(NearFavourite {
            section_id: s.id,
            rating: s.rating,
            distance_m: d,
            bearing_deg: if d > 0.0 { bearing_deg(at, point) } else { 0.0 },
            on,
            left_m,
        });
    }
    out.sort_by(|a, b| {
        a.distance_m
            .total_cmp(&b.distance_m)
            .then(a.section_id.cmp(&b.section_id))
    });
    out.truncate(MAX_NEAR);
    Ok(out)
}

#[cfg(test)]
mod tests;
