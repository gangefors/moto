// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `--golden`: the golden-route regression set. Each case is a JSON file
//! (in `core/moto-core/tests/golden/`): a start and an end on the real
//! region, the favourite sections the rider has (each marked like in the
//! app, between two points on the road), and the properties the route must
//! have. Every change to route scoring is checked against all cases, and
//! CI shows this build's figures next to the last main build's.
//!
//! A case is either a route (`from` → `to`) or a round trip (`from` and a
//! `loop` target): then every loop returned must be within ±15 % of the
//! target, come back to the start and ride at most 10 % of its length
//! twice outside the home zone (measured here from the line, not taken
//! from the core), and the best loop is checked against the expectations.
//!
//! Case files are read into strict types (unknown fields are errors), are
//! size-capped and validated like any other input.

use std::path::Path;

use std::collections::HashMap;

use moto_core::geo::{distance_to_line, haversine_m};
use moto_core::roundtrip::{MAX_LOOPS, MAX_REUSE, TOLERANCE, home_radius_m};
use moto_core::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};
use moto_core::{
    Engine, Favourites, Gravel, LatLon, LoopOptions, RoundTripTarget, Route, RouteOptions,
    TimeBudget,
};
use serde::{Deserialize, Serialize};

/// Largest case file read.
const MAX_CASE_BYTES: u64 = 64 * 1024;
/// Most case files in a directory.
const MAX_CASES: usize = 500;
/// Most favourites and checkpoints in one case.
const MAX_ITEMS: usize = 100;
/// A route passes a point when it comes this close to it.
pub const PASS_RADIUS_M: f64 = 50.0;

/// One golden route.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Case {
    pub name: String,
    /// What the case is about and why the expectations hold.
    #[serde(default)]
    pub description: String,
    /// Start and end as [lat, lon]; a round trip has no end.
    pub from: [f64; 2],
    pub to: Option<[f64; 2]>,
    /// A round trip's target, in place of `to`.
    #[serde(rename = "loop")]
    pub round_trip: Option<LoopTarget>,
    /// Time budget as extra over the fastest route (0.4 = 40 %, the
    /// default), or ...
    pub max_detour: Option<f64>,
    /// ... as the most minutes in all (like arriving by a set time).
    pub max_minutes: Option<f64>,
    /// Guard: seconds of rating-weighted favourite riding each extra
    /// second must buy (see `RouteOptions::min_gain`); the default if
    /// left out.
    pub min_gain: Option<f64>,
    /// Gravel (unpaved) roads: "avoid" (where possible, the default),
    /// "allow" or "prefer", like the app's gravel choice.
    #[serde(default)]
    pub gravel: GravelName,
    /// Whether curvy roads pull the route (default true, as in the app);
    /// false for cases about favourites or gravel alone.
    #[serde(default = "yes")]
    pub curvy: bool,
    #[serde(default)]
    pub favourites: Vec<Favourite>,
    pub expect: Expect,
}

/// A round trip's length: `km` or `minutes`; optionally the app's
/// Shuffle `seed` and Direction (`direction`, degrees from north).
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LoopTarget {
    pub km: Option<f64>,
    pub minutes: Option<f64>,
    #[serde(default)]
    pub seed: u32,
    pub direction: Option<f64>,
}

impl LoopTarget {
    fn shape(self) -> Result<LoopOptions, String> {
        let shape = LoopOptions {
            seed: self.seed,
            bearing: self.direction,
        };
        shape.validate().map_err(|e| e.to_string())?;
        Ok(shape)
    }

    fn target(self) -> Result<RoundTripTarget, String> {
        match (self.km, self.minutes) {
            (Some(km), None) if km.is_finite() && km > 0.0 => {
                Ok(RoundTripTarget::DistanceM(km * 1000.0))
            }
            (None, Some(min)) if min.is_finite() && min > 0.0 => {
                Ok(RoundTripTarget::DurationS(min * 60.0))
            }
            _ => Err("loop needs a positive km or minutes, not both".into()),
        }
    }
}

/// A favourite section: the road between two points, as the app marks it.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Favourite {
    pub from: [f64; 2],
    pub to: [f64; 2],
    pub rating: RatingName,
    #[serde(default)]
    pub one_way: bool,
}

#[derive(Debug, Clone, Copy, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum GravelName {
    #[default]
    Avoid,
    Allow,
    Prefer,
}

