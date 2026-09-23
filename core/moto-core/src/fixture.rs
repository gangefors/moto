// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Tiny hand-made regions for tests. [`region`] is a small network near
//! Lund:
//!
//! ```text
//!            C (55.71, 13.21)
//!           /
//!          ) bend (55.705, 13.212)
//!           \
//! A ——————— B ———————> D      (B→D one-way)
//! 13.20     13.21      13.22   all at lat 55.70
//! ```
//!
//! [`ladder`] is two parallel east–west roads joined at both ends, for
//! routing tests.

use crate::LatLon;
use crate::curvature::curvature_metrics;
use crate::geo::polyline_length_m;
use crate::region::format::*;
use crate::region::{RegionData, RegionInfo};

pub const A: u32 = 0;
pub const B: u32 = 1;
pub const C: u32 = 2;
pub const D: u32 = 3;

pub const E_AB: u32 = 0;
pub const E_BA: u32 = 1;
pub const E_BC: u32 = 2;
pub const E_BD: u32 = 3;
pub const E_CB: u32 = 4;

/// Bounding-box padding, 0.005° in 1e-7°.
const PAD: i32 = 50_000;

fn p(lat: f64, lon: f64) -> PointE7 {
    PointE7 {
        lat: (lat * COORD_SCALE).round() as i32,
        lon: (lon * COORD_SCALE).round() as i32,
    }
}

/// A road between two fixture nodes.
#[derive(Debug, Clone)]
pub struct Road {
    pub from: u32,
    pub to: u32,
    /// Shape points between the two nodes, as (lat, lon).
    pub via: Vec<(f64, f64)>,
    pub oneway: bool,
    pub class: RoadClass,
    pub surface: Surface,
    pub speed_kmh: u8,
    pub flags: u8,
    pub way_id: i64,
    /// Index of the road's first node in its OSM way, for ways split into
    /// several roads.
    pub way_start: u32,
}

impl Road {
    pub fn new(from: u32, to: u32, class: RoadClass, speed_kmh: u8, way_id: i64) -> Self {
        Self {
            from,
            to,
            via: Vec::new(),
            oneway: false,
            class,
            surface: Surface::Asphalt,
            speed_kmh,
            flags: 0,
            way_id,
            way_start: 0,
        }
    }
}

/// Builds region content from node positions (lat, lon) and roads. Edges
/// are numbered by tail, then in road order (forward edge before reverse).
pub fn build(nodes: &[(f64, f64)], roads: &[Road], cell: i32) -> RegionData {
    let nodes: Vec<PointE7> = nodes.iter().map(|&(lat, lon)| p(lat, lon)).collect();
    let mut geometry_offsets = vec![0u32];
    let mut shape_points = Vec::new();
    let mut drafts = Vec::new();
    for (g, road) in roads.iter().enumerate() {
        let mut shape = vec![nodes[road.from as usize]];
        shape.extend(road.via.iter().map(|&(lat, lon)| p(lat, lon)));
        shape.push(nodes[road.to as usize]);
        let n = shape.len() as u32 - 1;
        shape_points.extend_from_slice(&shape);
        geometry_offsets.push(shape_points.len() as u32);

        let line: Vec<LatLon> = shape
            .iter()
            .map(|q| LatLon {
                lat: f64::from(q.lat) / COORD_SCALE,
                lon: f64::from(q.lon) / COORD_SCALE,
            })
            .collect();
        let length_dm = (polyline_length_m(&line) * 10.0).round() as u32;
        let curvature = curvature_metrics(&line);
        let edge = |tail, head, reversed: bool| Edge {
            tail,
            head,
            length_dm,
            geometry: g as u32,
            speed_kmh: road.speed_kmh,
            class: road.class as u8,
            surface: road.surface as u8,
            flags: road.flags | if reversed { edge_flags::REVERSED } else { 0 },
        };
        let way_ref = |from_idx, to_idx| WayRef {
            way_id: road.way_id,
            from_idx,
            to_idx,
        };
        let (first, last) = (road.way_start, road.way_start + n);
        drafts.push((
            edge(road.from, road.to, false),
            curvature,
            way_ref(first, last),
        ));
        if !road.oneway {
            drafts.push((
                edge(road.to, road.from, true),
                curvature,
                way_ref(last, first),
            ));
        }
    }
    drafts.sort_by_key(|d| d.0.tail); // stable: keeps road order per tail

    let (mut min, mut max) = ((i32::MAX, i32::MAX), (i32::MIN, i32::MIN));
    for q in &shape_points {
        min = (min.0.min(q.lat), min.1.min(q.lon));
        max = (max.0.max(q.lat), max.1.max(q.lon));
    }
    RegionData {
        info: RegionInfo {
            osm_timestamp: 1_790_000_000,
            // Padded by 0.005° (about 500 m), as a real region's box
            // extends past the roads near its edge.
            bbox: BBoxE7 {
                min_lat: min.0 - PAD,
                min_lon: min.1 - PAD,
                max_lat: max.0 + PAD,
                max_lon: max.1 + PAD,
            },
            builder_version: "fixture 1".into(),
            source_name: "hand-made test fixture".into(),
        },
        nodes,
        edges: drafts.iter().map(|d| d.0).collect(),
        geometry_offsets,
        shape_points,
        curvature: drafts.iter().map(|d| d.1).collect(),
        way_refs: drafts.iter().map(|d| d.2).collect(),
        grid_cell: (cell, cell),
    }
}

