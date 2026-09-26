// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Round trips (PRD R7, ADR-0007): loops from a start through two
//! waypoints, routed leg by leg with the one-way cost (favourites and
//! curvature) plus a penalty on roads the loop already rides, resized once
//! towards the target, and kept when they are within ±15 % of it and ride
//! the same road twice for at most 10 % of their length. The best loops by
//! worth per second that differ from each other are returned.
//!
//! Round trips usually start at home, often in a town, where the way out
//! and the way home share streets. So roads in the home zone around the
//! start ([`home_radius_m`]) are neither penalised nor counted as reuse.

use std::collections::{HashMap, HashSet};

use crate::favourites::Favourites;
use crate::geo::{bearing_deg, destination, haversine_m};
use crate::region::format::{RoadClass, Surface, edge_flags};
use crate::route::{Cost, Fun, Off, Partial, Routed, build, latlon, path};
use crate::scoring::PARAMS;
use crate::{
    CoreError, Engine, Gravel, LatLon, LoopOptions, RoadPoint, RoundTripTarget, Route, RouteOptions,
};

/// Headings tried, evenly spread.
pub const HEADINGS: usize = 12;
/// Half the angle between the two waypoints, seen from the start.
const SPREAD_DEG: f64 = 30.0;
/// A loop may miss its target by this share.
pub const TOLERANCE: f64 = 0.15;
/// Most of a loop's length that may ride a road it already rides.
pub const MAX_REUSE: f64 = 0.10;
/// Two loops are alternatives when they share less than this share of
/// the shorter one.
pub const MAX_OVERLAP: f64 = 0.5;
/// Most loops returned.
pub const MAX_LOOPS: usize = 3;
/// Shortest and longest loop asked for.
pub const MIN_TARGET_M: f64 = 5_000.0;
pub const MAX_TARGET_M: f64 = 400_000.0;
/// Shares of the radius tried for a waypoint, in order, until one lies
/// near a road.
const WAYPOINT_PULL_IN: [f64; 3] = [1.0, 0.8, 0.6];
/// How far from a waypoint's place a road for it is looked for.
const WAYPOINT_SEARCH_M: f64 = 1_500.0;
/// Most roads looked at for a waypoint.
const WAYPOINT_CANDIDATES: usize = 64;

/// A favourite's middle can be a waypoint when it lies this far from the
/// start (as a share of the waypoint radius) and within `SPREAD_DEG` of
/// the waypoint's bearing.
const ANCHOR_REACH: (f64, f64) = (0.6, 1.4);
/// The home zone's radius as a share of the target, and its bounds.
const HOME_SHARE: f64 = 0.05;
const HOME_MIN_M: f64 = 2_000.0;
const HOME_MAX_M: f64 = 5_000.0;

/// Radius of the home zone around the start for a loop of `target_m`:
/// roads wholly inside it may be ridden out and back freely (the way out
/// of town and the way home).
pub fn home_radius_m(target_m: f64) -> f64 {
    (target_m * HOME_SHARE).clamp(HOME_MIN_M, HOME_MAX_M)
}

/// A loop and how it was judged.
struct Loop {
    routed: Routed,
    /// Metres of each road geometry ridden, for overlap.
    roads: HashMap<u32, f64>,
    /// Metres on roads the loop had already ridden (either way).
    reused_m: f64,
    heading: usize,
    /// Which way the loop heads: from the start to its middle, degrees.
    bearing: f64,
}

/// Up to [`MAX_LOOPS`] round trips from `start` of about `target`, best
/// first; see the module docs. At least two when two valid loops exist.
pub fn round_trip(
    engine: &Engine,
    start: LatLon,
    target: RoundTripTarget,
    opts: &RouteOptions,
    favourites: &Favourites,
) -> Result<Vec<Route>, CoreError> {
    loops(
        engine,
        start,
        target,
        opts,
        favourites,
        &LoopOptions::default(),
    )
}

