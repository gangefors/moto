// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::store::NewRoute;
use crate::track::tests::fix;
use rusqlite::params;

const T0: i64 = 1_790_000_000;

#[test]
fn reads_where_routes_and_rides_start() {
    let mut s = Store::open_in_memory().unwrap();
    let route = s
        .save_route(
            &NewRoute {
                name: "Out east".into(),
                is_loop: false,
                distance_m: 1_000.0,
                duration_s: 60.0,
                geometry: vec![
                    LatLon {
                        lat: 55.7,
                        lon: 13.2,
                    },
                    LatLon {
                        lat: 55.8,
                        lon: 13.3,
                    },
                ],
            },
            T0,
        )
        .unwrap();
    let points: Vec<_> = (0..5)
        .map(|i| fix(T0 * 1000 + i * 1000, 56.0 + i as f64 * 1e-3, 14.0))
        .collect();
    let ride = s.import_track(&points).unwrap();
    // A ride still without fixes has no start.
    s.start_track(T0).unwrap();

    let routes = s.route_starts().unwrap();
    assert_eq!(routes.len(), 1);
    assert_eq!(routes[0].id, route.id);
    assert!((routes[0].position.lat - 55.7).abs() < 1e-6);
    let tracks = s.track_starts().unwrap();
    assert_eq!(tracks.len(), 1);
    assert_eq!(tracks[0].id, ride.id);
    assert!((tracks[0].position.lat - 56.0).abs() < 1e-6);
    assert!((tracks[0].position.lon - 14.0).abs() < 1e-6);
}

#[test]
fn leaves_out_starts_stored_out_of_range() {
    let mut s = Store::open_in_memory().unwrap();
    let points: Vec<_> = (0..3)
        .map(|i| fix(T0 * 1000 + i * 1000, 56.0, 14.0 + i as f64 * 1e-3))
        .collect();
    let ride = s.import_track(&points).unwrap();
    s.conn
        .execute(
            "UPDATE track_points SET lat = ?2 WHERE track_id = ?1 AND seq = 0",
            params![ride.id, 1_000_000_000_i64 * 10],
        )
        .unwrap();
    assert!(s.track_starts().unwrap().is_empty());
}
