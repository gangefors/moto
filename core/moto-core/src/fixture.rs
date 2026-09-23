// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! A tiny hand-made region for tests, near Lund:
//!
//! ```text
//!            C (55.71, 13.21)
//!           /
//!          ) bend (55.705, 13.212)
//!           \
//! A ——————— B ———————> D      (B→D one-way)
//! 13.20     13.21      13.22   all at lat 55.70
//! ```

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

fn p(lat: f64, lon: f64) -> PointE7 {
    PointE7 {
        lat: (lat * COORD_SCALE).round() as i32,
        lon: (lon * COORD_SCALE).round() as i32,
    }
}

pub fn region() -> RegionData {
    region_with_cell(50_000)
}

/// The fixture with a square grid cell of `cell` × 1e-7 degrees.
pub fn region_with_cell(cell: i32) -> RegionData {
    let nodes = vec![
        p(55.70, 13.20),
        p(55.70, 13.21),
        p(55.71, 13.21),
        p(55.70, 13.22),
    ];
    let geometries = [
        vec![nodes[0], nodes[1]],                    // g0: A–B
        vec![nodes[1], p(55.705, 13.212), nodes[2]], // g1: B–C
        vec![nodes[1], nodes[3]],                    // g2: B→D
    ];
    let mut geometry_offsets = vec![0u32];
    let mut shape_points = Vec::new();
    for g in &geometries {
        shape_points.extend_from_slice(g);
        geometry_offsets.push(shape_points.len() as u32);
    }
    let line = |g: usize| -> Vec<LatLon> {
        geometries[g]
            .iter()
            .map(|q| LatLon {
                lat: f64::from(q.lat) / COORD_SCALE,
                lon: f64::from(q.lon) / COORD_SCALE,
            })
            .collect()
    };

    // (tail, head, geometry, reversed, class, way id)
    let spec = [
        (A, B, 0, false, RoadClass::Tertiary, 100),
        (B, A, 0, true, RoadClass::Tertiary, 100),
        (B, C, 1, false, RoadClass::Unclassified, 200),
        (B, D, 2, false, RoadClass::Primary, 300),
        (C, B, 1, true, RoadClass::Unclassified, 200),
    ];
    let mut edges = Vec::new();
    let mut curvature = Vec::new();
    let mut way_refs = Vec::new();
    for (tail, head, g, reversed, class, way_id) in spec {
        let mut pts = line(g);
        let n = pts.len() as u32 - 1;
        if reversed {
            pts.reverse();
        }
        edges.push(Edge {
            tail,
            head,
            length_dm: (polyline_length_m(&pts) * 10.0).round() as u32,
            geometry: g as u32,
            speed_kmh: 70,
            class: class as u8,
            surface: Surface::Asphalt as u8,
            flags: if reversed { edge_flags::REVERSED } else { 0 },
        });
        curvature.push(curvature_metrics(&pts));
        let (from_idx, to_idx) = if reversed { (n, 0) } else { (0, n) };
        way_refs.push(WayRef {
            way_id,
            from_idx,
            to_idx,
        });
    }

    RegionData {
        info: RegionInfo {
            osm_timestamp: 1_790_000_000,
            bbox: BBoxE7 {
                min_lat: p(55.70, 0.0).lat,
                min_lon: p(0.0, 13.20).lon,
                max_lat: p(55.71, 0.0).lat,
                max_lon: p(0.0, 13.22).lon,
            },
            builder_version: "fixture 1".into(),
            source_name: "hand-made test fixture".into(),
        },
        nodes,
        edges,
        geometry_offsets,
        shape_points,
        curvature,
        way_refs,
        grid_cell: (cell, cell),
    }
}
