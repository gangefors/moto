// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture;

fn region(named: bool) -> Region {
    let data = if named {
        fixture::named_region()
    } else {
        fixture::region()
    };
    Region::from_bytes(&data.to_bytes().unwrap()).unwrap()
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

#[test]
fn names_the_road_and_the_places_at_each_end() {
    // A to D along road 13 (Storgatan, then Landsvägen, as long).
    let d = describe(&region(true), &[ll(55.70, 13.20), ll(55.70, 13.22)]).unwrap();
    assert_eq!(d.roads.len(), 1, "{d:?}");
    let road = &d.roads[0];
    assert_eq!(road.road_ref.as_deref(), Some("13"));
    assert_eq!(
        road.name.as_deref(),
        Some("Storgatan"),
        "ties: the first name"
    );
    assert!(road.share > 0.99, "{road:?}");
    let start = d.start.unwrap();
    assert_eq!((start.name.as_str(), start.kind), ("Lund", PlaceKind::Town));
    assert!((start.distance_m - 838.0).abs() < 10.0, "{start:?}");
    assert_eq!(d.end.unwrap().name, "Dalby");
}

#[test]
fn names_two_roads_the_longer_first() {
    // A–B on 13 (about 630 m), then B–C on Bergsvägen (about 1.1 km).
    let line = [
        ll(55.70, 13.20),
        ll(55.70, 13.21),
        ll(55.705, 13.212),
        ll(55.71, 13.21),
    ];
    let d = describe(&region(true), &line).unwrap();
    let names: Vec<(Option<&str>, Option<&str>)> = d
        .roads
        .iter()
        .map(|r| (r.road_ref.as_deref(), r.name.as_deref()))
        .collect();
    assert_eq!(
        names,
        [(None, Some("Bergsvägen")), (Some("13"), Some("Storgatan"))]
    );
    assert!(d.roads[0].share > d.roads[1].share);
    assert!((d.roads[0].share + d.roads[1].share - 1.0).abs() < 1e-9);
}

#[test]
fn a_bigger_place_wins_unless_a_smaller_one_is_much_nearer() {
    let r = region(true);
    // Halfway between Lund (town) and Dalby (village): Lund.
    assert_eq!(nearest_place(&r, ll(55.70, 13.21)).unwrap().name, "Lund");
    // Right by Dalby: Dalby.
    assert_eq!(nearest_place(&r, ll(55.70, 13.229)).unwrap().name, "Dalby");
    // 2.8 km from the hamlet Ö is beyond a hamlet's reach, 600 m isn't;
    // nothing is near a point 30 km out.
    assert!(nearest_place(&r, ll(55.58, 13.14)).is_none());
    assert_eq!(nearest_place(&r, ll(55.60, 13.11)).unwrap().name, "Ö");
    assert!(nearest_place(&r, ll(56.0, 13.2)).is_none());
}

#[test]
fn off_the_roads_and_without_names_there_is_nothing_to_say() {
    // Far from any road: no roads, but places still name the ends.
    let d = describe(&region(true), &[ll(55.69, 13.20), ll(55.69, 13.21)]).unwrap();
    assert!(d.roads.is_empty(), "{d:?}");
    assert_eq!(d.start.unwrap().name, "Lund");
    // A file without names (format 1.1): no words, only curviness.
    let d = describe(&region(false), &[ll(55.70, 13.20), ll(55.70, 13.22)]).unwrap();
    assert_eq!(d, Description::default(), "straight roads: 0 curvy");
}

#[test]
fn bad_lines_are_refused() {
    let r = region(true);
    assert!(describe(&r, &[ll(55.7, 13.2)]).is_err());
    assert!(describe(&r, &[ll(55.7, 13.2), ll(f64::NAN, 13.2)]).is_err());
    let long = vec![ll(55.7, 13.2); MAX_SECTION_POINTS + 1];
    assert!(describe(&r, &long).is_err());
    // A line of one repeated point is described, not a panic.
    describe(&r, &[ll(55.7, 13.2), ll(55.7, 13.2)]).unwrap();
}

#[test]
fn samples_spread_evenly_along_the_line() {
    let line = [ll(55.70, 13.20), ll(55.70, 13.21), ll(55.70, 13.22)];
    let s = samples(&line);
    assert_eq!(s.len(), 9, "1.26 km at one per 150 m");
    assert!(s.windows(2).all(|w| w[1].lon > w[0].lon));
    assert!((s[0].lon - 13.20).abs() < 0.002 && (s[8].lon - 13.22).abs() < 0.002);
    // Short lines still get a few samples; long ones at most MAX_SAMPLES.
    assert_eq!(
        samples(&[ll(55.7, 13.2), ll(55.7, 13.2001)]).len(),
        MIN_SAMPLES
    );
    assert_eq!(
        samples(&[ll(55.0, 13.0), ll(56.0, 13.0)]).len(),
        MAX_SAMPLES
    );
}

#[test]
fn road_numbers_read_as_signed() {
    assert_eq!(signed_ref("M 1121"), "1121");
    assert_eq!(signed_ref("E 22"), "E22");
    assert_eq!(signed_ref("AC 364"), "364");
    assert_eq!(signed_ref("13"), "13");
    assert_eq!(signed_ref("E22"), "E22");
    assert_eq!(signed_ref("Kungsleden 3"), "Kungsleden 3");
    assert_eq!(signed_ref("M 11a"), "M 11a");
    assert_eq!(signed_ref("M "), "M ");
}

#[test]
fn hamlets_only_when_nothing_bigger_is_in_reach() {
    let mut data = fixture::named_region();
    // A hamlet right by A, where the town Lund is 840 m away: Lund.
    data.names.strings.push("Kvärnby".into());
    let name = data.names.strings.len() as u32 - 1;
    data.names.places.push(crate::region::format::Place {
        pos: crate::region::format::PointE7 {
            lat: 557_010_000,
            lon: 132_000_000,
        },
        name,
        kind: PlaceKind::Hamlet as u8,
        reserved: [0; 3],
    });
    data.names.places.sort_by_key(|p| p.pos.lat);
    let r = Region::from_bytes(&data.to_bytes().unwrap()).unwrap();
    assert_eq!(nearest_place(&r, ll(55.70, 13.20)).unwrap().name, "Lund");
}

#[test]
fn curviness_is_measured_like_a_routes() {
    use crate::fixture::Road;
    use crate::region::format::RoadClass;
    // A zigzag secondary road with a bend every 50 m, then a straight one.
    let via = (1..20)
        .map(|i| {
            (
                55.70 + if i % 2 == 0 { 0.0 } else { 0.0003 },
                13.20 + f64::from(i) * 0.0005,
            )
        })
        .collect();
    let zigzag = Road {
        via,
        ..Road::new(0, 1, RoadClass::Secondary, 70, 1)
    };
    let straight = Road::new(1, 2, RoadClass::Secondary, 70, 2);
    let data = fixture::build(
        &[(55.70, 13.20), (55.70, 13.21), (55.70, 13.22)],
        &[zigzag, straight],
        5_000,
    );
    let r = Region::from_bytes(&data.to_bytes().unwrap()).unwrap();
    let curvy = describe(&r, &[ll(55.7001, 13.2005), ll(55.7001, 13.2095)])
        .unwrap()
        .curvy_share;
    let flat = describe(&r, &[ll(55.70, 13.2105), ll(55.70, 13.2195)])
        .unwrap()
        .curvy_share;
    let both = describe(
        &r,
        &[ll(55.7001, 13.2005), ll(55.70, 13.21), ll(55.70, 13.2195)],
    )
    .unwrap()
    .curvy_share;
    assert!(curvy > 0.5, "{curvy}");
    assert_eq!(flat, 0.0);
    assert!(both > flat && both < curvy, "{both}");
}
