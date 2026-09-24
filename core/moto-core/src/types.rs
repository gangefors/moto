// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

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

/// How much time a route may take (PRD R6). The time over the fastest
/// route is spent on favourites: the more there is, the harder they pull.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TimeBudget {
    /// Up to this fraction more than the fastest route (0.4 = 40 % longer).
    Extra(f64),
    /// At most this many seconds in all, e.g. to arrive by a set time. Less
    /// than the fastest route gives the fastest route.
    Total(f64),
}

impl TimeBudget {
    /// The extra seconds allowed over a fastest route of `fastest_s`.
    pub fn extra_s(&self, fastest_s: f64) -> f64 {
        match *self {
            TimeBudget::Extra(ratio) => fastest_s * ratio,
            TimeBudget::Total(total) => (total - fastest_s).max(0.0),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct RouteOptions {
    pub avoid: Avoid,
    pub budget: TimeBudget,
    /// Guard against poor trades: a detour must buy at least this many
    /// seconds of favourite riding (weighted by rating, epic = 1) per extra
    /// second. 0 spends the whole budget if that adds any favourite road
    /// (for "arrive by" routes); see `RouteOptions::default`.
    pub min_gain: f64,
    /// Whether curvy roads pull the route too (R5), besides favourites.
    pub curvy: bool,
}

impl Default for RouteOptions {
    fn default() -> Self {
        Self {
            avoid: Avoid::default(),
            budget: TimeBudget::Extra(0.4),
            min_gain: crate::scoring::PARAMS.min_gain,
            curvy: true,
        }
    }
}

impl RouteOptions {
    pub fn validate(&self) -> Result<(), CoreError> {
        let bad = |what: &str, v: f64| {
            Err(CoreError::InvalidArgument(format!(
                "{what} must be a non-negative number, got {v}"
            )))
        };
        match self.budget {
            TimeBudget::Extra(v) if !(v.is_finite() && v >= 0.0) => return bad("extra time", v),
            TimeBudget::Total(v) if !(v.is_finite() && v >= 0.0) => return bad("total time", v),
            _ => {}
        }
        if !(self.min_gain.is_finite() && self.min_gain >= 0.0) {
            return bad("min_gain", self.min_gain);
        }
        Ok(())
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
    /// Share of the distance on curvy roads, 0.0–1.0: each metre counts by
    /// how curvy it is (see `ScoringParams::curviness`).
    pub curvy_share: f64,
    /// Time of the fastest route between the same points, to show what
    /// the favourites cost (equal to `duration_s` for the fastest route).
    pub fastest_duration_s: f64,
    /// The stretches of `geometry` on favourite sections, in order, each
    /// at least two points: for drawing them highlighted.
    pub favourite_parts: Vec<Vec<LatLon>>,
    /// Metres on gravel and other unpaved roads.
    pub unpaved_m: f64,
    /// The stretches of `geometry` on unpaved roads, in order, each at
    /// least two points: for drawing them marked.
    pub unpaved_parts: Vec<Vec<LatLon>>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_route_options_are_valid() {
        assert!(RouteOptions::default().validate().is_ok());
    }

    #[test]
    fn rejects_negative_or_nan_budgets() {
        for bad in [-0.1, f64::NAN, f64::INFINITY] {
            for budget in [TimeBudget::Extra(bad), TimeBudget::Total(bad)] {
                let opts = RouteOptions {
                    budget,
                    ..RouteOptions::default()
                };
                assert!(opts.validate().is_err(), "{budget:?} should be rejected");
            }
            let opts = RouteOptions {
                min_gain: bad,
                ..RouteOptions::default()
            };
            assert!(
                opts.validate().is_err(),
                "min_gain {bad} should be rejected"
            );
        }
    }

    #[test]
    fn budgets_give_extra_seconds() {
        assert_eq!(TimeBudget::Extra(0.4).extra_s(1000.0), 400.0);
        assert_eq!(TimeBudget::Total(1500.0).extra_s(1000.0), 500.0);
        // Less time than the fastest route: nothing extra.
        assert_eq!(TimeBudget::Total(600.0).extra_s(1000.0), 0.0);
    }

    #[test]
    fn round_trip_target_must_be_positive() {
        assert!(RoundTripTarget::DistanceM(100_000.0).validate().is_ok());
        assert!(RoundTripTarget::DurationS(0.0).validate().is_err());
        assert!(RoundTripTarget::DistanceM(-5.0).validate().is_err());
    }
}
