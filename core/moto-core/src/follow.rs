// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Following a route while riding it (ADR-0011): where the rider is on the
//! route, how much is left, which favourites are near, and whether they
//! have left it.
//!
//! A [`RouteFollower`] is built once from the route's line and fed one GPS
//! fix at a time. Each fix is matched to the line only within a window
//! along it, around the last matched place, so a road ridden twice (out
//! and back, a figure eight, a loop's last kilometre on its first) matches
//! the pass the rider is on. Leaving the route needs several fixes and a
//! few seconds, so GPS noise doesn't raise false alerts; poor fixes never
//! count.

use std::collections::HashMap;

use crate::geo::{bearing_deg, haversine_m, polyline_length_m};
use crate::handoff::MAX_LINE_POINTS;
use crate::section::Rating;
use crate::{CoreError, Engine, LatLon, Route, RouteOptions};

/// Longest route that can be followed, metres: far more than a day's ride.
pub const MAX_FOLLOW_M: f64 = 5_000_000.0;
/// Most favourite parts a followed route may have.
pub const MAX_FOLLOW_PARTS: usize = 10_000;
/// Favourites starting within this far ahead are shown.
pub const FAVOURITE_AHEAD_M: f64 = 2_000.0;
/// Most favourites shown at once (2026-10-02).
pub const MAX_NEAR_FAVOURITES: usize = 2;
/// Off the route: further than this, or [`OFF_ACCURACY_FACTOR`] times the
/// fix's accuracy if that is more...
pub const OFF_MIN_M: f64 = 40.0;
pub const OFF_ACCURACY_FACTOR: f64 = 1.5;
/// ...for at least this many fixes in a row...
pub const OFF_FIXES: u32 = 3;
/// ...and this long.
pub const OFF_MIN_MS: i64 = 5_000;
/// Back on the route: this close...
pub const BACK_ON_M: f64 = 25.0;
/// ...for this many fixes in a row.
pub const BACK_ON_FIXES: u32 = 2;
/// Fixes less accurate than this never move the rider on or off the route.
pub const POOR_ACCURACY_M: f64 = 50.0;
/// Finished: this close to the end...
pub const FINISH_M: f64 = 30.0;
/// ...with at least this share of the route passed.
pub const FINISH_SHARE: f64 = 0.9;
/// Joining: the rider reaches the route anywhere, heading along it
/// (2026-10-02); within its first this many metres also without a
/// bearing (standing at the start).
pub const JOIN_WITHIN_M: f64 = 1_000.0;
/// Heading along the route: within this many degrees of it...
pub const ALONG_DEG: f64 = 60.0;
/// ...against it: more than this many.
pub const AGAINST_DEG: f64 = 120.0;
/// Riding the route backwards for this many fixes in a row (heading
/// against it, with no pass the right way near) turns following back to
/// joining: the rider is on their way to the start.
pub const WRONG_WAY_FIXES: u32 = 4;

/// Where a way back may meet the route, metres on from the last matched
/// place (off the route), or from its start (joining).
pub const REJOIN_AHEAD_M: [f64; 4] = [0.0, 1_000.0, 3_000.0, 8_000.0];
pub const JOIN_AT_M: [f64; 3] = [0.0, 500.0, 1_000.0];
/// "Turn round" when the way back's first [`TURN_CHECK_M`] heads more than
/// this far from the rider's bearing.
pub const TURN_ROUND_DEG: f64 = 120.0;
pub const TURN_CHECK_M: f64 = 100.0;

/// A route ending this close to its start is a loop.
const LOOP_END_M: f64 = 100.0;

