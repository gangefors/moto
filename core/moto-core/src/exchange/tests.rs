// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture;
use crate::region::Region;

const T0: i64 = 1_790_000_000;

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn engine() -> Engine {
    Engine::from_region(Region::from_bytes(&fixture::region().to_bytes().unwrap()).unwrap())
}

/// A section along lat 55.70 from `from_lon` to `to_lon` (A–B–D in the
/// fixture), as proposed from the map.
fn along(
    e: &Engine,
    from_lon: f64,
    to_lon: f64,
    rating: Rating,
    direction: Direction,
) -> NewSection {
    let d = e
        .section_between(ll(55.7001, from_lon), ll(55.7001, to_lon))
        .unwrap();
    NewSection {
        rider_id: LOCAL_RIDER.into(),
        name: format!("{from_lon}-{to_lon}"),
        rating,
        direction,
        source: Source::Map,
        ways: d.ways,
        geometry: d.geometry,
    }
}

fn store_with(sections: &[NewSection]) -> Store {
    let mut store = Store::open_in_memory().unwrap();
    for s in sections {
        store.add_section(s, T0).unwrap();
    }
    store
}

fn names(store: &Store) -> Vec<String> {
    let mut v: Vec<String> = store
        .list_sections(None)
        .unwrap()
        .into_iter()
        .map(|s| s.name)
        .collect();
    v.sort();
    v
}

#[test]
fn round_trips_through_every_format() {
    let e = engine();
    let source = store_with(&[
        along(&e, 13.202, 13.208, Rating::Epic, Direction::Forward),
        along(&e, 13.211, 13.219, Rating::Great, Direction::Both),
    ]);
    for format in [
        ExportFormat::GeoJson,
        ExportFormat::Gzip,
        ExportFormat::Zip,
        ExportFormat::TarGz,
    ] {
        let bytes = export_sections(&source, format).unwrap();
        let mut target = Store::open_in_memory().unwrap();
        let r = import_sections(&mut target, Some(&e), &bytes, T0 + 60).unwrap();
        assert_eq!(
            r,
            ImportReport {
                added: 2,
                skipped: 0,
                replaced: 0,
                unmatched: 0
            },
            "{format:?}"
        );
        let got = target.list_sections(None).unwrap();
        let want = source.list_sections(None).unwrap();
        for (g, w) in got.iter().zip(&want) {
            assert_eq!(
                (g.rating, g.direction, g.source),
                (w.rating, w.direction, Source::Import)
            );
            assert_eq!(g.status, Status::Ok);
            assert_eq!(g.ways, w.ways);
            assert_eq!(g.name, w.name);
        }
    }
}

#[test]
fn the_export_is_plain_geojson() {
    let e = engine();
    let store = store_with(&[along(&e, 13.202, 13.208, Rating::Epic, Direction::Forward)]);
    let bytes = export_sections(&store, ExportFormat::GeoJson).unwrap();
    let v: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(v["type"], "FeatureCollection");
    assert_eq!(v["moto"]["format"], 1);
    let f = &v["features"][0];
    assert_eq!(f["geometry"]["type"], "LineString");
    // Longitude first.
    assert!((f["geometry"]["coordinates"][0][0].as_f64().unwrap() - 13.202).abs() < 1e-6);
    assert_eq!(f["properties"]["rating"], "epic");
    assert_eq!(f["properties"]["direction"], "forward");
    assert_eq!(f["properties"]["ways"][0], serde_json::json!([100, 0, 1]));
}

#[test]
fn importing_the_same_file_twice_adds_nothing() {
    let e = engine();
    let source = store_with(&[along(&e, 13.202, 13.219, Rating::Good, Direction::Both)]);
    let bytes = export_sections(&source, ExportFormat::Zip).unwrap();
    let mut target = Store::open_in_memory().unwrap();
    import_sections(&mut target, Some(&e), &bytes, T0).unwrap();
    let again = import_sections(&mut target, Some(&e), &bytes, T0).unwrap();
    assert_eq!(
        again,
        ImportReport {
            added: 0,
            skipped: 1,
            replaced: 0,
            unmatched: 0
        }
    );
    assert_eq!(target.list_sections(None).unwrap().len(), 1);
}

#[test]
fn a_shorter_imported_section_inside_a_saved_one_is_skipped() {
    let e = engine();
    let mut store = store_with(&[along(&e, 13.201, 13.219, Rating::Good, Direction::Both)]);
    let file = store_with(&[along(&e, 13.203, 13.207, Rating::Epic, Direction::Both)]);
    let bytes = export_sections(&file, ExportFormat::GeoJson).unwrap();
    let r = import_sections(&mut store, Some(&e), &bytes, T0).unwrap();
    assert_eq!((r.added, r.skipped, r.replaced), (0, 1, 0));
    assert_eq!(names(&store), ["13.201-13.219"]);
}

