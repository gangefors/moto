// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::favourites::Favourites;
use crate::fixture;
use crate::region::Region;
use crate::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status, WaySpan};

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn fork() -> Engine {
    Engine::from_region(Region::from_bytes(&fixture::fork().to_bytes().unwrap()).unwrap())
}

const FROM: LatLon = LatLon {
    lat: 55.70,
    lon: 13.395,
};
const TO: LatLon = LatLon {
    lat: 55.70,
    lon: 13.445,
};

/// The route over the north loop, marked epic.
fn north_route(e: &Engine) -> crate::Route {
    let s = Section {
        id: 1,
        rider_id: LOCAL_RIDER.into(),
        name: String::new(),
        rating: Rating::Epic,
        direction: Direction::Both,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: vec![WaySpan {
            way_id: fixture::FORK_NORTH,
            from_idx: 0,
            to_idx: 3,
        }],
        geometry: vec![ll(55.70, 13.40), ll(55.70, 13.44)],
    };
    let fav = Favourites::build(e, &[s]);
    assert_eq!(fav.edge_count(), 6);
    // The loop takes 41 % longer than the south road.
    let opts = RouteOptions {
        budget: crate::TimeBudget::Extra(1.0),
        ..RouteOptions::default()
    };
    let r = e.route_with(FROM, TO, &opts, &fav).unwrap();
    assert!(r.geometry.iter().any(|p| p.lat > 55.705), "{r:?}");
    r
}

#[test]
fn the_fastest_route_needs_only_its_ends() {
    let e = fork();
    let r = e.route(FROM, TO, &RouteOptions::default()).unwrap();
    let points = route_points(&e, &r.geometry, &RouteOptions::default()).unwrap();
    assert_eq!(points, [r.geometry[0], *r.geometry.last().unwrap()]);
}

#[test]
fn a_detour_gets_points_that_keep_every_leg_on_it() {
    let e = fork();
    let r = north_route(&e);
    let opts = RouteOptions::default();
    let points = route_points(&e, &r.geometry, &opts).unwrap();
    assert!(points.len() >= 3, "{points:?}");
    assert_eq!(points[0], r.geometry[0]);
    assert_eq!(points.last(), r.geometry.last());
    // Routing fastest between neighbours never leaves the north loop.
    for w in points.windows(2) {
        let leg = e.route(w[0], w[1], &opts).unwrap();
        for p in &leg.geometry {
            assert!(
                distance_to_line(*p, &r.geometry) < 1.0,
                "{p:?} off the route"
            );
        }
    }
}

#[test]
fn bad_lines_are_refused() {
    let e = fork();
    let opts = RouteOptions::default();
    for bad in [
        vec![],
        vec![FROM],
        vec![FROM, ll(95.0, 13.0)],
        vec![FROM, ll(f64::NAN, 13.0)],
    ] {
        assert!(matches!(
            route_points(&e, &bad, &opts),
            Err(CoreError::InvalidArgument(_) | CoreError::InvalidCoordinate { .. })
        ));
    }
    let long = vec![FROM; MAX_LINE_POINTS + 1];
    assert!(route_points(&e, &long, &opts).is_err());
}

#[test]
fn lines_off_the_roads_still_end_where_they_end() {
    // A line the router can't follow (partly far outside the region):
    // points still come out, in order, capped, ending at the end.
    let e = fork();
    let line: Vec<LatLon> = (0..300)
        .map(|i| {
            ll(
                55.70 + f64::from(i % 3) * 0.3,
                13.39 + f64::from(i) * 0.0002,
            )
        })
        .collect();
    let points = route_points(&e, &line, &RouteOptions::default()).unwrap();
    assert!(points.len() <= MAX_ROUTE_POINTS, "{}", points.len());
    assert_eq!(points[0], line[0]);
    assert_eq!(points.last(), line.last());
}

#[test]
fn long_routes_get_a_point_at_least_every_leg() {
    // A 30 km straight road: the fastest route follows it all the way, but
    // legs stay within the cap.
    use crate::fixture::{Road, build};
    use crate::region::format::RoadClass;
    let nodes = [(55.70, 13.0), (55.70, 13.48)];
    let road = Road {
        via: (1..48)
            .map(|i| (55.70, 13.0 + f64::from(i) * 0.01))
            .collect(),
        ..Road::new(0, 1, RoadClass::Primary, 90, 7)
    };
    let e = Engine::from_region(
        Region::from_bytes(&build(&nodes, &[road], 50_000).to_bytes().unwrap()).unwrap(),
    );
    let r = e
        .route(ll(55.70, 13.0), ll(55.70, 13.48), &RouteOptions::default())
        .unwrap();
    assert!(r.distance_m > 29_000.0, "{r:?}");
    let points = route_points(&e, &r.geometry, &RouteOptions::default()).unwrap();
    assert!((4..=6).contains(&points.len()), "{}", points.len());
    for w in points.windows(2) {
        assert!(haversine_m(w[0], w[1]) <= MAX_LEG_M + 700.0, "{w:?}");
    }
}
