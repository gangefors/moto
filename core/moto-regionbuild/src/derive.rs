// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! What the builder derives from the finished road graph rather than from
//! single ways (ADR-0005): curvature measured between junctions, and which
//! roads lie in built-up areas. Both run on [`RegionData`], so an existing
//! region file can be refreshed without the OSM extract (`--refresh`).

use std::collections::{HashMap, HashSet};

use moto_core::LatLon;
use moto_core::curvature::curvature_between_junctions;
use moto_core::geo::{EARTH_RADIUS_M, haversine_m};
use moto_core::region::format::{COORD_SCALE, PointE7, edge_flags};
use moto_core::region::{Region, RegionData};

/// Built-up areas are told by how densely roads meet: junctions (nodes
/// where three or more roads meet) per km² over the 3 × 3 cells around a
/// point, cells of this size in degrees (about 560 × 560 m in Skåne).
const CELL_DEG: (f64, f64) = (0.005, 0.009);
/// Junctions per km² from which a place counts as built up. Measured on
/// Skåne (2026-09): towns and suburbs 42–83 (Malmö's outskirts, Lund,
/// Hörby, Sjöbo, Veberöd, Dalby), villages like Röstånga 24, open country
/// 0–16, including the roads just outside Malmö.
const BUILT_UP_JUNCTIONS_PER_KM2: f64 = 30.0;
/// Only large built-up areas count: at least this many km² of dense cells
/// next to each other (Stefan: the outskirts of a city are no fun, a town
/// or village on the way is fine). Measured on Skåne (2026-09), in cells:
/// Malmö 341, Helsingborg 175, Lund 119, Kristianstad 78; towns 12–43
/// (Hässleholm, Ystad, Eslöv, Sjöbo, Åstorp, Höör, Klippan, Hörby);
/// villages 5 or fewer.
const MIN_AREA_KM2: f64 = 15.0;
/// A road is built up when at least this share of it lies in built-up
/// places.
const BUILT_UP_SHARE: f64 = 0.5;

/// What [`finish`] found.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct DeriveStats {
    pub junctions: usize,
    /// Road length (each road once) in all and in built-up areas, metres.
    pub road_m: f64,
    pub built_up_m: f64,
}

/// Recomputes every edge's curvature between junctions and sets
/// [`edge_flags::BUILT_UP`] on roads in built-up areas. Idempotent.
pub fn finish(data: &mut RegionData) -> DeriveStats {
    let junction = junctions(data);
    let dense = DenseCells::new(data, &junction);
    let mut stats = DeriveStats {
        junctions: junction.iter().filter(|&&j| j).count(),
        ..DeriveStats::default()
    };
    // Per geometry: its curvature and whether it is built up. Both
    // directions of a road share a geometry.
    let mut done: HashMap<u32, (moto_core::region::format::CurvatureMetrics, bool)> =
        HashMap::new();
    for i in 0..data.edges.len() {
        let e = data.edges[i];
        let entry = done.entry(e.geometry).or_insert_with(|| {
            let line = geometry(data, e.geometry);
            // The geometry runs from the tail unless the edge is reversed.
            let (start, end) = if e.flags & edge_flags::REVERSED == 0 {
                (e.tail, e.head)
            } else {
                (e.head, e.tail)
            };
            let at = |n: u32| junction.get(n as usize).copied().unwrap_or(false);
            let curvature = curvature_between_junctions(&line, at(start), at(end));
            let (total, built) = dense.built_up_m(&line);
            stats.road_m += total;
            let built_up = total > 0.0 && built >= total * BUILT_UP_SHARE;
            if built_up {
                stats.built_up_m += total;
            }
            (curvature, built_up)
        });
        let (curvature, built_up) = *entry;
        if let Some(c) = data.curvature.get_mut(i) {
            *c = curvature;
        }
        let edge = &mut data.edges[i];
        edge.flags &= !edge_flags::BUILT_UP;
        if built_up {
            edge.flags |= edge_flags::BUILT_UP;
        }
    }
    stats
}

/// Whether each node is a junction: three or more other nodes linked to
/// it by a road (either way). Where one road simply continues into the
/// next, the node has two.
fn junctions(data: &RegionData) -> Vec<bool> {
    let mut pairs: Vec<(u32, u32)> = data
        .edges
        .iter()
        .filter(|e| e.tail != e.head)
        .map(|e| (e.tail.min(e.head), e.tail.max(e.head)))
        .collect();
    pairs.sort_unstable();
    pairs.dedup();
    let mut degree = vec![0u32; data.nodes.len()];
    for (a, b) in pairs {
        for n in [a, b] {
            if let Some(d) = degree.get_mut(n as usize) {
                *d += 1;
            }
        }
    }
    degree.into_iter().map(|d| d >= 3).collect()
}

fn latlon(p: PointE7) -> LatLon {
    LatLon {
        lat: f64::from(p.lat) / COORD_SCALE,
        lon: f64::from(p.lon) / COORD_SCALE,
    }
}

