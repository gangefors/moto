// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use moto_core::fixture::{self, Road};
use moto_core::region::format::RoadClass;

/// A town: an `n` × `n` grid of 40 km/h streets 100 m apart (south-west
/// corner at 55.70, 13.40), plus a 70 km/h road from its north-east
/// corner 10 km east into open country. The country road is the last
/// road. From about 45 × 45 it is a city (see `MIN_AREA_KM2`).
fn town_and_country(n: u32) -> RegionData {
    let mut nodes: Vec<(f64, f64)> = (0..n * n)
        .map(|i| {
            (
                55.70 + f64::from(i / n) * 0.0009,
                13.40 + f64::from(i % n) * 0.0016,
            )
        })
        .collect();
    let mut roads = Vec::new();
    for r in 0..n {
        for c in 0..n {
            let i = r * n + c;
            let way = 1000 + i64::from(roads.len() as u32);
            if c + 1 < n {
                roads.push(Road::new(i, i + 1, RoadClass::Residential, 40, way));
            }
            if r + 1 < n {
                roads.push(Road::new(i, i + n, RoadClass::Residential, 40, way + 1));
            }
        }
    }
    let corner = n * n - 1;
    let (lat, lon) = nodes[corner as usize];
    nodes.push((lat, lon + 0.16));
    roads.push(Road::new(corner, n * n, RoadClass::Tertiary, 70, 9));
    fixture::build(&nodes, &roads, 50_000)
}

fn built_up(data: &RegionData, geometry: u32) -> bool {
    data.edges
        .iter()
        .filter(|e| e.geometry == geometry)
        .all(|e| e.flags & edge_flags::BUILT_UP != 0)
}

#[test]
fn towns_are_built_up_and_open_country_is_not() {
    let mut town = town_and_country(50);
    let stats = finish(&mut town);
    // 48 × 48 inner crossings plus the edge ones meet three roads.
    assert!(stats.junctions >= 48 * 48, "{stats:?}");
    let country = town.geometry_offsets.len() as u32 - 2;
    let centre = 50 * 50 / 2 + 25;
    let middle = town
        .edges
        .iter()
        .find(|e| e.tail == centre)
        .unwrap()
        .geometry;
    assert!(built_up(&town, middle), "a street in the middle of town");
    assert!(!built_up(&town, country), "the road out of town");
    assert!(stats.built_up_m > 0.0 && stats.built_up_m < stats.road_m);
    // Both directions of a road agree.
    for e in &town.edges {
        let twin = town
            .edges
            .iter()
            .find(|t| t.geometry == e.geometry && t.tail == e.head);
        if let Some(t) = twin {
            assert_eq!(
                t.flags & edge_flags::BUILT_UP,
                e.flags & edge_flags::BUILT_UP
            );
        }
    }

    // A town of 1.4 × 1.4 km is dense but too small: towns and villages
    // on the way are fine.
    let mut small = town_and_country(15);
    let stats = finish(&mut small);
    assert!(stats.junctions >= 13 * 13);
    assert_eq!(stats.built_up_m, 0.0);

    // Roads 1 km apart: open country, junctions or not.
    let mut grid = fixture::grid(13);
    let stats = finish(&mut grid);
    assert_eq!(stats.junctions, 11 * 11 + 4 * 11);
    assert_eq!(stats.built_up_m, 0.0);
    assert!(
        grid.edges
            .iter()
            .all(|e| e.flags & edge_flags::BUILT_UP == 0)
    );
}

#[test]
fn finishing_is_idempotent_and_clears_stale_flags() {
    let mut grid = fixture::grid(5);
    for e in &mut grid.edges {
        e.flags |= edge_flags::BUILT_UP;
    }
    finish(&mut grid);
    assert!(
        grid.edges
            .iter()
            .all(|e| e.flags & edge_flags::BUILT_UP == 0)
    );
    let mut town = town_and_country(50);
    finish(&mut town);
    assert!(
        town.edges
            .iter()
            .any(|e| e.flags & edge_flags::BUILT_UP != 0)
    );
    let once = town.clone();
    finish(&mut town);
    assert_eq!(town, once);
}

#[test]
fn curvature_is_measured_between_junctions() {
    // A 30 m corner right at a junction (B, where A–B, B–C and B–D meet)
    // and the same corner where one road simply continues into the next
    // (E, where only D–E and E–F meet).
    let nodes = [
        (55.70, 13.40),
        (55.70, 13.41),
        (55.71, 13.41),
        (55.69, 13.41),
        (55.69, 13.43),
        (55.69, 13.45),
    ];
    let corner = |from: (f64, f64)| {
        // A quarter circle of about 20 m radius leaving `from` eastwards.
        (1..6)
            .map(|i| {
                let t = f64::from(i) * 18f64.to_radians();
                (
                    from.0 + 0.00018 * (1.0 - t.cos()),
                    from.1 + 0.00032 * t.sin(),
                )
            })
            .collect::<Vec<_>>()
    };
    let mut ab = Road::new(0, 1, RoadClass::Tertiary, 70, 1);
    ab.via = corner(nodes[0])
        .into_iter()
        .rev()
        .map(|(la, lo)| (la, lo + 0.0095 - 0.00032))
        .collect();
    let bc = Road::new(1, 2, RoadClass::Tertiary, 70, 2);
    let bd = Road::new(1, 3, RoadClass::Tertiary, 70, 3);
    let mut de = Road::new(3, 4, RoadClass::Tertiary, 70, 4);
    de.via = corner(nodes[3])
        .into_iter()
        .map(|(la, lo)| (la - 0.00036, lo + 0.01))
        .collect();
    let mut ef = Road::new(4, 5, RoadClass::Tertiary, 70, 5);
    ef.via = corner(nodes[4]);
    let mut data = fixture::build(&nodes, &[ab, bc, bd, de, ef], 50_000);
    finish(&mut data);
    for (g, e) in data.edges.iter().enumerate().map(|(i, e)| (e.geometry, i)) {
        let line = geometry(&data, g);
        let j = junctions(&data);
        let (s, t) = {
            let edge = data.edges[e];
            if edge.flags & edge_flags::REVERSED == 0 {
                (edge.tail, edge.head)
            } else {
                (edge.head, edge.tail)
            }
        };
        assert_eq!(
            data.curvature[e],
            curvature_between_junctions(&line, j[s as usize], j[t as usize]),
            "edge {e}"
        );
    }
    let j = junctions(&data);
    assert!(j[1], "B is a junction");
    assert!(!j[4], "E only joins two roads");
}

#[test]
fn a_region_file_refreshes_to_the_same_content() {
    let mut data = town_and_country(6);
    finish(&mut data);
    let bytes = data.to_bytes().unwrap();
    let region = Region::from_bytes(&bytes).unwrap();
    let mut again = data_of(&region);
    assert_eq!(again, data);
    finish(&mut again);
    assert_eq!(again.to_bytes().unwrap(), bytes);
}

#[test]
fn bad_offsets_never_panic() {
    let mut data = town_and_country(4);
    data.geometry_offsets.truncate(2);
    finish(&mut data);
    let mut data = town_and_country(4);
    data.edges[0].geometry = u32::MAX;
    data.edges[0].tail = u32::MAX;
    finish(&mut data);
    finish(&mut RegionData::default());
}
