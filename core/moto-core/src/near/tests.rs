// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::section::{LOCAL_RIDER, Source, Status};

fn section(id: i64, geometry: Vec<LatLon>, rating: Rating, direction: Direction) -> Section {
    Section {
        id,
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

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

// A section running north from (55.70, 13.20), about 1.1 km long.
fn north(id: i64, lon: f64, rating: Rating, direction: Direction) -> Section {
    section(id, vec![ll(55.70, lon), ll(55.71, lon)], rating, direction)
}

#[test]
fn lists_the_nearest_two_within_five_km_with_the_way_to_them() {
    let east = north(1, 13.22, Rating::Epic, Direction::Both); // ~1.25 km east
    let west = north(2, 13.17, Rating::Great, Direction::Both); // ~1.9 km west
    let far = north(3, 13.40, Rating::Good, Direction::Both); // ~12.5 km east
    let farther = north(4, 13.25, Rating::Good, Direction::Both); // ~3.1 km east
    let at = ll(55.705, 13.20);
    let near = near_favourites(&[west, far, farther, east], at, None).unwrap();
    assert_eq!(
        near.iter().map(|n| n.section_id).collect::<Vec<_>>(),
        vec![1, 2]
    );
    assert!(
        (near[0].distance_m - 1_255.0).abs() < 30.0,
        "{}",
        near[0].distance_m
    );
    assert!((near[0].bearing_deg - 90.0).abs() < 1.0);
    assert!((near[1].bearing_deg - 270.0).abs() < 1.0);
    assert!(!near[0].on && near[0].left_m.is_none());
    assert_eq!(near[0].rating, Rating::Epic);
}

#[test]
fn on_a_favourite_says_how_much_is_left_the_way_the_rider_goes() {
    let s = north(1, 13.20, Rating::Epic, Direction::Both);
    // A quarter of the way up it.
    let at = ll(55.7025, 13.20);
    let going_north = near_favourites(std::slice::from_ref(&s), at, Some(5.0)).unwrap();
    assert!(going_north[0].on);
    let left = going_north[0].left_m.unwrap();
    assert!((left - 834.0).abs() < 10.0, "{left}");
    let going_south = near_favourites(std::slice::from_ref(&s), at, Some(185.0)).unwrap();
    assert!((going_south[0].left_m.unwrap() - 278.0).abs() < 10.0);
    // No heading: its own way.
    let no_heading = near_favourites(std::slice::from_ref(&s), at, None).unwrap();
    assert!((no_heading[0].left_m.unwrap() - 834.0).abs() < 10.0);
    // A one-way favourite counts its own way whatever the heading.
    let one_way = north(2, 13.20, Rating::Epic, Direction::Forward);
    let against = near_favourites(&[one_way], at, Some(180.0)).unwrap();
    assert!((against[0].left_m.unwrap() - 834.0).abs() < 10.0);
}

#[test]
fn bad_input_is_rejected_or_skipped() {
    assert!(near_favourites(&[], ll(f64::NAN, 13.0), None).is_err());
    assert!(near_favourites(&[], ll(91.0, 13.0), None).is_err());
    let short = section(1, vec![ll(55.70, 13.20)], Rating::Good, Direction::Both);
    let broken = section(
        2,
        vec![ll(55.70, 13.20), ll(f64::NAN, 13.2)],
        Rating::Good,
        Direction::Both,
    );
    let at = ll(55.70, 13.20);
    assert!(
        near_favourites(&[short, broken], at, Some(f64::INFINITY))
            .unwrap()
            .is_empty()
    );
}

#[test]
fn never_panics_on_odd_positions() {
    let s = north(1, 13.20, Rating::Good, Direction::Both);
    for (lat, lon) in [
        (89.999, 179.999),
        (-89.999, -180.0),
        (0.0, 0.0),
        (55.70, 13.20),
        (55.71, 13.20),
    ] {
        let _ = near_favourites(std::slice::from_ref(&s), ll(lat, lon), Some(-720.0));
    }
}