#[test]
fn a_longer_imported_section_replaces_the_shorter_saved_one() {
    let e = engine();
    let mut store = store_with(&[
        along(&e, 13.203, 13.207, Rating::Epic, Direction::Both),
        along(&e, 13.212, 13.218, Rating::Great, Direction::Both),
    ]);
    let file = store_with(&[along(&e, 13.201, 13.209, Rating::Good, Direction::Both)]);
    let bytes = export_sections(&file, ExportFormat::GeoJson).unwrap();
    let r = import_sections(&mut store, Some(&e), &bytes, T0).unwrap();
    assert_eq!((r.added, r.skipped, r.replaced), (1, 0, 1));
    // The one on B→D is untouched: the import doesn't reach it.
    assert_eq!(names(&store), ["13.201-13.209", "13.212-13.218"]);
}

#[test]
fn partial_overlaps_keep_both() {
    let e = engine();
    let mut store = store_with(&[along(&e, 13.201, 13.207, Rating::Good, Direction::Both)]);
    let file = store_with(&[along(&e, 13.204, 13.215, Rating::Good, Direction::Both)]);
    let bytes = export_sections(&file, ExportFormat::GeoJson).unwrap();
    let r = import_sections(&mut store, Some(&e), &bytes, T0).unwrap();
    assert_eq!((r.added, r.skipped, r.replaced), (1, 0, 0));
    assert_eq!(store.list_sections(None).unwrap().len(), 2);
}

#[test]
fn one_way_sections_only_cover_the_same_way() {
    let e = engine();
    // Saved: one-way westwards on A–B. Imported: the same road both ways,
    // and a shorter piece one-way eastwards: neither is covered by it.
    let mut west = along(&e, 13.209, 13.201, Rating::Good, Direction::Forward);
    west.name = "west".into();
    let mut store = store_with(&[west]);
    let file = store_with(&[
        along(&e, 13.203, 13.207, Rating::Good, Direction::Forward),
        along(&e, 13.2035, 13.2065, Rating::Good, Direction::Forward),
    ]);
    let bytes = export_sections(&file, ExportFormat::GeoJson).unwrap();
    let r = import_sections(&mut store, Some(&e), &bytes, T0).unwrap();
    // The east-bound piece isn't covered by the west-bound section; the
    // shorter east-bound piece is covered by the longer one in the file.
    assert_eq!((r.added, r.skipped, r.replaced), (1, 1, 0), "{r:?}");
    // A west-bound import inside the saved one is covered.
    let file = store_with(&[along(&e, 13.207, 13.203, Rating::Good, Direction::Forward)]);
    let bytes = export_sections(&file, ExportFormat::GeoJson).unwrap();
    let r = import_sections(&mut store, Some(&e), &bytes, T0).unwrap();
    assert_eq!((r.added, r.skipped), (0, 1));
}

#[test]
fn sections_off_the_map_are_imported_as_unmatched() {
    // A friend's section 500 km away.
    let json = br#"{"type":"FeatureCollection","features":[{"type":"Feature",
        "geometry":{"type":"LineString","coordinates":[[18.0,59.3],[18.01,59.31]]},
        "properties":{"rating":"great"}}]}"#;
    let mut store = Store::open_in_memory().unwrap();
    let r = import_sections(&mut store, Some(&engine()), json, T0).unwrap();
    assert_eq!((r.added, r.unmatched), (1, 1));
    let s = &store.list_sections(None).unwrap()[0];
    assert_eq!(
        (s.status, s.rating, s.direction),
        (Status::Unmatched, Rating::Great, Direction::Both)
    );
}

#[test]
fn without_a_region_imports_wait_for_one() {
    let e = engine();
    let file = store_with(&[along(&e, 13.202, 13.208, Rating::Good, Direction::Both)]);
    let bytes = export_sections(&file, ExportFormat::GeoJson).unwrap();
    let mut store = Store::open_in_memory().unwrap();
    let r = import_sections(&mut store, None, &bytes, T0).unwrap();
    assert_eq!((r.added, r.unmatched), (1, 0));
    assert_eq!(
        store.list_sections(None).unwrap()[0].status,
        Status::NeedsRematch
    );
    // Once the region is open, the re-match fits it.
    rematch_store(&mut store, &e).unwrap();
    assert_eq!(store.list_sections(None).unwrap()[0].status, Status::Ok);
}

