// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Suggested sections from quick-tags (PRD R3): about [`TAG_REACH_M`] of
//! road on each side of where the rider tapped, for the rider to confirm,
//! trim or discard after the ride.
//!
//! With the ride's track, the stretch actually ridden around the tag is
//! map-matched. Without one (or when the track has no fix near the tag's
//! time) the road is followed from the tagged point in the direction of
//! travel, keeping to the straightest way on at junctions and preferring
//! the same OSM way.

use std::collections::HashSet;

use crate::draft::{SectionDraft, from_path};
use crate::geo::haversine_m;
use crate::region::Region;
use crate::region::format::{Edge, edge_flags};
use crate::route::{Partial, edge_line, twin};
use crate::tag::Tag;
use crate::track::TrackPoint;
use crate::{CoreError, Engine, LatLon};

/// Road taken on each side of a tag, in metres.
pub const TAG_REACH_M: f64 = 1000.0;
/// Sharpest turn followed at a junction, in degrees.
const MAX_TURN_DEG: f64 = 60.0;
/// A junction's way on along the same OSM way counts as this much
/// straighter, so a road is followed through small bends at junctions.
const SAME_WAY_BONUS_DEG: f64 = 30.0;
/// A track fix further than this in time from the tag isn't used for it.
const MAX_FIX_GAP_MS: i64 = 60_000;
/// Most edges followed on each side (short edges in towns).
const MAX_STEPS: usize = 500;

/// The suggested section for `tag`, using its ride's fixes if given.
pub(crate) fn from_tag(
    engine: &Engine,
    tag: &Tag,
    track: Option<&[TrackPoint]>,
) -> Result<SectionDraft, CoreError> {
    tag.position.validate()?;
    if let Some(draft) = track.and_then(|t| from_track(engine, tag, t)) {
        return Ok(draft);
    }
    along_road(engine, tag.position, tag.heading_deg)
}

/// The stretch of `track` ridden around the tag, map-matched; `None` if the
/// track has no fix near the tag's time or nothing matches.
fn from_track(engine: &Engine, tag: &Tag, track: &[TrackPoint]) -> Option<SectionDraft> {
    let (i, nearest) = track
        .iter()
        .enumerate()
        .min_by_key(|(_, p)| p.time_ms.abs_diff(tag.time_ms))?;
    if nearest.time_ms.abs_diff(tag.time_ms) > MAX_FIX_GAP_MS.unsigned_abs() {
        return None;
    }
    let step = |k: usize, j: usize| haversine_m(track[k].position, track[j].position);
    let (mut a, mut before) = (i, 0.0);
    while a > 0 && before < TAG_REACH_M {
        before += step(a - 1, a);
        a -= 1;
    }
    let (mut b, mut after) = (i, 0.0);
    while b + 1 < track.len() && after < TAG_REACH_M {
        after += step(b, b + 1);
        b += 1;
    }
    let points: Vec<LatLon> = track[a..=b].iter().map(|p| p.position).collect();
    let matched = engine.match_track(&points).ok()?;
    let k = i - a;
    let piece = matched
        .pieces
        .iter()
        .find(|p| p.first_point <= k && k <= p.last_point)
        .or_else(|| {
            matched
                .pieces
                .iter()
                .max_by(|x, y| x.distance_m.total_cmp(&y.distance_m))
        })?;
    Some(SectionDraft {
        ways: piece.ways.clone(),
        geometry: piece.geometry.clone(),
        distance_m: piece.distance_m,
    })
}

/// [`TAG_REACH_M`] of road behind and ahead of `position`, in the direction
/// of `heading_deg` (the road's own direction if unknown).
pub(crate) fn along_road(
    engine: &Engine,
    position: LatLon,
    heading_deg: Option<f64>,
) -> Result<SectionDraft, CoreError> {
    let region = engine.region();
    let p = engine.snap(position)?;
    let (mut edge, mut offset) = (p.edge, p.offset);
    if let Some(h) = heading_deg.filter(|h| h.is_finite()) {
        let e = region.edges()[edge as usize];
        if turn(bearing_at(region, &e, offset), h) > 90.0
            && let Some(t) = twin(region, edge)
        {
            edge = t;
            offset = 1.0 - offset;
        }
    }
    let mut parts = walk_back(region, edge, offset, TAG_REACH_M);
    for part in walk_on(region, edge, offset, TAG_REACH_M) {
        match parts.last_mut() {
            Some(q) if q.edge == part.edge && (q.to - part.from).abs() < 1e-9 => q.to = part.to,
            _ => parts.push(part),
        }
    }
    parts.retain(|p| p.to > p.from);
    from_path(region, &parts)
}

fn length_m(e: &Edge) -> f64 {
    f64::from(e.length_dm) / 10.0
}

