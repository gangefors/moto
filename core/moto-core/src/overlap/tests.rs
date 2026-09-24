// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::Engine;
use crate::fixture;
use crate::region::Region;
use crate::section::{LOCAL_RIDER, Source};

const T0: i64 = 1_790_000_000;

fn engine() -> Engine {
    Engine::from_region(Region::from_bytes(&fixture::region().to_bytes().unwrap()).unwrap())
}

/// A section along A–B (lat 55.70) from `from_lon` to `to_lon`; with
/// `Direction::Forward` it runs from `from_lon` to `to_lon`.
fn along(
    e: &Engine,
    from_lon: f64,
    to_lon: f64,
    rating: Rating,
    direction: Direction,
) -> NewSection {
    let p = |lon| LatLon { lat: 55.7001, lon };
    let d = e.section_between(p(from_lon), p(to_lon)).unwrap();
    NewSection {
        rider_id: LOCAL_RIDER.into(),
        name: format!("{from_lon}-{to_lon} {rating:?} {direction:?}"),
        rating,
        direction,
        source: Source::Map,
        ways: d.ways,
        geometry: d.geometry,
    }
}

/// Adds `first` as it is, then `second` under the overlap rules.
fn add_after(first: &NewSection, second: &NewSection) -> (Store, i64, Added) {
    let mut store = Store::open_in_memory().unwrap();
    let id = store.add_section(first, T0).unwrap().id;
    let added = add_section(&mut store, second, T0 + 1).unwrap();
    (store, id, added)
}

fn kept_both((store, _, added): (Store, i64, Added)) {
    assert!(
        matches!(added, Added::Saved { ref replaced, .. } if replaced.is_empty()),
        "{added:?}"
    );
    assert_eq!(store.list_sections(None).unwrap().len(), 2);
}

fn covered((store, id, added): (Store, i64, Added)) {
    assert_eq!(added, Added::Covered { by: id });
    assert_eq!(store.list_sections(None).unwrap().len(), 1);
}

fn replaced((store, id, added): (Store, i64, Added)) {
    let Added::Saved { section, replaced } = added else {
        panic!("{added:?}");
    };
    assert_eq!(replaced, [id]);
    let left = store.list_sections(None).unwrap();
    assert_eq!(left.iter().map(|s| s.id).collect::<Vec<_>>(), [section.id]);
}

use Direction::{Both, Forward};
use Rating::{Epic, Good, Great};

#[test]
fn opposite_one_ways_are_both_kept() {
    let e = engine();
    kept_both(add_after(
        &along(&e, 13.201, 13.209, Epic, Forward),
        &along(&e, 13.209, 13.201, Good, Forward),
    ));
    // Even rated the same.
    kept_both(add_after(
        &along(&e, 13.201, 13.209, Good, Forward),
        &along(&e, 13.209, 13.201, Good, Forward),
    ));
}

#[test]
fn a_one_way_rated_higher_than_a_two_way_is_kept() {
    let e = engine();
    kept_both(add_after(
        &along(&e, 13.201, 13.209, Great, Both),
        &along(&e, 13.201, 13.209, Epic, Forward),
    ));
    // And the other way round: the two-way doesn't replace it.
    kept_both(add_after(
        &along(&e, 13.201, 13.209, Epic, Forward),
        &along(&e, 13.201, 13.209, Great, Both),
    ));
}

#[test]
fn a_two_way_rated_as_high_makes_a_one_way_redundant() {
    let e = engine();
    covered(add_after(
        &along(&e, 13.201, 13.209, Epic, Both),
        &along(&e, 13.201, 13.209, Good, Forward),
    ));
    replaced(add_after(
        &along(&e, 13.201, 13.209, Great, Forward),
        &along(&e, 13.201, 13.209, Great, Both),
    ));
}

#[test]
fn a_short_stretch_rated_higher_inside_a_longer_one_is_kept() {
    let e = engine();
    kept_both(add_after(
        &along(&e, 13.201, 13.209, Good, Both),
        &along(&e, 13.203, 13.207, Epic, Both),
    ));
    kept_both(add_after(
        &along(&e, 13.203, 13.207, Epic, Both),
        &along(&e, 13.201, 13.209, Good, Both),
    ));
}

#[test]
fn the_longer_of_two_equal_sections_wins() {
    let e = engine();
    covered(add_after(
        &along(&e, 13.201, 13.209, Great, Both),
        &along(&e, 13.203, 13.207, Great, Both),
    ));
    replaced(add_after(
        &along(&e, 13.203, 13.207, Great, Both),
        &along(&e, 13.201, 13.209, Great, Both),
    ));
    // An exact duplicate is not saved again.
    covered(add_after(
        &along(&e, 13.201, 13.209, Good, Both),
        &along(&e, 13.201, 13.209, Good, Both),
    ));
    // Rated higher over the same road, it replaces the old one.
    replaced(add_after(
        &along(&e, 13.201, 13.209, Good, Both),
        &along(&e, 13.201, 13.209, Epic, Both),
    ));
}

#[test]
fn one_ways_cover_only_the_same_way() {
    let e = engine();
    covered(add_after(
        &along(&e, 13.201, 13.209, Great, Forward),
        &along(&e, 13.203, 13.207, Good, Forward),
    ));
    kept_both(add_after(
        &along(&e, 13.201, 13.209, Great, Forward),
        &along(&e, 13.207, 13.203, Good, Forward),
    ));
}

#[test]
fn partial_overlaps_are_both_kept() {
    let e = engine();
    kept_both(add_after(
        &along(&e, 13.201, 13.207, Epic, Both),
        &along(&e, 13.204, 13.215, Good, Both),
    ));
}

#[test]
fn invalid_sections_are_refused_before_anything_changes() {
    let e = engine();
    let mut store = Store::open_in_memory().unwrap();
    store
        .add_section(&along(&e, 13.201, 13.209, Good, Both), T0)
        .unwrap();
    let mut bad = along(&e, 13.201, 13.209, Epic, Both);
    bad.geometry.truncate(1);
    assert!(matches!(
        add_section(&mut store, &bad, T0),
        Err(CoreError::InvalidArgument(_))
    ));
    assert_eq!(store.list_sections(None).unwrap().len(), 1);
}
