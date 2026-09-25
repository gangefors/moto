// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The region benchmark behind `--check`: verify, open, snap, route (with
//! and without favourites), round-trip and map-matching timings on fixed pseudo-random
//! inputs, plus a CPU calibration run so CI
//! can compare builds made on different machines.

use std::path::Path;
use std::time::Instant;

use moto_core::region::Region;
use moto_core::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};
use moto_core::{Avoid, Engine, Favourites, LatLon, RoundTripTarget, RouteOptions};

/// Random points snapped by the benchmark.
pub const SNAP_POINTS: usize = 10_000;
/// Random start/end pairs routed by the benchmark.
pub const ROUTE_PAIRS: usize = 100;
/// Favourite sections made for routing with favourites: from random road
/// points to the road nearest a point [`FAVOURITE_REACH_DEG`] north (less
/// in a region under four times that tall).
pub const FAVOURITE_SECTIONS: usize = 300;
const FAVOURITE_REACH_DEG: f64 = 0.02;
/// Round trips: from the starts of this many route pairs, each of these
/// lengths, with the favourites; fewer rounds, as each is many routes.
pub const LOOP_STARTS: usize = 10;
pub const LOOP_KM: [f64; 2] = [50.0, 100.0];
const LOOP_ROUNDS: usize = 3;
/// Routes turned into noisy GPS tracks and map-matched by the benchmark.
pub const MATCH_TRACKS: usize = 20;
/// Synthetic tracks: a fix every this many metres along the route...
const TRACK_STEP_M: f64 = 20.0;
/// ...moved by up to this many metres north and east.
const TRACK_NOISE_M: f64 = 8.0;
/// Timed repetitions; the fastest run counts, being the one least disturbed
/// by the rest of the machine. Short timings get more rounds.
const ROUNDS: usize = 5;
const SHORT_ROUNDS: usize = 15;

/// Everything `--check` measures.
#[derive(Debug, Clone, PartialEq)]
pub struct Report {
    pub file_bytes: u64,
    pub source_name: String,
    pub osm_timestamp: i64,
    pub builder_version: String,
    pub nodes: usize,
    pub edges: usize,
    pub grid: (u32, u32),
    /// Median time of the fixed CPU workload, to scale the other timings.
    pub calibration_ms: f64,
    pub verify_ms: f64,
    pub open_ms: f64,
    pub snap_us_mean: f64,
    pub snapped: usize,
    pub route_ms_mean: f64,
    pub route_ms_p50: f64,
    pub route_ms_p95: f64,
    pub route_ms_max: f64,
    pub route_pairs: usize,
    pub routes_found: usize,
    pub routes_found_avoiding_nothing: usize,
    pub route_km_mean: f64,
    /// Building the favourites overlay from [`FAVOURITE_SECTIONS`] sections.
    pub favourites_build_ms: f64,
    pub favourite_sections: usize,
    pub favourite_edges: usize,
    /// Routing the same pairs with those favourites.
    pub fav_route_ms_mean: f64,
    pub fav_route_ms_p95: f64,
    /// Mean share of the route on favourites, the same for the fastest
    /// route, and mean time over the fastest route (1.0 = no detour), over
    /// the pairs routed.
    pub fav_share_mean: f64,
    pub fav_share_fastest: f64,
    pub fav_detour_mean: f64,
    /// Routing the same pairs with no favourites, curvature pulling (what
    /// a rider without favourites gets), and the curvy distance it gains
    /// over the fastest route (median ratio; 1.0 = none).
    pub curvy_route_ms_mean: f64,
    pub curvy_route_ms_p95: f64,
    pub curvy_gain_median: f64,
    /// Round trips ([`LOOP_STARTS`] × [`LOOP_KM`], with the favourites):
    /// time per request, requests made, those that got two loops or more,
    /// and the mean number of loops per request.
    pub loop_ms_mean: f64,
    pub loop_ms_p95: f64,
    pub loop_requests: usize,
    pub loop_found_two: usize,
    pub loops_mean: f64,
    /// Map matching time per km of track (fastest round).
    pub match_ms_per_km: f64,
    pub match_tracks: usize,
    pub match_km: f64,
    /// Matched length over ridden length, and pieces per track.
    pub match_share: f64,
    pub match_pieces: usize,
    /// Pairs that have no route even when avoiding nothing.
    pub unroutable: Vec<(LatLon, LatLon)>,
}

