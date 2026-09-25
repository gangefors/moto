// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture::{self, E_AB, E_BD, Road};
use crate::region::format::edge_flags;
use crate::region::{Region, RegionData};
use crate::{Engine, LatLon};

fn engine(data: RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

#[test]
fn a_two_way_road_is_described() {
    let e = engine(fixture::region());
    let info = e.road_at(ll(55.7002, 13.205)).unwrap();
    assert_eq!(info.point.edge / 2, E_AB / 2, "{info:?}");
    assert_eq!(info.class, Some(RoadClass::Tertiary));
    assert_eq!(info.surface, Some(Surface::Asphalt));
    assert_eq!(info.speed_kmh, 70);
    assert!(!info.one_way);
    assert!(!info.toll && !info.ferry && !info.destination_only);
    assert_eq!(info.way_id, 100);
    // 0.01° of longitude at 55.7° N is about 628 m.
    assert!((info.length_m - 628.0).abs() < 5.0, "{}", info.length_m);
    assert_eq!(info.curviness, 0.0, "a straight road");
}

#[test]
fn a_one_way_road_says_so() {
    let e = engine(fixture::region());
    let info = e.road_at(ll(55.7001, 13.215)).unwrap();
    assert_eq!(info.point.edge, E_BD);
    assert!(info.one_way);
    assert_eq!(info.class, Some(RoadClass::Primary));
    assert_eq!(info.way_id, 300);
}

#[test]
fn a_winding_road_is_curvy() {
    // A zigzag with a bend about every 50 m: tight turns all the way.
    let via = (1..20)
        .map(|i| {
            (
                55.70 + if i % 2 == 0 { 0.0 } else { 0.0003 },
                13.20 + f64::from(i) * 0.0005,
            )
        })
        .collect();
    let road = Road {
        via,
        ..Road::new(0, 1, RoadClass::Secondary, 70, 9)
    };
    let e = engine(fixture::build(
        &[(55.70, 13.20), (55.70, 13.21)],
        &[road],
        50_000,
    ));
    let info = e.road_at(ll(55.70, 13.2025)).unwrap();
    assert_eq!(info.way_id, 9);
    assert!(info.curviness > 0.5, "{info:?}");
    assert!(info.curviness <= 1.0);
}

#[test]
fn gravel_is_reported() {
    let e = engine(fixture::ladder(Surface::Gravel));
    let info = e.road_at(ll(55.7201, 13.43)).unwrap();
    assert_eq!(info.surface, Some(Surface::Gravel));
    assert_eq!(info.class, Some(RoadClass::Residential));
    assert_eq!(info.way_id, 1);
}

#[test]
fn flags_are_reported() {
    let road = Road {
        flags: edge_flags::TOLL | edge_flags::DESTINATION,
        ..Road::new(0, 1, RoadClass::Secondary, 80, 7)
    };
    let e = engine(fixture::build(
        &[(55.70, 13.20), (55.70, 13.21)],
        &[road],
        50_000,
    ));
    let info = e.road_at(ll(55.7001, 13.205)).unwrap();
    assert!(info.toll && info.destination_only && !info.ferry);
}

#[test]
fn unknown_class_and_surface_are_none() {
    let mut data = fixture::region();
    for e in &mut data.edges {
        e.class = 200;
        e.surface = 200;
    }
    let e = engine(data);
    let info = e.road_at(ll(55.7002, 13.205)).unwrap();
    assert_eq!(info.class, None);
    assert_eq!(info.surface, None);
    assert_eq!(info.curviness, 0.0);
}

#[test]
fn a_point_far_from_roads_is_a_typed_error() {
    let e = engine(fixture::region());
    assert!(matches!(
        e.road_at(ll(10.0, 10.0)),
        Err(CoreError::OutsideRegion { .. })
    ));
    assert!(e.road_at(ll(f64::NAN, 13.2)).is_err());
}

#[test]
fn a_bad_edge_is_a_typed_error() {
    let e = engine(fixture::region());
    let mut p = e.snap(ll(55.7002, 13.205)).unwrap();
    p.edge = u32::MAX;
    assert!(matches!(
        road_info(e.region(), p),
        Err(CoreError::Region(_))
    ));
}
