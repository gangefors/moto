// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Map matching (PRD R4, M1 step 4): fits a recorded GPS track to the road
//! network, as OSM way spans plus geometry, so a ride can become sections.
//!
//! A hidden Markov model after Newson & Krumm (2009), solved with Viterbi:
//! each GPS point has candidate positions on the roads near it, scored by
//! their distance from the fix (Gaussian GPS noise); moving between the
//! candidates of consecutive points is scored by how much the distance
//! along the road differs from the straight-line distance between the
//! fixes (exponential), which favours staying on the road actually ridden
//! over hopping to a parallel one. Where no candidates connect (the rider
//! left the network, or GPS dropped out for long), the track is split into
//! separately matched pieces.

use crate::draft::trace;
use crate::geo::haversine_m;
use crate::region::Region;
use crate::route::{Partial, shortest_within};
use crate::section::WaySpan;
use crate::snap::nearby;
use crate::{CoreError, LatLon, RoadPoint};

/// Most points one track may have: over 55 hours at one fix per second.
pub const MAX_TRACK_POINTS: usize = 200_000;
/// Standard deviation of GPS position error, in metres.
const GPS_SIGMA_M: f64 = 10.0;
/// Points closer than this to the last point used are skipped: their
/// differences are mostly noise (Newson & Krumm: 2σ).
const MIN_STEP_M: f64 = 2.0 * GPS_SIGMA_M;
/// How far from a fix a road may be to be a candidate.
const CANDIDATE_RADIUS_M: f64 = 50.0;
/// Most candidates per point, nearest first.
const MAX_CANDIDATES: usize = 8;
/// Candidates more than this much further from the fix than the nearest
/// road are dropped (2σ). Otherwise a road parallel to the one ridden stays
/// a far, unlikely candidate, and Viterbi can drag the match along it where
/// the road actually ridden can't be reached.
const CANDIDATE_SPREAD_M: f64 = 2.0 * GPS_SIGMA_M;
/// Scale of the transition score: metres of difference between road and
/// straight-line distance per unit of log-probability.
const BETA_M: f64 = 10.0;
/// The road between two fixes may be at most this many times the straight
/// line, plus [`DETOUR_SLACK_M`], and never more than [`MAX_SEARCH_M`].
const DETOUR_FACTOR: f64 = 2.0;
const DETOUR_SLACK_M: f64 = 200.0;
const MAX_SEARCH_M: f64 = 20_000.0;

/// A stretch of a track matched to the road network.
#[derive(Debug, Clone, PartialEq)]
pub struct MatchedPiece {
    /// Index of the first and last track points the piece covers.
    pub first_point: usize,
    pub last_point: usize,
    /// The OSM ways ridden, in order, each as a node range.
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
}

/// A track fitted to the roads: the matched pieces in track order. Points
/// between pieces could not be matched (off the network or a long gap).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct MatchedTrack {
    pub pieces: Vec<MatchedPiece>,
}

/// One step of the Viterbi lattice: a track point and its candidates, each
/// with its best score so far and how it was reached.
struct Step {
    point: usize,
    candidates: Vec<RoadPoint>,
    score: Vec<f64>,
    /// For each candidate: the previous step's candidate and the road
    /// between them (empty in a chain's first step).
    back: Vec<Option<(usize, Vec<Partial>)>>,
}

fn emission(c: &RoadPoint) -> f64 {
    let z = c.distance_m / GPS_SIGMA_M;
    -0.5 * z * z
}

fn transition(road_m: f64, straight_m: f64) -> f64 {
    -(road_m - straight_m).abs() / BETA_M
}