/// [`round_trip`], shaped by `shape`: a non-zero seed gives another set of
/// loops (see [`Candidates`]).
pub fn loops(
    engine: &Engine,
    start: LatLon,
    target: RoundTripTarget,
    opts: &RouteOptions,
    favourites: &Favourites,
    shape: &LoopOptions,
) -> Result<Vec<Route>, CoreError> {
    start.validate()?;
    target.validate()?;
    opts.validate()?;
    shape.validate()?;
    favourites.check(engine)?;
    let target_m = match target {
        RoundTripTarget::DistanceM(m) => m,
        RoundTripTarget::DurationS(s) => s * PARAMS.loop_speed_mps,
    };
    if !(MIN_TARGET_M..=MAX_TARGET_M).contains(&target_m) {
        return Err(CoreError::InvalidArgument(format!(
            "a round trip must be {}–{} km (or about as long in time)",
            MIN_TARGET_M / 1000.0,
            MAX_TARGET_M / 1000.0
        )));
    }
    let s = engine.snap(start)?;
    let fun = Fun::new(engine.region(), favourites, opts);
    let fits = |r: &Route| match target {
        RoundTripTarget::DistanceM(m) => (r.distance_m - m).abs() <= m * TOLERANCE,
        RoundTripTarget::DurationS(t) => (r.duration_s - t).abs() <= t * TOLERANCE,
    };
    let size = |r: &Route| match target {
        RoundTripTarget::DistanceM(_) => r.distance_m,
        RoundTripTarget::DurationS(_) => r.duration_s * PARAMS.loop_speed_mps,
    };

    let radius = target_m / (3.0 * PARAMS.loop_detour);
    let home = home_radius_m(target_m);
    let collect = |candidates: Candidates| {
        let mut loops = Vec::new();
        for (heading, c) in candidates.enumerate() {
            let at = |r: f64| {
                loop_at(
                    engine, &fun, opts, &s, c.bearing, c.spread, r, home, favourites,
                )
            };
            let Some(first) = at(radius * c.size) else {
                continue;
            };
            // One resize towards the target.
            let found = if fits(&first.routed.route) {
                Some(first)
            } else {
                let scale = (target_m / size(&first.routed.route).max(1.0)).clamp(0.5, 2.0);
                at(radius * c.size * scale).filter(|l| fits(&l.routed.route))
            };
            if let Some(mut l) = found {
                l.heading = heading;
                l.bearing = middle_bearing(&s, &l.routed.route.geometry);
                if reuse_share(&l) <= MAX_REUSE {
                    loops.push(l);
                }
            }
        }
        loops
    };
    // The loops that way first; when there are fewer than two (the sea,
    // the region's edge), the best of any way fill up to two.
    let found = collect(Candidates::new(shape.seed, shape.bearing));
    let mut kept = if shape.seed != 0 && shape.bearing.is_none() {
        pick_shuffled(found, shape.seed, MAX_LOOPS)
    } else {
        pick(Vec::new(), found, MAX_LOOPS)
    };
    if shape.bearing.is_some() && kept.len() < 2 {
        kept = pick(kept, collect(Candidates::new(shape.seed, None)), 2);
    }
    if kept.is_empty() {
        return Err(CoreError::NoRoute(
            "no loop of that length from here; try another length or start".into(),
        ));
    }
    Ok(kept
        .into_iter()
        .map(|l| {
            let mut r = l.routed.route;
            // A loop has no fastest route to compare with.
            r.fastest_duration_s = r.duration_s;
            r
        })
        .collect())
}

/// `kept` plus the best of `loops` (worth per second; ties by heading, so
/// results are stable) that overlap every kept loop by less than
/// [`MAX_OVERLAP`], up to `max` loops in all.
fn pick(mut kept: Vec<Loop>, mut loops: Vec<Loop>, max: usize) -> Vec<Loop> {
    loops.sort_by(|a, b| {
        worth_per_s(b)
            .total_cmp(&worth_per_s(a))
            .then(a.heading.cmp(&b.heading))
    });
    for l in loops {
        if kept.len() >= max {
            break;
        }
        if kept.iter().all(|k| overlap(k, &l) < MAX_OVERLAP) {
            kept.push(l);
        }
    }
    kept
}

