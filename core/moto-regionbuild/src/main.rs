// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `moto-regionbuild`: OSM extract → routing region file (ADR-0001,
//! ADR-0005, PRD R12). Runs on desktop/CI, never on the phone.
//!
//! Reads a `.osm.pbf` (e.g. Geofabrik's Sweden extract), keeps the
//! motorcycle-routable ways inside a bounding box (Skåne and its
//! neighbourhood by default), builds
//! the routing graph and writes the region file. `--check` opens an existing
//! file and measures verification, open and snap times.

mod graph;
mod hilbert;
mod tags;

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use moto_core::region::format::{BBoxE7, PointE7, RoadClass, Surface};
use moto_core::region::{Region, RegionInfo};
use moto_core::{Engine, LatLon};
use osmpbf::{BlobDecode, BlobReader, Element};
use rayon::prelude::*;

use graph::{NodeIndex, RawWay};

const USAGE: &str = "\
usage: moto-regionbuild <input.osm.pbf> <output.region> [--bbox S,W,N,E]
       moto-regionbuild --check <file.region> [LAT,LON ...]

  --bbox   cut to this box in degrees (default: Skåne and surroundings,
           55.28,12.20,56.72,15.05)
  --check  verify checksums, time opening and snapping, and snap the
           given points";

/// M0 region (ADR-0005; a polygon comes later): Skåne plus the southern
/// half of Halland, southern Småland and western Blekinge, from Trelleborg
/// to just north of Halmstad and Älmhult, and east to Karlshamn.
const SKANE_BBOX: [f64; 4] = [55.28, 12.20, 56.72, 15.05];

