// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use rusqlite::Connection;

use super::super::{MIGRATIONS, migrate};
use super::*;
use crate::track::tests::fix;

const T0: i64 = 1_790_000_000;

fn store() -> Store {
    Store::open_in_memory().unwrap()
}

fn span(way_id: i64, from_idx: u32, to_idx: u32) -> WaySpan {
    WaySpan {
        way_id,
        from_idx,
        to_idx,
    }
}

/// A finished ride of a few fixes; its id.
fn finished(s: &mut Store) -> i64 {
    let points: Vec<_> = (0..5)
        .map(|i| fix(T0 * 1000 + i * 1000, 55.7 + i as f64 * 1e-4, 13.2))
        .collect();
    s.import_track(&points).unwrap().id
}

#[test]
fn finished_rides_wait_to_be_matched_once_per_map() {
    let mut s = store();
    let a = finished(&mut s);
    let b = finished(&mut s);
    // Still being recorded: not matched yet.
    let recording = s.start_track(T0).unwrap().id;
    assert_eq!(s.rides_to_match("map1").unwrap(), vec![a, b]);

    assert!(s.save_ride_ways(a, "map1", &[span(7, 0, 3)]).unwrap());
    assert_eq!(s.rides_to_match("map1").unwrap(), vec![b]);
    // Another map: every ride again.
    assert_eq!(s.rides_to_match("map2").unwrap(), vec![a, b]);
    assert_eq!(s.ride_ways_key(a).unwrap().as_deref(), Some("map1"));
    assert_eq!(s.ride_ways_key(b).unwrap(), None);

    s.finish_track(recording, T0 + 60).unwrap();
    assert_eq!(s.rides_to_match("map1").unwrap(), vec![b, recording]);
}

#[test]
fn spans_are_read_back_per_ride_for_their_map() {
    let mut s = store();
    let a = finished(&mut s);
    let b = finished(&mut s);
    let c = finished(&mut s);
    s.save_ride_ways(a, "map1", &[span(7, 0, 3), span(8, 5, 2)])
        .unwrap();
    s.save_ride_ways(b, "map1", &[]).unwrap();
    s.save_ride_ways(c, "map0", &[span(9, 0, 1)]).unwrap();
    assert_eq!(
        s.ride_ways("map1").unwrap(),
        vec![vec![span(7, 0, 3), span(8, 5, 2)]]
    );
    assert_eq!(s.ride_ways("map0").unwrap(), vec![vec![span(9, 0, 1)]]);

    // Matched again: the old spans are replaced, not added to.
    s.save_ride_ways(a, "map1", &[span(10, 1, 2)]).unwrap();
    assert_eq!(s.ride_ways("map1").unwrap(), vec![vec![span(10, 1, 2)]]);
    assert_eq!(s.ride_ways("map2").unwrap(), Vec::<Vec<WaySpan>>::new());
}

#[test]
fn deleting_a_ride_deletes_its_spans() {
    let mut s = store();
    let a = finished(&mut s);
    s.save_ride_ways(a, "map1", &[span(7, 0, 3)]).unwrap();
    assert!(s.delete_track(a).unwrap());
    assert!(s.ride_ways("map1").unwrap().is_empty());
    let left: i64 = s
        .conn
        .query_row("SELECT count(*) FROM track_ways", [], |r| r.get(0))
        .unwrap();
    assert_eq!(left, 0);
}

#[test]
fn no_such_ride_and_too_many_spans_are_refused() {
    let mut s = store();
    assert!(!s.save_ride_ways(42, "map1", &[span(7, 0, 3)]).unwrap());
    assert_eq!(s.ride_ways_key(42).unwrap(), None);
    let a = finished(&mut s);
    let many = vec![span(7, 0, 1); MAX_RIDE_WAYS + 1];
    assert!(matches!(
        s.save_ride_ways(a, "map1", &many),
        Err(CoreError::InvalidArgument(_))
    ));
    // Nothing was written.
    assert_eq!(s.ride_ways_key(a).unwrap(), None);
}

#[test]
fn damaged_rows_are_refused_not_trusted() {
    let mut s = store();
    let a = finished(&mut s);
    s.save_ride_ways(a, "map1", &[span(7, 0, 3)]).unwrap();
    // A damaged database: constraints bypassed, an index out of range.
    s.conn
        .pragma_update(None, "ignore_check_constraints", true)
        .unwrap();
    s.conn
        .execute("UPDATE track_ways SET to_idx = -1", [])
        .unwrap();
    assert!(matches!(s.ride_ways("map1"), Err(CoreError::Storage(_))));
    s.conn
        .execute("UPDATE track_ways SET to_idx = 4294967296", [])
        .unwrap();
    assert!(matches!(s.ride_ways("map1"), Err(CoreError::Storage(_))));
}

#[test]
fn rides_from_schema_6_wait_to_be_matched() {
    let mut conn = Connection::open_in_memory().unwrap();
    migrate(&mut conn, &MIGRATIONS[..6]).unwrap();
    conn.execute(
        "INSERT INTO tracks (rider_id, started_at, ended_at) VALUES ('local', 1, 2)",
        [],
    )
    .unwrap();
    migrate(&mut conn, MIGRATIONS).unwrap();
    let (id, key): (i64, Option<String>) = conn
        .query_row("SELECT id, ways_key FROM tracks", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    assert_eq!((id, key), (1, None));
}