/// How far back from the last matched place a fix may still match.
const BACK_WINDOW_M: f64 = 100.0;
/// The least the window reaches ahead...
const AHEAD_WINDOW_M: f64 = 1_000.0;
/// ...or this many times the distance covered since the last fix.
const AHEAD_FACTOR: f64 = 3.0;
/// Roads further from the fix than this are not considered at all.
const SEARCH_M: f64 = 200.0;
/// Grid cells, in degrees: about 550 m north–south, 380–640 m east–west
/// in the Nordic countries (up to 70° N). Both are more than `SEARCH_M` +
/// `SAMPLE_M` / 2, so a fix's 3 × 3 cells hold every segment within
/// `SEARCH_M` of it.
const CELL_LAT: f64 = 0.005;
const CELL_LON: f64 = 0.01;
/// Segments are entered in the grid at points this far apart.
const SAMPLE_M: f64 = 100.0;
/// A segment heading more than this far from the rider's bearing...
const WRONG_WAY_DEG: f64 = 90.0;
/// ...counts as this much further away.
const WRONG_WAY_PENALTY_M: f64 = 60.0;
/// Bearings are trusted from this speed up.
const BEARING_MIN_MPS: f64 = 2.0;
/// Each metre between a candidate and where the rider is expected along
/// the route counts as this much distance.
const EXPECTED_WEIGHT: f64 = 0.02;
/// Favourite parts of the same rating closer than this are one stretch.
const TOUCHING_M: f64 = 50.0;
/// A favourite part's start is found on the line within this distance.
const PART_ON_LINE_M: f64 = 15.0;

/// One GPS fix.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FollowFix {
    pub position: LatLon,
    /// Milliseconds since the Unix epoch.
    pub time_ms: i64,
    /// Estimated horizontal accuracy, metres.
    pub accuracy_m: Option<f64>,
    pub speed_mps: Option<f64>,
    /// Degrees from north.
    pub bearing_deg: Option<f64>,
}

impl FollowFix {
    fn validate(&self) -> Result<(), CoreError> {
        self.position.validate()?;
        let bad = |what: &str| Err(CoreError::InvalidArgument(format!("fix: invalid {what}")));
        if self
            .accuracy_m
            .is_some_and(|a| !(a.is_finite() && a >= 0.0))
        {
            return bad("accuracy");
        }
        if self.speed_mps.is_some_and(|s| !(s.is_finite() && s >= 0.0)) {
            return bad("speed");
        }
        if self.bearing_deg.is_some_and(|b| !b.is_finite()) {
            return bad("bearing");
        }
        Ok(())
    }
}

/// Where the rider stands with the route.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FollowPhase {
    /// Not yet on the route's first kilometre.
    Joining,
    OnRoute,
    OffRoute,
    /// At the end; the follower takes no more fixes.
    Finished,
}

/// A favourite on the route ahead or under the rider.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NearFavourite {
    pub rating: Rating,
    /// True while the rider is on it.
    pub on: bool,
    /// To its start, or while on it, to its end, metres.
    pub distance_m: f64,
}

/// The state after a fix.
#[derive(Debug, Clone, PartialEq)]
pub struct FollowState {
    pub phase: FollowPhase,
    /// How far along the route the rider is, metres.
    pub along_m: f64,
    pub left_m: f64,
    pub left_s: f64,
    pub total_m: f64,
    /// The route's segment the rider was last matched to (from point
    /// `segment` to `segment + 1`) and how far along it, 0–1, so the map
    /// can draw what is behind and ahead in its own units.
    pub segment: u32,
    pub segment_t: f64,
    /// How far the rider is from the route: while off it, or joining.
    /// None when no road of the route is near (more than 200 m away, or
    /// for joining, the first kilometre further than that).
    pub off_m: Option<f64>,
    /// When the rider left the route (fix time), while off it.
    pub off_since_ms: Option<i64>,
    /// At most [`MAX_NEAR_FAVOURITES`], nearest first.
    pub favourites: Vec<NearFavourite>,
    /// Joining because the rider is on the route the wrong way (on their
    /// way to its start).
    pub wrong_way: bool,
    /// The rider has been at the route's start, heading along it (the app
    /// starts recording then). Joining further on shows the figures from
    /// there but doesn't count as started; a loop joined part way starts
    /// when the rider comes round to its start (2026-10-02).
    pub started: bool,
}

/// A favourite part placed on the route.
#[derive(Debug, Clone, Copy)]
struct Stretch {
    from_m: f64,
    to_m: f64,
    rating: Rating,
}

/// A place on the line near a fix.
#[derive(Debug, Clone, Copy)]
struct Candidate {
    segment: usize,
    t: f64,
    along_m: f64,
    distance_m: f64,
    heading: Heading,
}

/// How the rider heads relative to the route where a fix matched.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Heading {
    /// No bearing (too slow, or none given).
    Unknown,
    Along,
    Across,
    Against,
}

