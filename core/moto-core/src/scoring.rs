// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Weights and thresholds of the route cost, in one place. Change them only
//! with a stated hypothesis and a before/after comparison of the golden
//! routes (see the route-scoring procedure).

use crate::region::format::{CurvatureMetrics, RADIUS_BINS_M, RoadClass};
use crate::section::Rating;

/// What the route cost is made of.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScoringParams {
    /// How much each rating counts, relative to epic (good, great, epic):
    /// both how hard a favourite pulls and what riding it is worth.
    pub favourite_weight: [f64; 3],
    /// Share of an epic edge's travel time taken off at full pull. The
    /// pull is scaled down from full until the route fits the time budget
    /// (and the guard), so the budget, not a fixed bonus, decides how far
    /// a route goes for favourites. Kept below 1 so every edge still costs
    /// something: this caps how far a route strays and keeps the A*
    /// estimate admissible.
    pub max_pull: f64,
    /// Default for `RouteOptions::min_gain`: seconds of rating-weighted
    /// favourite riding each extra second must buy.
    pub min_gain: f64,
    /// Cost factor on roads the options ask to avoid. Avoiding is "where
    /// possible", not a ban: a farm on a gravel road must still be
    /// reachable.
    pub avoid_penalty: f64,
    /// How much a metre of road in each turn-radius bin (`RADIUS_BINS_M`:
    /// ≤ 30, 60, 100, 175, 300, 500 m) counts as curvy. Sweepers count
    /// most; hairpins less (slow, and often junction artefacts); wide
    /// bends little.
    pub curve_bin_weight: [f64; RADIUS_BINS_M.len()],
    /// Weighted curvy metres per metre at which a road counts as fully
    /// curvy (about the curviest 3 % of tertiary roads in Skåne).
    pub curve_full: f64,
    /// How much curvature counts by road class (`RoadClass` order): none on
    /// motorways (their ramps), service roads, living streets and tracks
    /// (car parks, estates, farms), little on residential streets.
    pub curve_class_weight: [f64; RoadClass::ALL.len()],
    /// What a fully curvy road is worth next to an epic favourite (1).
    /// Favourite and curvature add up, capped at 1.
    pub curve_weight: f64,
    /// Bisection steps over the pull when full pull gives a route over the
    /// budget or the guard (each one is a route search).
    pub detour_steps: u32,
    /// Round trips (ADR-0007): cost factor on roads the loop already rides.
    pub reuse_penalty: f64,
    /// Road length over straight-line length assumed when sizing a loop.
    pub loop_detour: f64,
    /// Pull of favourites and curvature on round-trip legs (0–1).
    pub loop_pull: f64,
    /// Speed used to turn a duration target into a distance, m/s.
    pub loop_speed_mps: f64,
}

impl ScoringParams {
    /// The bonus of a whole edge on a section rated `rating`, at full pull.
    pub fn bonus(&self, rating: Rating) -> f64 {
        self.max_pull * self.favourite_weight[rating as usize - 1]
    }

    /// The largest bonus any edge can get.
    pub fn max_bonus(&self) -> f64 {
        self.max_pull * self.favourite_weight.iter().copied().fold(0.0, f64::max)
    }

    /// How curvy an edge of road class `class` and `length_m` is, 0–1,
    /// from its curvature metrics (R5).
    pub fn curviness(&self, m: &CurvatureMetrics, class: u8, length_m: f64) -> f64 {
        if length_m <= 0.0 {
            return 0.0;
        }
        let class_weight = self
            .curve_class_weight
            .get(usize::from(class))
            .copied()
            .unwrap_or(0.0);
        let weighted: f64 = m
            .radius_len_m
            .iter()
            .zip(self.curve_bin_weight)
            .map(|(&metres, w)| f64::from(metres) * w)
            .sum();
        (weighted / length_m / self.curve_full).min(1.0) * class_weight
    }
}

pub const PARAMS: ScoringParams = ScoringParams {
    favourite_weight: [0.4, 0.7, 1.0],
    max_pull: 0.8,
    min_gain: 1.0,
    avoid_penalty: 10.0,
    curve_bin_weight: [0.6, 1.0, 1.0, 0.8, 0.5, 0.2],
    curve_full: 0.4,
    //                 motorway trunk primary secondary tertiary unclassified
    //                 residential living_street service track ferry
    curve_class_weight: [0.0, 0.5, 1.0, 1.0, 1.0, 0.6, 0.2, 0.0, 0.0, 0.0, 0.0],
    curve_weight: 0.8,
    detour_steps: 5,
    reuse_penalty: 4.0,
    loop_detour: 1.3,
    loop_pull: 1.0,
    loop_speed_mps: 15.0,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bonuses_grow_with_the_rating_and_stay_capped() {
        let p = PARAMS;
        assert!(p.bonus(Rating::Good) < p.bonus(Rating::Great));
        assert!(p.bonus(Rating::Great) < p.bonus(Rating::Epic));
        assert_eq!(p.max_bonus(), p.bonus(Rating::Epic));
        const { assert!(PARAMS.max_pull < 1.0 && PARAMS.max_pull > 0.0) };
        const { assert!(PARAMS.favourite_weight[0] > 0.0 && PARAMS.favourite_weight[2] <= 1.0) };
        const { assert!(PARAMS.min_gain >= 0.0) };
        const { assert!(PARAMS.avoid_penalty >= 1.0) };
        const { assert!(PARAMS.curve_full > 0.0 && PARAMS.curve_weight <= 1.0) };
    }

    #[test]
    fn curviness_is_weighted_capped_and_by_class() {
        let p = PARAMS;
        let m = |bins: [u16; 6]| CurvatureMetrics {
            turn_ddeg: 0,
            radius_len_m: bins,
        };
        let tertiary = RoadClass::Tertiary as u8;
        // Straight road.
        assert_eq!(p.curviness(&m([0; 6]), tertiary, 1000.0), 0.0);
        // 200 m of 60–100 m sweepers per km: half way to fully curvy.
        let half = p.curviness(&m([0, 0, 200, 0, 0, 0]), tertiary, 1000.0);
        assert!((half - 0.5).abs() < 1e-9, "{half}");
        // Very curvy roads cap at 1; hairpins count less than sweepers.
        assert_eq!(p.curviness(&m([0, 900, 0, 0, 0, 0]), tertiary, 1000.0), 1.0);
        assert!(p.curviness(&m([200, 0, 0, 0, 0, 0]), tertiary, 1000.0) < half);
        // The same bends on a motorway ramp or a car park count nothing,
        // on a residential street little.
        for class in [RoadClass::Motorway, RoadClass::Service, RoadClass::Track] {
            assert_eq!(
                p.curviness(&m([0, 0, 200, 0, 0, 0]), class as u8, 1000.0),
                0.0
            );
        }
        let street = p.curviness(
            &m([0, 0, 200, 0, 0, 0]),
            RoadClass::Residential as u8,
            1000.0,
        );
        assert!(street > 0.0 && street < half / 2.0);
        // Nonsense input never panics.
        assert_eq!(p.curviness(&m([u16::MAX; 6]), 250, 1000.0), 0.0);
        assert_eq!(p.curviness(&m([u16::MAX; 6]), tertiary, 0.0), 0.0);
        assert_eq!(p.curviness(&m([u16::MAX; 6]), tertiary, 1.0), 1.0);
    }
}
