// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::geo::destination;
use crate::section::{LOCAL_RIDER, Source, Status};

const START: LatLon = LatLon {
    lat: 57.4,
    lon: 15.1,
};

/// A line from `from` through each (bearing, metres) leg, a point every
/// 25 m.
fn path(from: LatLon, legs: &[(f64, f64)]) -> Vec<LatLon> {
    let mut out = vec![from];
    let mut at = from;
    for &(bearing, length) in legs {
        let n = (length / 25.0).ceil() as usize;
        for _ in 0..n {
            at = destination(at, bearing, length / n as f64);
            out.push(at);
        }
    }
    out
}

fn section(geometry: Vec<LatLon>, rating: Rating, direction: Direction) -> Section {
    Section {
        id: 1,
        rider_id: LOCAL_RIDER.into(),
        name: String::new(),
        rating,
        direction,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: vec![],
        geometry,
    }
}

fn length(p: &FavouritePart) -> f64 {
    p.line.windows(2).map(|w| haversine_m(w[0], w[1])).sum()
}

/// 5 km north.
fn route() -> Vec<LatLon> {
    path(START, &[(0.0, 5_000.0)])
}

/// The part of the route from `from` to `to` metres, as a section (the
/// section's own points, 5 m to the side, as a hand-drawn one would be).
fn on_route(from: f64, to: f64) -> Vec<LatLon> {
    path(
        destination(destination(START, 0.0, from), 90.0, 5.0),
        &[(0.0, to - from)],
    )
}

#[test]
fn finds_the_stretch_on_a_section() {
    let s = section(on_route(1_000.0, 2_000.0), Rating::Great, Direction::Both);
    let parts = favourite_parts_along(&route(), &[s]).unwrap();
    assert_eq!(parts.len(), 1);
    assert_eq!(parts[0].rating, Rating::Great);
    assert!(
        (length(&parts[0]) - 1_000.0).abs() < 30.0,
        "{}",
        length(&parts[0])
    );
    assert!(haversine_m(parts[0].line[0], destination(START, 0.0, 1_000.0)) < 20.0);
}

#[test]
fn the_best_rating_wins_where_sections_overlap() {
    let good = section(on_route(1_000.0, 3_000.0), Rating::Good, Direction::Both);
    let epic = section(on_route(1_500.0, 2_000.0), Rating::Epic, Direction::Both);
    let parts = favourite_parts_along(&route(), &[good, epic]).unwrap();
    let ratings: Vec<Rating> = parts.iter().map(|p| p.rating).collect();
    assert_eq!(ratings, [Rating::Good, Rating::Epic, Rating::Good]);
    assert!((length(&parts[1]) - 500.0).abs() < 30.0);
}

#[test]
fn one_way_sections_count_only_their_way() {
    // Drawn southwards: the route north doesn't ride it its way.
    let mut south = on_route(1_000.0, 2_000.0);
    south.reverse();
    let one_way = section(south.clone(), Rating::Epic, Direction::Forward);
    assert!(
        favourite_parts_along(&route(), &[one_way])
            .unwrap()
            .is_empty()
    );
    let both = section(south, Rating::Epic, Direction::Both);
    assert_eq!(favourite_parts_along(&route(), &[both]).unwrap().len(), 1);
    let north = section(on_route(1_000.0, 2_000.0), Rating::Epic, Direction::Forward);
    assert_eq!(favourite_parts_along(&route(), &[north]).unwrap().len(), 1);
}

#[test]
fn crossing_a_favourite_is_not_riding_it() {
    // An east–west section across the route at 2 km.
    let mid = destination(START, 0.0, 2_000.0);
    let across = path(destination(mid, 270.0, 1_000.0), &[(90.0, 2_000.0)]);
    let s = section(across, Rating::Epic, Direction::Both);
    assert!(favourite_parts_along(&route(), &[s]).unwrap().is_empty());
}

#[test]
fn a_parallel_road_further_away_is_not_it() {
    let beside = path(destination(START, 90.0, 40.0), &[(0.0, 5_000.0)]);
    let s = section(beside, Rating::Epic, Direction::Both);
    assert!(favourite_parts_along(&route(), &[s]).unwrap().is_empty());
}

#[test]
fn short_brushes_are_dropped() {
    let s = section(on_route(1_000.0, 1_030.0), Rating::Epic, Direction::Both);
    assert!(favourite_parts_along(&route(), &[s]).unwrap().is_empty());
}

#[test]
fn bad_lines_are_refused_and_bad_sections_skipped() {
    assert!(favourite_parts_along(&[START], &[]).is_err());
    let nan = LatLon {
        lat: f64::NAN,
        lon: 15.0,
    };
    assert!(favourite_parts_along(&[START, nan], &[]).is_err());
    // A section of one point (can't come from the store, but never panics).
    let odd = section(vec![START], Rating::Epic, Direction::Both);
    let same = section(vec![START, START], Rating::Epic, Direction::Both);
    assert!(
        favourite_parts_along(&route(), &[odd, same])
            .unwrap()
            .is_empty()
    );
    // A line standing still.
    assert!(
        favourite_parts_along(&[START, START], &[])
            .unwrap()
            .is_empty()
    );
}