/// Follows one route through a ride; see the module docs.
#[derive(Debug)]
pub struct RouteFollower {
    line: Vec<LatLon>,
    /// Distance along the line at each point.
    along: Vec<f64>,
    grid: HashMap<(i32, i32), Vec<u32>>,
    stretches: Vec<Stretch>,
    duration_s: f64,
    phase: FollowPhase,
    along_m: f64,
    segment: usize,
    segment_t: f64,
    last_fix: Option<FollowFix>,
    far_fixes: u32,
    far_since_ms: Option<i64>,
    near_fixes: u32,
    off_m: Option<f64>,
    wrong_fixes: u32,
    wrong_way: bool,
    started: bool,
}

impl RouteFollower {
    /// A follower for the route along `line` that takes `duration_s`
    /// seconds, with its favourite parts (each a line along the route, in
    /// route order) and their ratings. The line and parts come from the
    /// app and are checked like any input.
    pub fn new(
        line: Vec<LatLon>,
        favourite_parts: &[Vec<LatLon>],
        favourite_ratings: &[Rating],
        duration_s: f64,
    ) -> Result<Self, CoreError> {
        if line.len() < 2 || line.len() > MAX_LINE_POINTS {
            return Err(CoreError::InvalidArgument(format!(
                "a route line needs 2–{MAX_LINE_POINTS} points, got {}",
                line.len()
            )));
        }
        for p in &line {
            p.validate()?;
        }
        if !(duration_s.is_finite() && duration_s >= 0.0) {
            return Err(CoreError::InvalidArgument("invalid route time".into()));
        }
        if favourite_parts.len() != favourite_ratings.len()
            || favourite_parts.len() > MAX_FOLLOW_PARTS
        {
            return Err(CoreError::InvalidArgument(
                "favourite parts and ratings don't match".into(),
            ));
        }
        let mut along = Vec::with_capacity(line.len());
        let mut walked = 0.0;
        along.push(0.0);
        for w in line.windows(2) {
            walked += haversine_m(w[0], w[1]);
            along.push(walked);
        }
        if !(walked.is_finite() && walked <= MAX_FOLLOW_M) {
            return Err(CoreError::InvalidArgument(format!(
                "a route to follow may be at most {} km long",
                MAX_FOLLOW_M / 1000.0
            )));
        }
        let grid = build_grid(&line);
        let mut follower = Self {
            line,
            along,
            grid,
            stretches: Vec::new(),
            duration_s,
            phase: FollowPhase::Joining,
            along_m: 0.0,
            segment: 0,
            segment_t: 0.0,
            last_fix: None,
            far_fixes: 0,
            far_since_ms: None,
            near_fixes: 0,
            off_m: None,
            wrong_fixes: 0,
            wrong_way: false,
            started: false,
        };
        follower.stretches = follower.place_parts(favourite_parts, favourite_ratings)?;
        Ok(follower)
    }

    /// The route's length, metres.
    pub fn total_m(&self) -> f64 {
        self.along.last().copied().unwrap_or(0.0)
    }

    /// Where the rider stands with the route now.
    pub fn phase(&self) -> FollowPhase {
        self.phase
    }

    /// How far along the route the rider is, metres.
    pub fn along_m(&self) -> f64 {
        self.along_m
    }

    /// The route's time, seconds.
    pub fn duration_s(&self) -> f64 {
        self.duration_s
    }

    /// The point `m` metres along the route (clamped to it).
    pub fn point_at(&self, m: f64) -> LatLon {
        let m = m.clamp(0.0, self.total_m());
        let i = self
            .along
            .partition_point(|&a| a < m)
            .clamp(1, self.line.len() - 1);
        let (a0, a1) = (self.along[i - 1], self.along[i]);
        let t = if a1 > a0 { (m - a0) / (a1 - a0) } else { 0.0 };
        let (p, q) = (self.line[i - 1], self.line[i]);
        LatLon {
            lat: p.lat + t * (q.lat - p.lat),
            lon: p.lon + t * (q.lon - p.lon),
        }
    }

    /// The route's line.
    pub fn line(&self) -> &[LatLon] {
        &self.line
    }

    /// The distance along the route at each point of its line.
    pub fn along(&self) -> &[f64] {
        &self.along
    }

