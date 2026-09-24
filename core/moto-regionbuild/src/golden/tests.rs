// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::test_support::{TempFile, built_fixture};

/// A route across the Lund fixture, with `extra` spliced into the JSON.
fn case(extra: &str, expect: &str) -> String {
    format!(
        r#"{{"name":"across Lund","from":[55.7043,13.1905],"to":[55.7057,13.1935]{extra},
            "expect":{{{expect}}}}}"#
    )
}

#[test]
fn a_plain_route_meets_its_expectations() {
    let file = built_fixture("golden-plain");
    let engine = Engine::open(file.path()).unwrap();
    let c = Case::parse(&case("", r#""max_favourite_share":0.0"#)).unwrap();
    let o = c.run(&engine);
    assert!(o.failures.is_empty(), "{o:?}");
    assert!(o.distance_km > 0.15 && o.distance_km < 1.0, "{o:?}");
    assert!((o.detour_ratio - 1.0).abs() < 1e-9);
    assert_eq!(o.favourite_share, 0.0);
    assert!(o.fastest_min > 0.0 && o.duration_min > 0.0);
}

#[test]
fn favourites_count_and_broken_expectations_are_reported() {
    let file = built_fixture("golden-fav");
    let engine = Engine::open(file.path()).unwrap();
    let fav =
        r#","favourites":[{"from":[55.7043,13.1905],"to":[55.7057,13.1935],"rating":"epic"}]"#;
    let c = Case::parse(&case(fav, r#""min_favourite_share":0.9"#)).unwrap();
    let o = c.run(&engine);
    assert!(o.failures.is_empty(), "{o:?}");
    assert!(o.favourite_share > 0.9, "{o:?}");

    // The same route can't also stay off favourites, or pass a point far
    // away, or avoid its own start.
    let start = engine
        .snap(LatLon {
            lat: 55.7043,
            lon: 13.1905,
        })
        .unwrap()
        .position;
    let c = Case::parse(&case(
        fav,
        &format!(
            r#""max_favourite_share":0.5,"pass":[[55.7059,13.1901]],"avoid":[[{},{}]]"#,
            start.lat, start.lon
        ),
    ))
    .unwrap();
    let o = c.run(&engine);
    assert_eq!(o.failures.len(), 3, "{o:?}");
    assert!(o.failures[0].contains("at most 50 %"), "{o:?}");
    assert!(o.failures[1].starts_with("misses"), "{o:?}");
    assert!(o.failures[2].starts_with("passes"), "{o:?}");
    let lines = table(&[o]);
    assert!(lines[1].ends_with("FAILED"), "{lines:?}");
    assert_eq!(lines.len(), 5);
}

#[test]
fn routing_errors_are_failures_not_panics() {
    let file = built_fixture("golden-err");
    let engine = Engine::open(file.path()).unwrap();
    // The end lies far outside the fixture.
    let text = r#"{"name":"off the map","from":[55.7043,13.1905],"to":[56.5,14.0],"expect":{}}"#;
    let o = Case::parse(text).unwrap().run(&engine);
    assert_eq!(o.failures.len(), 1, "{o:?}");
    // A favourite that can't be marked.
    let fav = r#","favourites":[{"from":[56.5,14.0],"to":[56.6,14.0],"rating":"good"}]"#;
    let o = Case::parse(&case(fav, "")).unwrap().run(&engine);
    assert!(o.failures[0].starts_with("favourite 1:"), "{o:?}");
}

#[test]
fn bad_case_files_are_refused() {
    for bad in [
        "",
        "{}",
        "[1,2]",
        &case(r#","speed":3"#, ""),                // unknown field
        &case("", r#""min_favourite_share":1.5"#), // not a share
        &case("", r#""max_detour_ratio":0.5"#),    // below 1
        &case(r#","max_detour":-1"#, ""),          // negative budget
        &case(r#","max_detour":0.2,"max_minutes":30"#, ""), // two budgets
        &case(r#","min_gain":-1"#, ""),            // negative guard
        &case("", r#""pass":[[91.0,13.0]]"#),      // not a coordinate
        &case(
            r#","favourites":[{"from":[55.7,13.19],"to":[55.7,13.19],"rating":"superb"}]"#,
            "",
        ),
        r#"{"name":" ","from":[55.7,13.19],"to":[55.7,13.19],"expect":{}}"#,
    ] {
        assert!(Case::parse(bad).is_err(), "{bad}");
    }
}

#[test]
fn loads_every_case_in_a_directory() {
    let dir = std::env::temp_dir().join(format!("moto-golden-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("b.json"), case("", "")).unwrap();
    std::fs::write(
        dir.join("a.json"),
        case("", "").replace("across Lund", "first"),
    )
    .unwrap();
    std::fs::write(dir.join("notes.txt"), "not a case").unwrap();
    let cases = load(&dir).unwrap();
    assert_eq!(
        cases.iter().map(|c| c.name.as_str()).collect::<Vec<_>>(),
        ["first", "across Lund"]
    );

    let region = built_fixture("golden-dir");
    let json = TempFile::new("golden.json");
    run(region.path(), &dir, Some(json.path())).unwrap();
    let written: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(json.path()).unwrap()).unwrap();
    assert_eq!(written.as_array().unwrap().len(), 2);
    assert!(written[0]["detour_ratio"].is_number());

    // A failing case fails the run; a broken file fails the load.
    std::fs::write(dir.join("c.json"), case("", r#""min_favourite_share":0.5"#)).unwrap();
    assert!(
        run(region.path(), &dir, None)
            .unwrap_err()
            .contains("1 of 3")
    );
    std::fs::write(dir.join("d.json"), "{").unwrap();
    assert!(load(&dir).unwrap_err().contains("d.json"));
    std::fs::remove_dir_all(&dir).unwrap();
    assert!(load(&dir).is_err());
}

#[test]
fn the_committed_cases_are_valid() {
    let dir = Path::new(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../moto-core/tests/golden"
    ));
    let cases = load(dir).unwrap();
    assert!(cases.len() >= 12, "{}", cases.len());
    let mut names: Vec<&str> = cases.iter().map(|c| c.name.as_str()).collect();
    names.sort_unstable();
    names.dedup();
    assert_eq!(names.len(), cases.len(), "case names are unique");
    assert!(cases.iter().all(|c| !c.description.is_empty()));
}

#[test]
fn gravel_can_be_allowed() {
    let c = Case::parse(&case(r#","allow_unpaved":true"#, "")).unwrap();
    assert!(!c.options().avoid.unpaved);
    let c = Case::parse(&case("", "")).unwrap();
    assert!(c.options().avoid.unpaved);
    assert!(Case::parse(&case(r#","allow_unpaved":"yes""#, "")).is_err());
}

#[test]
fn a_total_time_budget_caps_the_minutes() {
    let file = built_fixture("golden-total");
    let engine = Engine::open(file.path()).unwrap();
    let c = Case::parse(&case(r#","max_minutes":10,"min_gain":0"#, "")).unwrap();
    let o = c.run(&engine);
    assert!(o.failures.is_empty(), "{o:?}");
    // A total below the fastest route gives the fastest route, which is
    // not held against it.
    let c = Case::parse(&case(r#","max_minutes":0.01"#, "")).unwrap();
    assert!(c.run(&engine).failures.is_empty());
}
