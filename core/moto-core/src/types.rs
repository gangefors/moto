//! Request and result types of the core API (PRD R6–R8).

use crate::{CoreError, LatLon};

/// A point snapped onto the road network.
#[derive(Debug, Clone, PartialEq)]
pub struct RoadPoint {
    /// The snapped position on the road.
    pub position: LatLon,
    /// Distance from the input point to `position`, in metres.
    pub distance_m: f64,
    /// Graph edge the point lies on (an index into the region file).
    pub edge: u32,
    /// Position along the edge, 0.0 at its start and 1.0 at its end.
    pub offset: f64,
}

/// Road types a route should stay off.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Avoid {
    pub motorways: bool,
    pub unpaved: bool,
    pub ferries: bool,
}

impl Default for Avoid {
    fn default() -> Self {
        Self {
            motorways: true,
            unpaved: true,
            ferries: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct RouteOptions {
    pub avoid: Avoid,
    /// Extra time allowed over the fastest route, as a fraction
    /// (0.4 = up to 40 % longer). How riders express this is still an open
    /// product question; the core keeps a plain ratio.
    pub max_detour: f64,
}

impl Default for RouteOptions {
    fn default() -> Self {
        Self {
            avoid: Avoid::default(),
            max_detour: 0.4,
        }
    }
}

impl RouteOptions {
    pub fn validate(&self) -> Result<(), CoreError> {
        if self.max_detour.is_finite() && self.max_detour >= 0.0 {
            Ok(())
        } else {
            Err(CoreError::InvalidArgument(format!(
                "max_detour must be a non-negative number, got {}",
                self.max_detour
            )))
        }
    }
}

/// Target size of a round trip; the result may deviate by ±15 % (PRD R7).
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum RoundTripTarget {
    DistanceM(f64),
    DurationS(f64),
}

impl RoundTripTarget {
    pub fn validate(&self) -> Result<(), CoreError> {
        let (name, v) = match *self {
            RoundTripTarget::DistanceM(v) => ("distance", v),
            RoundTripTarget::DurationS(v) => ("duration", v),
        };
        if v.is_finite() && v > 0.0 {
            Ok(())
        } else {
            Err(CoreError::InvalidArgument(format!(
                "round-trip {name} must be positive, got {v}"
            )))
        }
    }
}

/// A computed route with the summary figures shown to the rider (PRD R8).
#[derive(Debug, Clone, PartialEq)]
pub struct Route {
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
    pub duration_s: f64,
    /// Share of the distance on the rider's favourite sections, 0.0–1.0.
    pub favourite_share: f64,
    /// Share of the distance on high-curvature roads, 0.0–1.0.
    pub curvy_share: f64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_route_options_are_valid() {
        assert!(RouteOptions::default().validate().is_ok());
    }

    #[test]
    fn rejects_negative_or_nan_detour() {
        for bad in [-0.1, f64::NAN, f64::INFINITY] {
            let opts = RouteOptions {
                max_detour: bad,
                ..RouteOptions::default()
            };
            assert!(opts.validate().is_err(), "{bad} should be rejected");
        }
    }

    #[test]
    fn round_trip_target_must_be_positive() {
        assert!(RoundTripTarget::DistanceM(100_000.0).validate().is_ok());
        assert!(RoundTripTarget::DurationS(0.0).validate().is_err());
        assert!(RoundTripTarget::DistanceM(-5.0).validate().is_err());
    }
}
