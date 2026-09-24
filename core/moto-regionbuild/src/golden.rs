// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `--golden`: the golden-route regression set. Each case is a JSON file
//! (in `core/moto-core/tests/golden/`): a start and an end on the real
//! region, the favourite sections the rider has (each marked like in the
//! app, between two points on the road), and the properties the route must
//! have. Every change to route scoring is checked against all cases, and
//! CI shows this build's figures next to the last main build's.
//!
//! Case files are read into strict types (unknown fields are errors), are
//! size-capped and validated like any other input.

use std::path::Path;

use moto_core::geo::distance_to_line;
use moto_core::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};
use moto_core::{Engine, Favourites, LatLon, Route, RouteOptions, TimeBudget};
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
    /// Start and end as [lat, lon].
    pub from: [f64; 2],
    pub to: [f64; 2],
    /// Time budget as extra over the fastest route (0.4 = 40 %, the
    /// default), or ...
    pub max_detour: Option<f64>,
    /// ... as the most minutes in all (like arriving by a set time).
    pub max_minutes: Option<f64>,
    /// Guard: seconds of rating-weighted favourite riding each extra
    /// second must buy (see `RouteOptions::min_gain`); the default if
    /// left out.
    pub min_gain: Option<f64>,
    #[serde(default)]
    pub favourites: Vec<Favourite>,
    pub expect: Expect,
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
    /// Time over the fastest route as a ratio (1.0 = none), at most.
    /// Defaults to 1 + the detour budget.
    pub max_detour_ratio: Option<f64>,
    /// Points the route must pass within [`PASS_RADIUS_M`], as [lat, lon].
    #[serde(default)]
    pub pass: Vec<[f64; 2]>,
    /// Points the route must keep away from.
    #[serde(default)]
    pub avoid: Vec<[f64; 2]>,
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
        for p in [case.from, case.to]
            .iter()
            .chain(case.favourites.iter().flat_map(|f| [&f.from, &f.to]))
            .chain(&e.pass)
            .chain(&e.avoid)
        {
            ll(*p)?;
        }
        share(e.min_favourite_share, "min_favourite_share")?;
        share(e.max_favourite_share, "max_favourite_share")?;
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
            failures: Vec::new(),
        };
        let routed = (|| -> Result<(Route, Route), String> {
            let (from, to) = (ll(self.from)?, ll(self.to)?);
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
        out
    }
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
        out.push(format!(
            "{:<44} {:>7.1} {:>7.1} {:>5.2}× {:>6.1} {:>6.1}  {}",
            o.name.chars().take(44).collect::<String>(),
            o.distance_km,
            o.duration_min,
            o.detour_ratio,
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