/// Matches `points` (in recording order) to the roads of `region`. Points
/// outside `bounds` (south-west, north-east) or far from any road are
/// skipped.
pub(crate) fn match_track(
    region: &Region,
    bounds: (LatLon, LatLon),
    points: &[LatLon],
) -> Result<MatchedTrack, CoreError> {
    if points.len() > MAX_TRACK_POINTS {
        return Err(CoreError::InvalidArgument(format!(
            "a track may have at most {MAX_TRACK_POINTS} points, got {}",
            points.len()
        )));
    }
    for p in points {
        p.validate()?;
    }
    let (sw, ne) = bounds;
    let inside =
        |p: &LatLon| (sw.lat..=ne.lat).contains(&p.lat) && (sw.lon..=ne.lon).contains(&p.lon);

    let mut pieces = Vec::new();
    let mut chain: Vec<Step> = Vec::new();
    let mut last_used: Option<LatLon> = None;
    for (i, &p) in points.iter().enumerate() {
        if !inside(&p) || last_used.is_some_and(|q| haversine_m(q, p) < MIN_STEP_M) {
            continue;
        }
        let mut candidates = nearby(region, p, CANDIDATE_RADIUS_M, MAX_CANDIDATES);
        let Some(nearest) = candidates.first().map(|c| c.distance_m) else {
            continue;
        };
        candidates.retain(|c| c.distance_m <= nearest + CANDIDATE_SPREAD_M);
        last_used = Some(p);

        let next = match chain.last() {
            None => None,
            Some(prev) => advance(region, points, prev, i, &candidates),
        };
        let step = next.unwrap_or_else(|| {
            // A new chain: the previous one (if any) ends here.
            pieces.extend(finish(region, &chain));
            chain.clear();
            Step {
                point: i,
                score: candidates.iter().map(emission).collect(),
                back: vec![None; candidates.len()],
                candidates,
            }
        });
        chain.push(step);
    }
    pieces.extend(finish(region, &chain));
    Ok(MatchedTrack { pieces })
}

/// The Viterbi step from `prev` to point `i`, or `None` if no candidate of
/// `i` can be reached from any candidate of `prev`.
fn advance(
    region: &Region,
    points: &[LatLon],
    prev: &Step,
    i: usize,
    candidates: &[RoadPoint],
) -> Option<Step> {
    let straight = haversine_m(points[prev.point], points[i]);
    let limit = (straight * DETOUR_FACTOR + DETOUR_SLACK_M).min(MAX_SEARCH_M);
    let mut score = vec![f64::NEG_INFINITY; candidates.len()];
    let mut back: Vec<Option<(usize, Vec<Partial>)>> = vec![None; candidates.len()];
    for (k, from) in prev.candidates.iter().enumerate() {
        let base = prev.score[k];
        for (j, path) in shortest_within(region, from, candidates, limit)
            .into_iter()
            .enumerate()
        {
            let Some((road, parts)) = path else {
                continue;
            };
            let s = base + transition(road, straight) + emission(&candidates[j]);
            if s > score[j] {
                score[j] = s;
                back[j] = Some((k, parts));
            }
        }
    }
    if back.iter().all(Option::is_none) {
        return None;
    }
    Some(Step {
        point: i,
        candidates: candidates.to_vec(),
        score,
        back,
    })
}

/// The matched piece of a chain: its best final candidate traced back to
/// the start. A chain of fewer than two points matches nothing.
fn finish(region: &Region, chain: &[Step]) -> Option<MatchedPiece> {
    let (first, last) = (chain.first()?, chain.last()?);
    if chain.len() < 2 {
        return None;
    }
    let (mut k, _) = last
        .score
        .iter()
        .enumerate()
        .filter(|(_, s)| s.is_finite())
        .max_by(|a, b| a.1.total_cmp(b.1))?;
    // Road pieces between consecutive points, collected backwards.
    let mut legs: Vec<&[Partial]> = Vec::with_capacity(chain.len());
    for step in chain.iter().rev() {
        let Some((prev, parts)) = &step.back[k] else {
            break;
        };
        legs.push(parts);
        k = *prev;
    }
    let mut parts: Vec<Partial> = Vec::new();
    for leg in legs.into_iter().rev() {
        for &p in leg {
            if p.to - p.from <= 0.0 {
                continue;
            }
            match parts.last_mut() {
                // Consecutive legs continue each other along the same edge.
                Some(q) if q.edge == p.edge && (q.to - p.from).abs() < 1e-9 => q.to = p.to,
                _ => parts.push(p),
            }
        }
    }
    let draft = trace(region, &parts);
    if draft.geometry.len() < 2 || draft.distance_m <= 0.0 {
        return None;
    }
    Some(MatchedPiece {
        first_point: first.point,
        last_point: last.point,
        ways: draft.ways,
        geometry: draft.geometry,
        distance_m: draft.distance_m,
    })
}

#[cfg(test)]
mod tests;