/// A shuffled set, so that a set doesn't always head the best way: the
/// best loop; then the best loop heading within `loop_focus_deg` of a
/// direction drawn from `seed`, if it is worth at least
/// `loop_explore_share` of the best; then the best of the rest heading at
/// least `loop_apart_deg` away from every kept loop; then, if still
/// short, any that overlap little enough. Loops are returned best first.
fn pick_shuffled(mut loops: Vec<Loop>, seed: u32, max: usize) -> Vec<Loop> {
    loops.sort_by(|a, b| {
        worth_per_s(b)
            .total_cmp(&worth_per_s(a))
            .then(a.heading.cmp(&b.heading))
    });
    let Some(best) = loops.first().map(worth_per_s) else {
        return Vec::new();
    };
    let focus = focus_bearing(seed);
    // At least this worth to take the focus slot: a share of the best
    // (worth can be below 0 on dull roads).
    let floor = best - (1.0 - PARAMS.loop_explore_share) * best.abs();
    let fits = |kept: &[Loop], l: &Loop| kept.iter().all(|k| overlap(k, l) < MAX_OVERLAP);
    let apart = |kept: &[Loop], l: &Loop| {
        kept.iter()
            .all(|k| angle_between(k.bearing, l.bearing) >= PARAMS.loop_apart_deg)
    };
    let mut rest: Vec<Option<Loop>> = loops.into_iter().map(Some).collect();
    let mut kept: Vec<Loop> = Vec::new();
    let mut take = |kept: &mut Vec<Loop>, ok: &dyn Fn(&[Loop], &Loop) -> bool| {
        if kept.len() >= max {
            return;
        }
        if let Some(slot) = rest
            .iter_mut()
            .find(|l| l.as_ref().is_some_and(|l| ok(kept, l)))
        {
            kept.extend(slot.take());
        }
    };
    take(&mut kept, &|_, _| true);
    take(&mut kept, &|k, l| {
        angle_between(l.bearing, focus) <= PARAMS.loop_focus_deg
            && worth_per_s(l) >= floor
            && fits(k, l)
    });
    for _ in 0..max {
        take(&mut kept, &|k, l| apart(k, l) && fits(k, l));
    }
    while kept.len() < max {
        let before = kept.len();
        take(&mut kept, &|k, l| fits(k, l));
        if kept.len() == before {
            break;
        }
    }
    kept.sort_by(|a, b| {
        worth_per_s(b)
            .total_cmp(&worth_per_s(a))
            .then(a.heading.cmp(&b.heading))
    });
    kept
}

/// The direction a shuffled set looks in, from its seed (not the same
/// numbers as the candidates' own).
fn focus_bearing(seed: u32) -> f64 {
    let mut c = Candidates::new(seed ^ 0x5eed_f0c5, None);
    c.unit() * 360.0
}

/// Degrees between two bearings, 0–180.
fn angle_between(a: f64, b: f64) -> f64 {
    let d = (a - b).rem_euclid(360.0);
    d.min(360.0 - d)
}

/// The bearing from `start` to the middle (mean point) of `line`.
fn middle_bearing(start: &RoadPoint, line: &[LatLon]) -> f64 {
    if line.is_empty() {
        return 0.0;
    }
    let n = line.len() as f64;
    let (lat, lon) = line
        .iter()
        .fold((0.0, 0.0), |(a, b), p| (a + p.lat, b + p.lon));
    bearing_deg(
        start.position,
        LatLon {
            lat: lat / n,
            lon: lon / n,
        },
    )
}

fn worth_per_s(l: &Loop) -> f64 {
    l.routed.value_s / l.routed.route.duration_s.max(1.0)
}

/// One candidate loop: the waypoints lie `spread` degrees either side of
/// `bearing`, at the loop radius times `size`.
#[derive(Debug, Clone, Copy, PartialEq)]
struct Candidate {
    bearing: f64,
    spread: f64,
    size: f64,
}

/// Half the fan of headings tried when the loops should head one way.
const DIRECTION_SPREAD_DEG: f64 = 60.0;

/// The [`HEADINGS`] candidates of a seed and an optional direction. The
/// headings are every 30° all round, or spread evenly over
/// [`DIRECTION_SPREAD_DEG`] either side of `bearing`. Seed 0: spread
/// [`SPREAD_DEG`], size 1 (the standard loops). Other seeds: the headings
/// turned by up to one step, and each candidate's spread 15–45° and size
/// 0.8–1.2, from a small deterministic generator (splitmix64), so a seed
/// always gives the same loops.
struct Candidates {
    seed: u32,
    state: u64,
    first: f64,
    step: f64,
    turn: f64,
    i: usize,
}

impl Candidates {
    fn new(seed: u32, bearing: Option<f64>) -> Self {
        let (first, step) = match bearing {
            None => (0.0, 360.0 / HEADINGS as f64),
            Some(b) => (
                b - DIRECTION_SPREAD_DEG,
                2.0 * DIRECTION_SPREAD_DEG / (HEADINGS - 1) as f64,
            ),
        };
        let mut c = Self {
            seed,
            state: u64::from(seed),
            first,
            step,
            turn: 0.0,
            i: 0,
        };
        if seed != 0 {
            c.turn = c.unit() * step;
        }
        c
    }