pub fn region() -> RegionData {
    region_with_cell(50_000)
}

/// The Lund fixture with a square grid cell of `cell` × 1e-7 degrees.
pub fn region_with_cell(cell: i32) -> RegionData {
    let nodes = [
        (55.70, 13.20),
        (55.70, 13.21),
        (55.71, 13.21),
        (55.70, 13.22),
    ];
    let bend = Road {
        via: vec![(55.705, 13.212)],
        ..Road::new(B, C, RoadClass::Unclassified, 70, 200)
    };
    let one_way = Road {
        oneway: true,
        ..Road::new(B, D, RoadClass::Primary, 70, 300)
    };
    build(
        &nodes,
        &[Road::new(A, B, RoadClass::Tertiary, 70, 100), bend, one_way],
        cell,
    )
}

/// Ladder nodes: `L_NW`–`L_N`–`L_NE` along lat 55.72 and `L_SW`–`L_S`–`L_SE`
/// along lat 55.70, lon 13.40 / 13.42 / 13.44.
pub const L_NW: u32 = 0;
pub const L_N: u32 = 1;
pub const L_NE: u32 = 2;
pub const L_SW: u32 = 3;
pub const L_S: u32 = 4;
pub const L_SE: u32 = 5;

/// A ladder: a slow residential road in the north and a fast motorway in
/// the south, joined by unclassified roads at both ends. `north_surface`
/// sets the north road's surface.
pub fn ladder(north_surface: Surface) -> RegionData {
    let nodes = [
        (55.72, 13.40),
        (55.72, 13.42),
        (55.72, 13.44),
        (55.70, 13.40),
        (55.70, 13.42),
        (55.70, 13.44),
    ];
    let north = |from, to, way| Road {
        surface: north_surface,
        ..Road::new(from, to, RoadClass::Residential, 30, way)
    };
    let roads = [
        north(L_NW, L_N, 1),
        Road {
            way_start: 1,
            ..north(L_N, L_NE, 1)
        },
        Road::new(L_SW, L_S, RoadClass::Motorway, 110, 2),
        Road::new(L_S, L_SE, RoadClass::Motorway, 110, 2),
        Road::new(L_NW, L_SW, RoadClass::Unclassified, 50, 3),
        Road::new(L_NE, L_SE, RoadClass::Unclassified, 50, 4),
    ];
    build(&nodes, &roads, 50_000)
}