/// Deterministic pseudo-random numbers in [0, 1) (xorshift64).
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> f64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }
}

fn fastest(v: Vec<f64>) -> f64 {
    v.into_iter().reduce(f64::min).unwrap_or(0.0)
}

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1e3
}

/// A fixed CPU- and memory-bound workload (sorting 2 M pseudo-random
/// numbers), timed in milliseconds (fastest of [`ROUNDS`]).
pub fn calibrate() -> f64 {
    let times = (0..ROUNDS)
        .map(|_| {
            let t = Instant::now();
            let mut rng = Rng(0x2545_f491_4f6c_dd1d);
            let mut v: Vec<u64> = (0..2_000_000).map(|_| rng.next().to_bits()).collect();
            v.sort_unstable();
            std::hint::black_box(v[v.len() / 2]);
            ms(t)
        })
        .collect();
    fastest(times)
}

/// Runs the benchmark on a region file.
pub fn run(path: &Path) -> Result<Report, String> {
    let file_bytes = std::fs::metadata(path)
        .map_err(|e| format!("{}: {e}", path.display()))?
        .len();
    let calibration_ms = calibrate();

    // Untimed warm-up: the first read of the file comes from disk, the
    // phone-relevant case is the file already in memory.
    moto_core::region::verify_file(path).map_err(|e| e.to_string())?;
    let mut verify = Vec::new();
    let mut open = Vec::new();
    let mut region = None;
    for _ in 0..SHORT_ROUNDS {
        let t = Instant::now();
        moto_core::region::verify_file(path).map_err(|e| e.to_string())?;
        verify.push(ms(t));
        let t = Instant::now();
        region = Some(Region::open(path).map_err(|e| e.to_string())?);
        open.push(ms(t));
    }
    let region = region.ok_or("no rounds")?;
    let info = region.info().clone();
    let (nodes, edges) = (region.node_count(), region.edge_count());
    let grid = (region.grid_meta().rows, region.grid_meta().cols);
    let engine = Engine::from_region(region);

    // Fixed pseudo-random points over the bounding box.
    let b = info.bbox;
    let mut rng = Rng(0x9e37_79b9_7f4a_7c15);
    let points: Vec<LatLon> = (0..SNAP_POINTS)
        .map(|_| LatLon {
            lat: (f64::from(b.min_lat) + rng.next() * f64::from(b.max_lat - b.min_lat)) / 1e7,
            lon: (f64::from(b.min_lon) + rng.next() * f64::from(b.max_lon - b.min_lon)) / 1e7,
        })
        .collect();
    let mut on_road = Vec::new();
    let mut snap_runs = Vec::new();
    for _ in 0..ROUNDS {
        let t = Instant::now();
        on_road = points
            .iter()
            .copied()
            .filter(|&p| engine.snap(p).is_ok())
            .collect();
        snap_runs.push(t.elapsed().as_secs_f64() * 1e6 / points.len() as f64);
    }
    let snap_us_mean = fastest(snap_runs);

    let pairs: Vec<(LatLon, LatLon)> = on_road
        .as_chunks::<2>()
        .0
        .iter()
        .take(ROUTE_PAIRS)
        .map(|&[a, z]| (a, z))
        .collect();
    let opts = RouteOptions::default();
    let anything = RouteOptions {
        avoid: Avoid {
            motorways: false,
            unpaved: false,
            ferries: false,
        },
        ..opts.clone()
    };

    // Per-pair times: the fastest of the rounds.
    let mut per_pair = vec![Vec::new(); pairs.len()];
    let (mut found, mut km) = (0, 0.0);
    let mut ridden: Vec<Vec<LatLon>> = Vec::new();
    for round in 0..ROUNDS {
        for (i, &(a, z)) in pairs.iter().enumerate() {
            let t = Instant::now();
            let r = engine.route(a, z, &opts);
            per_pair[i].push(ms(t));
            if round == 0
                && let Ok(r) = r
            {
                found += 1;
                km += r.distance_m / 1000.0;
                if ridden.len() < MATCH_TRACKS {
                    ridden.push(r.geometry);
                }
            }
        }
    }
    let times = sorted(per_pair);

    // Routing with favourites: sections drawn from road points not used
    // as route ends.
    let reach = FAVOURITE_REACH_DEG.min(f64::from(b.max_lat - b.min_lat) / 1e7 / 4.0);
    let sections: Vec<Section> = on_road
        .iter()
        .skip(2 * ROUTE_PAIRS)
        .take(FAVOURITE_SECTIONS)
        .enumerate()
        .filter_map(|(i, &p)| {
            let north = LatLon {
                lat: p.lat + reach,
                lon: p.lon,
            };
            let d = engine.section_between(p, north).ok()?;
            Some(Section {
                id: i as i64 + 1,
                rider_id: LOCAL_RIDER.into(),
                name: String::new(),
                rating: [Rating::Good, Rating::Great, Rating::Epic][i % 3],
                direction: [Direction::Both, Direction::Forward][usize::from(i % 5 == 0)],
                source: Source::Map,
                status: Status::Ok,
                created_at: 0,
                updated_at: 0,
                ways: d.ways,
                geometry: d.geometry,
            })
        })
        .collect();
    let mut build_runs = Vec::new();
    let mut favourites = Favourites::none();
    for _ in 0..ROUNDS {
        let t = Instant::now();
        favourites = Favourites::build(&engine, &sections);
        build_runs.push(ms(t));
    }
    let mut fav_per_pair = vec![Vec::new(); pairs.len()];
    let (mut fav_found, mut fav_share, mut fav_detour) = (0u32, 0.0, 0.0);
    let mut fastest_share = 0.0;
    let no_detour = RouteOptions {
        budget: moto_core::TimeBudget::Extra(0.0),
        ..opts.clone()
    };
    for round in 0..ROUNDS {
        for (i, &(a, z)) in pairs.iter().enumerate() {
            let t = Instant::now();
            let r = engine.route_with(a, z, &opts, &favourites);
            fav_per_pair[i].push(ms(t));
            if round == 0
                && let (Ok(r), Ok(fastest)) = (r, engine.route_with(a, z, &no_detour, &favourites))
                && fastest.duration_s > 0.0
            {
                fastest_share += fastest.favourite_share;
                fav_found += 1;
                fav_share += r.favourite_share;
                fav_detour += r.duration_s / fastest.duration_s;
            }
        }
    }
    let fav_times = sorted(fav_per_pair);

    // Curvature alone: no favourites.
    let none = Favourites::none();
    let mut curvy_per_pair = vec![Vec::new(); pairs.len()];
    let mut gains = Vec::new();
    for round in 0..ROUNDS {
        for (i, &(a, z)) in pairs.iter().enumerate() {
            let t = Instant::now();
            let r = engine.route_with(a, z, &opts, &none);
            curvy_per_pair[i].push(ms(t));
            if round == 0
                && let (Ok(r), Ok(f)) = (r, engine.route(a, z, &opts))
                && f.curvy_share * f.distance_m > 0.0
            {
                gains.push(r.curvy_share * r.distance_m / (f.curvy_share * f.distance_m));
            }
        }
    }
    let curvy_times = sorted(curvy_per_pair);
    gains.sort_by(f64::total_cmp);

    // Round trips from some of the starts, with the favourites.
    let loop_requests: Vec<(LatLon, f64)> = pairs
        .iter()
        .take(LOOP_STARTS)
        .flat_map(|&(a, _)| LOOP_KM.map(|km| (a, km)))
        .collect();
    let mut loop_per_request = vec![Vec::new(); loop_requests.len()];
    let (mut loop_found_two, mut loop_count) = (0, 0);
    for round in 0..LOOP_ROUNDS {
        for (i, &(a, km)) in loop_requests.iter().enumerate() {
            let t = Instant::now();
            let r = engine.round_trip_with(
                a,
                RoundTripTarget::DistanceM(km * 1000.0),
                &opts,
                &favourites,
                &Default::default(),
            );
            loop_per_request[i].push(ms(t));
            if round == 0 {
                let n = r.map_or(0, |l| l.len());
                loop_count += n;
                loop_found_two += usize::from(n >= 2);
            }
        }
    }
    let loop_times = sorted(loop_per_request);

    // Map matching: noisy synthetic tracks along some of the routes.
    let mut noise = Rng(0x1234_5678_9abc_def1);
    let tracks: Vec<Vec<LatLon>> = ridden
        .iter()
        .map(|line| synthetic_track(line, &mut noise))
        .collect();
    let track_km: f64 = ridden
        .iter()
        .map(|l| moto_core::geo::polyline_length_m(l) / 1000.0)
        .sum();
    let (mut matched_km, mut match_pieces) = (0.0, 0);
    let mut match_runs = Vec::new();
    for round in 0..ROUNDS {
        let t = Instant::now();
        for track in &tracks {
            let m = engine.match_track(track).map_err(|e| e.to_string())?;
            if round == 0 {
                match_pieces += m.pieces.len();
                matched_km += m.pieces.iter().map(|p| p.distance_m / 1000.0).sum::<f64>();
            }
        }
        match_runs.push(ms(t));
    }
    let match_ms = fastest(match_runs);

    let unroutable: Vec<(LatLon, LatLon)> = pairs
        .iter()
        .copied()
        .filter(|&(a, z)| engine.route(a, z, &anything).is_err())
        .collect();

    Ok(Report {
        file_bytes,
        source_name: info.source_name,
        osm_timestamp: info.osm_timestamp,
        builder_version: info.builder_version,
        nodes,
        edges,
        grid,
        calibration_ms,
        verify_ms: fastest(verify),
        open_ms: fastest(open),
        snap_us_mean,
        snapped: on_road.len(),
        route_ms_mean: mean(&times),
        route_ms_p50: pick(&times, 0.5),
        route_ms_p95: pick(&times, 0.95),
        route_ms_max: pick(&times, 1.0),
        route_pairs: pairs.len(),
        routes_found: found,
        routes_found_avoiding_nothing: pairs.len() - unroutable.len(),
        route_km_mean: km / f64::from(u32::try_from(found.max(1)).unwrap_or(u32::MAX)),
        favourites_build_ms: fastest(build_runs),
        favourite_sections: sections.len(),
        favourite_edges: favourites.edge_count(),
        fav_route_ms_mean: mean(&fav_times),
        fav_route_ms_p95: pick(&fav_times, 0.95),
        fav_share_mean: fav_share / f64::from(fav_found.max(1)),
        fav_share_fastest: fastest_share / f64::from(fav_found.max(1)),
        fav_detour_mean: if fav_found > 0 {
            fav_detour / f64::from(fav_found)
        } else {
            0.0
        },
        curvy_route_ms_mean: mean(&curvy_times),
        curvy_route_ms_p95: pick(&curvy_times, 0.95),
        curvy_gain_median: pick(&gains, 0.5),
        loop_ms_mean: mean(&loop_times),
        loop_ms_p95: pick(&loop_times, 0.95),
        loop_requests: loop_requests.len(),
        loop_found_two,
        loops_mean: if loop_requests.is_empty() {
            0.0
        } else {
            loop_count as f64 / loop_requests.len() as f64
        },
        match_ms_per_km: if track_km > 0.0 {
            match_ms / track_km
        } else {
            0.0
        },
        match_tracks: tracks.len(),
        match_km: track_km,
        match_share: if track_km > 0.0 {
            matched_km / track_km
        } else {
            0.0
        },
        match_pieces,
        unroutable,
    })
}