#[test]
fn other_tools_geojson_is_accepted() {
    // Minimal: no properties, altitude in positions, extra fields.
    let json = "\u{feff}{\"type\":\"FeatureCollection\",\"name\":\"x\",\"features\":[{\"type\":\"Feature\",\"id\":7,\
        \"geometry\":{\"type\":\"LineString\",\"coordinates\":[[13.202,55.70,12.5],[13.208,55.70,13.0]]}}]}";
    let s = from_geojson(json.as_bytes()).unwrap();
    assert_eq!(s.len(), 1);
    assert_eq!(
        (s[0].rating, s[0].direction, s[0].source),
        (Rating::Good, Direction::Both, Source::Import)
    );
    assert!(s[0].ways.is_empty());
}

#[test]
fn invalid_files_are_rejected_whole() {
    let feature = |geometry: &str, props: &str| {
        format!(
            r#"{{"type":"FeatureCollection","features":[{{"type":"Feature","geometry":{geometry},"properties":{props}}}]}}"#
        )
    };
    let line = r#"{"type":"LineString","coordinates":[[13.2,55.7],[13.21,55.7]]}"#;
    let bad = [
        "".to_string(),
        "not json".into(),
        "[]".into(),
        r#"{"type":"Feature","features":[]}"#.into(),
        r#"{"type":"FeatureCollection"}"#.into(),
        feature(r#"{"type":"Point","coordinates":[13.2,55.7]}"#, "{}"),
        feature(r#"{"type":"LineString","coordinates":[[13.2,55.7]]}"#, "{}"),
        feature(
            r#"{"type":"LineString","coordinates":[[13.2,95.0],[13.21,55.7]]}"#,
            "{}",
        ),
        feature(
            r#"{"type":"LineString","coordinates":[[13.2],[13.21,55.7]]}"#,
            "{}",
        ),
        feature(
            r#"{"type":"LineString","coordinates":[["13.2",55.7],[13.21,55.7]]}"#,
            "{}",
        ),
        feature(line, r#"{"rating":"awesome"}"#),
        feature(line, r#"{"direction":"backward"}"#),
        feature(line, r#"{"ways":[[1,-1,2]]}"#),
        feature(line, r#"{"ways":[[0,1,2]]}"#),
        feature(line, r#"{"rating":5}"#),
    ];
    for b in &bad {
        assert!(
            matches!(
                from_geojson(b.as_bytes()),
                Err(CoreError::InvalidArgument(_))
            ),
            "{b}"
        );
    }
    // A bad file changes nothing in the store.
    let e = engine();
    let mut store = store_with(&[along(&e, 13.202, 13.208, Rating::Good, Direction::Both)]);
    assert!(import_sections(&mut store, Some(&e), bad[7].as_bytes(), T0).is_err());
    assert_eq!(store.list_sections(None).unwrap().len(), 1);
}

#[test]
fn names_are_cleaned_and_capped() {
    let long = "é".repeat(MAX_NAME_CHARS + 50);
    let json = format!(
        r#"{{"type":"FeatureCollection","features":[{{"type":"Feature","geometry":{{"type":"LineString","coordinates":[[13.2,55.7],[13.21,55.7]]}},"properties":{{"name":"a\u0000b{long}"}}}}]}}"#
    );
    let s = from_geojson(json.as_bytes()).unwrap();
    assert!(s[0].name.starts_with("abé"));
    assert_eq!(s[0].name.chars().count(), MAX_NAME_CHARS);
}

#[test]
fn too_many_sections_are_refused() {
    let one = r#"{"type":"Feature","geometry":{"type":"LineString","coordinates":[[13.2,55.7],[13.21,55.7]]}}"#;
    let json = format!(
        r#"{{"type":"FeatureCollection","features":[{}]}}"#,
        vec![one; MAX_IMPORT_SECTIONS + 1].join(",")
    );
    assert!(matches!(
        from_geojson(json.as_bytes()),
        Err(CoreError::InvalidArgument(_))
    ));
}

#[test]
fn deeply_nested_json_is_an_error_not_a_crash() {
    let deep = format!("{}{}", "[".repeat(100_000), "]".repeat(100_000));
    assert!(from_geojson(deep.as_bytes()).is_err());
}

#[test]
fn random_json_never_panics() {
    let e = engine();
    let base = export_sections(
        &store_with(&[along(&e, 13.202, 13.208, Rating::Good, Direction::Both)]),
        ExportFormat::GeoJson,
    )
    .unwrap();
    let mut x: u64 = 0x9e37_79b9_7f4a_7c15;
    for _ in 0..500 {
        let mut b = base.clone();
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        for k in 0..(x % 4 + 1) {
            let i = ((x >> (k * 9)) as usize) % b.len();
            b[i] = b"{}[],:\"0-.e9 abcnul"[((x >> (k * 5)) % 19) as usize];
        }
        let _ = from_geojson(&b);
    }
}
