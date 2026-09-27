// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::fixture::{self, Road};
use crate::region::format::{COORD_SCALE, section};
use crate::region::{Region, RegionData};
use crate::{CoreError, Engine, LatLon};

fn p(lat: f64, lon: f64) -> PointE7 {
    PointE7 {
        lat: (lat * COORD_SCALE).round() as i32,
        lon: (lon * COORD_SCALE).round() as i32,
    }
}

/// A square road 0.3° across around a lake near Lund, a short road on
/// Gotland, and a ferry between them.
fn lake_and_island() -> RegionData {
    let nodes = [
        (55.6, 13.1),
        (55.6, 13.6),
        (55.9, 13.6),
        (55.9, 13.1),
        (57.50, 18.30),
        (57.52, 18.33),
    ];
    let mut roads: Vec<Road> = (0..4)
        .map(|i| Road::new(i, (i + 1) % 4, RoadClass::Tertiary, 70, 100 + i64::from(i)))
        .collect();
    roads.push(Road::new(4, 5, RoadClass::Tertiary, 70, 200));
    roads.push(Road::new(1, 4, RoadClass::Ferry, 20, 300));
    fixture::build(&nodes, &roads, 50_000)
}

fn signed_area(ring: &[PointE7]) -> f64 {
    ring.windows(2)
        .map(|w| {
            f64::from(w[0].lon) * f64::from(w[1].lat) - f64::from(w[1].lon) * f64::from(w[0].lat)
        })
        .sum::<f64>()
        / 2.0
}

#[test]
fn outlines_each_island_and_fills_the_lake() {
    let d = lake_and_island();
    let (offsets, points) = trace(&d.edges, &d.geometry_offsets, &d.shape_points, d.info.bbox);
    assert_eq!(
        offsets.len(),
        3,
        "two outlines: the lake road and the island"
    );
    for w in offsets.windows(2) {
        let ring = &points[w[0] as usize..w[1] as usize];
        assert!(ring.len() >= 4 && ring.first() == ring.last(), "{ring:?}");
        assert!(signed_area(ring) > 0.0, "counter-clockwise");
    }
    let inside = |lat, lon| contains(&offsets, &points, p(lat, lon));
    // On the roads, and in the lake the road goes round.
    assert!(inside(55.6, 13.3));
    assert!(inside(55.75, 13.35));
    assert!(inside(57.51, 18.31));
    // Just past the road still counts (up to the region's box, 500 m
    // out here); 10 km out doesn't.
    assert!(inside(55.597, 13.3));
    assert!(!inside(55.5, 13.3));
    // Out at sea under the ferry line.
    assert!(!inside(56.55, 15.95));
    // Simplified: far fewer corners than the cell staircase.
    assert!(points.len() < 100, "{}", points.len());
}

#[test]
fn no_roads_no_outline() {
    let (offsets, points) = trace(&[], &[0], &[], BBoxE7::default());
    assert_eq!((offsets, points), (vec![0], vec![]));
}

#[test]
fn the_same_roads_give_the_same_outline() {
    let d = lake_and_island();
    let a = trace(&d.edges, &d.geometry_offsets, &d.shape_points, d.info.bbox);
    let b = trace(&d.edges, &d.geometry_offsets, &d.shape_points, d.info.bbox);
    assert_eq!(a, b);
}

#[test]
fn outlines_stay_in_the_bounding_box() {
    let d = lake_and_island();
    let b = d.info.bbox;
    let (_, points) = trace(&d.edges, &d.geometry_offsets, &d.shape_points, b);
    assert!(
        points
            .iter()
            .all(|q| (b.min_lat..=b.max_lat).contains(&q.lat)
                && (b.min_lon..=b.max_lon).contains(&q.lon))
    );
}

#[test]
fn the_region_file_carries_the_outline() {
    let bytes = lake_and_island().to_bytes().unwrap();
    let region = Region::from_bytes(&bytes).unwrap();
    assert_eq!(region.coverage().len(), 2);
    let engine = Engine::from_region(region);
    let rings = engine.coverage();
    assert_eq!(rings.len(), 2);
    assert_eq!(
        engine.covers(LatLon {
            lat: 55.75,
            lon: 13.35
        }),
        Some(true)
    );
    // Inside the bounding box but nowhere near the roads: outside the
    // region, not merely "no road nearby".
    let sea = LatLon {
        lat: 57.0,
        lon: 14.5,
    };
    assert!(matches!(
        engine.snap(sea),
        Err(CoreError::OutsideRegion { .. })
    ));
    // In the lake: covered, so no road nearby.
    let lake = LatLon {
        lat: 55.75,
        lon: 13.35,
    };
    assert!(matches!(
        engine.snap(lake),
        Err(CoreError::NoRoadNearby { .. })
    ));
}

