// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Weights and thresholds of the route cost, in one place. Change them only
//! with a stated hypothesis and a before/after comparison of the golden
//! routes (see the route-scoring procedure).

use crate::section::Rating;

/// What the route cost is made of.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScoringParams {
    /// Share of an edge's travel time taken off when the whole edge is on a
    /// favourite section, by rating (good, great, epic). An epic road of
    /// 0.5 costs half its time: the route takes it over a road up to twice
    /// as fast.
    pub favourite_bonus: [f64; 3],
    /// Cost factor on roads the options ask to avoid. Avoiding is "where
    /// possible", not a ban: a farm on a gravel road must still be
    /// reachable.
    pub avoid_penalty: f64,
    /// Radius bins (see `RADIUS_BINS_M`) that count as "curvy" for
    /// `Route::curvy_share`: turn radius up to 175 m. Provisional; the real
    /// definition comes with curvature scoring (M2b).
    pub curvy_bins: usize,
    /// Bisection steps over the favourite weight when the full weight
    /// gives a route over the detour budget (each one is a route search).
    pub detour_steps: u32,
}

impl ScoringParams {
    /// The bonus of a whole edge on a section rated `rating`.
    pub fn bonus(&self, rating: Rating) -> f64 {
        self.favourite_bonus[rating as usize - 1]
    }

    /// The largest bonus any edge can get. Kept below 1 so every edge
    /// still costs something: this cap bounds how far a route strays for
    /// a favourite, and keeps the A* estimate admissible.
    pub fn max_bonus(&self) -> f64 {
        self.favourite_bonus.iter().copied().fold(0.0, f64::max)
    }
}

pub const PARAMS: ScoringParams = ScoringParams {
    favourite_bonus: [0.2, 0.35, 0.5],
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
        const { assert!(PARAMS.favourite_bonus[2] < 1.0 && PARAMS.favourite_bonus[0] > 0.0) };
        const { assert!(PARAMS.avoid_penalty >= 1.0) };
        const { assert!(PARAMS.curvy_bins <= crate::region::format::RADIUS_BINS_M.len()) };
    }
}
