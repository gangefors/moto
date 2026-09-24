// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture::{self, A, B, C, D, Road, build};
use crate::region::format::RoadClass;
use crate::region::{Region, RegionData};
use crate::section::{Direction, LOCAL_RIDER, NewSection, Rating, Source};

const T0: i64 = 1_790_000_000;

fn engine(data: RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

/// The Lund fixture rebuilt with its nodes moved `north_m` metres north,
/// OSM way ids plus `renumber`, B→D one-way the other way round if
/// `flip_one_way`, and B–D left out if `without_bd`; a later OSM
/// timestamp, as a new extract would have.
fn lund(north_m: f64, renumber: i64, flip_one_way: bool, without_bd: bool) -> RegionData {
    let dlat = north_m / 111_195.0;
    let nodes = [
        (55.70 + dlat, 13.20),
        (55.70 + dlat, 13.21),
        (55.71 + dlat, 13.21),
        (55.70 + dlat, 13.22),
    ];
    let mut roads = vec![
        Road::new(A, B, RoadClass::Tertiary, 70, 100 + renumber),
        Road {
            via: vec![(55.705 + dlat, 13.212)],
            ..Road::new(B, C, RoadClass::Unclassified, 70, 200 + renumber)
        },
    ];
    if !without_bd {
        let (from, to) = if flip_one_way { (D, B) } else { (B, D) };
        roads.push(Road {
            oneway: true,
            ..Road::new(from, to, RoadClass::Primary, 70, 300 + renumber)
        });
    }
    let mut data = build(&nodes, &roads, 50_000);
    data.info.osm_timestamp += 7 * 86_400;
    data
}

/// A store with one section along A→B→D, marked on the original fixture.
fn store_with_section() -> (Store, i64) {
    let (mut store, id) = store_without_key();
    store
        .set_region_key(&region_key(&engine(fixture::region())))
        .unwrap();
    (store, id)
}

/// The same, before any region has been remembered (a new database).
fn store_without_key() -> (Store, i64) {
    let old = engine(fixture::region());
    let draft = old
        .section_between(ll(55.7001, 13.202), ll(55.7001, 13.219))
        .unwrap();
    let mut store = Store::open_in_memory().unwrap();
    let s = store
        .add_section(
            &NewSection {
                rider_id: LOCAL_RIDER.into(),
                name: "A to D".into(),
                rating: Rating::Epic,
                direction: Direction::Forward,
                source: Source::Map,
                ways: draft.ways,
                geometry: draft.geometry,
            },
            T0,
        )
        .unwrap();
    (store, s.id)
}

#[test]
fn nothing_to_do_while_the_region_is_the_same() {
    let (mut store, id) = store_with_section();
    let report = rematch_store(&mut store, &engine(fixture::region())).unwrap();
    assert_eq!(report, RematchReport::default());
    assert_eq!(store.get_section(id).unwrap().unwrap().status, Status::Ok);
}

#[test]
fn a_first_run_matches_everything_and_remembers_the_region() {
    let (mut store, id) = store_without_key();
    assert_eq!(store.region_key().unwrap(), None);
    let e = engine(fixture::region());
    let report = rematch_store(&mut store, &e).unwrap();
    assert_eq!(
        (report.checked, report.matched, report.unmatched),
        (1, 1, 0)
    );
    assert_eq!(store.region_key().unwrap(), Some(region_key(&e)));
    assert_eq!(store.get_section(id).unwrap().unwrap().status, Status::Ok);
}

#[test]
fn renumbered_ways_are_followed() {
    let (mut store, id) = store_with_section();
    let before = store.get_section(id).unwrap().unwrap();
    let report = rematch_store(&mut store, &engine(lund(0.0, 1000, false, false))).unwrap();
    assert_eq!(
        (report.checked, report.matched, report.unmatched),
        (1, 1, 0)
    );
    let after = store.get_section(id).unwrap().unwrap();
    assert_eq!(after.status, Status::Ok);
    assert_eq!(
        after.ways.iter().map(|w| w.way_id).collect::<Vec<_>>(),
        [1100, 1300]
    );
    // The rider's own fields are untouched.
    assert_eq!(
        (
            after.name.as_str(),
            after.rating,
            after.direction,
            after.updated_at
        ),
        (
            before.name.as_str(),
            before.rating,
            before.direction,
            before.updated_at
        )
    );
}

#[test]
fn a_slightly_realigned_road_still_fits() {
    let (mut store, id) = store_with_section();
    rematch_store(&mut store, &engine(lund(10.0, 0, false, false))).unwrap();
    let after = store.get_section(id).unwrap().unwrap();
    assert_eq!(after.status, Status::Ok);
    // The geometry follows the road's new line.
    assert!(
        (after.geometry[0].lat - (55.70 + 10.0 / 111_195.0)).abs() < 1e-6,
        "{after:?}"
    );
}

#[test]
fn sections_that_no_longer_fit_are_flagged_and_kept() {
    for (name, region) in [
        ("road gone", lund(0.0, 0, false, true)),
        ("road moved 60 m", lund(60.0, 0, false, false)),
        ("one-way flipped", lund(0.0, 0, true, false)),
    ] {
        let (mut store, id) = store_with_section();
        let before = store.get_section(id).unwrap().unwrap();
        let report = rematch_store(&mut store, &engine(region)).unwrap();
        assert_eq!((report.checked, report.unmatched), (1, 1), "{name}");
        let after = store.get_section(id).unwrap().unwrap();
        assert_eq!(after.status, Status::Unmatched, "{name}");
        assert_eq!(
            (after.ways, after.geometry),
            (before.ways, before.geometry),
            "{name}"
        );
    }
}

#[test]
fn unmatched_sections_are_tried_again_on_the_next_region() {
    let (mut store, id) = store_with_section();
    rematch_store(&mut store, &engine(lund(0.0, 0, false, true))).unwrap();
    assert_eq!(
        store.get_section(id).unwrap().unwrap().status,
        Status::Unmatched
    );
    // The next extract has the road again.
    let mut back = lund(0.0, 0, false, false);
    back.info.osm_timestamp += 86_400;
    let report = rematch_store(&mut store, &engine(back)).unwrap();
    assert_eq!(report.matched, 1);
    assert_eq!(store.get_section(id).unwrap().unwrap().status, Status::Ok);
}

#[test]
fn an_interrupted_run_is_finished_next_time() {
    // The app died after flagging the sections, before remembering the
    // region: the next start sees sections waiting and does them.
    let (mut store, id) = store_with_section();
    store.flag_all_for_rematch().unwrap();
    assert_eq!(
        store.get_section(id).unwrap().unwrap().status,
        Status::NeedsRematch
    );
    let report = rematch_store(&mut store, &engine(fixture::region())).unwrap();
    assert_eq!(report.checked, 1);
    assert_eq!(store.get_section(id).unwrap().unwrap().status, Status::Ok);
}

#[test]
fn keys_differ_by_data_and_build() {
    let a = region_key(&engine(fixture::region()));
    assert_eq!(a, region_key(&engine(fixture::region())));
    assert_ne!(a, region_key(&engine(lund(0.0, 0, false, false))));
    assert_ne!(a, region_key(&engine(lund(0.0, 0, false, true))));
}

#[test]
fn densifies_and_measures() {
    let line = [ll(55.70, 13.20), ll(55.70, 13.201)];
    let pts = densify(&line, 15.0);
    // 62.7 m: five steps of 12.5 m, both ends included.
    assert_eq!(pts.len(), 6);
    assert_eq!(pts[0], line[0]);
    assert_eq!(*pts.last().unwrap(), line[1]);
    assert!((distance_to_line(ll(55.7001, 13.2005), &line) - 11.1).abs() < 0.1);
    assert!(distance_to_line(ll(55.70, 13.2005), &line) < 1e-6);
    assert!((distance_to_line(ll(55.70, 13.2), &[ll(55.7001, 13.2)]) - 11.1).abs() < 0.1);
}

#[test]
fn deletes_only_unmatched_sections() {
    let (mut store, id) = store_with_section();
    let other = store.get_section(id).unwrap().unwrap();
    let keep = store
        .add_section(
            &NewSection {
                rider_id: LOCAL_RIDER.into(),
                name: "B to C".into(),
                rating: Rating::Good,
                direction: Direction::Both,
                source: Source::Map,
                ways: vec![],
                geometry: vec![ll(55.70, 13.21), ll(55.705, 13.212), ll(55.71, 13.21)],
            },
            T0,
        )
        .unwrap();
    // B–D is gone: A to D no longer fits, B to C does.
    rematch_store(&mut store, &engine(lund(0.0, 0, false, true))).unwrap();
    assert_eq!(
        store.get_section(other.id).unwrap().unwrap().status,
        Status::Unmatched
    );
    assert_eq!(
        store.get_section(keep.id).unwrap().unwrap().status,
        Status::Ok
    );
    assert_eq!(store.delete_unmatched().unwrap(), 1);
    assert!(store.get_section(other.id).unwrap().is_none());
    assert!(store.get_section(keep.id).unwrap().is_some());
    assert_eq!(store.delete_unmatched().unwrap(), 0);
}
