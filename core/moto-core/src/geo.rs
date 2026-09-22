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
    fn rejects_invalid_coordinates() {
        assert!(LatLon::new(55.6, 13.0).is_ok());
        assert!(LatLon::new(90.1, 0.0).is_err());
        assert!(LatLon::new(0.0, -180.5).is_err());
        assert!(LatLon::new(f64::NAN, 0.0).is_err());
    }
}