    /// Takes one fix and returns the state after it. Fixes not newer than
    /// the last one, and every fix once finished, leave the state as it is.
    pub fn update(&mut self, fix: FollowFix) -> Result<FollowState, CoreError> {
        fix.validate()?;
        if self.phase == FollowPhase::Finished {
            return Ok(self.state());
        }
        if let Some(last) = self.last_fix
            && fix.time_ms <= last.time_ms
        {
            return Ok(self.state());
        }
        let previous = self.last_fix.replace(fix);
        let accuracy = fix.accuracy_m.unwrap_or(0.0);
        if accuracy > POOR_ACCURACY_M {
            return Ok(self.state());
        }
        match self.phase {
            FollowPhase::Joining => self.join(fix),
            FollowPhase::OnRoute => self.follow(fix, previous),
            FollowPhase::OffRoute => self.rejoin(fix),
            FollowPhase::Finished => {}
        }
        // A loop joined part way: coming round to its start starts it.
        if self.phase == FollowPhase::OnRoute
            && !self.started
            && self.is_loop()
            && haversine_m(fix.position, self.line[0]) <= off_limit(fix)
        {
            self.along_m = 0.0;
            self.segment = 0;
            self.segment_t = 0.0;
            self.started = true;
        }
        if self.phase == FollowPhase::OnRoute && self.at_end(fix.position) {
            self.phase = FollowPhase::Finished;
            self.along_m = self.total_m();
            self.segment = self.line.len() - 2;
            self.segment_t = 1.0;
        }
        Ok(self.state())
    }

    /// The state now.
    pub fn state(&self) -> FollowState {
        let total = self.total_m();
        let left_m = (total - self.along_m).max(0.0);
        let left_s = if total > 0.0 {
            self.duration_s * left_m / total
        } else {
            0.0
        };
        let off = matches!(self.phase, FollowPhase::OffRoute | FollowPhase::Joining);
        FollowState {
            phase: self.phase,
            along_m: self.along_m,
            left_m,
            left_s,
            total_m: total,
            segment: self.segment as u32,
            segment_t: self.segment_t,
            off_m: if off { self.off_m } else { None },
            off_since_ms: if self.phase == FollowPhase::OffRoute {
                self.far_since_ms
            } else {
                None
            },
            favourites: if self.phase == FollowPhase::OnRoute || self.phase == FollowPhase::OffRoute
            {
                self.near_favourites()
            } else {
                Vec::new()
            },
            wrong_way: self.phase == FollowPhase::Joining && self.wrong_way,
            started: self.started,
        }
    }

    /// Joining: the route counts as reached anywhere the rider heads
    /// along it (in its first kilometre also without a bearing, as when
    /// standing at the start); heading against it, they are on the route
    /// the wrong way, on their way to its start.
    fn join(&mut self, fix: FollowFix) {
        let best = self.nearest_in(fix, 0.0, self.total_m(), None);
        self.off_m = best.map(|c| c.distance_m);
        let Some(c) = best.filter(|c| c.distance_m <= off_limit(fix)) else {
            return;
        };
        let joins = match c.heading {
            Heading::Along => true,
            Heading::Unknown => c.along_m <= JOIN_WITHIN_M,
            Heading::Across | Heading::Against => false,
        };
        if joins {
            self.take(c);
            self.phase = FollowPhase::OnRoute;
            self.wrong_way = false;
            self.started |= c.along_m <= JOIN_WITHIN_M;
            self.reset_counts();
        } else if c.heading == Heading::Against {
            self.wrong_way = true;
        }
    }

    fn follow(&mut self, fix: FollowFix, previous: Option<FollowFix>) {
        let covered = match previous {
            Some(p) => {
                let dt = (fix.time_ms - p.time_ms) as f64 / 1000.0;
                let by_speed = fix.speed_mps.map(|s| s * dt).unwrap_or(0.0);
                by_speed.max(haversine_m(p.position, fix.position))
            }
            None => 0.0,
        };
        let from = self.along_m - BACK_WINDOW_M;
        let to = self.along_m + AHEAD_WINDOW_M.max(AHEAD_FACTOR * covered);
        let best = self.nearest_in(fix, from, to, Some(self.along_m + covered));
        match best {
            Some(c) if c.distance_m <= off_limit(fix) && c.heading == Heading::Against => {
                // On the route, but no pass the right way near: the rider
                // rides it backwards, on their way to its start.
                self.wrong_fixes += 1;
                if self.wrong_fixes >= WRONG_WAY_FIXES {
                    self.phase = FollowPhase::Joining;
                    self.wrong_way = true;
                    self.reset_counts();
                }
            }
            Some(c) if c.distance_m <= off_limit(fix) => {
                if c.along_m >= self.along_m {
                    self.take(c);
                }
                self.reset_counts();
            }
            _ => {
                self.off_m = best.map(|c| c.distance_m);
                self.far_fixes += 1;
                let since = *self.far_since_ms.get_or_insert(fix.time_ms);
                if self.far_fixes >= OFF_FIXES && fix.time_ms - since >= OFF_MIN_MS {
                    self.phase = FollowPhase::OffRoute;
                    self.near_fixes = 0;
                }
            }
        }
    }

