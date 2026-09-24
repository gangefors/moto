// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture::{self, Road, build};
use crate::region::Region;
use crate::region::format::RoadClass;
use crate::section::{Rating, Source, WaySpan};
use crate::{LatLon, Route, RouteOptions};

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn engine(data: crate::region::RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

const X: u32 = 0;
const W: u32 = 1;
const E: u32 = 2;
const Y: u32 = 3;
const N1: u32 = 4;
const N2: u32 = 5;
/// The north loop W–N1–N2–E, way nodes 0–3.
const NORTH: i64 = 11;

/// A fork: stubs X–W and E–Y, a straight south road W–E (2.5 km, 100 s)
/// and a longer north loop W–N1–N2–E (3.8 km, 152 s), all at 90 km/h.
fn fork() -> Engine {
    let nodes = [
        (55.70, 13.39),
        (55.70, 13.40),
        (55.70, 13.44),
        (55.70, 13.45),
        (55.71, 13.41),
        (55.71, 13.43),
    ];
    let road = |from, to, way| Road::new(from, to, RoadClass::Primary, 90, way);
    let north = |from, to, start| Road {
        way_start: start,
        ..Road::new(from, to, RoadClass::Tertiary, 90, NORTH)
    };
    engine(build(
        &nodes,
        &[
            road(X, W, 12),
            road(W, E, 10),
            north(W, N1, 0),
            north(N1, N2, 1),
            north(N2, E, 2),
            road(E, Y, 13),
        ],
        50_000,
    ))
}

/// Routes from the middle of the west stub to the middle of the east one.
const FROM: LatLon = LatLon {
    lat: 55.70,
    lon: 13.395,
};
const TO: LatLon = LatLon {
    lat: 55.70,
    lon: 13.445,
};

fn section(ways: &[(i64, u32, u32)], rating: Rating, direction: Direction) -> Section {
    Section {
        id: 1,
        rider_id: crate::section::LOCAL_RIDER.into(),
        name: String::new(),
        rating,
        direction,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: ways
            .iter()
            .map(|&(way_id, from_idx, to_idx)| WaySpan {
                way_id,
                from_idx,
                to_idx,
            })
            .collect(),
        geometry: vec![ll(55.70, 13.40), ll(55.70, 13.44)],
    }
}

fn detour(max_detour: f64) -> RouteOptions {
    RouteOptions {
        max_detour,
        ..RouteOptions::default()
    }
}

fn north_of(r: &Route) -> bool {
    r.geometry.iter().any(|p| p.lat > 55.705)
}

#[test]
fn an_epic_road_is_worth_a_detour() {
    let e = fork();
    let fastest = e.route(FROM, TO, &detour(0.5)).unwrap();
    assert!(!north_of(&fastest), "{fastest:?}");
    assert_eq!(fastest.favourite_share, 0.0);

    let fav = Favourites::build(
        &e,
        &[section(&[(NORTH, 0, 3)], Rating::Epic, Direction::Both)],
    );
    assert_eq!(fav.edge_count(), 6, "three edges, both ways");
    let r = e.route_with(FROM, TO, &detour(0.5), &fav).unwrap();
    assert!(north_of(&r), "{r:?}");
    // 3.8 km of the 4.4 km are on the section; the time is real time.
    assert!((r.favourite_share - 0.859).abs() < 0.005, "{r:?}");
    assert!((r.duration_s - r.distance_m / 25.0).abs() < 0.5, "{r:?}");
    assert!(r.duration_s > fastest.duration_s * 1.3);
    // The same inputs give the same route.
    assert_eq!(e.route_with(FROM, TO, &detour(0.5), &fav).unwrap(), r);
    // Back the other way too: the section counts both ways.
    let back = e.route_with(TO, FROM, &detour(0.5), &fav).unwrap();
    assert!(north_of(&back), "{back:?}");
}

#[test]
fn the_bonus_grows_with_the_rating_and_is_capped() {
    let e = fork();
    let with = |rating| {
        let fav = Favourites::build(&e, &[section(&[(NORTH, 0, 3)], rating, Direction::Both)]);
        e.route_with(FROM, TO, &detour(1.0), &fav).unwrap()
    };
    // 152 s of north road against 100 s of south road: great (×0.65)
    // just tips it, good (×0.8) doesn't.
    assert!(north_of(&with(Rating::Great)));
    assert!(!north_of(&with(Rating::Good)));
}

#[test]
fn a_detour_over_the_budget_falls_back_to_the_fastest_route() {
    let e = fork();
    let fav = Favourites::build(
        &e,
        &[section(&[(NORTH, 0, 3)], Rating::Epic, Direction::Both)],
    );
    let fastest = e.route(FROM, TO, &detour(0.3)).unwrap();
    // The north loop takes 41 % longer.
    let r = e.route_with(FROM, TO, &detour(0.3), &fav).unwrap();
    assert!(!north_of(&r), "{r:?}");
    assert_eq!(r, fastest);
    let r = e.route_with(FROM, TO, &detour(0.0), &fav).unwrap();
    assert_eq!(r, fastest);
    assert!(north_of(
        &e.route_with(FROM, TO, &detour(0.42), &fav).unwrap()
    ));
}

#[test]
fn a_short_favourite_is_not_worth_a_long_detour() {
    // Only the middle of the north loop: 51 + 25 + 51 s against 100 s.
    let e = fork();
    let fav = Favourites::build(
        &e,
        &[section(&[(NORTH, 1, 2)], Rating::Epic, Direction::Both)],
    );
    assert_eq!(fav.edge_count(), 2);
    let r = e.route_with(FROM, TO, &detour(1.0), &fav).unwrap();
    assert!(!north_of(&r), "{r:?}");
    assert_eq!(r.favourite_share, 0.0);
}

#[test]
fn a_one_way_section_only_counts_in_its_direction() {
    let e = fork();
    // Rated only along the way, W to E.
    let fav = Favourites::build(
        &e,
        &[section(&[(NORTH, 0, 3)], Rating::Epic, Direction::Forward)],
    );
    assert_eq!(fav.edge_count(), 3);
    assert!(north_of(
        &e.route_with(FROM, TO, &detour(1.0), &fav).unwrap()
    ));
    assert!(!north_of(
        &e.route_with(TO, FROM, &detour(1.0), &fav).unwrap()
    ));
    // Rated only against the way, E to W.
    let fav = Favourites::build(
        &e,
        &[section(&[(NORTH, 3, 0)], Rating::Epic, Direction::Forward)],
    );
    assert!(!north_of(
        &e.route_with(FROM, TO, &detour(1.0), &fav).unwrap()
    ));
    assert!(north_of(
        &e.route_with(TO, FROM, &detour(1.0), &fav).unwrap()
    ));
}

#[test]
fn only_sections_matched_to_this_region_count() {
    let e = fork();
    for status in [Status::NeedsRematch, Status::Unmatched] {
        let s = Section {
            status,
            ..section(&[(NORTH, 0, 3)], Rating::Epic, Direction::Both)
        };
        let fav = Favourites::build(&e, &[s]);
        assert!(fav.is_empty(), "{status:?}");
        assert!(!north_of(
            &e.route_with(FROM, TO, &detour(1.0), &fav).unwrap()
        ));
    }
}

#[test]
fn overlapping_sections_give_the_best_bonus() {
    let e = fork();
    let fav = Favourites::build(
        &e,
        &[
            section(&[(NORTH, 0, 3)], Rating::Good, Direction::Both),
            section(&[(NORTH, 0, 3)], Rating::Epic, Direction::Both),
            section(&[(NORTH, 0, 3)], Rating::Good, Direction::Forward),
        ],
    );
    assert_eq!(fav.edge_count(), 6);
    let edges = e.region().edge_count() as u32;
    let best = (0..edges).map(|id| fav.bonus(id)).fold(0.0, f64::max);
    assert!((best - PARAMS.bonus(Rating::Epic)).abs() < 1e-6);
    assert!((fav.max_bonus() - best).abs() < 1e-9);
}

#[test]
fn a_section_ending_mid_edge_covers_part_of_it() {
    // One road with four equal segments (way nodes 0–4).
    let nodes = [(55.70, 13.40), (55.70, 13.44)];
    let road = Road {
        via: vec![(55.70, 13.41), (55.70, 13.42), (55.70, 13.43)],
        ..Road::new(0, 1, RoadClass::Tertiary, 70, 20)
    };
    let e = engine(build(&nodes, &[road], 50_000));
    let fav = Favourites::build(&e, &[section(&[(20, 1, 3)], Rating::Epic, Direction::Both)]);
    assert_eq!(fav.edge_count(), 2);
    for id in 0..2 {
        assert!((fav.coverage(id) - 0.5).abs() < 1e-3, "edge {id}");
        assert!((fav.bonus(id) - 0.25).abs() < 1e-3, "edge {id}");
    }
    // A span touching the road at one node only covers nothing.
    let fav = Favourites::build(&e, &[section(&[(20, 4, 4)], Rating::Epic, Direction::Both)]);
    assert!(fav.is_empty());

    // Ridden from 13.405 to 13.435, two thirds of it on the section
    // (13.41–13.43), though the route rides only part of the edge.
    let r = e
        .route_with(
            ll(55.70, 13.405),
            ll(55.70, 13.435),
            &detour(0.4),
            &Favourites::build(&e, &[section(&[(20, 1, 3)], Rating::Epic, Direction::Both)]),
        )
        .unwrap();
    assert!((r.favourite_share - 2.0 / 3.0).abs() < 0.01, "{r:?}");
}

#[test]
fn favourites_of_another_region_are_refused() {
    let e = fork();
    let fav = Favourites::build(
        &e,
        &[section(&[(NORTH, 0, 3)], Rating::Epic, Direction::Both)],
    );
    let other = engine(fixture::region());
    assert!(matches!(
        other.route_with(ll(55.7001, 13.201), ll(55.7001, 13.209), &detour(0.4), &fav),
        Err(CoreError::InvalidArgument(_))
    ));
    // No favourites fit any region.
    let none = Favourites::none();
    assert!(
        other
            .route_with(
                ll(55.7001, 13.201),
                ll(55.7001, 13.209),
                &detour(0.4),
                &none
            )
            .is_ok()
    );
    // Favourites with no edge still belong to their region.
    let empty = Favourites::build(&e, &[]);
    assert!(empty.is_empty());
    assert!(e.route_with(FROM, TO, &detour(0.4), &empty).is_ok());
    assert!(
        other
            .route_with(
                ll(55.7001, 13.201),
                ll(55.7001, 13.209),
                &detour(0.4),
                &empty
            )
            .is_err()
    );
}

#[test]
fn odd_way_spans_never_panic() {
    let e = fork();
    let mut rng = 0x2545_f491_4f6c_dd1du64;
    let mut next = || {
        rng ^= rng << 13;
        rng ^= rng >> 7;
        rng ^= rng << 17;
        rng
    };
    let mut sections = Vec::new();
    for i in 0..200 {
        let pick = |v: u64| -> u32 {
            match v % 4 {
                0 => u32::MAX,
                1 => 0,
                _ => (v % 7) as u32,
            }
        };
        let way = [NORTH, 10, 12, 13, i64::MAX, 1][(next() % 6) as usize];
        let rating = [Rating::Good, Rating::Great, Rating::Epic][i % 3];
        let dir = [Direction::Both, Direction::Forward][i % 2];
        sections.push(section(&[(way, pick(next()), pick(next()))], rating, dir));
    }
    let fav = Favourites::build(&e, &sections);
    let edges = e.region().edge_count() as u32;
    for id in 0..edges + 5 {
        let (b, c) = (fav.bonus(id), fav.coverage(id));
        assert!((0.0..=PARAMS.max_bonus()).contains(&b), "{b}");
        assert!((0.0..=1.0).contains(&c), "{c}");
    }
    let r = e.route_with(FROM, TO, &detour(0.4), &fav).unwrap();
    assert!((0.0..=1.0).contains(&r.favourite_share));
}
