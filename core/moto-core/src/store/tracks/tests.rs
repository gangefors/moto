// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use rusqlite::Connection;

use super::super::{MIGRATIONS, SCHEMA_VERSION, migrate, user_version};
use super::*;
use crate::track::tests::fix;

const T0: i64 = 1_790_000_000;
const MS0: i64 = T0 * 1000;

fn store() -> Store {
    Store::open_in_memory().unwrap()
}

/// `n` fixes a second apart from `start` (ms), heading north 20 m apart.
fn ride(start: i64, n: usize) -> Vec<TrackPoint> {
    (0..n)
        .map(|i| {
            let i = i as i64;
            fix(start + i * 1000, 55.7 + (i as f64) * 20.0 / 111_195.0, 13.2)
        })
        .collect()
}

#[test]
fn records_a_ride_in_batches() {
    let mut s = store();
    let t = s.start_track(T0).unwrap();
    assert_eq!((t.started_at, t.ended_at, t.point_count), (T0, None, 0));
    assert_eq!(t.rider_id, LOCAL_RIDER);

    let points = ride(MS0, 100);
    let a = s.append_track_points(t.id, &points[..40]).unwrap();
    assert_eq!(
        a,
        Appended {
            added: 40,
            point_count: 40
        }
    );
    let a = s.append_track_points(t.id, &points[40..]).unwrap();
    assert_eq!(
        a,
        Appended {
            added: 60,
            point_count: 100
        }
    );

    let done = s.finish_track(t.id, T0 + 100).unwrap().unwrap();
    assert_eq!((done.ended_at, done.point_count), (Some(T0 + 100), 100));
    // 99 steps of 20 m.
    assert!((done.distance_m - 1980.0).abs() < 2.0, "{done:?}");

    let read = s.track_points(t.id).unwrap().unwrap();
    assert_eq!(read.len(), 100);
    assert_eq!(read[0].time_ms, MS0);
    assert_eq!(read[99].time_ms, MS0 + 99_000);
    assert!((read[50].position.lat - points[50].position.lat).abs() < 1e-7);
    assert_eq!(read[50].accuracy_m, Some(5.0));
    assert_eq!(read[50].bearing_deg, Some(90.0));
}

#[test]
fn a_batch_sent_again_adds_nothing() {
    // The app died after the core stored a batch but before the buffer was
    // cleared: on restart the same points arrive again.
    let mut s = store();
    let t = s.start_track(T0).unwrap();
    let points = ride(MS0, 30);
    s.append_track_points(t.id, &points[..20]).unwrap();
    let a = s.append_track_points(t.id, &points[10..]).unwrap();
    assert_eq!(
        a,
        Appended {
            added: 10,
            point_count: 30
        }
    );
    let times: Vec<i64> = s
        .track_points(t.id)
        .unwrap()
        .unwrap()
        .iter()
        .map(|p| p.time_ms)
        .collect();
    assert_eq!(times, points.iter().map(|p| p.time_ms).collect::<Vec<_>>());
}

#[test]
fn fixes_out_of_order_within_a_batch_are_skipped() {
    let mut s = store();
    let t = s.start_track(T0).unwrap();
    let batch = [
        fix(MS0, 55.7, 13.2),
        fix(MS0 + 2000, 55.7, 13.2),
        fix(MS0 + 1000, 55.7, 13.2),
        fix(MS0 + 2000, 55.7, 13.2),
        fix(MS0 + 3000, 55.7, 13.2),
    ];
    let a = s.append_track_points(t.id, &batch).unwrap();
    assert_eq!(a.added, 3);
    let times: Vec<i64> = s
        .track_points(t.id)
        .unwrap()
        .unwrap()
        .iter()
        .map(|p| p.time_ms)
        .collect();
    assert_eq!(times, [MS0, MS0 + 2000, MS0 + 3000]);
}

#[test]
fn an_invalid_fix_refuses_the_whole_batch() {
    let mut s = store();
    let t = s.start_track(T0).unwrap();
    let mut batch = ride(MS0, 10);
    batch[7].speed_mps = Some(f64::NAN);
    assert!(matches!(
        s.append_track_points(t.id, &batch),
        Err(CoreError::InvalidArgument(_))
    ));
    assert_eq!(s.get_track(t.id).unwrap().unwrap().point_count, 0);
    assert!(s.track_points(t.id).unwrap().unwrap().is_empty());
}