    /// A number in [0, 1) (splitmix64).
    fn unit(&mut self) -> f64 {
        self.state = self.state.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        z ^= z >> 31;
        (z >> 11) as f64 / (1u64 << 53) as f64
    }
}

impl Iterator for Candidates {
    type Item = Candidate;

    fn next(&mut self) -> Option<Candidate> {
        if self.i == HEADINGS {
            return None;
        }
        let bearing = (self.first + self.i as f64 * self.step + self.turn).rem_euclid(360.0);
        self.i += 1;
        if self.seed == 0 {
            return Some(Candidate {
                bearing,
                spread: SPREAD_DEG,
                size: 1.0,
            });
        }
        let spread = 15.0 + 30.0 * self.unit();
        let size = 0.8 + 0.4 * self.unit();
        Some(Candidate {
            bearing,
            spread,
            size,
        })
    }
}

/// The loop through two waypoints `spread` degrees either side of
/// `bearing` at `radius`, or through a favourite near a waypoint; `None` when a
/// waypoint has no road or a leg no route. Roads within `home` metres of
/// the start are free to ride twice.
#[allow(clippy::too_many_arguments)]
fn loop_at(
    engine: &Engine,
    fun: &Fun,
    opts: &RouteOptions,
    start: &RoadPoint,
    bearing: f64,
    spread: f64,
    radius: f64,
    home: f64,
    favourites: &Favourites,
) -> Option<Loop> {
    let region = engine.region();
    let at_home = |edge: u32| {
        let e = region.edges()[edge as usize];
        [e.tail, e.head]
            .iter()
            .all(|&n| haversine_m(start.position, latlon(region.nodes()[n as usize])) <= home)
    };
    let mut taken: Vec<LatLon> = Vec::new();
    let mut waypoint = |b: f64| -> Option<RoadPoint> {
        if let Some(p) = anchor_near(start.position, b, radius, favourites, &taken) {
            taken.push(p);
            return engine.snap(p).ok();
        }
        // A waypoint with no road for a loop near it (the sea, the
        // region's edge, forest) is pulled in towards the start; the
        // resize makes up the length. Failing that, any road will do.
        WAYPOINT_PULL_IN
            .iter()
            .find_map(|&f| {
                let p = destination(start.position, b, radius * f);
                loop_road_near(engine, p, opts).inspect(|_| taken.push(p))
            })
            .or_else(|| {
                let p = destination(start.position, b, radius);
                engine.snap(p).ok().inspect(|_| taken.push(p))
            })
    };
    let w1 = waypoint(bearing - spread)?;
    let w2 = waypoint(bearing + spread)?;

    let mut used: HashSet<u32> = HashSet::new();
    let mut parts: Vec<Partial> = Vec::new();
    for (from, to) in [(start, &w1), (&w1, &w2), (&w2, start)] {
        let cost = Cost::Loop(Off::of(opts), *fun, PARAMS.loop_pull, &used);
        let leg = path(region, from, to, cost, engine.max_speed_kmh()).ok()?;
        for p in leg.iter().filter(|p| !at_home(p.edge)) {
            used.insert(region.edges()[p.edge as usize].geometry);
        }
        append_leg(region, &mut parts, leg);
    }
    let mut roads: HashMap<u32, f64> = HashMap::new();
    let mut reused = 0.0;
    for p in parts.iter().filter(|p| !at_home(p.edge)) {
        let e = region.edges()[p.edge as usize];
        let metres = (p.to - p.from).max(0.0) * f64::from(e.length_dm) / 10.0;
        let seen = roads.entry(e.geometry).or_insert(0.0);
        if *seen > 0.0 {
            reused += metres;
        }
        *seen += metres;
    }
    Some(Loop {
        routed: build(fun, &parts),
        roads,
        reused_m: reused,
        heading: 0,
        bearing: 0.0,
    })
}