    fn rejoin(&mut self, fix: FollowFix) {
        let from = self.along_m - BACK_WINDOW_M;
        let best = self.nearest_in(fix, from, self.total_m(), Some(self.along_m));
        self.off_m = best.map(|c| c.distance_m);
        match best {
            Some(c) if c.distance_m <= BACK_ON_M => {
                self.near_fixes += 1;
                if self.near_fixes >= BACK_ON_FIXES {
                    if c.along_m >= self.along_m {
                        self.take(c);
                    }
                    self.phase = FollowPhase::OnRoute;
                    self.reset_counts();
                }
            }
            _ => self.near_fixes = 0,
        }
    }

    fn take(&mut self, c: Candidate) {
        self.along_m = c.along_m;
        self.segment = c.segment;
        self.segment_t = c.t;
    }

    fn reset_counts(&mut self) {
        self.wrong_fixes = 0;
        self.far_fixes = 0;
        self.far_since_ms = None;
        self.near_fixes = 0;
        self.off_m = None;
    }

    /// A loop: it ends where it starts.
    fn is_loop(&self) -> bool {
        haversine_m(self.line[0], self.line[self.line.len() - 1]) <= LOOP_END_M
    }

    fn at_end(&self, p: LatLon) -> bool {
        // A loop joined part way isn't over at its start: it starts there.
        if self.is_loop() && !self.started {
            return false;
        }
        let total = self.total_m();
        let end = self.line[self.line.len() - 1];
        self.along_m >= FINISH_SHARE * total && haversine_m(p, end) <= FINISH_M
    }

    /// The best place on the line between `from` and `to` metres along for
    /// `fix`, within [`SEARCH_M`]: the nearest, counting a segment heading
    /// against the rider's bearing as further, and preferring places near
    /// `expected` metres along, if given.
    fn nearest_in(
        &self,
        fix: FollowFix,
        from: f64,
        to: f64,
        expected: Option<f64>,
    ) -> Option<Candidate> {
        let p = fix.position;
        let bearing = fix
            .bearing_deg
            .filter(|_| fix.speed_mps.is_some_and(|s| s >= BEARING_MIN_MPS));
        let (ci, cj) = cell(p);
        let mut best: Option<(f64, Candidate)> = None;
        for di in -1..=1 {
            for dj in -1..=1 {
                let Some(segments) = self.grid.get(&(ci + di, cj + dj)) else {
                    continue;
                };
                for &s in segments {
                    let s = s as usize;
                    let (Some(&a), Some(&b)) = (self.line.get(s), self.line.get(s + 1)) else {
                        continue;
                    };
                    let (d, t) = project(p, a, b);
                    if d > SEARCH_M {
                        continue;
                    }
                    let start = self.along[s];
                    let along = start + t * (self.along[s + 1] - start);
                    if along < from || along > to {
                        continue;
                    }
                    let turn = bearing
                        .filter(|_| a != b)
                        .map(|br| angle_between(br, bearing_deg(a, b)));
                    let heading = match turn {
                        None => Heading::Unknown,
                        Some(t) if t <= ALONG_DEG => Heading::Along,
                        Some(t) if t > AGAINST_DEG => Heading::Against,
                        Some(_) => Heading::Across,
                    };
                    let wrong_way = turn.is_some_and(|t| t > WRONG_WAY_DEG);
                    let score = d
                        + if wrong_way { WRONG_WAY_PENALTY_M } else { 0.0 }
                        + expected.map_or(0.0, |e| EXPECTED_WEIGHT * (along - e).abs());
                    if best.as_ref().is_none_or(|(b, _)| score < *b) {
                        best = Some((
                            score,
                            Candidate {
                                segment: s,
                                t,
                                along_m: along,
                                distance_m: d,
                                heading,
                            },
                        ));
                    }
                }
            }
        }
        best.map(|(_, c)| c)
    }

