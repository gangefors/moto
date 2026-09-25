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
fn curvature_is_on_unless_turned_off() {
    assert!(Case::parse(&case("", "")).unwrap().options().curvy);
    assert!(
        !Case::parse(&case(r#","curvy":false"#, ""))
            .unwrap()
            .options()
            .curvy
    );
    assert!(Case::parse(&case("", r#""min_curvy_share":2"#)).is_err());
    // The fixture route is not curvy: a demand for curvy road fails.
    let file = built_fixture("golden-curvy");
    let engine = Engine::open(file.path()).unwrap();
    let o = Case::parse(&case("", r#""min_curvy_share":0.99"#))
        .unwrap()
        .run(&engine);
    assert!(o.failures[0].contains("curvy"), "{o:?}");
}

#[test]
fn gravel_can_be_allowed_or_preferred() {
    let c = Case::parse(&case(r#","gravel":"allow""#, "")).unwrap();
    assert_eq!(c.options().gravel, Gravel::Allow);
    let c = Case::parse(&case(r#","gravel":"prefer""#, "")).unwrap();
    assert_eq!(c.options().gravel, Gravel::Prefer);
    let c = Case::parse(&case("", "")).unwrap();
    assert_eq!(c.options().gravel, Gravel::Avoid);
    assert!(Case::parse(&case(r#","gravel":"yes""#, "")).is_err());
    assert!(Case::parse(&case(r#","allow_unpaved":true"#, "")).is_err());
}

#[test]
fn a_demand_for_gravel_is_checked() {
    // The fixture route is paved.
    let file = built_fixture("golden-gravel");
    let engine = Engine::open(file.path()).unwrap();
    let o = Case::parse(&case("", r#""min_unpaved_km":0.0"#))
        .unwrap()
        .run(&engine);
    assert!(o.failures.is_empty(), "{o:?}");
    let o = Case::parse(&case("", r#""min_unpaved_km":1.0"#))
        .unwrap()
        .run(&engine);
    assert!(o.failures[0].contains("gravel"), "{o:?}");
    assert!(Case::parse(&case("", r#""min_unpaved_km":-1"#)).is_err());
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

/// A round trip from the middle of a 13 × 13 km grid (`fixture::grid`).
fn loop_case(target: &str, expect: &str) -> String {
    format!(
        r#"{{"name":"grid loop","from":[55.754,13.496],"loop":{{{target}}},
            "expect":{{{expect}}}}}"#
    )
}

fn grid_engine(name: &str) -> (TempFile, Engine) {
    let file = TempFile::new(name);
    let bytes = moto_core::fixture::grid(13).to_bytes().unwrap();
    std::fs::write(file.path(), bytes).unwrap();
    let engine = Engine::open(file.path()).unwrap();
    (file, engine)
}

#[test]
fn round_trips_are_checked_loop_by_loop() {
    let (_f, engine) = grid_engine("golden-loop");
    let o = Case::parse(&loop_case(r#""km":20"#, ""))
        .unwrap()
        .run(&engine);
    assert!(o.failures.is_empty(), "{o:?}");
    assert!(o.loops.unwrap() >= 2, "{o:?}");
    assert!((o.distance_km - 20.0).abs() <= 3.0, "{o:?}");
    assert!(o.reuse_share.unwrap() <= MAX_REUSE, "{o:?}");
    assert_eq!(o.detour_ratio, 1.0);
    // Time targets too.
    let o = Case::parse(&loop_case(r#""minutes":20"#, ""))
        .unwrap()
        .run(&engine);
    assert!(o.failures.is_empty(), "{o:?}");
    // More loops than there are, or a point far away, fail.
    let o = Case::parse(&loop_case(
        r#""km":20"#,
        r#""min_loops":3,"pass":[[55.70,13.40]]"#,
    ))
    .unwrap()
    .run(&engine);
    assert!(o.failures.iter().any(|f| f.starts_with("misses")), "{o:?}");
    // No loop at all is a failure, not a panic.
    let o = Case::parse(&loop_case(r#""km":300"#, ""))
        .unwrap()
        .run(&engine);
    assert_eq!(o.loops, None);
    assert_eq!(o.failures.len(), 1, "{o:?}");
    // Loop figures are written only for round trips.
    let json = serde_json::to_string(&o).unwrap();
    assert!(json.contains("\"loops\"") == o.loops.is_some());
}

#[test]
fn bad_round_trip_cases_are_refused() {
    for bad in [
        loop_case("", ""),                                    // no target
        loop_case(r#""km":20,"minutes":30"#, ""),             // two targets
        loop_case(r#""km":-5"#, ""),                          // negative
        loop_case(r#""km":20,"miles":3"#, ""),                // unknown field
        loop_case(r#""km":20"#, r#""min_loops":0"#),          // too few
        loop_case(r#""km":20"#, r#""min_loops":4"#),          // more than returned
        loop_case(r#""km":20"#, r#""max_detour_ratio":1.2"#), // no detour on loops
        loop_case(r#""km":20},"max_detour":0.3,"x":{"#, "").replace(",\"x\":{}", ""),
        loop_case(r#""km":20},"to":[55.75,13.5],"x":{"#, "").replace(",\"x\":{}", ""),
        case("", r#""min_loops":2"#), // min_loops on a one-way route
    ] {
        assert!(Case::parse(&bad).is_err(), "{bad}");
    }
    // Neither an end nor a loop.
    assert!(Case::parse(r#"{"name":"x","from":[55.7,13.19],"expect":{}}"#).is_err());
}

#[test]
fn reuse_is_measured_from_the_line() {
    let p = |lat: f64, lon: f64| LatLon { lat, lon };
    // A 4 km square: no reuse.
    let square = [
        p(55.7, 13.4),
        p(55.718, 13.4),
        p(55.718, 13.432),
        p(55.7, 13.432),
        p(55.7, 13.4),
    ];
    assert!(
        reuse_share(&square, 0.0) < 0.01,
        "{}",
        reuse_share(&square, 0.0)
    );
    // 1 km out and back along the first side before the square: 2 km of
    // 6 km, half of it ridden twice (1 km, 17 %).
    let mut spur = vec![p(55.7, 13.4), p(55.709, 13.4), p(55.7, 13.4)];
    spur.extend_from_slice(&square[1..]);
    let r = reuse_share(&spur, 0.0);
    assert!((r - 1.0 / 6.0).abs() < 0.03, "{r}");
    // The same spur inside the home zone is free.
    assert!(reuse_share(&spur, 1_100.0) < 0.01);
    // Crossing its own line costs next to nothing.
    let figure_eight = [
        p(55.7, 13.4),
        p(55.718, 13.432),
        p(55.718, 13.4),
        p(55.7, 13.432),
        p(55.7, 13.4),
    ];
    assert!(reuse_share(&figure_eight, 0.0) < 0.01);
    // Degenerate lines.
    assert_eq!(reuse_share(&[], 0.0), 0.0);
    assert_eq!(reuse_share(&[p(55.7, 13.4); 3], 0.0), 0.0);
}