impl From<GravelName> for Gravel {
    fn from(g: GravelName) -> Self {
        match g {
            GravelName::Avoid => Gravel::Avoid,
            GravelName::Allow => Gravel::Allow,
            GravelName::Prefer => Gravel::Prefer,
        }
    }
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RatingName {
    Good,
    Great,
    Epic,
}

impl From<RatingName> for Rating {
    fn from(r: RatingName) -> Self {
        match r {
            RatingName::Good => Rating::Good,
            RatingName::Great => Rating::Great,
            RatingName::Epic => Rating::Epic,
        }
    }
}

/// What the route must look like. Every field is optional; the detour
/// cap always applies.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Expect {
    /// Share of the distance on favourites, at least / at most.
    pub min_favourite_share: Option<f64>,
    pub max_favourite_share: Option<f64>,
    /// Share of the distance on curvy roads (see `Route::curvy_share`),
    /// at least.
    pub min_curvy_share: Option<f64>,
    /// Kilometres on gravel and other unpaved roads, at least.
    pub min_unpaved_km: Option<f64>,
    /// Kilometres on roads posted 100 km/h or more, at most (every loop
    /// of a round trip).
    pub max_fast_km: Option<f64>,
    /// Time over the fastest route as a ratio (1.0 = none), at most.
    /// Defaults to 1 + the detour budget.
    pub max_detour_ratio: Option<f64>,
    /// Points the route must pass within [`PASS_RADIUS_M`], as [lat, lon].
    #[serde(default)]
    pub pass: Vec<[f64; 2]>,
    /// Points the route must keep away from.
    #[serde(default)]
    pub avoid: Vec<[f64; 2]>,
    /// Round trips: the fewest loops returned (default 2, as the PRD asks).
    pub min_loops: Option<usize>,
}

/// The figures of one case, for the report and the before/after table.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Outcome {
    pub name: String,
    pub distance_km: f64,
    pub duration_min: f64,
    pub fastest_min: f64,
    /// Time over the fastest route (1.0 = none).
    pub detour_ratio: f64,
    pub favourite_share: f64,
    pub curvy_share: f64,
    /// Round trips: loops returned, and the most any of them rides twice
    /// (share of its length, outside the home zone).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub loops: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reuse_share: Option<f64>,
    /// Broken expectations; empty when the case passes.
    pub failures: Vec<String>,
}

fn ll(p: [f64; 2]) -> Result<LatLon, String> {
    LatLon::new(p[0], p[1]).map_err(|e| e.to_string())
}

fn share(v: Option<f64>, what: &str) -> Result<(), String> {
    match v {
        Some(x) if !(0.0..=1.0).contains(&x) => Err(format!("{what} must be 0–1, got {x}")),
        _ => Ok(()),
    }
}

impl Case {
    /// Parses and checks a case file's text.
    pub fn parse(text: &str) -> Result<Self, String> {
        let case: Case = serde_json::from_str(text).map_err(|e| e.to_string())?;
        if case.name.trim().is_empty() || case.name.len() > 200 {
            return Err("name must be 1–200 bytes".into());
        }
        let e = &case.expect;
        if case.favourites.len() > MAX_ITEMS
            || e.pass.len() > MAX_ITEMS
            || e.avoid.len() > MAX_ITEMS
        {
            return Err(format!(
                "at most {MAX_ITEMS} favourites, pass and avoid points"
            ));
        }
        match (case.to, case.round_trip) {
            (Some(_), None) => {
                if e.min_loops.is_some() {
                    return Err("min_loops is for round trips".into());
                }
            }
            (None, Some(t)) => {
                t.target()?;
                if case.max_detour.is_some()
                    || case.max_minutes.is_some()
                    || case.min_gain.is_some()
                    || e.max_detour_ratio.is_some()
                {
                    return Err("a round trip's budget is its target; no max_detour, \
                                max_minutes, min_gain or max_detour_ratio"
                        .into());
                }
                if e.min_loops.is_some_and(|n| !(1..=MAX_LOOPS).contains(&n)) {
                    return Err(format!("min_loops must be 1–{MAX_LOOPS}"));
                }
            }
            _ => return Err("give to or loop, not both".into()),
        }
        for p in [Some(case.from), case.to]
            .iter()
            .flatten()
            .chain(case.favourites.iter().flat_map(|f| [&f.from, &f.to]))
            .chain(&e.pass)
            .chain(&e.avoid)
        {
            ll(*p)?;
        }
        share(e.min_favourite_share, "min_favourite_share")?;
        share(e.max_favourite_share, "max_favourite_share")?;
        share(e.min_curvy_share, "min_curvy_share")?;
        for (name, v) in [
            ("min_unpaved_km", e.min_unpaved_km),
            ("max_fast_km", e.max_fast_km),
        ] {
            if let Some(km) = v
                && !(km.is_finite() && km >= 0.0)
            {
                return Err(format!("{name} must be a non-negative number, got {km}"));
            }
        }
        if let Some(t) = case.round_trip {
            t.shape()?;
        }
        if let Some(r) = e.max_detour_ratio
            && !(r.is_finite() && r >= 1.0)
        {
            return Err(format!("max_detour_ratio must be at least 1, got {r}"));
        }
        if case.max_detour.is_some() && case.max_minutes.is_some() {
            return Err("give max_detour or max_minutes, not both".into());
        }
        case.options().validate().map_err(|e| e.to_string())?;
        Ok(case)
    }

