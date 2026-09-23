// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Curvature metrics of a polyline (ADR-0005): what the region file stores
//! per edge. Scoring them into a cost happens at query time.

use crate::LatLon;
use crate::geo::{EARTH_RADIUS_M, haversine_m};
use crate::region::format::{CurvatureMetrics, RADIUS_BINS_M};

/// Measures total turning and how much road lies in each turn-radius bin.
///
/// At every interior vertex the turn radius is the radius of the circle
/// through the vertex and its two neighbours; half of each adjacent segment
/// is attributed to that radius. Points are projected onto a local plane,
/// which is exact enough at the scale of a road bend. Points closer than
/// [`MIN_SPACING_M`] to the previous kept point are skipped first, so
/// coordinate rounding on densely mapped roads doesn't read as tight bends.
pub fn curvature_metrics(points: &[LatLon]) -> CurvatureMetrics {
    let points = thin(points);
    let mut turn_deg = 0.0;
    let mut bins = [0.0f64; RADIUS_BINS_M.len()];
    for w in points.windows(3) {
        let (a, b, c) = (w[0], w[1], w[2]);
        let k = b.lat.to_radians().cos();
        let xy = |p: LatLon| {
            (
                (p.lon - b.lon).to_radians() * k * EARTH_RADIUS_M,
                (p.lat - b.lat).to_radians() * EARTH_RADIUS_M,
            )
        };
        let (pa, pc) = (xy(a), xy(c));
        let (u, v) = ((-pa.0, -pa.1), pc); // a→b and b→c
        let (lu, lv) = (u.0.hypot(u.1), v.0.hypot(v.1));
        if lu == 0.0 || lv == 0.0 {
            continue;
        }
        let cross = u.0 * v.1 - u.1 * v.0;
        let dot = u.0 * v.0 + u.1 * v.1;
        let angle = cross.atan2(dot).abs(); // heading change, 0..π
        turn_deg += angle.to_degrees();

        // Circumradius R = |ac| / (2 sin θ), θ the angle at b between ba and bc,
        // which is π − heading change.
        let chord = (pc.0 - pa.0).hypot(pc.1 - pa.1);
        let s = (std::f64::consts::PI - angle).sin();
        if s <= 0.0 {
            continue;
        }
        let radius = chord / (2.0 * s);
        if let Some(bin) = RADIUS_BINS_M.iter().position(|&r| radius <= r) {
            bins[bin] += (haversine_m(a, b) + haversine_m(b, c)) / 2.0;
        }
    }
    CurvatureMetrics {
        turn_ddeg: (turn_deg * 10.0).round().min(f64::from(u32::MAX)) as u32,
        radius_len_m: bins.map(|m| m.round().min(f64::from(u16::MAX)) as u16),
    }
}

/// Minimum spacing of the points curvature is measured on.
pub const MIN_SPACING_M: f64 = 5.0;

/// Drops points within [`MIN_SPACING_M`] of the previous kept one; the
/// last point is always kept (replacing a too-close predecessor).
fn thin(points: &[LatLon]) -> Vec<LatLon> {
    let mut out: Vec<LatLon> = Vec::with_capacity(points.len());
    for (i, &p) in points.iter().enumerate() {
        let far = out.last().is_none_or(|q| haversine_m(*q, p) >= MIN_SPACING_M);
        if far {
            out.push(p);
        } else if i == points.len() - 1 && out.len() > 1 {
            *out.last_mut().unwrap() = p;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Points on a circle of `radius` metres around (55.6, 13.4), every
    /// `step_deg` degrees of arc from 0 to `arc_deg`.
    fn arc(radius: f64, arc_deg: f64, step_deg: f64) -> Vec<LatLon> {
        let (lat0, lon0) = (55.6f64, 13.4f64);
        let k = lat0.to_radians().cos();
        let steps = (arc_deg / step_deg).round() as usize;
        (0..=steps)
            .map(|i| {
                let t = (i as f64 * step_deg).to_radians();
                LatLon {
                    lat: lat0 + (radius * t.sin() / EARTH_RADIUS_M).to_degrees(),
                    lon: lon0 + (radius * t.cos() / (EARTH_RADIUS_M * k)).to_degrees(),
                }
            })
            .collect()
    }

    #[test]
    fn straight_line_has_no_curvature() {
        let pts: Vec<LatLon> = (0..10)
            .map(|i| LatLon {
                lat: 55.6 + i as f64 * 0.001,
                lon: 13.4,
            })
            .collect();
        assert_eq!(curvature_metrics(&pts), CurvatureMetrics::default());
    }

    #[test]
    fn quarter_circle_turns_ninety_degrees_in_the_right_bin() {
        let pts = arc(80.0, 90.0, 5.0);
        let m = curvature_metrics(&pts);
        assert!((m.turn_ddeg as i64 - 850).abs() <= 2, "got {m:?}"); // 17 vertices × 5°
        // All of it in the 60–100 m bin, about the arc length minus the
        // two half end segments.
        let len = 80.0 * std::f64::consts::FRAC_PI_2;
        let seg = len / 18.0;
        assert_eq!(m.radius_len_m[..2], [0, 0]);
        assert!(
            (f64::from(m.radius_len_m[2]) - (len - seg)).abs() <= 1.0,
            "got {m:?}"
        );
        assert_eq!(m.radius_len_m[3..], [0, 0, 0]);
    }

    #[test]
    fn wide_bend_counts_as_straight() {
        let m = curvature_metrics(&arc(2000.0, 20.0, 1.0));
        assert_eq!(m.radius_len_m, [0; 6]);
        assert!((m.turn_ddeg as i64 - 190).abs() <= 2, "got {m:?}");
    }

    #[test]
    fn rounding_jitter_on_dense_points_is_ignored() {
        // A straight road mapped every metre, with 1e-7° zig-zag.
        let pts: Vec<LatLon> = (0..200)
            .map(|i| LatLon {
                lat: 55.6 + i as f64 * 9e-6 + if i % 2 == 0 { 1e-7 } else { 0.0 },
                lon: 13.4,
            })
            .collect();
        let m = curvature_metrics(&pts);
        assert_eq!(m.radius_len_m, [0; 6], "got {m:?}");
        assert!(m.turn_ddeg < 10, "got {m:?}");
    }

    #[test]
    fn degenerate_input_is_harmless() {
        let p = LatLon {
            lat: 55.6,
            lon: 13.4,
        };
        assert_eq!(curvature_metrics(&[]), CurvatureMetrics::default());
        assert_eq!(curvature_metrics(&[p, p, p]), CurvatureMetrics::default());
    }
}
