// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Weights and thresholds of the route cost, in one place. Change them only
//! with a stated hypothesis and a before/after comparison of the golden
//! routes (see the route-scoring procedure).

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
    /// Radius bins (see `RADIUS_BINS_M`) that count as "curvy" for
    /// `Route::curvy_share`: turn radius up to 175 m. Provisional; the real
    /// definition comes with curvature scoring (M2b).
    pub curvy_bins: usize,
    /// Bisection steps over the pull when full pull gives a route over the
    /// budget or the guard (each one is a route search).
    pub detour_steps: u32,
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
}

pub const PARAMS: ScoringParams = ScoringParams {
    favourite_weight: [0.4, 0.7, 1.0],
    max_pull: 0.8,
    min_gain: 1.0,
    avoid_penalty: 10.0,
    curvy_bins: 4,
    detour_steps: 5,
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
        const { assert!(PARAMS.curvy_bins <= crate::region::format::RADIUS_BINS_M.len()) };
    }
}