    fn options(&self) -> RouteOptions {
        let mut opts = RouteOptions::default();
        if let Some(d) = self.max_detour {
            opts.budget = TimeBudget::Extra(d);
        }
        if let Some(m) = self.max_minutes {
            opts.budget = TimeBudget::Total(m * 60.0);
        }
        if let Some(g) = self.min_gain {
            opts.min_gain = g;
        }
        opts.gravel = self.gravel.into();
        opts.curvy = self.curvy;
        opts
    }

    /// The case's favourites on `engine`'s region, marked like in the app.
    fn favourites(&self, engine: &Engine) -> Result<Vec<Section>, String> {
        self.favourites
            .iter()
            .enumerate()
            .map(|(i, f)| {
                let d = engine
                    .section_between(ll(f.from)?, ll(f.to)?)
                    .map_err(|e| format!("favourite {}: {e}", i + 1))?;
                Ok(Section {
                    id: i as i64 + 1,
                    rider_id: LOCAL_RIDER.into(),
                    name: String::new(),
                    rating: f.rating.into(),
                    direction: if f.one_way {
                        Direction::Forward
                    } else {
                        Direction::Both
                    },
                    source: Source::Map,
                    status: Status::Ok,
                    created_at: 0,
                    updated_at: 0,
                    ways: d.ways,
                    geometry: d.geometry,
                })
            })
            .collect()
    }

    /// Routes the case on `engine` and checks the expectations. Errors
    /// (no route, a point off the map) are failures, not panics.
    pub fn run(&self, engine: &Engine) -> Outcome {
        let mut out = Outcome {
            name: self.name.clone(),
            distance_km: 0.0,
            duration_min: 0.0,
            fastest_min: 0.0,
            detour_ratio: 0.0,
            favourite_share: 0.0,
            curvy_share: 0.0,
            loops: None,
            reuse_share: None,
            failures: Vec::new(),
        };
        if let Some(t) = self.round_trip {
            self.run_loop(engine, t, &mut out);
            return out;
        }
        let routed = (|| -> Result<(Route, Route), String> {
            let to = self.to.ok_or("no end")?;
            let (from, to) = (ll(self.from)?, ll(to)?);
            let opts = self.options();
            let fav = Favourites::build(engine, &self.favourites(engine)?);
            let fastest = engine.route(from, to, &opts).map_err(|e| e.to_string())?;
            let route = engine
                .route_with(from, to, &opts, &fav)
                .map_err(|e| e.to_string())?;
            Ok((fastest, route))
        })();
        let (fastest, route) = match routed {
            Ok(r) => r,
            Err(e) => {
                out.failures.push(e);
                return out;
            }
        };
        out.distance_km = route.distance_m / 1000.0;
        out.duration_min = route.duration_s / 60.0;
        out.fastest_min = fastest.duration_s / 60.0;
        out.detour_ratio = if fastest.duration_s > 0.0 {
            route.duration_s / fastest.duration_s
        } else {
            1.0
        };
        out.favourite_share = route.favourite_share;
        out.curvy_share = route.curvy_share;

        let e = &self.expect;
        let cap = e.max_detour_ratio.unwrap_or(match self.options().budget {
            TimeBudget::Extra(ratio) => 1.0 + ratio,
            TimeBudget::Total(_) => f64::INFINITY,
        });
        if out.detour_ratio > cap + 1e-9 {
            out.failures.push(format!(
                "detour {:.2}× the fastest time, allowed {cap:.2}×",
                out.detour_ratio
            ));
        }
        if let Some(max) = self.max_minutes
            && out.duration_min > max + 1e-6
            && out.duration_min > out.fastest_min + 1e-6
        {
            out.failures
                .push(format!("{:.1} min, allowed {max:.1} min", out.duration_min));
        }
        self.check_fast(engine, "", &route, &mut out);
        self.check_route(&route, &mut out);
        out
    }

