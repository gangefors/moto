// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture;
use crate::region::Region;
use crate::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};

fn engine(data: crate::region::RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

/// The middle of a 13 × 13 km grid.
const CENTRE: LatLon = LatLon {
    lat: 55.754,
    lon: 13.496,
};

fn km(m: f64) -> RoundTripTarget {
    RoundTripTarget::DistanceM(m * 1000.0)
}

#[test]
fn loops_come_back_at_about_the_target_length() {
    let e = engine(fixture::grid(13));
    let opts = RouteOptions::default();
    let loops = round_trip(&e, CENTRE, km(20.0), &opts, &Favourites::none()).unwrap();
    assert!(
        loops.len() >= 2 && loops.len() <= MAX_LOOPS,
        "{}",
        loops.len()
    );
    let start = e.snap(CENTRE).unwrap().position;
    for l in &loops {
        assert!(
            (l.distance_m - 20_000.0).abs() <= 20_000.0 * TOLERANCE,
            "{}",
            l.distance_m
        );
        let (a, z) = (l.geometry[0], *l.geometry.last().unwrap());
        assert!(haversine_m(a, start) < 1.0 && haversine_m(z, start) < 1.0);
        assert_eq!(l.fastest_duration_s, l.duration_s);
        // The loop really goes somewhere: its far point is kilometres out.
        let far = l
            .geometry
            .iter()
            .map(|p| haversine_m(*p, start))
            .fold(0.0, f64::max);
        assert!(far > 2_500.0, "{far}");
    }
    // Alternatives differ: they share less than half their roads.
    let roads = |r: &Route| -> Vec<(i64, i64)> {
        r.geometry
            .windows(2)
            .map(|w| {
                (
                    (w[0].lat * 1e4 + w[1].lat * 1e4) as i64,
                    (w[0].lon * 1e4 + w[1].lon * 1e4) as i64,
                )
            })
            .collect()
    };
    let (a, b) = (roads(&loops[0]), roads(&loops[1]));
    let shared = a.iter().filter(|s| b.contains(s)).count();
    assert!(
        (shared as f64) < 0.5 * a.len().min(b.len()) as f64,
        "{shared} of {}",
        a.len()
    );
    // The same request gives the same loops.
    assert_eq!(
        round_trip(&e, CENTRE, km(20.0), &opts, &Favourites::none()).unwrap(),
        loops
    );
}

#[test]
fn loops_do_not_ride_back_the_way_they_came() {
    let e = engine(fixture::grid(13));
    for target in [10.0, 20.0, 25.0] {
        let loops = round_trip(
            &e,
            CENTRE,
            km(target),
            &RouteOptions::default(),
            &Favourites::none(),
        )
        .unwrap();
        for l in &loops {
            // Each 1 km grid road ridden at most once, give or take the
            // stretch the loop starts and ends on.
            let mut seen: Vec<(i64, i64)> = Vec::new();
            let mut twice = 0.0;
            for w in l.geometry.windows(2) {
                let mid = ((w[0].lat + w[1].lat) * 5e3).round() as i64;
                let key = (mid, ((w[0].lon + w[1].lon) * 5e3).round() as i64);
                if seen.contains(&key) {
                    twice += haversine_m(w[0], w[1]);
                }
                seen.push(key);
            }
            assert!(
                twice <= l.distance_m * MAX_REUSE + 1_100.0,
                "{target} km: {twice} m twice"
            );
        }
    }
}

#[test]
fn duration_targets_are_met_in_time() {
    let e = engine(fixture::grid(13));
    let loops = round_trip(
        &e,
        CENTRE,
        RoundTripTarget::DurationS(20.0 * 60.0),
        &RouteOptions::default(),
        &Favourites::none(),
    )
    .unwrap();
    for l in &loops {
        assert!(
            (l.duration_s - 1200.0).abs() <= 1200.0 * TOLERANCE,
            "{}",
            l.duration_s
        );
    }
}

#[test]
fn favourites_near_a_waypoint_are_ridden() {
    // An epic section on the grid road 5 km north-east of the centre.
    let e = engine(fixture::grid(13));
    let d = e
        .section_between(ll(55.790, 13.536), ll(55.790, 13.568))
        .unwrap();
    let s = Section {
        id: 1,
        rider_id: LOCAL_RIDER.into(),
        name: String::new(),
        rating: Rating::Epic,
        direction: Direction::Both,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: d.ways,
        geometry: d.geometry,
    };
    let fav = Favourites::build(&e, &[s]);
    let loops = round_trip(&e, CENTRE, km(20.0), &RouteOptions::default(), &fav).unwrap();
    assert!(
        loops[0].favourite_share > 0.05,
        "{:?}",
        loops[0].favourite_share
    );
    assert!(!loops[0].favourite_parts.is_empty());
}

#[test]
fn bad_requests_are_typed_errors() {
    let e = engine(fixture::grid(13));
    let opts = RouteOptions::default();
    let none = Favourites::none();
    for bad in [
        km(1.0),
        km(1000.0),
        RoundTripTarget::DistanceM(f64::NAN),
        RoundTripTarget::DurationS(-5.0),
    ] {
        assert!(
            matches!(
                round_trip(&e, CENTRE, bad, &opts, &none),
                Err(CoreError::InvalidArgument(_))
            ),
            "{bad:?}"
        );
    }
    assert!(matches!(
        round_trip(&e, ll(57.0, 15.0), km(20.0), &opts, &none),
        Err(CoreError::OutsideRegion { .. })
    ));
    // One lonely road: no loop at all.
    let lone = engine(fixture::region());
    assert!(matches!(
        round_trip(&lone, ll(55.7001, 13.205), km(10.0), &opts, &none),
        Err(CoreError::NoRoute(_))
    ));
    // Favourites of another region are refused.
    let other = Favourites::build(&lone, &[]);
    assert!(round_trip(&e, CENTRE, km(20.0), &opts, &other).is_err());
}

#[test]
fn waypoints_off_the_roads_are_pulled_in() {
    // On the grid's west edge half the headings point off the map; their
    // waypoints are pulled in, so westward loops still count, and a loop
    // too big for the grid is refused rather than stretched.
    let e = engine(fixture::grid(13));
    let edge = ll(55.754, 13.40);
    let loops = round_trip(
        &e,
        edge,
        km(20.0),
        &RouteOptions::default(),
        &Favourites::none(),
    )
    .unwrap();
    assert!(loops.len() >= 2, "{}", loops.len());
    for l in &loops {
        assert!(
            (l.distance_m - 20_000.0).abs() <= 20_000.0 * TOLERANCE,
            "{}",
            l.distance_m
        );
    }
    assert!(matches!(
        round_trip(
            &e,
            CENTRE,
            km(60.0),
            &RouteOptions::default(),
            &Favourites::none()
        ),
        Err(CoreError::NoRoute(_))
    ));
}

#[test]
fn a_start_in_a_corner_still_gets_loops() {
    // The south-west corner: most headings point off the grid.
    let e = engine(fixture::grid(13));
    let loops = round_trip(
        &e,
        ll(55.70, 13.40),
        km(15.0),
        &RouteOptions::default(),
        &Favourites::none(),
    )
    .unwrap();
    assert!(!loops.is_empty());
}

#[test]
fn the_street_home_may_be_ridden_out_and_back() {
    // Home at the end of a 1.8 km dead end: every loop rides it both
    // ways, 12 % of a 15 km loop, which only the home zone allows.
    let e = engine(fixture::grid_with_home(13));
    let home = fixture::grid_home(13);
    let street = ll(home.lat, home.lon + 0.012);
    let loops = round_trip(
        &e,
        home,
        km(15.0),
        &RouteOptions::default(),
        &Favourites::none(),
    )
    .unwrap();
    assert!(loops.len() >= 2, "{}", loops.len());
    for l in &loops {
        let (first, last) = (l.geometry[0], l.geometry[l.geometry.len() - 1]);
        assert!(haversine_m(first, home) < 1.0 && haversine_m(last, home) < 1.0);
        assert!(crate::geo::distance_to_line(street, &l.geometry) < 1.0);
    }
    assert_eq!(home_radius_m(15_000.0), 2_000.0);
    assert_eq!(home_radius_m(60_000.0), 3_000.0);
    assert_eq!(home_radius_m(400_000.0), 5_000.0);
}

#[test]
fn seed_zero_is_the_standard_candidates() {
    let c: Vec<Candidate> = Candidates::new(0, None).collect();
    assert_eq!(c.len(), HEADINGS);
    for (i, c) in c.iter().enumerate() {
        assert_eq!(
            *c,
            Candidate {
                bearing: i as f64 * 30.0,
                spread: SPREAD_DEG,
                size: 1.0
            }
        );
    }
}

#[test]
fn seeded_candidates_vary_within_bounds_and_repeat() {
    let a: Vec<Candidate> = Candidates::new(42, None).collect();
    assert_eq!(a, Candidates::new(42, None).collect::<Vec<_>>());
    assert_ne!(a, Candidates::new(43, None).collect::<Vec<_>>());
    assert_eq!(a.len(), HEADINGS);
    let turn = a[0].bearing;
    assert!((0.0..30.0).contains(&turn), "{turn}");
    for (i, c) in a.iter().enumerate() {
        assert!((c.bearing - (i as f64 * 30.0 + turn)).abs() < 1e-9);
        assert!((15.0..45.0).contains(&c.spread), "{c:?}");
        assert!((0.8..1.2).contains(&c.size), "{c:?}");
    }
    // The extremes of the generator stay in range.
    for seed in [1, u32::MAX] {
        assert!(Candidates::new(seed, None).all(|c| (15.0..45.0).contains(&c.spread)));
    }
}

#[test]
fn shuffled_loops_differ_and_still_fit() {
    let e = engine(fixture::grid(13));
    let opts = RouteOptions::default();
    let none = Favourites::none();
    let standard = round_trip(&e, CENTRE, km(20.0), &opts, &none).unwrap();
    let seeded = |seed| {
        loops(
            &e,
            CENTRE,
            km(20.0),
            &opts,
            &none,
            &LoopOptions {
                seed,
                bearing: None,
            },
        )
        .unwrap()
    };
    assert_eq!(seeded(0), standard);
    assert_eq!(seeded(9), seeded(9));
    let mut differing = 0;
    for seed in 1..=6 {
        let set = seeded(seed);
        assert!(!set.is_empty());
        for l in &set {
            assert!(
                (l.distance_m - 20_000.0).abs() <= 20_000.0 * TOLERANCE,
                "{}",
                l.distance_m
            );
            assert_eq!(l.geometry.first(), l.geometry.last());
        }
        if set != standard {
            differing += 1;
        }
    }
    assert!(differing >= 3, "{differing} of 6 seeds gave other loops");
}

#[test]
fn a_direction_fans_the_headings_around_it() {
    let c: Vec<Candidate> = Candidates::new(0, Some(90.0)).collect();
    assert_eq!(c.len(), HEADINGS);
    assert!((c[0].bearing - 30.0).abs() < 1e-9);
    assert!((c[HEADINGS - 1].bearing - 150.0).abs() < 1e-9);
    assert!(c.iter().all(|c| c.spread == SPREAD_DEG && c.size == 1.0));
    // Around north the bearings wrap into 0-360.
    let north: Vec<f64> = Candidates::new(0, Some(0.0)).map(|c| c.bearing).collect();
    assert!(north.iter().all(|b| (0.0..360.0).contains(b)));
    assert!(
        north
            .iter()
            .all(|&b| !(60.0 + 1e-9..300.0 - 1e-9).contains(&b)),
        "{north:?}"
    );
    // Seeded: still within one step of the fan.
    let step = 120.0 / (HEADINGS - 1) as f64;
    for c in Candidates::new(7, Some(180.0)) {
        assert!((120.0..=240.0 + step).contains(&c.bearing), "{c:?}");
    }
}

/// Mean latitude of a loop's line.
fn mean_lat(r: &Route) -> f64 {
    r.geometry.iter().map(|p| p.lat).sum::<f64>() / r.geometry.len() as f64
}

#[test]
fn loops_head_the_way_asked() {
    let e = engine(fixture::grid(13));
    let opts = RouteOptions::default();
    let none = Favourites::none();
    let start = e.snap(CENTRE).unwrap().position;
    for (bearing, north) in [(0.0, true), (180.0, false)] {
        let shape = LoopOptions {
            seed: 0,
            bearing: Some(bearing),
        };
        let set = loops(&e, CENTRE, km(20.0), &opts, &none, &shape).unwrap();
        assert!(set.len() >= 2, "{bearing}: {}", set.len());
        for l in &set {
            assert!((l.distance_m - 20_000.0).abs() <= 20_000.0 * TOLERANCE);
            assert_eq!(mean_lat(l) > start.lat, north, "{bearing}: {}", mean_lat(l));
        }
    }
}

#[test]
fn a_direction_with_too_few_loops_is_topped_up() {
    // Asked to head west from the grid's west edge, into nothing.
    let e = engine(fixture::grid(13));
    let edge = ll(55.754, 13.40);
    let shape = LoopOptions {
        seed: 0,
        bearing: Some(270.0),
    };
    let set = loops(
        &e,
        edge,
        km(20.0),
        &RouteOptions::default(),
        &Favourites::none(),
        &shape,
    )
    .unwrap();
    assert!(!set.is_empty());
    for l in &set {
        assert!((l.distance_m - 20_000.0).abs() <= 20_000.0 * TOLERANCE);
    }
}

#[test]
fn a_bad_bearing_is_a_typed_error() {
    let e = engine(fixture::grid(13));
    for b in [f64::NAN, f64::INFINITY] {
        let shape = LoopOptions {
            seed: 0,
            bearing: Some(b),
        };
        assert!(matches!(
            loops(
                &e,
                CENTRE,
                km(20.0),
                &RouteOptions::default(),
                &Favourites::none(),
                &shape
            ),
            Err(CoreError::InvalidArgument(_))
        ));
    }
}
