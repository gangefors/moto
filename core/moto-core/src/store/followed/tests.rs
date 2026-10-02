// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::track::tests::fix;

const T0: i64 = 1_790_000_000;

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn route() -> FollowedRoute {
    FollowedRoute {
        name: "Loop from Aby".into(),
        is_loop: true,
        duration_s: 3_600.0,
        line: vec![ll(57.0, 14.0), ll(57.01, 14.0), ll(57.02, 14.01)],
        parts: vec![vec![ll(57.0, 14.0), ll(57.01, 14.0)]],
        ratings: vec![Rating::Epic],
    }
}

/// Positions are stored in 1e-7 degrees: compare after that rounding.
fn rounded(r: &FollowedRoute) -> FollowedRoute {
    let round = |p: &LatLon| ll((p.lat * 1e7).round() / 1e7, (p.lon * 1e7).round() / 1e7);
    FollowedRoute {
        line: r.line.iter().map(round).collect(),
        parts: r
            .parts
            .iter()
            .map(|p| p.iter().map(round).collect())
            .collect(),
        ..r.clone()
    }
}

#[test]
fn keeps_and_clears_the_followed_route() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    assert_eq!(s.followed_route(t.id).unwrap(), None);
    s.set_followed_route(t.id, &route()).unwrap();
    assert_eq!(s.followed_route(t.id).unwrap(), Some(rounded(&route())));
    // Replacing keeps one.
    let other = FollowedRoute {
        name: "Aby → Bro".into(),
        is_loop: false,
        parts: vec![],
        ratings: vec![],
        ..route()
    };
    s.set_followed_route(t.id, &other).unwrap();
    assert_eq!(s.followed_route(t.id).unwrap(), Some(rounded(&other)));
    assert!(s.clear_followed_route(t.id).unwrap());
    assert!(!s.clear_followed_route(t.id).unwrap());
    assert_eq!(s.followed_route(t.id).unwrap(), None);
}

#[test]
fn finishing_or_deleting_the_ride_drops_the_route() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    s.set_followed_route(t.id, &route()).unwrap();
    s.finish_track(t.id, T0 + 60).unwrap();
    assert_eq!(s.followed_route(t.id).unwrap(), None);
    // Only a ride still recording can follow a route.
    assert!(s.set_followed_route(t.id, &route()).is_err());
    let u = s.start_track(T0).unwrap();
    s.set_followed_route(u.id, &route()).unwrap();
    s.delete_track(u.id).unwrap();
    assert_eq!(s.followed_route(u.id).unwrap(), None);
    assert!(s.set_followed_route(999, &route()).is_err());
}

#[test]
fn refuses_bad_routes() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    let bad = [
        FollowedRoute {
            line: vec![ll(57.0, 14.0)],
            ..route()
        },
        FollowedRoute {
            line: vec![ll(57.0, 14.0), ll(f64::NAN, 14.0)],
            ..route()
        },
        FollowedRoute {
            duration_s: -1.0,
            ..route()
        },
        FollowedRoute {
            ratings: vec![],
            ..route()
        },
        FollowedRoute {
            parts: vec![vec![ll(57.0, 14.0)]],
            ..route()
        },
        FollowedRoute {
            name: "line\nbreak".into(),
            ..route()
        },
    ];
    for r in &bad {
        assert!(s.set_followed_route(t.id, r).is_err(), "{r:?}");
    }
    assert_eq!(s.followed_route(t.id).unwrap(), None);
}

#[test]
fn corrupt_rows_are_errors_not_panics() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    s.set_followed_route(t.id, &route()).unwrap();
    for sql in [
        "UPDATE followed_routes SET geometry = x'0102'",
        "UPDATE followed_routes SET geometry = x'00000000'",
        "UPDATE followed_parts SET geometry = x'ffffff7fffffff7fffffff7fffffff7f'",
    ] {
        s.set_followed_route(t.id, &route()).unwrap();
        s.conn.execute_batch(sql).unwrap();
        assert!(s.followed_route(t.id).is_err(), "{sql}");
    }
    s.set_followed_route(t.id, &route()).unwrap();
    // A rating out of range can't even be written (CHECK); is_loop too.
    assert!(
        s.conn
            .execute_batch("UPDATE followed_parts SET rating = 9")
            .is_err()
    );
    assert!(
        s.conn
            .execute_batch("UPDATE followed_routes SET is_loop = 2")
            .is_err()
    );
    s.conn
        .execute_batch("UPDATE followed_routes SET duration_s = 1e400")
        .ok();
    let _ = s.followed_route(t.id);
}