    /// Round trips: every loop's length, closure and reuse, then the best
    /// loop against the expectations.
    fn run_loop(&self, engine: &Engine, t: LoopTarget, out: &mut Outcome) {
        let loops = (|| -> Result<(RoundTripTarget, Vec<Route>), String> {
            let target = t.target()?;
            let fav = Favourites::build(engine, &self.favourites(engine)?);
            let loops = engine
                .round_trip_with(ll(self.from)?, target, &self.options(), &fav, &t.shape()?)
                .map_err(|e| e.to_string())?;
            Ok((target, loops))
        })();
        let (target, loops) = match loops {
            Ok(l) => l,
            Err(e) => {
                out.failures.push(e);
                return;
            }
        };
        let (goal, unit, size): (f64, &str, fn(&Route) -> f64) = match target {
            RoundTripTarget::DistanceM(m) => (m / 1000.0, "km", |r| r.distance_m / 1000.0),
            RoundTripTarget::DurationS(s) => (s / 60.0, "min", |r| r.duration_s / 60.0),
        };
        let home = home_radius_m(match target {
            RoundTripTarget::DistanceM(m) => m,
            RoundTripTarget::DurationS(s) => s * moto_core::scoring::PARAMS.loop_speed_mps,
        });
        out.loops = Some(loops.len());
        let min_loops = self.expect.min_loops.unwrap_or(2);
        if loops.len() < min_loops {
            out.failures.push(format!(
                "{} loop(s), expected at least {min_loops}",
                loops.len()
            ));
        }
        let mut worst: f64 = 0.0;
        for (i, l) in loops.iter().enumerate() {
            let n = i + 1;
            if (size(l) - goal).abs() > goal * TOLERANCE + 1e-9 {
                out.failures.push(format!(
                    "loop {n}: {:.1} {unit}, target {goal:.1} {unit} ±{:.0} %",
                    size(l),
                    TOLERANCE * 100.0
                ));
            }
            let closed = match (l.geometry.first(), l.geometry.last()) {
                (Some(a), Some(b)) => haversine_m(*a, *b) <= 1.0,
                _ => false,
            };
            if !closed {
                out.failures
                    .push(format!("loop {n}: does not come back to the start"));
            }
            self.check_fast(engine, &format!("loop {n}: "), l, out);
            let reuse = reuse_share(&l.geometry, home);
            worst = worst.max(reuse);
            if reuse > MAX_REUSE + REUSE_SLACK {
                out.failures.push(format!(
                    "loop {n}: rides {:.0} % of its length twice, allowed {:.0} %",
                    reuse * 100.0,
                    MAX_REUSE * 100.0
                ));
            }
        }
        out.reuse_share = Some(worst);
        let Some(best) = loops.first() else {
            return;
        };
        out.distance_km = best.distance_m / 1000.0;
        out.duration_min = best.duration_s / 60.0;
        out.fastest_min = out.duration_min;
        out.detour_ratio = 1.0;
        out.favourite_share = best.favourite_share;
        out.curvy_share = best.curvy_share;
        self.check_route(best, out);
    }

    /// Kilometres on roads posted 100 km/h or more, against `max_fast_km`.
    fn check_fast(&self, engine: &Engine, what: &str, route: &Route, out: &mut Outcome) {
        let Some(max) = self.expect.max_fast_km else {
            return;
        };
        let km = fast_km(engine, &route.geometry);
        if km > max {
            out.failures.push(format!(
                "{what}{km:.1} km on 100+ km/h roads, expected at most {max:.1} km"
            ));
        }
    }