    /// Places each favourite part on the line, in order: its start is
    /// found on the line from where the last part ended, and it reaches
    /// as far as it is long. Parts that can't be placed are left out.
    /// Touching parts with the same rating are joined.
    fn place_parts(
        &self,
        parts: &[Vec<LatLon>],
        ratings: &[Rating],
    ) -> Result<Vec<Stretch>, CoreError> {
        let mut out: Vec<Stretch> = Vec::new();
        let mut cursor = 0usize;
        for (part, &rating) in parts.iter().zip(ratings) {
            if part.len() < 2 || part.len() > MAX_LINE_POINTS {
                return Err(CoreError::InvalidArgument(
                    "a favourite part needs at least 2 points".into(),
                ));
            }
            for p in part {
                p.validate()?;
            }
            let Some((segment, from_m)) = self.locate(part[0], cursor) else {
                continue;
            };
            let to_m = (from_m + polyline_length_m(part)).min(self.total_m());
            cursor = segment;
            match out.last_mut() {
                Some(last) if last.rating == rating && from_m - last.to_m <= TOUCHING_M => {
                    last.to_m = last.to_m.max(to_m);
                }
                _ => out.push(Stretch {
                    from_m,
                    to_m,
                    rating,
                }),
            }
        }
        Ok(out)
    }

    /// The first place on the line from segment `from` on within
    /// [`PART_ON_LINE_M`] of `p`: its segment and distance along.
    fn locate(&self, p: LatLon, from: usize) -> Option<(usize, f64)> {
        (from..self.line.len() - 1).find_map(|s| {
            let (d, t) = project(p, self.line[s], self.line[s + 1]);
            (d <= PART_ON_LINE_M)
                .then(|| (s, self.along[s] + t * (self.along[s + 1] - self.along[s])))
        })
    }

    fn near_favourites(&self) -> Vec<NearFavourite> {
        self.stretches
            .iter()
            .filter(|s| s.to_m > self.along_m && s.from_m - self.along_m <= FAVOURITE_AHEAD_M)
            .take(MAX_NEAR_FAVOURITES)
            .map(|s| {
                let on = s.from_m <= self.along_m;
                NearFavourite {
                    rating: s.rating,
                    on,
                    distance_m: if on {
                        s.to_m - self.along_m
                    } else {
                        s.from_m - self.along_m
                    },
                }
            })
            .collect()
    }
}

/// The way back to a followed route.
#[derive(Debug, Clone)]
pub struct Rejoin {
    /// From the rider to the route.
    pub route: Route,
    /// Where it meets the route, metres along it.
    pub to_along_m: f64,
    /// It starts behind the rider.
    pub turn_round: bool,
}

impl Engine {
    /// The quickest way from `position` back to the route `follower`
    /// follows (ADR-0011), meeting it at the earliest place it can reach
    /// without riding the route backwards: of the places
    /// [`REJOIN_AHEAD_M`] on from where the rider left it (when joining,
    /// [`JOIN_AT_M`] from its start), the one with the least time to get
    /// there plus time of the route skipped, so favourites aren't given up
    /// for a small saving. `bearing_deg` (where the rider is heading) says
    /// whether it starts behind them. Fastest roads under `opts.avoid`.
    pub fn rejoin(
        &self,
        position: LatLon,
        bearing_deg: Option<f64>,
        follower: &RouteFollower,
        opts: &RouteOptions,
    ) -> Result<Rejoin, CoreError> {
        position.validate()?;
        if bearing_deg.is_some_and(|b| !b.is_finite()) {
            return Err(CoreError::InvalidArgument("invalid bearing".into()));
        }
        let total = follower.total_m();
        let (base, offsets): (f64, &[f64]) = if follower.phase() == FollowPhase::Joining {
            (0.0, &JOIN_AT_M)
        } else {
            (follower.along_m(), &REJOIN_AHEAD_M)
        };
        let mut targets: Vec<f64> = offsets.iter().map(|o| (base + o).min(total)).collect();
        targets.dedup();
        let per_m = if total > 0.0 {
            follower.duration_s() / total
        } else {
            0.0
        };
        let ways = crate::par::map(&targets, |&m| {
            self.route(position, follower.point_at(m), opts)
                .map(|r| (m, r))
        });
        let cost = |m: f64, r: &Route| r.duration_s + (m - base) * per_m;
        let mut best: Option<(f64, Route)> = None;
        let mut failure = None;
        for way in ways {
            match way {
                Ok((m, r)) => {
                    if best
                        .as_ref()
                        .is_none_or(|(bm, br)| cost(m, &r) < cost(*bm, br))
                    {
                        best = Some((m, r));
                    }
                }
                Err(e) => failure = failure.or(Some(e)),
            }
        }
        let (to_along_m, route) = match (best, failure) {
            (Some(b), _) => b,
            (None, Some(e)) => return Err(e),
            (None, None) => return Err(CoreError::NoRoute("no way back to the route".into())),
        };
        let turn_round = bearing_deg.is_some_and(|b| starts_behind(&route.geometry, b));
        Ok(Rejoin {
            route,
            to_along_m,
            turn_round,
        })
    }
}

