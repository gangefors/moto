// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;

const T0: i64 = 1_790_000_000;

fn line(n: usize) -> Vec<LatLon> {
    (0..n)
        .map(|i| LatLon {
            lat: 55.7 + i as f64 * 1e-5,
            lon: 13.2,
        })
        .collect()
}

fn route(name: &str) -> NewRoute {
    NewRoute {
        name: name.into(),
        is_loop: false,
        distance_m: 52_300.0,
        duration_s: 2_700.0,
        geometry: line(10),
    }
}

#[test]
fn saves_lists_renames_and_deletes() {
    let mut s = Store::open_in_memory().unwrap();
    let a = s.save_route(&route("Kullaberg"), T0).unwrap();
    let b = s
        .save_route(
            &NewRoute {
                is_loop: true,
                ..route("Loop from home")
            },
            T0 + 60,
        )
        .unwrap();
    assert_eq!(
        (a.name.as_str(), a.is_loop, a.created_at),
        ("Kullaberg", false, T0)
    );
    assert_eq!(a.rider_id, LOCAL_RIDER);
    assert_eq!((a.distance_m, a.duration_s), (52_300.0, 2_700.0));
    assert!(b.is_loop);
    assert_eq!(s.list_routes().unwrap(), [b.clone(), a.clone()]);

    let g = s.route_geometry(a.id).unwrap().unwrap();
    assert_eq!(g.len(), 10);
    assert!((g[9].lat - 55.70009).abs() < 1e-7);

    assert!(s.rename_route(a.id, "Kullaberg twisties").unwrap());
    assert_eq!(
        s.get_route(a.id).unwrap().unwrap().name,
        "Kullaberg twisties"
    );
    assert!(!s.rename_route(999, "x").unwrap());

    assert!(s.delete_route(a.id).unwrap());
    assert!(!s.delete_route(a.id).unwrap());
    assert_eq!(s.get_route(a.id).unwrap(), None);
    assert_eq!(s.route_geometry(a.id).unwrap(), None);
    assert_eq!(s.list_routes().unwrap(), [b]);
}

#[test]
fn long_routes_fit() {
    let mut s = Store::open_in_memory().unwrap();
    let r = NewRoute {
        geometry: line(MAX_ROUTE_POINTS),
        ..route("long")
    };
    let saved = s.save_route(&r, T0).unwrap();
    assert_eq!(
        s.route_geometry(saved.id).unwrap().unwrap().len(),
        MAX_ROUTE_POINTS
    );
}

#[test]
fn bad_routes_are_refused() {
    let mut s = Store::open_in_memory().unwrap();
    let bad = [
        route(&"é".repeat(201)),
        route("a\u{0}b"),
        NewRoute {
            distance_m: f64::NAN,
            ..route("x")
        },
        NewRoute {
            duration_s: -1.0,
            ..route("x")
        },
        NewRoute {
            geometry: line(1),
            ..route("x")
        },
        NewRoute {
            geometry: line(MAX_ROUTE_POINTS + 1),
            ..route("x")
        },
        NewRoute {
            geometry: vec![
                LatLon {
                    lat: 91.0,
                    lon: 0.0
                };
                2
            ],
            ..route("x")
        },
    ];
    for r in bad {
        assert!(s.save_route(&r, T0).is_err(), "{:?}", r.name);
    }
    assert!(s.list_routes().unwrap().is_empty());
    let ok = s.save_route(&route("ok"), T0).unwrap();
    assert!(s.rename_route(ok.id, "a\nb").is_err());
    assert_eq!(s.get_route(ok.id).unwrap().unwrap().name, "ok");
}

#[test]
fn a_damaged_row_is_a_storage_error() {
    let mut s = Store::open_in_memory().unwrap();
    let r = s.save_route(&route("ok"), T0).unwrap();
    s.conn
        .execute("UPDATE routes SET geometry = x'0102' WHERE id = ?1", [r.id])
        .unwrap();
    assert!(matches!(s.route_geometry(r.id), Err(CoreError::Storage(_))));
    s.conn
        .execute(
            "UPDATE routes SET name = ?2 WHERE id = ?1",
            params![r.id, "a\u{0}"],
        )
        .unwrap();
    assert!(matches!(s.list_routes(), Err(CoreError::Storage(_))));
}

#[test]
fn saves_a_ride_as_a_route() {
    use crate::track::tests::fix;
    let mut s = Store::open_in_memory().unwrap();
    // Out about 2 km north and back: a loop, ending where it started.
    let mut points: Vec<_> = (0..20)
        .map(|i| fix(T0 * 1000 + i * 1000, 55.7 + i as f64 * 1e-3, 13.2))
        .collect();
    points.extend((0..20).map(|i| {
        fix(
            T0 * 1000 + (20 + i) * 1000,
            55.7 + (19 - i) as f64 * 1e-3,
            13.2005,
        )
    }));
    let ride = s.import_track(&points).unwrap();
    let saved = s
        .save_track_as_route(ride.id, "Evening ride", T0)
        .unwrap()
        .unwrap();
    assert_eq!(saved.name, "Evening ride");
    assert!(saved.is_loop);
    assert_eq!(saved.distance_m, ride.distance_m);
    assert_eq!(saved.duration_s, 39.0);
    let line = s.route_geometry(saved.id).unwrap().unwrap();
    assert_eq!(line.len(), points.len());
    assert!((line[5].lat - points[5].position.lat).abs() < 1e-7);

    // A one-way ride is a route, not a loop.
    let straight: Vec<_> = (0..30)
        .map(|i| fix(T0 * 1000 + i * 1000, 55.7 + i as f64 * 2e-4, 13.2))
        .collect();
    let one_way = s.import_track(&straight).unwrap();
    assert!(
        !s.save_track_as_route(one_way.id, "To work", T0)
            .unwrap()
            .unwrap()
            .is_loop
    );

    // A short ride ends near its start only because it never went
    // anywhere: about 200 m straight on is not a loop.
    let short: Vec<_> = (0..10)
        .map(|i| fix(T0 * 1000 + i * 1000, 55.7 + i as f64 * 2e-4, 13.2))
        .collect();
    let short = s.import_track(&short).unwrap();
    assert!(
        !s.save_track_as_route(short.id, "Round the block", T0)
            .unwrap()
            .unwrap()
            .is_loop
    );
    // Nor is out 400 m and back.
    let mut there_and_back: Vec<_> = (0..20)
        .map(|i| fix(T0 * 1000 + i * 1000, 55.7 + i as f64 * 2e-4, 13.2))
        .collect();
    there_and_back.extend((0..20).map(|i| {
        fix(
            T0 * 1000 + (20 + i) * 1000,
            55.7 + (19 - i) as f64 * 2e-4,
            13.2005,
        )
    }));
    let there_and_back = s.import_track(&there_and_back).unwrap();
    assert!(
        !s.save_track_as_route(there_and_back.id, "To the shop", T0)
            .unwrap()
            .unwrap()
            .is_loop
    );

    // No such ride; a ride still recording; a bad name.
    assert_eq!(s.save_track_as_route(999, "x", T0).unwrap(), None);
    let recording = s.start_track(T0).unwrap();
    assert!(s.save_track_as_route(recording.id, "x", T0).is_err());
    assert!(s.save_track_as_route(ride.id, "a\nb", T0).is_err());
}