    /// Favourite and curvy shares, gravel, pass and avoid points.
    fn check_route(&self, route: &Route, out: &mut Outcome) {
        let e = &self.expect;
        if let Some(min) = e.min_favourite_share
            && route.favourite_share < min
        {
            out.failures.push(format!(
                "{:.0} % on favourites, expected at least {:.0} %",
                route.favourite_share * 100.0,
                min * 100.0
            ));
        }
        if let Some(max) = e.max_favourite_share
            && route.favourite_share > max
        {
            out.failures.push(format!(
                "{:.0} % on favourites, expected at most {:.0} %",
                route.favourite_share * 100.0,
                max * 100.0
            ));
        }
        if let Some(min) = e.min_curvy_share
            && route.curvy_share < min
        {
            out.failures.push(format!(
                "{:.0} % curvy, expected at least {:.0} %",
                route.curvy_share * 100.0,
                min * 100.0
            ));
        }
        if let Some(min) = e.min_unpaved_km
            && route.unpaved_m / 1000.0 < min
        {
            out.failures.push(format!(
                "{:.1} km on gravel, expected at least {min:.1} km",
                route.unpaved_m / 1000.0
            ));
        }
        for p in &e.pass {
            let d = ll(*p).map_or(f64::INFINITY, |p| distance_to_line(p, &route.geometry));
            if d > PASS_RADIUS_M {
                out.failures
                    .push(format!("misses {:.5},{:.5} by {d:.0} m", p[0], p[1]));
            }
        }
        for p in &e.avoid {
            let d = ll(*p).map_or(f64::INFINITY, |p| distance_to_line(p, &route.geometry));
            if d <= PASS_RADIUS_M {
                out.failures.push(format!("passes {:.5},{:.5}", p[0], p[1]));
            }
        }
    }
}

/// What counts as a fast (and dull) road, km/h.
const FAST_KMH: u32 = 100;

/// Kilometres of `line` on roads posted [`FAST_KMH`] or more: each
/// stretch between two points counts by the road under its middle.
pub fn fast_km(engine: &Engine, line: &[LatLon]) -> f64 {
    line.windows(2)
        .filter(|w| {
            let mid = LatLon {
                lat: (w[0].lat + w[1].lat) / 2.0,
                lon: (w[0].lon + w[1].lon) / 2.0,
            };
            engine.road_at(mid).is_ok_and(|r| r.speed_kmh >= FAST_KMH)
        })
        .map(|w| haversine_m(w[0], w[1]))
        .sum::<f64>()
        / 1000.0
}

/// Reuse is measured on points this far apart along the line...
const REUSE_STEP_M: f64 = 20.0;
/// ...each counting as ridden twice when another point, at least
/// [`REUSE_GAP_M`] further along, lies this close...
const REUSE_NEAR_M: f64 = 10.0;
const REUSE_GAP_M: f64 = 300.0;
/// ...and the measure may exceed the core's by this much (crossings and
/// the points either side of them count a little).
const REUSE_SLACK: f64 = 0.02;

/// Share of `line`'s length ridden twice, outside `home` metres from its
/// start: points every [`REUSE_STEP_M`] along it that lie near a point far
/// from them along the line, halved (both passes are near each other).
/// Independent of how the core counts reuse, so it checks the core.
pub fn reuse_share(line: &[LatLon], home: f64) -> f64 {
    let Some(&start) = line.first() else {
        return 0.0;
    };
    // Points every REUSE_STEP_M along the line, with how far along.
    let mut points: Vec<(LatLon, f64)> = vec![(start, 0.0)];
    let mut along = 0.0;
    let mut next = REUSE_STEP_M;
    for w in line.windows(2) {
        let d = haversine_m(w[0], w[1]);
        while d > 0.0 && next <= along + d {
            let f = (next - along) / d;
            let p = LatLon {
                lat: w[0].lat + (w[1].lat - w[0].lat) * f,
                lon: w[0].lon + (w[1].lon - w[0].lon) * f,
            };
            points.push((p, next));
            next += REUSE_STEP_M;
        }
        along += d;
    }
    if along <= 0.0 {
        return 0.0;
    }
    // Grid cells of about 11 m (lat) by 7–12 m (lon, in Sweden).
    let cell = |p: LatLon| ((p.lat * 1e4).floor() as i64, (p.lon * 1e4).floor() as i64);
    let mut grid: HashMap<(i64, i64), Vec<usize>> = HashMap::new();
    for (i, (p, _)) in points.iter().enumerate() {
        grid.entry(cell(*p)).or_default().push(i);
    }
    let twice = points
        .iter()
        .filter(|(p, a)| {
            if haversine_m(start, *p) <= home {
                return false;
            }
            let (y, x) = cell(*p);
            (-1..=1).any(|dy| {
                (-2..=2).any(|dx| {
                    grid.get(&(y + dy, x + dx)).is_some_and(|near| {
                        near.iter().any(|&j| {
                            let (q, b) = points[j];
                            (a - b).abs() >= REUSE_GAP_M && haversine_m(*p, q) <= REUSE_NEAR_M
                        })
                    })
                })
            })
        })
        .count();
    (twice as f64 * REUSE_STEP_M / 2.0 / along).min(1.0)
}