/// Whether `line`'s first [`TURN_CHECK_M`] head more than
/// [`TURN_ROUND_DEG`] away from `bearing`.
fn starts_behind(line: &[LatLon], bearing: f64) -> bool {
    let Some(&first) = line.first() else {
        return false;
    };
    let mut walked = 0.0;
    let mut to = None;
    for w in line.windows(2) {
        walked += haversine_m(w[0], w[1]);
        to = Some(w[1]);
        if walked >= TURN_CHECK_M {
            break;
        }
    }
    match to {
        Some(to) if to != first => angle_between(bearing, bearing_deg(first, to)) > TURN_ROUND_DEG,
        _ => false,
    }
}

/// How far from the route a fix may be and still be on it.
fn off_limit(fix: FollowFix) -> f64 {
    OFF_MIN_M.max(OFF_ACCURACY_FACTOR * fix.accuracy_m.unwrap_or(0.0))
}

fn cell(p: LatLon) -> (i32, i32) {
    (
        (p.lat / CELL_LAT).floor() as i32,
        (p.lon / CELL_LON).floor() as i32,
    )
}

/// Each segment entered in every cell it passes through (sampled every
/// [`SAMPLE_M`]).
fn build_grid(line: &[LatLon]) -> HashMap<(i32, i32), Vec<u32>> {
    let mut grid: HashMap<(i32, i32), Vec<u32>> = HashMap::new();
    for (s, w) in line.windows(2).enumerate() {
        let (a, b) = (w[0], w[1]);
        let n = (haversine_m(a, b) / SAMPLE_M).ceil().max(1.0) as usize;
        let mut last = None;
        for k in 0..=n {
            let f = k as f64 / n as f64;
            let c = cell(LatLon {
                lat: a.lat + f * (b.lat - a.lat),
                lon: a.lon + f * (b.lon - a.lon),
            });
            if last != Some(c) {
                let cell = grid.entry(c).or_default();
                if cell.last() != Some(&(s as u32)) {
                    cell.push(s as u32);
                }
                last = Some(c);
            }
        }
    }
    grid
}

/// Distance in metres from `p` to the segment `a`–`b`, and how far along
/// the segment the nearest point is (0–1). A local flat approximation.
fn project(p: LatLon, a: LatLon, b: LatLon) -> (f64, f64) {
    let k = p.lat.to_radians().cos();
    let m = 111_195.0;
    let xy = |q: LatLon| ((q.lon - p.lon) * k * m, (q.lat - p.lat) * m);
    let (a, b) = (xy(a), xy(b));
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let len2 = dx * dx + dy * dy;
    let t = if len2 > 0.0 {
        (-(a.0 * dx + a.1 * dy) / len2).clamp(0.0, 1.0)
    } else {
        0.0
    };
    ((a.0 + t * dx).hypot(a.1 + t * dy), t)
}

/// The smaller angle between two bearings, 0–180 degrees.
fn angle_between(a: f64, b: f64) -> f64 {
    let d = (a - b).rem_euclid(360.0);
    d.min(360.0 - d)
}

#[cfg(test)]
mod tests;