impl Report {
    /// Human-readable summary lines.
    pub fn lines(&self) -> Vec<String> {
        let mut out = vec![
            format!(
                "file      {:.1} MiB",
                self.file_bytes as f64 / (1024.0 * 1024.0)
            ),
            format!(
                "source    {} (OSM timestamp {})",
                self.source_name, self.osm_timestamp
            ),
            format!("builder   {}", self.builder_version),
            format!(
                "graph     {} nodes, {} edges; grid {}×{} cells",
                self.nodes, self.edges, self.grid.0, self.grid.1
            ),
            format!(
                "calibrate {:.1} ms (fixed CPU workload)",
                self.calibration_ms
            ),
            format!("verify    {:.1} ms (CRC32 + structure)", self.verify_ms),
            format!("open      {:.1} ms (map + structure)", self.open_ms),
            format!(
                "snap      {:.1} µs mean over {SNAP_POINTS} random points, {} within {} m of a road",
                self.snap_us_mean,
                self.snapped,
                moto_core::SNAP_MAX_DISTANCE_M
            ),
            format!(
                "route     {:.1} ms mean, {:.1} ms median, {:.1} ms p95, {:.1} ms max over {} random pairs; \
                 {} found with the default options ({} avoiding nothing), mean {:.1} km",
                self.route_ms_mean,
                self.route_ms_p50,
                self.route_ms_p95,
                self.route_ms_max,
                self.route_pairs,
                self.routes_found,
                self.routes_found_avoiding_nothing,
                self.route_km_mean
            ),
        ];
        out.push(format!(
            "favourite {:.1} ms mean, {:.1} ms p95 over the same pairs with {} sections \
             ({} edges, built in {:.1} ms); {:.1} % of the route on favourites (fastest route: {:.1} %), \
             {:.2}× the fastest time",
            self.fav_route_ms_mean,
            self.fav_route_ms_p95,
            self.favourite_sections,
            self.favourite_edges,
            self.favourites_build_ms,
            self.fav_share_mean * 100.0,
            self.fav_share_fastest * 100.0,
            self.fav_detour_mean
        ));
        out.push(format!(
            "curvy     {:.1} ms mean, {:.1} ms p95 over the same pairs with no favourites; \
             median {:.2}× the fastest route's curvy distance",
            self.curvy_route_ms_mean, self.curvy_route_ms_p95, self.curvy_gain_median
        ));
        out.push(format!(
            "loops     {:.1} ms mean, {:.1} ms p95 over {} round trips ({} starts × {:?} km, \
             with the favourites); {} with 2+ loops, {:.1} loops each on average",
            self.loop_ms_mean,
            self.loop_ms_p95,
            self.loop_requests,
            LOOP_STARTS,
            LOOP_KM,
            self.loop_found_two,
            self.loops_mean
        ));
        out.push(format!(
            "match     {:.2} ms per km over {} noisy tracks ({:.0} km, fix every {TRACK_STEP_M} m, \
             ±{TRACK_NOISE_M} m); {:.1} % of the length matched in {} pieces",
            self.match_ms_per_km,
            self.match_tracks,
            self.match_km,
            self.match_share * 100.0,
            self.match_pieces
        ));
        for (a, z) in &self.unroutable {
            out.push(format!(
                "no route  {:.5},{:.5} → {:.5},{:.5}",
                a.lat, a.lon, z.lat, z.lon
            ));
        }
        out
    }