#[test]
fn finished_and_unknown_tracks_take_no_points() {
    let mut s = store();
    let t = s.start_track(T0).unwrap();
    s.append_track_points(t.id, &ride(MS0, 3)).unwrap();
    let done = s.finish_track(t.id, T0 + 5).unwrap().unwrap();
    assert!(matches!(
        s.append_track_points(t.id, &ride(MS0 + 10_000, 3)),
        Err(CoreError::InvalidArgument(_))
    ));
    // Finishing again changes nothing.
    assert_eq!(s.finish_track(t.id, T0 + 99).unwrap().unwrap(), done);

    assert!(matches!(
        s.append_track_points(999, &ride(MS0, 3)),
        Err(CoreError::InvalidArgument(_))
    ));
    assert!(s.finish_track(999, T0).unwrap().is_none());
    assert!(s.get_track(999).unwrap().is_none());
    assert!(s.track_points(999).unwrap().is_none());
}

#[test]
fn batches_and_tracks_have_size_limits() {
    let mut s = store();
    let t = s.start_track(T0).unwrap();
    assert!(matches!(
        s.append_track_points(t.id, &ride(MS0, MAX_BATCH_POINTS + 1)),
        Err(CoreError::InvalidArgument(_))
    ));
    // A track just short of the limit (set directly: 200 000 inserts would
    // make a slow test).
    s.conn
        .execute(
            "UPDATE tracks SET point_count = ?2 WHERE id = ?1",
            params![t.id, (MAX_TRACK_POINTS - 2) as i64],
        )
        .unwrap();
    assert!(matches!(
        s.append_track_points(t.id, &ride(MS0, 3)),
        Err(CoreError::InvalidArgument(_))
    ));
    assert_eq!(s.append_track_points(t.id, &ride(MS0, 2)).unwrap().added, 2);
}

#[test]
fn lists_newest_first_and_deletes_with_points() {
    let mut s = store();
    let a = s.start_track(T0).unwrap();
    let b = s.start_track(T0 + 3600).unwrap();
    s.append_track_points(a.id, &ride(MS0, 5)).unwrap();
    let ids: Vec<i64> = s.list_tracks().unwrap().iter().map(|t| t.id).collect();
    assert_eq!(ids, [b.id, a.id]);
    // An unfinished track (the app died mid-ride) is listed as such.
    assert!(
        s.list_tracks()
            .unwrap()
            .iter()
            .all(|t| t.ended_at.is_none())
    );

    assert!(s.delete_track(a.id).unwrap());
    assert!(!s.delete_track(a.id).unwrap());
    let left: i64 = s
        .conn
        .query_row("SELECT count(*) FROM track_points", [], |r| r.get(0))
        .unwrap();
    assert_eq!(left, 0, "points go with their track");
}

#[test]
fn upgrades_a_schema_1_database_and_keeps_its_sections() {
    let mut conn = Connection::open_in_memory().unwrap();
    migrate(&mut conn, &MIGRATIONS[..1]).unwrap();
    conn.execute(
        "INSERT INTO sections (rider_id, name, rating, direction, source, status, created_at,
            updated_at, min_lat, min_lon, max_lat, max_lon, geometry)
         VALUES ('local', 'kept', 2, 0, 0, 0, 1, 1, 0, 0, 0, 0, x'')",
        [],
    )
    .unwrap();
    migrate(&mut conn, MIGRATIONS).unwrap();
    assert_eq!(user_version(&conn).unwrap(), SCHEMA_VERSION);
    const { assert!(SCHEMA_VERSION >= 2) };
    let name: String = conn
        .query_row("SELECT name FROM sections", [], |r| r.get(0))
        .unwrap();
    assert_eq!(name, "kept");
    let tracks: i64 = conn
        .query_row("SELECT count(*) FROM tracks", [], |r| r.get(0))
        .unwrap();
    assert_eq!(tracks, 0);
}

#[test]
fn damaged_track_rows_are_errors_not_panics() {
    let damage = [
        "UPDATE track_points SET lat = 5000000000",
        "UPDATE track_points SET lat = 950000000",
        "UPDATE track_points SET time_ms = -5",
        "UPDATE track_points SET bearing_deg = 400.0",
        "UPDATE track_points SET speed_mps = -1.0",
        "UPDATE tracks SET point_count = -1",
    ];
    for sql in damage {
        let mut s = store();
        let t = s.start_track(T0).unwrap();
        s.append_track_points(t.id, &ride(MS0, 3)).unwrap();
        // Some damage the schema itself refuses (CHECK constraints).
        if s.conn.execute_batch(sql).is_err() {
            continue;
        }
        let read = s.track_points(t.id);
        let got = s.get_track(t.id);
        assert!(
            matches!(read, Err(CoreError::Storage(_))) || matches!(got, Err(CoreError::Storage(_))),
            "{sql}: {read:?} {got:?}"
        );
    }
}
