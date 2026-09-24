// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture::{self, L_N};
use crate::region::format::Surface;
use crate::section::WaySpan;
use crate::tag::TagStatus;

fn engine(data: crate::region::RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn span(way_id: i64, from_idx: u32, to_idx: u32) -> WaySpan {
    WaySpan {
        way_id,
        from_idx,
        to_idx,
    }
}

const MS0: i64 = 1_790_000_000_000;

fn tag(position: LatLon, heading_deg: Option<f64>) -> Tag {
    Tag {
        id: 1,
        rider_id: "local".into(),
        time_ms: MS0,
        position,
        heading_deg,
        speed_mps: Some(20.0),
        track_id: None,
        status: TagStatus::Pending,
    }
}

#[test]
fn follows_the_road_straight_on_through_a_junction() {
    // Heading east on A–B: behind to A (313 m, a dead end), ahead through
    // B straight on along B→D to D (the bend north to C turns too sharply).
    let e = engine(fixture::region());
    let d = e
        .suggest_from_tag(&tag(ll(55.7001, 13.205), Some(90.0)), None)
        .unwrap();
    assert_eq!(d.ways, [span(100, 0, 1), span(300, 0, 1)]);
    assert!((d.geometry[0].lon - 13.20).abs() < 1e-9);
    assert!((d.geometry.last().unwrap().lon - 13.22).abs() < 1e-9);
}

#[test]
fn heading_sets_the_direction() {
    // Westwards: from B (nothing leads into it straight from the east, B→D
    // is one-way away from it) to A, recorded against the way's direction.
    let e = engine(fixture::region());
    let d = e
        .suggest_from_tag(&tag(ll(55.7001, 13.205), Some(265.0)), None)
        .unwrap();
    assert_eq!(d.ways, [span(100, 1, 0)]);
    assert!(d.geometry[0].lon > d.geometry.last().unwrap().lon);
    // Without a heading the road's own direction is used.
    let d = e
        .suggest_from_tag(&tag(ll(55.7001, 13.205), None), None)
        .unwrap();
    assert_eq!(d.ways[0], span(100, 0, 1));
}

#[test]
fn takes_about_a_kilometre_each_way() {
    // The ladder's north road is one OSM way over 2.5 km; tagged in the
    // middle, heading east.
    let e = engine(fixture::ladder(Surface::Asphalt));
    let n = e.region().nodes()[L_N as usize];
    let at = ll(f64::from(n.lat) / 1e7, f64::from(n.lon) / 1e7 + 0.0005);
    let d = e.suggest_from_tag(&tag(at, Some(90.0)), None).unwrap();
    assert!((d.distance_m - 2.0 * TAG_REACH_M).abs() < 1.0, "{d:?}");
    assert_eq!(d.ways, [span(1, 0, 2)]);
}

#[test]
fn tags_far_from_roads_are_errors() {
    let e = engine(fixture::region());
    assert!(matches!(
        e.suggest_from_tag(&tag(ll(55.7145, 13.2245), Some(0.0)), None),
        Err(CoreError::NoRoadNearby { .. })
    ));
    assert!(matches!(
        e.suggest_from_tag(&tag(ll(f64::NAN, 13.2), None), None),
        Err(CoreError::InvalidCoordinate { .. })
    ));
}

/// A ride from near A east to B, then north round the bend to C: a fix a
/// second, about 20 m apart.
fn ride_to_c() -> Vec<TrackPoint> {
    let line = [
        ll(55.70, 13.2005),
        ll(55.70, 13.21),
        ll(55.705, 13.212),
        ll(55.7098, 13.2101),
    ];
    let total = crate::geo::polyline_length_m(&line);
    let n = (total / 20.0) as i64;
    (0..=n)
        .map(|i| {
            let frac = (i as f64 * 20.0 / total).min(1.0);
            let p = *crate::geo::polyline_slice(&line, 0.0, frac).last().unwrap();
            TrackPoint {
                time_ms: MS0 + i * 1000,
                position: p,
                accuracy_m: Some(5.0),
                speed_mps: Some(20.0),
                bearing_deg: None,
            }
        })
        .collect()
}

#[test]
fn uses_the_ride_actually_taken() {
    // Tagged just before B with a heading pointing straight on (to D), but
    // the ride turned north to C: the track wins.
    let e = engine(fixture::region());
    let track = ride_to_c();
    let k = track.iter().position(|p| p.position.lon >= 13.209).unwrap();
    let t = Tag {
        time_ms: track[k].time_ms,
        ..tag(track[k].position, Some(90.0))
    };
    let d = e.suggest_from_tag(&t, Some(&track)).unwrap();
    assert_eq!(
        d.ways.iter().map(|w| w.way_id).collect::<Vec<_>>(),
        [100, 200],
        "{d:?}"
    );
    // Without the track, the road is followed straight on to D.
    let d = e.suggest_from_tag(&t, None).unwrap();
    assert_eq!(
        d.ways.iter().map(|w| w.way_id).collect::<Vec<_>>(),
        [100, 300],
        "{d:?}"
    );
}

#[test]
fn a_track_without_fixes_near_the_tag_is_not_used() {
    let e = engine(fixture::region());
    let track = ride_to_c();
    let k = track.iter().position(|p| p.position.lon >= 13.209).unwrap();
    let late = Tag {
        time_ms: track.last().unwrap().time_ms + 5 * 60_000,
        ..tag(track[k].position, Some(90.0))
    };
    let d = e.suggest_from_tag(&late, Some(&track)).unwrap();
    assert_eq!(
        d.ways.iter().map(|w| w.way_id).collect::<Vec<_>>(),
        [100, 300]
    );
    // An empty track falls back too.
    assert!(e.suggest_from_tag(&late, Some(&[])).is_ok());
}

#[test]
fn bearings_and_turns() {
    assert!((bearing(ll(55.0, 13.0), ll(55.1, 13.0)) - 0.0).abs() < 1e-9);
    assert!((bearing(ll(55.0, 13.0), ll(55.0, 13.1)) - 90.0).abs() < 1e-9);
    assert!((bearing(ll(55.0, 13.0), ll(54.9, 13.0)) - 180.0).abs() < 1e-9);
    assert!((bearing(ll(55.0, 13.0), ll(55.0, 12.9)) - 270.0).abs() < 1e-9);
    assert_eq!(turn(10.0, 350.0), 20.0);
    assert_eq!(turn(90.0, 270.0), 180.0);
    assert_eq!(turn(0.0, 0.0), 0.0);
}