    /// The report as a flat JSON object, for CI to compare builds.
    pub fn to_json(&self) -> String {
        let num = |k: &str, v: f64| format!("  \"{k}\": {}", if v.is_finite() { v } else { 0.0 });
        let fields = [
            num("file_bytes", self.file_bytes as f64),
            format!("  \"source_name\": \"{}\"", json_escape(&self.source_name)),
            num("osm_timestamp", self.osm_timestamp as f64),
            format!(
                "  \"builder_version\": \"{}\"",
                json_escape(&self.builder_version)
            ),
            num("nodes", self.nodes as f64),
            num("edges", self.edges as f64),
            num("calibration_ms", self.calibration_ms),
            num("verify_ms", self.verify_ms),
            num("open_ms", self.open_ms),
            num("snap_us_mean", self.snap_us_mean),
            num("snapped", self.snapped as f64),
            num("route_ms_mean", self.route_ms_mean),
            num("route_ms_p50", self.route_ms_p50),
            num("route_ms_p95", self.route_ms_p95),
            num("route_ms_max", self.route_ms_max),
            num("route_pairs", self.route_pairs as f64),
            num("routes_found", self.routes_found as f64),
            num(
                "routes_found_avoiding_nothing",
                self.routes_found_avoiding_nothing as f64,
            ),
            num("route_km_mean", self.route_km_mean),
            num("favourites_build_ms", self.favourites_build_ms),
            num("favourite_sections", self.favourite_sections as f64),
            num("favourite_edges", self.favourite_edges as f64),
            num("fav_route_ms_mean", self.fav_route_ms_mean),
            num("fav_route_ms_p95", self.fav_route_ms_p95),
            num("fav_share_mean", self.fav_share_mean),
            num("fav_share_fastest", self.fav_share_fastest),
            num("fav_detour_mean", self.fav_detour_mean),
            num("curvy_route_ms_mean", self.curvy_route_ms_mean),
            num("curvy_route_ms_p95", self.curvy_route_ms_p95),
            num("curvy_gain_median", self.curvy_gain_median),
            num("loop_ms_mean", self.loop_ms_mean),
            num("loop_ms_p95", self.loop_ms_p95),
            num("loop_requests", self.loop_requests as f64),
            num("loop_found_two", self.loop_found_two as f64),
            num("loops_mean", self.loops_mean),
            num("match_ms_per_km", self.match_ms_per_km),
            num("match_tracks", self.match_tracks as f64),
            num("match_km", self.match_km),
            num("match_share", self.match_share),
            num("match_pieces", self.match_pieces as f64),
        ];
        format!("{{\n{}\n}}\n", fields.join(",\n"))
    }
}

