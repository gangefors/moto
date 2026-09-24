// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Basic geodesy helpers shared by snapping, scoring and routing.

use crate::CoreError;

/// Mean Earth radius in metres (IUGG), used for haversine distances.
pub const EARTH_RADIUS_M: f64 = 6_371_008.8;

/// A WGS84 coordinate in degrees.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LatLon {
    pub lat: f64,
    pub lon: f64,
}

impl LatLon {
    /// Creates a coordinate, rejecting NaN and out-of-range values.
    pub fn new(lat: f64, lon: f64) -> Result<Self, CoreError> {
        let p = Self { lat, lon };
        p.validate()?;
        Ok(p)
    }

    pub fn validate(&self) -> Result<(), CoreError> {
        let ok = (-90.0..=90.0).contains(&self.lat) && (-180.0..=180.0).contains(&self.lon);
        if ok {
            Ok(())
        } else {
            Err(CoreError::InvalidCoordinate {
                lat: self.lat,
                lon: self.lon,
            })
        }
    }

    /// Great-circle distance in metres.
    pub fn distance_m(&self, other: &LatLon) -> f64 {
        haversine_m(*self, *other)
    }
}

/// Great-circle distance between two points in metres.
pub fn haversine_m(a: LatLon, b: LatLon) -> f64 {
    let (lat1, lat2) = (a.lat.to_radians(), b.lat.to_radians());
    let dlat = lat2 - lat1;
    let dlon = (b.lon - a.lon).to_radians();
    let h = (dlat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (dlon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().min(1.0).asin()
}

/// Total length of a polyline in metres.
pub fn polyline_length_m(points: &[LatLon]) -> f64 {
    points.windows(2).map(|w| haversine_m(w[0], w[1])).sum()
}

/// The part of a polyline between two fractions of its length (0.0 = start,
/// 1.0 = end), with interpolated endpoints. `from` must not exceed `to`.
pub fn polyline_slice(points: &[LatLon], from: f64, to: f64) -> Vec<LatLon> {
    let (from, to) = (from.clamp(0.0, 1.0), to.clamp(0.0, 1.0));
    if points.len() < 2 || from > to {
        return points.first().copied().into_iter().collect();
    }
    let lengths: Vec<f64> = points.windows(2).map(|w| haversine_m(w[0], w[1])).collect();
    let total: f64 = lengths.iter().sum();
    let at = |frac: f64| -> (usize, LatLon) {
        // Segment index and point at `frac` of the total length.
        let target = frac * total;
        let mut walked = 0.0;
        for (i, &len) in lengths.iter().enumerate() {
            if walked + len >= target || i == lengths.len() - 1 {
                let t = if len > 0.0 {
                    ((target - walked) / len).clamp(0.0, 1.0)
                } else {
                    0.0
                };
                let (a, b) = (points[i], points[i + 1]);
                let p = LatLon {
                    lat: a.lat + t * (b.lat - a.lat),
                    lon: a.lon + t * (b.lon - a.lon),
                };
                return (i, p);
            }
            walked += len;
        }
        (0, points[0])
    };
    let (i, start) = at(from);
    let (j, end) = at(to);
    let mut out = vec![start];
    out.extend_from_slice(&points[i + 1..=j]);
    out.push(end);
    out.dedup();
    out
}

/// Points along `line` no more than `step_m` apart, its vertices included.
pub(crate) fn densify(line: &[LatLon], step_m: f64) -> Vec<LatLon> {
    let mut out = Vec::with_capacity(line.len() * 2);
    for w in line.windows(2) {
        let d = haversine_m(w[0], w[1]);
        let n = (d / step_m).ceil().max(1.0) as usize;
        for k in 0..n {
            let f = k as f64 / n as f64;
            out.push(LatLon {
                lat: w[0].lat + f * (w[1].lat - w[0].lat),
                lon: w[0].lon + f * (w[1].lon - w[0].lon),
            });
        }
    }
    out.extend(line.last().copied());
    out
}

/// Distance in metres from `p` to the nearest point of `line` (local flat
/// approximation, fine over a section).
pub fn distance_to_line(p: LatLon, line: &[LatLon]) -> f64 {
    let k = p.lat.to_radians().cos();
    let m = 111_195.0;
    let xy = |q: LatLon| ((q.lon - p.lon) * k * m, (q.lat - p.lat) * m);
    let mut best = f64::INFINITY;
    for w in line.windows(2) {
        let (a, b) = (xy(w[0]), xy(w[1]));
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len2 = dx * dx + dy * dy;
        let t = if len2 > 0.0 {
            (-(a.0 * dx + a.1 * dy) / len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        best = best.min((a.0 + t * dx).hypot(a.1 + t * dy));
    }
    if line.len() == 1 {
        best = xy(line[0]).0.hypot(xy(line[0]).1);
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    const MALMO: LatLon = LatLon {
        lat: 55.6050,
        lon: 13.0038,
    };
    const LUND: LatLon = LatLon {
        lat: 55.7047,
        lon: 13.1910,
    };

    #[test]
    fn distance_to_self_is_zero() {
        assert_eq!(haversine_m(MALMO, MALMO), 0.0);
    }

    #[test]
    fn malmo_to_lund_is_about_16_km() {
        let d = haversine_m(MALMO, LUND);
        assert!((d - 16_000.0).abs() < 500.0, "got {d}");
    }

    #[test]
    fn distance_is_symmetric() {
        assert_eq!(haversine_m(MALMO, LUND), haversine_m(LUND, MALMO));
    }

    #[test]
    fn one_degree_of_latitude_is_about_111_km() {
        let d = haversine_m(
            LatLon {
                lat: 55.0,
                lon: 13.0,
            },
            LatLon {
                lat: 56.0,
                lon: 13.0,
            },
        );
        assert!((d - 111_195.0).abs() < 10.0, "got {d}");
    }

    #[test]
    fn polyline_length_sums_segments() {
        let mid = LatLon {
            lat: 55.65,
            lon: 13.1,
        };
        let len = polyline_length_m(&[MALMO, mid, LUND]);
        assert!((len - (haversine_m(MALMO, mid) + haversine_m(mid, LUND))).abs() < 1e-9);
        assert_eq!(polyline_length_m(&[MALMO]), 0.0);
        assert_eq!(polyline_length_m(&[]), 0.0);
    }

    #[test]
    fn slices_polylines_by_length() {
        let (a, b, c) = (
            LatLon {
                lat: 55.0,
                lon: 13.0,
            },
            LatLon {
                lat: 55.01,
                lon: 13.0,
            },
            LatLon {
                lat: 55.03,
                lon: 13.0,
            },
        );
        let line = [a, b, c];
        assert_eq!(polyline_slice(&line, 0.0, 1.0), line);
        // a–b is a third of the length.
        let mid = polyline_slice(&line, 0.25, 0.5);
        assert_eq!(mid.len(), 3);
        assert!((mid[0].lat - 55.0075).abs() < 1e-9, "{mid:?}");
        assert_eq!(mid[1], b);
        assert!((mid[2].lat - 55.015).abs() < 1e-9, "{mid:?}");
        let one = polyline_slice(&line, 0.5, 0.5);
        assert_eq!(one.len(), 1);
        assert_eq!(polyline_slice(&line, 0.6, 0.4).len(), 1);
        assert!(polyline_slice(&[], 0.0, 1.0).is_empty());
    }

    #[test]
    fn rejects_invalid_coordinates() {
        assert!(LatLon::new(55.6, 13.0).is_ok());
        assert!(LatLon::new(90.1, 0.0).is_err());
        assert!(LatLon::new(0.0, -180.5).is_err());
        assert!(LatLon::new(f64::NAN, 0.0).is_err());
    }
}