/// Pieces from `offset` on `edge` onwards for `reach` metres, in travel
/// order.
fn walk_on(region: &Region, edge: u32, offset: f64, reach: f64) -> Vec<Partial> {
    let mut parts = Vec::new();
    let (mut id, mut from, mut left) = (edge, offset, reach);
    let mut seen = HashSet::new();
    loop {
        let e = region.edges()[id as usize];
        let len = length_m(&e);
        let avail = (1.0 - from) * len;
        if len > 0.0 && avail >= left {
            parts.push(Partial {
                edge: id,
                from,
                to: from + left / len,
            });
            break;
        }
        parts.push(Partial {
            edge: id,
            from,
            to: 1.0,
        });
        left -= avail;
        if !seen.insert(id) || parts.len() >= MAX_STEPS {
            break;
        }
        let exit = exit_bearing(region, &e);
        let way = region.way_refs()[id as usize].way_id;
        let next = region
            .out_edges(e.head)
            .filter(|&c| {
                let ce = region.edges()[c as usize];
                ce.geometry != e.geometry && ce.flags & edge_flags::FERRY == 0
            })
            .map(|c| {
                let t = turn(exit, entry_bearing(region, &region.edges()[c as usize]));
                (c, t, region.way_refs()[c as usize].way_id == way)
            })
            .filter(|&(_, t, _)| t <= MAX_TURN_DEG)
            .min_by(|a, b| {
                score(a.1, a.2)
                    .total_cmp(&score(b.1, b.2))
                    .then(a.0.cmp(&b.0))
            });
        match next {
            Some((c, _, _)) => (id, from) = (c, 0.0),
            None => break,
        }
    }
    parts
}

/// Pieces leading up to `offset` on `edge` from `reach` metres back, in
/// travel order.
fn walk_back(region: &Region, edge: u32, offset: f64, reach: f64) -> Vec<Partial> {
    let mut parts = Vec::new();
    let (mut id, mut to, mut left) = (edge, offset, reach);
    let mut seen = HashSet::new();
    loop {
        let e = region.edges()[id as usize];
        let len = length_m(&e);
        let avail = to * len;
        if len > 0.0 && avail >= left {
            parts.push(Partial {
                edge: id,
                from: to - left / len,
                to,
            });
            break;
        }
        parts.push(Partial {
            edge: id,
            from: 0.0,
            to,
        });
        left -= avail;
        if !seen.insert(id) || parts.len() >= MAX_STEPS {
            break;
        }
        let entry = entry_bearing(region, &e);
        let way = region.way_refs()[id as usize].way_id;
        let prev = region
            .in_edges(e.tail)
            .iter()
            .copied()
            .filter(|&c| {
                let ce = region.edges().get(c as usize);
                ce.is_some_and(|ce| ce.geometry != e.geometry && ce.flags & edge_flags::FERRY == 0)
            })
            .map(|c| {
                let t = turn(exit_bearing(region, &region.edges()[c as usize]), entry);
                (c, t, region.way_refs()[c as usize].way_id == way)
            })
            .filter(|&(_, t, _)| t <= MAX_TURN_DEG)
            .min_by(|a, b| {
                score(a.1, a.2)
                    .total_cmp(&score(b.1, b.2))
                    .then(a.0.cmp(&b.0))
            });
        match prev {
            Some((c, _, _)) => (id, to) = (c, 1.0),
            None => break,
        }
    }
    parts.reverse();
    parts
}

fn score(turn_deg: f64, same_way: bool) -> f64 {
    turn_deg - if same_way { SAME_WAY_BONUS_DEG } else { 0.0 }
}

/// Initial bearing from `a` to `b` in degrees clockwise from north (flat
/// approximation, fine over one road segment).
fn bearing(a: LatLon, b: LatLon) -> f64 {
    let dx = (b.lon - a.lon) * a.lat.to_radians().cos();
    let dy = b.lat - a.lat;
    dx.atan2(dy).to_degrees().rem_euclid(360.0)
}

/// Difference between two bearings, 0–180 degrees.
fn turn(a: f64, b: f64) -> f64 {
    let d = (b - a).rem_euclid(360.0);
    if d > 180.0 { 360.0 - d } else { d }
}

/// Distinct consecutive points of an edge's shape, in travel order.
fn shape(region: &Region, e: &Edge) -> Vec<LatLon> {
    let mut line = edge_line(region, e);
    line.dedup();
    line
}

fn entry_bearing(region: &Region, e: &Edge) -> f64 {
    match shape(region, e).as_slice() {
        [a, b, ..] => bearing(*a, *b),
        _ => 0.0,
    }
}

fn exit_bearing(region: &Region, e: &Edge) -> f64 {
    match shape(region, e).as_slice() {
        [.., a, b] => bearing(*a, *b),
        _ => 0.0,
    }
}

/// Bearing of the edge's segment at fraction `offset` of its length.
fn bearing_at(region: &Region, e: &Edge, offset: f64) -> f64 {
    let line = shape(region, e);
    let lengths: Vec<f64> = line.windows(2).map(|w| haversine_m(w[0], w[1])).collect();
    let target = offset.clamp(0.0, 1.0) * lengths.iter().sum::<f64>();
    let mut walked = 0.0;
    for (i, len) in lengths.iter().enumerate() {
        walked += len;
        if walked >= target {
            return bearing(line[i], line[i + 1]);
        }
    }
    lengths
        .len()
        .checked_sub(1)
        .map_or(0.0, |i| bearing(line[i], line[i + 1]))
}

#[cfg(test)]
mod tests;