/// Appends `leg` to `parts`, cutting out an out-and-back where they
/// meet: at a waypoint partway along a road, the next leg may start by
/// riding back the way the last one came (the waypoint is only a guide,
/// not a place to visit). While the last piece so far and the leg's first
/// piece run opposite ways along the same road, the shared stretch is
/// removed from both.
fn append_leg(region: &crate::region::Region, parts: &mut Vec<Partial>, leg: Vec<Partial>) {
    const EPS: f64 = 1e-9;
    let mut leg = leg.into_iter().peekable();
    while let (Some(&prev), Some(&next)) = (parts.last(), leg.peek()) {
        if prev.to - prev.from <= EPS {
            parts.pop();
            continue;
        }
        if next.to - next.from <= EPS {
            leg.next();
            continue;
        }
        let edges = region.edges();
        let (Some(ep), Some(en)) = (edges.get(prev.edge as usize), edges.get(next.edge as usize))
        else {
            break;
        };
        // Opposite ways along one road: the twin edge, where fraction x
        // is 1 - x of the other; and the next piece starts where the last
        // one ends.
        let twins = prev.edge != next.edge && ep.geometry == en.geometry;
        if !twins || (1.0 - next.from - prev.to).abs() > 1e-6 {
            break;
        }
        let back_to = 1.0 - next.to;
        if back_to > prev.from + EPS {
            // Turns back within the last piece: it ends there instead.
            if let Some(last) = parts.last_mut() {
                last.to = back_to;
            }
            leg.next();
            break;
        } else if back_to < prev.from - EPS {
            // Rides back past the start of the last piece: that piece
            // goes, and the next one starts where it started.
            parts.pop();
            if let Some(n) = leg.peek_mut() {
                n.from = 1.0 - prev.from;
            }
        } else {
            parts.pop();
            leg.next();
        }
    }
    parts.extend(leg);
}

/// Share of the loop's length on roads it had already ridden, outside
/// the home zone.
fn reuse_share(l: &Loop) -> f64 {
    l.reused_m / l.routed.route.distance_m.max(1.0)
}

/// Share of the shorter loop's roads that the other rides too.
fn overlap(a: &Loop, b: &Loop) -> f64 {
    let shared: f64 = a
        .roads
        .iter()
        .filter_map(|(g, m)| b.roads.get(g).map(|n| m.min(*n)))
        .sum();
    shared
        / a.routed
            .route
            .distance_m
            .min(b.routed.route.distance_m)
            .max(1.0)
}

/// The best-rated favourite whose middle lies near where a waypoint at
/// `bearing` and `radius` would go, not yet a waypoint of this loop.
/// The nearest point near `p` on a road a loop should pass through: a
/// main or minor through road (trunk to unclassified), open to all, and
/// paved unless the rider prefers gravel. Never a service road, driveway,
/// residential street, track or ferry, which the loop would ride out to
/// and back for nothing.
fn loop_road_near(engine: &Engine, p: LatLon, opts: &RouteOptions) -> Option<RoadPoint> {
    let region = engine.region();
    crate::snap::nearby(region, p, WAYPOINT_SEARCH_M, WAYPOINT_CANDIDATES)
        .into_iter()
        .find(|rp| {
            let Some(e) = region.edges().get(rp.edge as usize) else {
                return false;
            };
            let through = matches!(
                RoadClass::from_u8(e.class),
                Some(
                    RoadClass::Trunk
                        | RoadClass::Primary
                        | RoadClass::Secondary
                        | RoadClass::Tertiary
                        | RoadClass::Unclassified
                )
            );
            let open = e.flags & (edge_flags::DESTINATION | edge_flags::FERRY) == 0;
            let surface = opts.gravel == Gravel::Prefer
                || Surface::from_u8(e.surface).is_none_or(Surface::is_paved);
            through && open && surface
        })
}

fn anchor_near(
    start: LatLon,
    bearing: f64,
    radius: f64,
    favourites: &Favourites,
    taken: &[LatLon],
) -> Option<LatLon> {
    favourites
        .anchors()
        .iter()
        .filter(|(p, _)| !taken.contains(p))
        .filter(|(p, _)| {
            let d = haversine_m(start, *p);
            let off = (bearing_deg(start, *p) - bearing + 540.0).rem_euclid(360.0) - 180.0;
            d >= radius * ANCHOR_REACH.0 && d <= radius * ANCHOR_REACH.1 && off.abs() <= SPREAD_DEG
        })
        .max_by(|a, b| a.1.total_cmp(&b.1))
        .map(|(p, _)| *p)
}

#[cfg(test)]
mod tests;