#[test]
fn a_file_without_coverage_still_opens() {
    let d = lake_and_island();
    let bytes = d.to_bytes().unwrap();
    let (_, table) = super::super::parse_header(&bytes).unwrap();
    // Rebuild the file from its own sections, leaving the coverage out.
    let sections: Vec<(u32, &[u8])> = table
        .iter()
        .filter(|s| s.id != section::COVERAGE_OFFSETS && s.id != section::COVERAGE_POINTS)
        .map(|s| (s.id, &bytes[s.offset as usize..(s.offset + s.len) as usize]))
        .collect();
    let old = super::super::writer::assemble(&d.info, &sections);
    let region = Region::from_bytes(&old).unwrap();
    assert!(region.coverage().is_empty());
    assert_eq!(region.covers(p(55.75, 13.35)), None);
    let engine = Engine::from_region(region);
    assert!(engine.coverage().is_empty());
    // Without an outline, far from roads is "no road nearby" as before.
    let sea = LatLon {
        lat: 57.0,
        lon: 14.5,
    };
    assert!(matches!(
        engine.snap(sea),
        Err(CoreError::NoRoadNearby { .. })
    ));
}

#[test]
fn broken_coverage_is_refused() {
    let d = lake_and_island();
    let bytes = d.to_bytes().unwrap();
    let (_, table) = super::super::parse_header(&bytes).unwrap();
    let get = |id: u32| {
        let s = table.iter().find(|s| s.id == id).unwrap();
        bytes[s.offset as usize..(s.offset + s.len) as usize].to_vec()
    };
    let others: Vec<(u32, Vec<u8>)> = table
        .iter()
        .filter(|s| s.id != section::COVERAGE_OFFSETS && s.id != section::COVERAGE_POINTS)
        .map(|s| {
            (
                s.id,
                bytes[s.offset as usize..(s.offset + s.len) as usize].to_vec(),
            )
        })
        .collect();
    let offsets = get(section::COVERAGE_OFFSETS);
    let points = get(section::COVERAGE_POINTS);
    let mut open_ring = points.clone();
    open_ring[0] ^= 1; // the first point no longer matches the last
    let mut far_point = points.clone();
    far_point[0..4].copy_from_slice(&i32::MAX.to_le_bytes());
    let mut bad_offsets = offsets.clone();
    let last = bad_offsets.len() - 4;
    bad_offsets[last..].copy_from_slice(&u32::MAX.to_le_bytes());
    type Sections = Vec<(u32, Vec<u8>)>;
    let cases: Vec<(&str, Sections)> = vec![
        (
            "points alone",
            vec![(section::COVERAGE_POINTS, points.clone())],
        ),
        (
            "offsets alone",
            vec![(section::COVERAGE_OFFSETS, offsets.clone())],
        ),
        (
            "open ring",
            vec![
                (section::COVERAGE_OFFSETS, offsets.clone()),
                (section::COVERAGE_POINTS, open_ring),
            ],
        ),
        (
            "point out of range",
            vec![
                (section::COVERAGE_OFFSETS, offsets.clone()),
                (section::COVERAGE_POINTS, far_point),
            ],
        ),
        (
            "offsets past the points",
            vec![
                (section::COVERAGE_OFFSETS, bad_offsets),
                (section::COVERAGE_POINTS, points.clone()),
            ],
        ),
        (
            "points not whole",
            vec![
                (section::COVERAGE_OFFSETS, offsets.clone()),
                (
                    section::COVERAGE_POINTS,
                    points[..points.len() - 3].to_vec(),
                ),
            ],
        ),
    ];
    for (why, extra) in cases {
        let all: Vec<(u32, &[u8])> = others
            .iter()
            .chain(extra.iter())
            .map(|(id, b)| (*id, b.as_slice()))
            .collect();
        let file = super::super::writer::assemble(&d.info, &all);
        assert!(
            matches!(Region::from_bytes(&file), Err(CoreError::Region(_))),
            "{why}"
        );
    }
}
