// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `moto-regionbuild`: OSM extract → routing region file (ADR-0001,
//! ADR-0005, PRD R12). Runs on desktop/CI, never on the phone.
//!
//! Reads a `.osm.pbf` (e.g. Geofabrik's Sweden extract), keeps the
//! motorcycle-routable ways inside a bounding box (Skåne and its
//! neighbourhood by default), builds the routing graph and writes the region
//! file. `--check` verifies an existing file and benchmarks it; CI compares
//! its `--json` output between builds. `--match` map-matches a ride
//! exported from the app, to check the matcher on real rides. `--golden`
//! runs the golden-route regression set. `--refresh` derives curvature
//! and built-up areas afresh for an existing region file.

mod bench;
mod derive;
mod golden;
mod graph;
mod hilbert;
mod manifest;
mod pbf;
mod poly;
mod ridecheck;
mod speed;
mod tags;

use std::path::Path;
use std::process::ExitCode;
use std::time::Instant;

use moto_core::region::format::{RoadClass, Surface};
use moto_core::region::{RegionData, RegionInfo};
use moto_core::{Engine, LatLon};

use graph::{GraphStats, NodeIndex};

const USAGE: &str = "\
usage: moto-regionbuild <input.osm.pbf> <output.region> [--bbox S,W,N,E] [--country CC]
                       [--poly <border.poly>]
       moto-regionbuild --check <file.region> [--json <out.json>] [LAT,LON ...]
       moto-regionbuild --match <file.region> <ride.gpx> [--geojson <out.geojson>]
       moto-regionbuild --golden <file.region>[,<file.region>...] <cases-dir> [--json <out.json>]
       moto-regionbuild --refresh <in.region> <out.region>
       moto-regionbuild --manifest <out.json> (<id> <name> <file.region> <file.region.gz>)...
       moto-regionbuild --check-manifest <signed.manifest> <manifest.json>
       moto-regionbuild --describe <file.region> LAT,LON LAT,LON [LAT,LON ...]

  --bbox   cut to this box in degrees (default: Skåne and surroundings,
           55.28,12.20,56.72,15.05)
  --country the country whose legal speed limits apply to roads without
           a signed limit: SE, DK, NO or FI (default SE)
  --poly   cut the region at this border (an Osmosis .poly file, the
           exact polygon of the country's boundary relation), keeping each
           road up to the first node past it (ADR-0009); without --bbox,
           the box is the border's
  --check  verify checksums, benchmark opening, snapping and routing, and
           snap the given points; --json also writes the numbers as JSON
  --match  map-match a ride exported from the app and report how well it
           fits; --geojson also writes the ride and the matched pieces
  --golden run the golden routes (core/moto-core/tests/golden/*.json) and
           check their expectations; --json also writes the figures;
           several region files (commas) are linked at their borders
  --refresh derive curvature and built-up areas afresh for an existing
           region file, without the extract (to try a change to them)
  --manifest write the manifest of downloadable regions (ADR-0008) for
           their gzip-compressed files, after checking that the core
           installs each one back to its region file
  --check-manifest check a signed manifest (signature, then the JSON) with
           the app's keys, and that it carries exactly <manifest.json>
  --describe name the fastest route through the points as the app names
           sections: its roads and the places at its ends";

/// M0 region (ADR-0005; a polygon comes later): Skåne plus the southern
/// half of Halland, southern Småland and western Blekinge, from Trelleborg
/// to just north of Halmstad and Älmhult, and east to Karlshamn.
const SKANE_BBOX: [f64; 4] = [55.28, 12.20, 56.72, 15.05];

/// Snapping grid cell: about 280 × 280 m at 56°N. Measured on Skåne: 4×
/// the cells of a 550 m grid add 1 MiB and make snapping in towns 3× faster.
const GRID_CELL_E7: (i32, i32) = (25_000, 45_000);

/// Road networks shorter than this in all are left out of the region:
/// on the M0 region (2026-09) 869 of 877 networks, 202 km of 40 342 km,
/// that a tap or a route end snapped to and then found no way out of.
/// The larger separate ones (islands, about 40 km each) stay.
const MIN_NETWORK_M: f64 = 5_000.0;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "-h" || a == "--help") {
        println!("{USAGE}");
        return ExitCode::SUCCESS;
    }
    let result = match args.as_slice() {
        [flag, file, rest @ ..] if flag == "--check" => match rest {
            [json, out, probes @ ..] if json == "--json" => {
                check(Path::new(file), Some(Path::new(out)), probes)
            }
            probes => check(Path::new(file), None, probes),
        },
        [flag, region, gpx] if flag == "--match" => {
            ridecheck::run(Path::new(region), Path::new(gpx), None)
        }
        [flag, region, gpx, out_flag, out] if flag == "--match" && out_flag == "--geojson" => {
            ridecheck::run(Path::new(region), Path::new(gpx), Some(Path::new(out)))
        }
        [flag, region, dir] if flag == "--golden" => {
            golden::run(Path::new(region), Path::new(dir), None)
        }
        [flag, region, dir, out_flag, out] if flag == "--golden" && out_flag == "--json" => {
            golden::run(Path::new(region), Path::new(dir), Some(Path::new(out)))
        }
        [flag, out, rest @ ..] if flag == "--manifest" => manifest::entries(rest).and_then(|e| {
            let scratch =
                std::env::temp_dir().join(format!("moto-manifest-{}", std::process::id()));
            let r = manifest::run(Path::new(out), &e, &scratch);
            let _ = std::fs::remove_dir_all(&scratch);
            r
        }),
        [flag, signed, json] if flag == "--check-manifest" => {
            manifest::check_signed(Path::new(signed), Path::new(json))
        }
        [flag, region, points @ ..] if flag == "--describe" && points.len() >= 2 => {
            describe(Path::new(region), points)
        }
        [flag, input, output] if flag == "--refresh" => {
            refresh(Path::new(input), Path::new(output))
        }
        [input, output, opts @ ..] if !input.starts_with("--") => match build_options(opts) {
            Ok(Some(o)) => build(Path::new(input), Path::new(output), &o),
            Ok(None) => {
                eprintln!("{USAGE}");
                return ExitCode::from(2);
            }
            Err(e) => Err(e),
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

/// What to build a region of.
#[derive(Debug, PartialEq)]
struct BuildOptions {
    /// Degrees S, W, N, E; without one, the border's box (or Skåne's).
    bbox: Option<[f64; 4]>,
    country: speed::Country,
    /// The country's border as an Osmosis `.poly` file (ADR-0009).
    poly: Option<std::path::PathBuf>,
}

#[cfg(test)]
impl BuildOptions {
    fn bbox(bbox: [f64; 4]) -> Self {
        Self {
            bbox: Some(bbox),
            country: speed::Country::Sweden,
            poly: None,
        }
    }
}

/// The build's options (`--bbox`, `--country`, `--poly`, each at most
/// once), or `None` for anything else; an option with a bad value is an
/// error.
fn build_options(opts: &[String]) -> Result<Option<BuildOptions>, String> {
    let (mut bbox, mut country, mut poly) = (None, None, None);
    let mut it = opts.iter();
    while let Some(flag) = it.next() {
        let Some(value) = it.next() else {
            return Ok(None);
        };
        match flag.as_str() {
            "--bbox" if bbox.is_none() => {
                bbox =
                    Some(parse_bbox(value).ok_or_else(|| {
                        format!("bad --bbox '{value}', expected S,W,N,E in degrees")
                    })?);
            }
            "--country" if country.is_none() => {
                country = Some(speed::Country::from_code(value).ok_or_else(|| {
                    format!("unknown --country '{value}', expected SE, DK, NO or FI")
                })?);
            }
            "--poly" if poly.is_none() => poly = Some(std::path::PathBuf::from(value)),
            _ => return Ok(None),
        }
    }
    Ok(Some(BuildOptions {
        bbox,
        country: country.unwrap_or(speed::Country::Sweden),
        poly,
    }))
}

/// Reads and parses a border polygon file, refusing one too large.
fn read_border(path: &Path) -> Result<poly::Border, String> {
    let name = path.display();
    let meta = std::fs::metadata(path).map_err(|e| format!("{name}: {e}"))?;
    if meta.len() > poly::MAX_FILE_BYTES as u64 {
        return Err(format!("{name}: polygon file too large"));
    }
    let text = std::fs::read_to_string(path).map_err(|e| format!("{name}: {e}"))?;
    poly::Border::parse(&text).map_err(|e| format!("{name}: {e}"))
}

/// How far past the border nodes are read, degrees: enough for the first
/// node past it (the stub) on any road.
const BORDER_MARGIN_DEG: f64 = 0.05;

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

/// Reads the extract and builds the region content `o` asks for.
fn build_region(input: &Path, o: &BuildOptions) -> Result<(RegionData, GraphStats), String> {
    if !input.is_file() {
        return Err(format!("input not found: {}", input.display()));
    }
    let border = o.poly.as_deref().map(read_border).transpose()?;
    let b = match (o.bbox, &border) {
        (Some(b), _) => b,
        (None, Some(border)) => {
            let e = border.bbox();
            let deg = |v: i32| f64::from(v) / moto_core::region::format::COORD_SCALE;
            [
                (deg(e.min_lat) - BORDER_MARGIN_DEG).max(-90.0),
                (deg(e.min_lon) - BORDER_MARGIN_DEG).max(-180.0),
                (deg(e.max_lat) + BORDER_MARGIN_DEG).min(90.0),
                (deg(e.max_lon) + BORDER_MARGIN_DEG).min(180.0),
            ]
        }
        (None, None) => SKANE_BBOX,
    };
    let country = o.country;
    let bbox = graph::bbox_e7(b[0], b[1], b[2], b[3]);
    let t = Instant::now();
    let osm = pbf::read(input, &bbox, country)?;
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
        source_name: format!(
            "{file_name} {} [{},{},{},{}]",
            country.code(),
            b[0],
            b[1],
            b[2],
            b[3]
        ),
    };
    let index = NodeIndex::new(osm.nodes);
    let (mut data, stats) = graph::build(
        &osm.ways,
        &index,
        info,
        GRID_CELL_E7,
        MIN_NETWORK_M,
        border.as_ref(),
    );
    let code = country.code().as_bytes();
    data.meta = Some(moto_core::region::format::RegionMeta {
        country: [code[0], code[1]],
        reserved: [0; 14],
    });
    graph::add_places(&mut data.names, &osm.places);
    eprintln!(
        "graph     {:>7.1} s  {} ways in bbox → {} nodes, {} edges, {} geometries, {} shape points",
        t.elapsed().as_secs_f64(),
        stats.ways,
        stats.nodes,
        stats.edges,
        stats.segments,
        stats.shape_points
    );
    eprintln!(
        "fragments          {} road networks under {:.0} km dropped, {:.1} km in all",
        stats.fragments,
        MIN_NETWORK_M / 1000.0,
        stats.fragment_m / 1000.0
    );
    if border.is_some() {
        let stubs = data
            .border
            .iter()
            .filter(|b| b.flags & moto_core::region::format::border_flags::OUTSIDE != 0)
            .count();
        eprintln!(
            "border             {} {} border nodes, {stubs} of them stubs past the border",
            country.code(),
            data.border.len()
        );
    }
    let named = data
        .names
        .geometry_names
        .iter()
        .filter(|g| {
            g.road_ref != moto_core::region::format::NO_NAME
                || g.name != moto_core::region::format::NO_NAME
        })
        .count();
    eprintln!(
        "names              {named} of {} road geometries named, {} strings, {} places",
        data.names.geometry_names.len(),
        data.names.strings.len(),
        data.names.places.len()
    );
    derive_all(&mut data);
    Ok((data, stats))
}

/// Derives curvature and built-up areas (see [`derive`]) and reports.
fn derive_all(data: &mut RegionData) {
    let t = Instant::now();
    let d = derive::finish(data);
    eprintln!(
        "derive    {:>7.1} s  {} junctions; {:.0} of {:.0} km built up ({:.1} %)",
        t.elapsed().as_secs_f64(),
        d.junctions,
        d.built_up_m / 1000.0,
        d.road_m / 1000.0,
        d.built_up_m / d.road_m.max(1.0) * 100.0
    );
}

/// Opens `input` (checked like the app checks it), derives its curvature
/// and built-up areas afresh and writes the result to `output`.
fn refresh(input: &Path, output: &Path) -> Result<(), String> {
    let region = moto_core::region::Region::open(input).map_err(|e| e.to_string())?;
    region.verify_checksums().map_err(|e| e.to_string())?;
    let mut data = derive::data_of(&region);
    drop(region);
    derive_all(&mut data);
    let bytes = data.to_bytes().map_err(|e| e.to_string())?;
    std::fs::write(output, &bytes).map_err(|e| format!("{}: {e}", output.display()))?;
    eprintln!("wrote {}", output.display());
    Ok(())
}

fn build(input: &Path, output: &Path, o: &BuildOptions) -> Result<(), String> {
    let start = Instant::now();
    let (data, _) = build_region(input, o)?;
    let t = Instant::now();
    let bytes = data.to_bytes().map_err(|e| e.to_string())?;
    std::fs::write(output, &bytes).map_err(|e| format!("{}: {e}", output.display()))?;
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

/// Verifies and benchmarks a region file, optionally writes the numbers as
/// JSON, and snaps the given points.
fn check(path: &Path, json: Option<&Path>, probes: &[String]) -> Result<(), String> {
    let points: Vec<LatLon> = probes
        .iter()
        .map(|p| parse_point(p).ok_or_else(|| format!("bad point '{p}', expected LAT,LON")))
        .collect::<Result<_, _>>()?;
    let report = bench::run(path)?;
    for line in report.lines() {
        println!("{line}");
    }
    if let Some(out) = json {
        std::fs::write(out, report.to_json()).map_err(|e| format!("{}: {e}", out.display()))?;
    }
    if !points.is_empty() {
        let engine = Engine::open(path).map_err(|e| e.to_string())?;
        for (probe, p) in probes.iter().zip(points) {
            println!("snap {probe}: {}", describe_snap(&engine, p));
        }
    }
    Ok(())
}

/// Describes the fastest route through `points` (see [`moto_core::describe`]).
fn describe(path: &Path, points: &[String]) -> Result<(), String> {
    let stops: Vec<LatLon> = points
        .iter()
        .map(|p| parse_point(p).ok_or_else(|| format!("bad point '{p}', expected LAT,LON")))
        .collect::<Result<_, _>>()?;
    let engine = Engine::open(path).map_err(|e| e.to_string())?;
    let (first, rest) = stops.split_first().ok_or("no points")?;
    let (last, via) = rest.split_last().ok_or("need two points")?;
    let route = engine
        .route_via(
            *first,
            via,
            *last,
            &moto_core::RouteOptions::default(),
            &moto_core::Favourites::none(),
        )
        .map_err(|e| e.to_string())?;
    let d = engine
        .describe(&route.geometry)
        .map_err(|e| e.to_string())?;
    println!(
        "{:.1} km, {:.0} % curvy",
        route.distance_m / 1000.0,
        d.curvy_share * 100.0
    );
    for road in &d.roads {
        println!(
            "road  {:>3.0} %  ref {:?}  name {:?}",
            road.share * 100.0,
            road.road_ref,
            road.name
        );
    }
    for (end, place) in [("start", &d.start), ("end", &d.end)] {
        match place {
            Some(p) => println!(
                "{end:<5} {} ({:?}, {:.1} km)",
                p.name,
                p.kind,
                p.distance_m / 1000.0
            ),
            None => println!("{end:<5} -"),
        }
    }
    Ok(())
}

/// Where `p` snaps to, with the road's OSM way and attributes.
fn describe_snap(engine: &Engine, p: LatLon) -> String {
    match engine.snap(p) {
        Ok(r) => {
            let region = engine.region();
            let e = region.edges()[r.edge as usize];
            let w = region.way_refs()[r.edge as usize];
            let names = region.geometry_name(e.geometry);
            format!(
                "{:.6},{:.6} ({:.1} m) edge {} offset {:.3}, way {} [{}..{}], {:?} {:?} {} km/h, ref {:?} name {:?}",
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
                e.speed_kmh,
                region.string(names.road_ref),
                region.string(names.name)
            )
        }
        Err(e) => e.to_string(),
    }
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

/// Shared test helpers: the committed PBF fixture and a region built from it.
#[cfg(test)]
mod test_support {
    use std::path::{Path, PathBuf};

    /// A tiny extract of central Lund (see tests/fixtures/README.md).
    pub const FIXTURE: &str = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/lund-centre.osm.pbf"
    );
    /// The box the fixture was cut to.
    pub const FIXTURE_BBOX: [f64; 4] = [55.7040, 13.1900, 55.7060, 13.1940];

    /// A file removed when dropped.
    pub struct TempFile(PathBuf);

    impl TempFile {
        pub fn new(name: &str) -> Self {
            Self(std::env::temp_dir().join(format!("moto-rb-{name}-{}", std::process::id())))
        }

        pub fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TempFile {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    /// The fixture built into a region file.
    pub fn built_fixture(name: &str) -> TempFile {
        let file = TempFile::new(name);
        super::build(
            Path::new(FIXTURE),
            file.path(),
            &super::BuildOptions::bbox(FIXTURE_BBOX),
        )
        .unwrap();
        file
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use test_support::{FIXTURE, FIXTURE_BBOX, TempFile, built_fixture};

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

    #[test]
    fn parses_build_options() {
        let o = |a: &[&str]| build_options(&a.iter().map(|s| s.to_string()).collect::<Vec<_>>());
        let plain = |bbox, country| BuildOptions {
            bbox,
            country,
            poly: None,
        };
        assert_eq!(o(&[]), Ok(Some(plain(None, speed::Country::Sweden))));
        assert_eq!(
            o(&["--country", "NO", "--bbox", "57,4,72,32"]),
            Ok(Some(plain(
                Some([57.0, 4.0, 72.0, 32.0]),
                speed::Country::Norway
            )))
        );
        assert_eq!(
            o(&["--country", "fi", "--poly", "fi.poly"]),
            Ok(Some(BuildOptions {
                bbox: None,
                country: speed::Country::Finland,
                poly: Some("fi.poly".into()),
            }))
        );
        assert!(o(&["--country", "DE"]).is_err());
        assert!(o(&["--bbox", "1,2,3"]).is_err());
        // A lone flag, an unknown one or a repeated one is a usage error.
        assert_eq!(o(&["--country"]), Ok(None));
        assert_eq!(o(&["--shape", "x"]), Ok(None));
        assert_eq!(o(&["--country", "SE", "--country", "DK"]), Ok(None));
        assert_eq!(o(&["--poly", "a", "--poly", "b"]), Ok(None));
    }

    #[test]
    fn parses_points() {
        let p = parse_point(" 55.7, 13.19 ").unwrap();
        assert_eq!((p.lat, p.lon), (55.7, 13.19));
        assert!(parse_point("55.7").is_none());
        assert!(parse_point("91,0").is_none());
        assert!(parse_point("x,y").is_none());
    }

    #[test]
    fn builds_a_working_region_from_osm_data() {
        let file = built_fixture("e2e");
        moto_core::region::verify_file(file.path()).unwrap();
        let engine = Engine::open(file.path()).unwrap();
        let info = engine.region().info();
        assert_eq!(info.osm_timestamp, 1_790_108_579);
        assert!(
            info.source_name.starts_with("lund-centre.osm.pbf SE ["),
            "{}",
            info.source_name
        );
        assert_eq!(engine.region().country(), Some("SE"));
        assert!(engine.region().border_nodes().is_empty(), "no border given");
        assert!(info.builder_version.starts_with("moto-regionbuild "));
        assert_eq!(
            (engine.region().node_count(), engine.region().edge_count()),
            (10, 16)
        );

        // A residential street with sett paving in the fixture.
        let snapped = describe_snap(
            &engine,
            LatLon {
                lat: 55.7050,
                lon: 13.1920,
            },
        );
        assert!(snapped.contains("way 79664841"), "{snapped}");
        assert!(
            snapped.contains("Residential") && snapped.contains("Sett"),
            "{snapped}"
        );
        // The street's name comes from OSM (format 1.2).
        assert!(engine.region().has_names());
        assert!(!snapped.contains("name None"), "{snapped}");
        let outside = describe_snap(
            &engine,
            LatLon {
                lat: 55.8,
                lon: 13.3,
            },
        );
        assert!(outside.contains("outside the loaded region"), "{outside}");

        let route = engine
            .route(
                LatLon {
                    lat: 55.7043,
                    lon: 13.1905,
                },
                LatLon {
                    lat: 55.7057,
                    lon: 13.1935,
                },
                &moto_core::RouteOptions::default(),
            )
            .unwrap();
        assert!(
            route.distance_m > 150.0 && route.distance_m < 1_000.0,
            "{route:?}"
        );
    }

    #[test]
    fn section_way_spans_point_at_the_right_osm_nodes() {
        // A section across the fixture: every span's end nodes, looked up in
        // the OSM data, must lie on the section's geometry.
        let file = built_fixture("spans");
        let engine = Engine::open(file.path()).unwrap();
        let draft = engine
            .section_between(
                LatLon {
                    lat: 55.7043,
                    lon: 13.1905,
                },
                LatLon {
                    lat: 55.7057,
                    lon: 13.1935,
                },
            )
            .unwrap();
        assert!(!draft.ways.is_empty());
        let b = FIXTURE_BBOX;
        let osm = pbf::read(
            Path::new(FIXTURE),
            &graph::bbox_e7(b[0], b[1], b[2], b[3]),
            speed::Country::Sweden,
        )
        .unwrap();
        let pos: std::collections::HashMap<i64, LatLon> = osm
            .nodes
            .iter()
            .map(|&(id, p)| {
                (
                    id,
                    LatLon {
                        lat: f64::from(p.lat) / 1e7,
                        lon: f64::from(p.lon) / 1e7,
                    },
                )
            })
            .collect();
        for span in &draft.ways {
            let way = osm.ways.iter().find(|w| w.id == span.way_id).unwrap();
            for idx in [span.from_idx, span.to_idx] {
                let node = pos[&way.refs[idx as usize]];
                let near = draft
                    .geometry
                    .iter()
                    .map(|&g| moto_core::geo::haversine_m(g, node))
                    .fold(f64::INFINITY, f64::min);
                // Span ends are rounded outwards to whole nodes, so they can
                // lie just past the section's ends.
                assert!(
                    near < 60.0,
                    "way {} node {idx} is {near:.1} m off",
                    span.way_id
                );
            }
        }
        // Nodes strictly inside a span are on the section itself.
        let mut inner = 0;
        for span in &draft.ways {
            let way = osm.ways.iter().find(|w| w.id == span.way_id).unwrap();
            let (lo, hi) = (
                span.from_idx.min(span.to_idx),
                span.from_idx.max(span.to_idx),
            );
            for idx in lo + 1..hi {
                let node = pos[&way.refs[idx as usize]];
                let near = draft
                    .geometry
                    .iter()
                    .map(|&g| moto_core::geo::haversine_m(g, node))
                    .fold(f64::INFINITY, f64::min);
                assert!(
                    near < 1.0,
                    "way {} inner node {idx} is {near:.2} m off",
                    span.way_id
                );
                inner += 1;
            }
        }
        assert!(
            inner > 5,
            "the test should cover real shape nodes, got {inner}"
        );
        // Consecutive spans of one way were merged.
        assert!(
            draft
                .ways
                .windows(2)
                .all(|w| w[0].way_id != w[1].way_id || w[0].to_idx != w[1].from_idx)
        );
    }

    #[test]
    fn cuts_ways_at_the_bounding_box() {
        let (whole, whole_stats) =
            build_region(Path::new(FIXTURE), &BuildOptions::bbox(FIXTURE_BBOX)).unwrap();
        let (half, half_stats) = build_region(
            Path::new(FIXTURE),
            &BuildOptions::bbox([55.7040, 13.1900, 55.7050, 13.1940]),
        )
        .unwrap();
        assert!(half_stats.edges < whole_stats.edges);
        let north = |d: &RegionData| d.shape_points.iter().map(|p| p.lat).max().unwrap();
        assert!(north(&half) <= 557_050_000 && north(&whole) > 557_050_000);
    }

    #[test]
    fn build_errors_are_reported() {
        let out = TempFile::new("never");
        let err = build(
            Path::new("/definitely/not/here.osm.pbf"),
            out.path(),
            &BuildOptions::bbox(FIXTURE_BBOX),
        )
        .unwrap_err();
        assert!(err.contains("input not found"), "{err}");
        let err = build(
            Path::new(FIXTURE),
            Path::new("/definitely/not/a/dir/x.region"),
            &BuildOptions::bbox(FIXTURE_BBOX),
        )
        .unwrap_err();
        assert!(err.contains("x.region"), "{err}");
    }

    #[test]
    fn check_writes_json_and_rejects_bad_points() {
        let file = built_fixture("check");
        let json = TempFile::new("check.json");
        check(file.path(), Some(json.path()), &["55.705,13.192".into()]).unwrap();
        let text = std::fs::read_to_string(json.path()).unwrap();
        assert!(text.contains("\"route_ms_p95\""), "{text}");
        assert!(check(file.path(), None, &["nonsense".into()]).is_err());
        assert!(check(Path::new("/definitely/not/here.region"), None, &[]).is_err());
    }

    #[test]
    fn reports_peak_memory_on_linux() {
        if cfg!(target_os = "linux") {
            assert!(peak_memory().unwrap().ends_with("MiB"));
        }
    }
}
