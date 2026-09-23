// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The region benchmark behind `--check`: verify, open, snap and route
//! timings on fixed pseudo-random inputs, plus a CPU calibration run so CI
//! can compare builds made on different machines.

use std::path::Path;
use std::time::Instant;

use moto_core::region::Region;
use moto_core::{Avoid, Engine, LatLon, RouteOptions};

/// Random points snapped by the benchmark.
pub const SNAP_POINTS: usize = 10_000;
/// Random start/end pairs routed by the benchmark.
pub const ROUTE_PAIRS: usize = 100;
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
        .chunks_exact(2)
        .take(ROUTE_PAIRS)
        .map(|c| (c[0], c[1]))
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
            }
        }
    }
    let mut times: Vec<f64> = per_pair.into_iter().map(fastest).collect();
    times.sort_by(f64::total_cmp);
    let pick = |q: f64| {
        times
            .get(((times.len() as f64 - 1.0) * q).round() as usize)
            .copied()
            .unwrap_or(0.0)
    };

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
        route_ms_mean: if times.is_empty() {
            0.0
        } else {
            times.iter().sum::<f64>() / times.len() as f64
        },
        route_ms_p50: pick(0.5),
        route_ms_p95: pick(0.95),
        route_ms_max: pick(1.0),
        route_pairs: pairs.len(),
        routes_found: found,
        routes_found_avoiding_nothing: pairs.len() - unroutable.len(),
        route_km_mean: km / f64::from(u32::try_from(found.max(1)).unwrap_or(u32::MAX)),
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
        ];
        format!("{{\n{}\n}}\n", fields.join(",\n"))
    }
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
        assert_eq!(r.lines().len(), 9 + r.unroutable.len());
    }

    #[test]
    fn inputs_are_the_same_every_run() {
        let file = built_fixture("bench-repeat");
        let (a, b) = (run(file.path()).unwrap(), run(file.path()).unwrap());
        assert_eq!(a.snapped, b.snapped);
        assert_eq!(a.routes_found, b.routes_found);
        assert_eq!(a.route_km_mean, b.route_km_mean);
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
        assert_eq!(json.matches(':').count(), 19);
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