/// Per-input timings merged to the fastest round each, sorted.
fn sorted(per_input: Vec<Vec<f64>>) -> Vec<f64> {
    let mut times: Vec<f64> = per_input.into_iter().map(fastest).collect();
    times.sort_by(f64::total_cmp);
    times
}

fn mean(v: &[f64]) -> f64 {
    if v.is_empty() {
        0.0
    } else {
        v.iter().sum::<f64>() / v.len() as f64
    }
}

/// The `q` quantile of sorted `times` (0 when empty).
fn pick(times: &[f64], q: f64) -> f64 {
    times
        .get(((times.len() as f64 - 1.0) * q).round() as usize)
        .copied()
        .unwrap_or(0.0)
}

/// A GPS track along `line`: a fix every [`TRACK_STEP_M`] metres, each
/// moved by up to [`TRACK_NOISE_M`] metres north and east.
fn synthetic_track(line: &[LatLon], rng: &mut Rng) -> Vec<LatLon> {
    const M_PER_DEG: f64 = 111_195.0;
    let mut track = Vec::new();
    let mut carry = 0.0; // metres since the last fix
    for w in line.windows(2) {
        let len = w[0].distance_m(&w[1]);
        let mut at = if track.is_empty() {
            0.0
        } else {
            TRACK_STEP_M - carry
        };
        while at <= len {
            let f = if len > 0.0 { at / len } else { 0.0 };
            let k = w[0].lat.to_radians().cos().max(0.01);
            track.push(LatLon {
                lat: w[0].lat
                    + f * (w[1].lat - w[0].lat)
                    + (rng.next() * 2.0 - 1.0) * TRACK_NOISE_M / M_PER_DEG,
                lon: w[0].lon
                    + f * (w[1].lon - w[0].lon)
                    + (rng.next() * 2.0 - 1.0) * TRACK_NOISE_M / (M_PER_DEG * k),
            });
            at += TRACK_STEP_M;
        }
        carry = len - (at - TRACK_STEP_M);
    }
    track
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::built_fixture;

    #[test]
    fn benchmarks_a_real_region() {
        let file = built_fixture("bench");
        let r = run(file.path()).unwrap();
        assert_eq!((r.nodes, r.edges), (10, 16));
        assert!(r.calibration_ms > 0.0 && r.verify_ms > 0.0 && r.open_ms > 0.0);
        assert!(r.snapped > SNAP_POINTS / 2, "{r:?}");
        assert_eq!(r.route_pairs, ROUTE_PAIRS);
        assert!(r.routes_found > 0 && r.routes_found <= r.routes_found_avoiding_nothing);
        assert!(r.route_ms_p50 <= r.route_ms_p95 && r.route_ms_p95 <= r.route_ms_max);
        assert!(r.route_km_mean > 0.0 && r.route_km_mean < 2.0, "{r:?}");
        assert_eq!(r.lines().len(), 13 + r.unroutable.len());
        // The fixture is far smaller than a loop: every request is timed
        // and none finds one.
        assert_eq!(
            r.loop_requests,
            LOOP_STARTS.min(ROUTE_PAIRS) * LOOP_KM.len()
        );
        assert!(r.loop_ms_mean > 0.0 && r.loop_ms_p95 >= 0.0, "{r:?}");
        assert_eq!((r.loop_found_two, r.loops_mean), (0, 0.0));
        assert!(
            r.curvy_route_ms_mean > 0.0 && r.curvy_route_ms_p95 > 0.0,
            "{r:?}"
        );
        assert!(r.favourite_sections > 0 && r.favourite_edges > 0, "{r:?}");
        assert!(
            r.fav_route_ms_mean > 0.0 && r.fav_route_ms_p95 > 0.0,
            "{r:?}"
        );
        assert!(r.fav_share_mean > 0.0 && r.fav_share_fastest > 0.0, "{r:?}");
        let moto_core::TimeBudget::Extra(ratio) = RouteOptions::default().budget else {
            panic!("the default budget is a ratio");
        };
        let budget = 1.0 + ratio;
        assert!(
            r.fav_detour_mean >= 1.0 - 1e-9 && r.fav_detour_mean <= budget,
            "{r:?}"
        );
        assert!(
            r.match_tracks > 0 && r.match_km > 0.0 && r.match_ms_per_km > 0.0,
            "{r:?}"
        );
        assert!(r.match_share > 0.8 && r.match_share < 1.2, "{r:?}");
    }

    #[test]
    fn inputs_are_the_same_every_run() {
        let file = built_fixture("bench-repeat");
        let (a, b) = (run(file.path()).unwrap(), run(file.path()).unwrap());
        assert_eq!(a.snapped, b.snapped);
        assert_eq!(a.routes_found, b.routes_found);
        assert_eq!(a.route_km_mean, b.route_km_mean);
        assert_eq!(a.match_share, b.match_share);
        assert_eq!(a.favourite_edges, b.favourite_edges);
        assert_eq!(a.fav_share_mean, b.fav_share_mean);
        assert_eq!(a.loops_mean, b.loops_mean);
    }

    #[test]
    fn json_is_flat_and_escaped() {
        let file = built_fixture("bench-json");
        let mut r = run(file.path()).unwrap();
        r.source_name = "a \"quoted\" \\ name\n".into();
        let json = r.to_json();
        assert!(json.starts_with("{\n") && json.ends_with("}\n"));
        assert!(
            json.contains(r#""source_name": "a \"quoted\" \\ name\u000a""#),
            "{json}"
        );
        assert!(json.contains("\"edges\": 16"), "{json}");
        assert_eq!(json.matches(':').count(), 40);
    }

    #[test]
    fn missing_or_bad_files_are_errors() {
        assert!(run(Path::new("/definitely/not/here.region")).is_err());
    }

    #[test]
    fn fastest_of_nothing_is_zero() {
        assert_eq!(fastest(vec![]), 0.0);
        assert_eq!(fastest(vec![3.0, 1.0, 2.0]), 1.0);
    }
}
