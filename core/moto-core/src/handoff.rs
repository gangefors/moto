// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Handing a route to a nav app (PRD R9): GPX with the exact line as a
//! track, and route points for apps that calculate their own route
//! between points.
//!
//! A nav app recalculating between route points takes its own fastest
//! road, which may skip the favourites the route was made for. So route
//! points are placed where our fastest route between two neighbours
//! follows the line: from each point, the next one is as far along as
//! that still holds. Nav apps route differently in details, but this keeps
//! every leg on the road a fastest-road router would take anyway.

use crate::geo::{distance_to_line, haversine_m};
use crate::{CoreError, Engine, LatLon, RouteOptions};

/// Most points a route line may have (far more than a day's ride).
pub const MAX_LINE_POINTS: usize = 500_000;
/// Most route points written; beyond this the rest of the line is only in
/// the track.
pub const MAX_ROUTE_POINTS: usize = 200;
/// A fastest leg follows the line when every point of it lies this close.
const FOLLOW_TOLERANCE_M: f64 = 30.0;
/// ...and it is about as long: within this share or [`LENGTH_SLACK_M`].
const LENGTH_TOLERANCE: f64 = 0.03;
const LENGTH_SLACK_M: f64 = 50.0;
/// First leg length tried; doubled while legs still follow the line...
const FIRST_LEG_M: f64 = 2_500.0;
/// ...up to this: nav apps weigh roads differently from our router, so
/// long legs leave them room to drift off the line.
pub const MAX_LEG_M: f64 = 10_000.0;

/// Route points along `line` (a route's geometry, start to end): the
/// start, the end, and between them as few points as keep the fastest
/// route between neighbours on the line, at most [`MAX_LEG_M`] apart
/// (along the line, give or take a shape point). `line` crosses the FFI back from
/// the app, so it is checked like any input.
pub fn route_points(
    engine: &Engine,
    line: &[LatLon],
    opts: &RouteOptions,
) -> Result<Vec<LatLon>, CoreError> {
    if line.len() < 2 || line.len() > MAX_LINE_POINTS {
        return Err(CoreError::InvalidArgument(format!(
            "a route line needs 2–{MAX_LINE_POINTS} points, got {}",
            line.len()
        )));
    }
    for p in line {
        p.validate()?;
    }
    opts.validate()?;
    let mut along = Vec::with_capacity(line.len());
    let mut walked = 0.0;
    along.push(0.0);
    for w in line.windows(2) {
        walked += haversine_m(w[0], w[1]);
        along.push(walked);
    }
    let last = line.len() - 1;
    let follows = |a: usize, b: usize| -> bool {
        let Ok(r) = engine.route(line[a], line[b], opts) else {
            return false;
        };
        let stretch = &line[a..=b];
        let length = along[b] - along[a];
        (r.distance_m - length).abs() <= (length * LENGTH_TOLERANCE).max(LENGTH_SLACK_M)
            && r.geometry
                .iter()
                .all(|&p| distance_to_line(p, stretch) <= FOLLOW_TOLERANCE_M)
    };
    // The first point at least `m` metres along from `from`, or the end.
    let at = |from: usize, m: f64| -> usize {
        let target = along[from] + m;
        let i = along.partition_point(|&d| d < target);
        i.clamp(from + 1, last)
    };

    let mut points = vec![line[0]];
    let mut cur = 0;
    while cur < last && points.len() < MAX_ROUTE_POINTS - 1 {
        // Grow the leg while it follows the line...
        let (mut good, mut bad) = (cur + 1, None);
        let mut leg = FIRST_LEG_M;
        loop {
            let j = at(cur, leg);
            if follows(cur, j) {
                good = j;
                if j == last || leg >= MAX_LEG_M {
                    break;
                }
                leg = (leg * 2.0).min(MAX_LEG_M);
            } else {
                bad = Some(j);
                break;
            }
        }
        // ...then find where it stops following.
        if let Some(mut bad) = bad {
            while bad - good > 1 {
                let mid = good + (bad - good) / 2;
                if follows(cur, mid) {
                    good = mid;
                } else {
                    bad = mid;
                }
            }
        }
        points.push(line[good]);
        cur = good;
    }
    if cur < last {
        points.push(line[last]);
    }
    Ok(points)
}

#[cfg(test)]
mod tests;