#[test]
fn breaks_split_a_ride_into_segments() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    // No fixes yet: nothing to break.
    s.break_track(t.id).unwrap();
    s.append_track_points(t.id, &[fix(1_000, 57.0, 14.0), fix(2_000, 57.001, 14.0)])
        .unwrap();
    s.break_track(t.id).unwrap();
    s.break_track(t.id).unwrap(); // twice at the same place: once
    // The app was stopped: the rider is 5 km on when recording restarts.
    s.append_track_points(
        t.id,
        &[fix(600_000, 57.05, 14.0), fix(601_000, 57.051, 14.0)],
    )
    .unwrap();
    let segments = s.track_segments(t.id).unwrap().unwrap();
    assert_eq!(segments.len(), 2);
    assert_eq!(segments[0].len(), 2);
    assert_eq!(segments[1][0].time_ms, 600_000);
    // The distance leaves the gap out: two 111 m stretches.
    let done = s.finish_track(t.id, T0 + 700).unwrap().unwrap();
    assert!((done.distance_m - 222.0).abs() < 5.0, "{}", done.distance_m);
    assert!(s.break_track(t.id).is_err(), "finished");
    assert!(s.break_track(999).is_err());
    assert_eq!(s.track_segments(999).unwrap(), None);
}

#[test]
fn a_ride_without_breaks_is_one_segment() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    assert_eq!(s.track_segments(t.id).unwrap().unwrap().len(), 0);
    s.append_track_points(t.id, &[fix(1_000, 57.0, 14.0)])
        .unwrap();
    assert_eq!(s.track_segments(t.id).unwrap().unwrap().len(), 1);
}

#[test]
fn split_ignores_bad_breaks() {
    let points: Vec<TrackPoint> = (0..5).map(|i| fix(i * 1000, 57.0, 14.0)).collect();
    let lens =
        |b: &[i64]| -> Vec<usize> { split_at(points.clone(), b).iter().map(Vec::len).collect() };
    assert_eq!(lens(&[]), [5]);
    assert_eq!(lens(&[2]), [2, 3]);
    assert_eq!(lens(&[2, 2, 1, 4]), [2, 2, 1]);
    assert_eq!(lens(&[0, -3, 5, 99, i64::MAX]), [5]);
    assert!(split_at(Vec::new(), &[1]).is_empty());
}

#[test]
fn gpx_has_a_segment_per_part() {
    let mut s = Store::open_in_memory().unwrap();
    let t = s.start_track(T0).unwrap();
    s.append_track_points(t.id, &[fix(1_000, 57.0, 14.0), fix(2_000, 57.001, 14.0)])
        .unwrap();
    s.break_track(t.id).unwrap();
    s.append_track_points(t.id, &[fix(9_000, 57.05, 14.0)])
        .unwrap();
    let segments = s.track_segments(t.id).unwrap().unwrap();
    let gpx = crate::gpx::track_gpx_segments("Ride", &segments);
    assert_eq!(gpx.matches("<trkseg>").count(), 2);
    assert_eq!(gpx.matches("<trkpt").count(), 3);
}

#[test]
fn favourite_parts_come_from_the_sections_near_the_line() {
    use crate::section::tests::sample;
    let mut s = Store::open_in_memory().unwrap();
    // The sample section runs 56.30,12.45 → 56.31,12.46; a line along it.
    let n = sample();
    let line: Vec<LatLon> = (0..=40)
        .map(|i| {
            let f = f64::from(i) / 40.0;
            ll(56.30 + f * 0.01, 12.45 + f * 0.01)
        })
        .collect();
    assert!(s.favourite_parts_along(&line).unwrap().is_empty());
    s.add_section(&n, T0).unwrap();
    let parts = s.favourite_parts_along(&line).unwrap();
    assert_eq!(parts.len(), 1);
    assert_eq!(parts[0].rating, n.rating);
    // Far away: nothing.
    let elsewhere = vec![ll(58.0, 15.0), ll(58.01, 15.0)];
    assert!(s.favourite_parts_along(&elsewhere).unwrap().is_empty());
    assert!(s.favourite_parts_along(&[ll(58.0, 15.0)]).is_err());
}