/// Snapping grid cell: about 280 × 280 m at 56°N. Measured on Skåne: 4×
/// the cells of a 550 m grid add 1 MiB and make snapping in towns 3× faster.
const GRID_CELL_E7: (i32, i32) = (25_000, 45_000);

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "-h" || a == "--help") {
        println!("{USAGE}");
        return ExitCode::SUCCESS;
    }
    let result = match args.as_slice() {
        [flag, file, probes @ ..] if flag == "--check" => check(Path::new(file), probes),
        [input, output] => build(input.into(), output.into(), SKANE_BBOX),
        [input, output, flag, bbox] if flag == "--bbox" => match parse_bbox(bbox) {
            Some(b) => build(input.into(), output.into(), b),
            None => Err(format!("bad --bbox '{bbox}', expected S,W,N,E in degrees")),
        },
        _ => {
            eprintln!("{USAGE}");
            return ExitCode::from(2);
        }
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

fn parse_bbox(s: &str) -> Option<[f64; 4]> {
    let v: Vec<f64> = s
        .split(',')
        .map(|p| p.trim().parse().ok())
        .collect::<Option<_>>()?;
    let b: [f64; 4] = v.try_into().ok()?;
    let ok = b[0] < b[2]
        && b[1] < b[3]
        && (-90.0..=90.0).contains(&b[0])
        && (-90.0..=90.0).contains(&b[2])
        && (-180.0..=180.0).contains(&b[1])
        && (-180.0..=180.0).contains(&b[3]);
    ok.then_some(b)
}

/// What one PBF blob contributes.
#[derive(Default)]
struct BlobOut {
    nodes: Vec<(i64, PointE7)>,
    ways: Vec<RawWay>,
    timestamp: Option<i64>,
}

fn read_pbf(path: &Path, bbox: &BBoxE7) -> Result<BlobOut, String> {
    let reader = BlobReader::from_path(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let inside = |lat: i32, lon: i32| {
        (bbox.min_lat..=bbox.max_lat).contains(&lat) && (bbox.min_lon..=bbox.max_lon).contains(&lon)
    };
    let parts: Vec<BlobOut> = reader
        .par_bridge()
        .map(|blob| -> Result<BlobOut, String> {
            let blob = blob.map_err(|e| e.to_string())?;
            let mut out = BlobOut::default();
            match blob.decode().map_err(|e| e.to_string())? {
                BlobDecode::OsmHeader(h) => out.timestamp = h.osmosis_replication_timestamp(),
                BlobDecode::OsmData(block) => block.for_each_element(|el| match el {
                    Element::DenseNode(n) => {
                        let (lat, lon) = (n.decimicro_lat(), n.decimicro_lon());
                        if inside(lat, lon) {
                            out.nodes.push((n.id(), PointE7 { lat, lon }));
                        }
                    }
                    Element::Node(n) => {
                        let (lat, lon) = (n.decimicro_lat(), n.decimicro_lon());
                        if inside(lat, lon) {
                            out.nodes.push((n.id(), PointE7 { lat, lon }));
                        }
                    }
                    Element::Way(w) => {
                        let tags: Vec<(&str, &str)> = w.tags().collect();
                        if let Some(attrs) = tags::classify(&tags) {
                            out.ways.push(RawWay {
                                id: w.id(),
                                refs: w.refs().collect(),
                                attrs,
                            });
                        }
                    }
                    Element::Relation(_) => {}
                }),
                BlobDecode::Unknown(_) => {}
            }
            Ok(out)
        })
        .collect::<Result<_, _>>()?;

    let mut all = BlobOut::default();
    for mut p in parts {
        all.nodes.append(&mut p.nodes);
        all.ways.append(&mut p.ways);
        all.timestamp = all.timestamp.or(p.timestamp);
    }
    // Blob order is lost in parallel; keep the output deterministic.
    all.ways.sort_unstable_by_key(|w| w.id);
    Ok(all)
}

fn build(input: PathBuf, output: PathBuf, b: [f64; 4]) -> Result<(), String> {
    if !input.is_file() {
        return Err(format!("input not found: {}", input.display()));
    }
    let start = Instant::now();
    let bbox = graph::bbox_e7(b[0], b[1], b[2], b[3]);

    let t = Instant::now();
    let osm = read_pbf(&input, &bbox)?;
    eprintln!(
        "read      {:>7.1} s  {} nodes in bbox, {} routable ways (whole file)",
        t.elapsed().as_secs_f64(),
        osm.nodes.len(),
        osm.ways.len()
    );

    let t = Instant::now();
    let file_name = input
        .file_name()
        .map(|f| f.to_string_lossy().into_owned())
        .unwrap_or_default();
    let info = RegionInfo {
        osm_timestamp: osm.timestamp.unwrap_or(0),
        bbox,
        builder_version: format!("moto-regionbuild {}", env!("CARGO_PKG_VERSION")),
        source_name: format!("{file_name} [{},{},{},{}]", b[0], b[1], b[2], b[3]),
    };
    let index = NodeIndex::new(osm.nodes);
    let (data, stats) = graph::build(&osm.ways, &index, info, GRID_CELL_E7);
    drop(index);
    eprintln!(
        "graph     {:>7.1} s  {} ways in bbox → {} nodes, {} edges, {} geometries, {} shape points",
        t.elapsed().as_secs_f64(),
        stats.ways,
        stats.nodes,
        stats.edges,
        stats.segments,
        stats.shape_points
    );

    let t = Instant::now();
    let bytes = data.to_bytes().map_err(|e| e.to_string())?;
    std::fs::write(&output, &bytes).map_err(|e| format!("{}: {e}", output.display()))?;
    eprintln!(
        "write     {:>7.1} s  {} ({:.1} MiB)",
        t.elapsed().as_secs_f64(),
        output.display(),
        bytes.len() as f64 / (1024.0 * 1024.0)
    );
    eprintln!(
        "total     {:>7.1} s  peak memory {}",
        start.elapsed().as_secs_f64(),
        peak_memory().unwrap_or_else(|| "unknown".into())
    );
    Ok(())
}

/// Opens a region file and reports what the phone will care about.
fn check(path: &Path, probes: &[String]) -> Result<(), String> {
    let size = std::fs::metadata(path)
        .map_err(|e| format!("{}: {e}", path.display()))?
        .len();
    let t = Instant::now();
    moto_core::region::verify_file(path).map_err(|e| e.to_string())?;
    let verify = t.elapsed();

    let t = Instant::now();
    let region = Region::open(path).map_err(|e| e.to_string())?;
    let open = t.elapsed();

    let info = region.info().clone();
    let (nodes, edges) = (region.node_count(), region.edge_count());
    let grid = *region.grid_meta();
    let engine = Engine::from_region(region);

    // Snap pseudo-random points spread over the bounding box.
    let b = info.bbox;
    let mut state = 0x9e37_79b9_7f4a_7c15u64;
    let mut rnd = move || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        (state >> 11) as f64 / (1u64 << 53) as f64
    };
    let points: Vec<LatLon> = (0..10_000)
        .map(|_| LatLon {
            lat: (f64::from(b.min_lat) + rnd() * f64::from(b.max_lat - b.min_lat)) / 1e7,
            lon: (f64::from(b.min_lon) + rnd() * f64::from(b.max_lon - b.min_lon)) / 1e7,
        })
        .collect();
    let t = Instant::now();
    let snapped = points.iter().filter(|&&p| engine.snap(p).is_ok()).count();
    let snap = t.elapsed();

    println!(
        "file      {} ({:.1} MiB)",
        path.display(),
        size as f64 / (1024.0 * 1024.0)
    );
    println!(
        "source    {} (OSM timestamp {})",
        info.source_name, info.osm_timestamp
    );
    println!("builder   {}", info.builder_version);
    println!(
        "graph     {nodes} nodes, {edges} edges; grid {}×{} cells",
        grid.rows, grid.cols
    );
    println!(
        "verify    {:.1} ms (CRC32 + structure)",
        verify.as_secs_f64() * 1e3
    );
    println!(
        "open      {:.1} ms (map + structure)",
        open.as_secs_f64() * 1e3
    );
    println!(
        "snap      {:.1} µs mean over {} random points, {} within {} m of a road",
        snap.as_secs_f64() * 1e6 / points.len() as f64,
        points.len(),
        snapped,
        moto_core::SNAP_MAX_DISTANCE_M
    );

    for probe in probes {
        let p =
            parse_point(probe).ok_or_else(|| format!("bad point '{probe}', expected LAT,LON"))?;
        match engine.snap(p) {
            Ok(r) => {
                let region = engine.region();
                let e = region.edges()[r.edge as usize];
                let w = region.way_refs()[r.edge as usize];
                println!(
                    "snap {probe}: {:.6},{:.6} ({:.1} m) edge {} offset {:.3}, way {} [{}..{}], {:?} {:?} {} km/h",
                    r.position.lat,
                    r.position.lon,
                    r.distance_m,
                    r.edge,
                    r.offset,
                    w.way_id,
                    w.from_idx,
                    w.to_idx,
                    RoadClass::from_u8(e.class),
                    Surface::from_u8(e.surface),
                    e.speed_kmh
                );
            }
            Err(e) => println!("snap {probe}: {e}"),
        }
    }
    Ok(())
}

fn parse_point(s: &str) -> Option<LatLon> {
    let (lat, lon) = s.split_once(',')?;
    LatLon::new(lat.trim().parse().ok()?, lon.trim().parse().ok()?).ok()
}

/// Peak resident memory of this process (Linux only).
fn peak_memory() -> Option<String> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = status.lines().find(|l| l.starts_with("VmHWM:"))?;
    let kb: f64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(format!("{:.0} MiB", kb / 1024.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_bbox() {
        assert_eq!(
            parse_bbox("55.3,12.4,56.55,14.65"),
            Some([55.3, 12.4, 56.55, 14.65])
        );
        assert_eq!(parse_bbox("56,12,55,14"), None);
        assert_eq!(parse_bbox("1,2,3"), None);
        assert_eq!(parse_bbox("a,b,c,d"), None);
        assert_eq!(parse_bbox("0,-200,1,0"), None);
    }
}
