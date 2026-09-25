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