fn yes() -> bool {
    true
}

/// Reads every `*.json` case in `dir`, sorted by file name.
pub fn load(dir: &Path) -> Result<Vec<Case>, String> {
    let mut files: Vec<_> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "json") && p.is_file())
        .collect();
    files.sort();
    if files.len() > MAX_CASES {
        return Err(format!("more than {MAX_CASES} cases in {}", dir.display()));
    }
    files
        .iter()
        .map(|f| {
            let size = std::fs::metadata(f).map_err(|e| e.to_string())?.len();
            if size > MAX_CASE_BYTES {
                return Err(format!(
                    "{}: larger than {MAX_CASE_BYTES} bytes",
                    f.display()
                ));
            }
            let text = std::fs::read_to_string(f).map_err(|e| format!("{}: {e}", f.display()))?;
            Case::parse(&text).map_err(|e| format!("{}: {e}", f.display()))
        })
        .collect()
}

/// Runs the golden cases in `dir` on `region`; prints a table, optionally
/// writes the outcomes as JSON, and fails if any case fails.
pub fn run(region: &Path, dir: &Path, json: Option<&Path>) -> Result<(), String> {
    let engine = Engine::open(region).map_err(|e| e.to_string())?;
    let cases = load(dir)?;
    if cases.is_empty() {
        return Err(format!("no *.json cases in {}", dir.display()));
    }
    let outcomes: Vec<Outcome> = cases.iter().map(|c| c.run(&engine)).collect();
    for line in table(&outcomes) {
        println!("{line}");
    }
    // What the failed cases are about, to judge the failure.
    for (c, o) in cases.iter().zip(&outcomes) {
        if !o.failures.is_empty() && !c.description.is_empty() {
            println!("\n{}: {}", c.name, c.description);
        }
    }
    if let Some(path) = json {
        let text = serde_json::to_string_pretty(&outcomes).map_err(|e| e.to_string())?;
        std::fs::write(path, text + "\n").map_err(|e| format!("{}: {e}", path.display()))?;
    }
    let failed = outcomes.iter().filter(|o| !o.failures.is_empty()).count();
    if failed > 0 {
        return Err(format!(
            "{failed} of {} golden routes failed",
            outcomes.len()
        ));
    }
    Ok(())
}

/// Human-readable lines: one per case, then its failures.
pub fn table(outcomes: &[Outcome]) -> Vec<String> {
    let mut out = vec![format!(
        "{:<44} {:>7} {:>7} {:>6} {:>6} {:>6}  result",
        "route", "km", "min", "detour", "fav %", "curvy%"
    )];
    for o in outcomes {
        // A round trip has no detour; its loops and reuse show instead.
        let detour = match o.loops {
            Some(n) => format!("{n} loops"),
            None => format!("{:.2}×", o.detour_ratio),
        };
        let reuse = o
            .reuse_share
            .map_or(String::new(), |r| format!(" ({:.0} % reuse)", r * 100.0));
        out.push(format!(
            "{:<44} {:>7.1} {:>7.1} {:>6} {:>6.1} {:>6.1}  {}{reuse}",
            o.name.chars().take(44).collect::<String>(),
            o.distance_km,
            o.duration_min,
            detour,
            o.favourite_share * 100.0,
            o.curvy_share * 100.0,
            if o.failures.is_empty() {
                "ok"
            } else {
                "FAILED"
            }
        ));
        for f in &o.failures {
            out.push(format!("    - {f}"));
        }
    }
    out
}

#[cfg(test)]
mod tests;