/// Geometry `g`'s points; empty when the offsets are out of range.
fn geometry(data: &RegionData, g: u32) -> Vec<LatLon> {
    let g = g as usize;
    let (Some(&a), Some(&b)) = (
        data.geometry_offsets.get(g),
        data.geometry_offsets.get(g + 1),
    ) else {
        return Vec::new();
    };
    data.shape_points
        .get(a as usize..b as usize)
        .unwrap_or(&[])
        .iter()
        .map(|&p| latlon(p))
        .collect()
}

type Cell = (i64, i64);

fn cell_of(p: LatLon) -> Cell {
    (
        (p.lat / CELL_DEG.0).floor() as i64,
        (p.lon / CELL_DEG.1).floor() as i64,
    )
}

/// The built-up cells: where junctions over the 3 × 3 cells around reach
/// [`BUILT_UP_JUNCTIONS_PER_KM2`], in areas of such cells (touching,
/// corners too) of at least [`MIN_AREA_KM2`].
struct DenseCells {
    dense: HashSet<Cell>,
}

impl DenseCells {
    fn new(data: &RegionData, junction: &[bool]) -> Self {
        let mut count: HashMap<Cell, u32> = HashMap::new();
        for (p, _) in data.nodes.iter().zip(junction).filter(|(_, j)| **j) {
            *count.entry(cell_of(latlon(*p))).or_default() += 1;
        }
        let mut dense = HashSet::new();
        // Only cells next to a junction can reach the threshold.
        let candidates: HashSet<Cell> = count
            .keys()
            .flat_map(|&(a, b)| {
                (-1..=1).flat_map(move |da| (-1..=1).map(move |db| (a + da, b + db)))
            })
            .collect();
        for c in candidates {
            let n: u32 = (-1..=1)
                .flat_map(|da| (-1..=1).map(move |db| (c.0 + da, c.1 + db)))
                .map(|k| count.get(&k).copied().unwrap_or(0))
                .sum();
            let lat = (c.0 as f64 + 0.5) * CELL_DEG.0;
            let km2 = 9.0 * cell_km2(lat);
            if km2 > 0.0 && f64::from(n) / km2 >= BUILT_UP_JUNCTIONS_PER_KM2 {
                dense.insert(c);
            }
        }
        Self {
            dense: large_areas(dense),
        }
    }

    /// The length of `line` in all and in built-up cells (each stretch
    /// by the cell of its middle), metres.
    fn built_up_m(&self, line: &[LatLon]) -> (f64, f64) {
        let (mut total, mut built) = (0.0, 0.0);
        for w in line.windows(2) {
            let m = haversine_m(w[0], w[1]);
            total += m;
            let mid = LatLon {
                lat: (w[0].lat + w[1].lat) / 2.0,
                lon: (w[0].lon + w[1].lon) / 2.0,
            };
            if self.dense.contains(&cell_of(mid)) {
                built += m;
            }
        }
        (total, built)
    }
}

/// The cells of `dense` that lie in areas (cells touching, corners too)
/// of at least [`MIN_AREA_KM2`].
fn large_areas(dense: HashSet<Cell>) -> HashSet<Cell> {
    let mut seen: HashSet<Cell> = HashSet::new();
    let mut large = HashSet::new();
    // Sorted, so the result doesn't depend on hash order.
    let mut cells: Vec<Cell> = dense.iter().copied().collect();
    cells.sort_unstable();
    for c in cells {
        if !seen.insert(c) {
            continue;
        }
        let mut area = vec![c];
        let mut i = 0;
        while let Some(&x) = area.get(i) {
            i += 1;
            for da in -1..=1 {
                for db in -1..=1 {
                    let y = (x.0 + da, x.1 + db);
                    if dense.contains(&y) && seen.insert(y) {
                        area.push(y);
                    }
                }
            }
        }
        let km2: f64 = area
            .iter()
            .map(|&(a, _)| cell_km2((a as f64 + 0.5) * CELL_DEG.0))
            .sum();
        if km2 >= MIN_AREA_KM2 {
            large.extend(area);
        }
    }
    large
}

/// Area of one cell at latitude `lat`, km².
fn cell_km2(lat: f64) -> f64 {
    let deg_m = EARTH_RADIUS_M.to_radians();
    let h = CELL_DEG.0 * deg_m;
    let w = CELL_DEG.1 * deg_m * lat.to_radians().cos().max(0.0);
    h * w / 1e6
}

/// The content of an opened region file, to derive afresh and write
/// again.
pub fn data_of(region: &Region) -> RegionData {
    let grid = region.grid_meta();
    RegionData {
        info: region.info().clone(),
        nodes: region.nodes().to_vec(),
        edges: region.edges().to_vec(),
        geometry_offsets: region.geometry_offsets().to_vec(),
        shape_points: region.shape_points().to_vec(),
        curvature: region.curvature().to_vec(),
        way_refs: region.way_refs().to_vec(),
        grid_cell: (grid.cell_lat, grid.cell_lon),
    }
}

#[cfg(test)]
mod tests;
