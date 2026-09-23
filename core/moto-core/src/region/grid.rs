// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The uniform snapping grid: which edges pass through which cell.

use super::format::{Edge, GridMeta, PointE7, edge_flags};

/// Builds the grid over all edges that run along their geometry (one per
/// geometry is enough; the reverse edge is found through the graph).
/// Returns the meta record, the row-major cell offsets and the edge ids.
pub(crate) fn build(
    edges: &[Edge],
    geometry_offsets: &[u32],
    shape: &[PointE7],
    (cell_lat, cell_lon): (i32, i32),
) -> Result<(GridMeta, Vec<u32>, Vec<u32>), String> {
    if cell_lat <= 0 || cell_lon <= 0 {
        return Err("grid cell size must be positive".into());
    }
    let polyline = |e: &Edge| -> Result<&[PointE7], String> {
        let g = e.geometry as usize;
        let (&a, &b) = geometry_offsets
            .get(g)
            .zip(geometry_offsets.get(g + 1))
            .ok_or("edge refers to a missing geometry")?;
        shape
            .get(a as usize..b as usize)
            .ok_or_else(|| "geometry offsets out of range".into())
    };
    let indexed = || {
        edges
            .iter()
            .enumerate()
            .filter(|(_, e)| e.flags & edge_flags::REVERSED == 0)
    };

    let mut min = (i32::MAX, i32::MAX);
    let mut max = (i32::MIN, i32::MIN);
    for (_, e) in indexed() {
        for p in polyline(e)? {
            min = (min.0.min(p.lat), min.1.min(p.lon));
            max = (max.0.max(p.lat), max.1.max(p.lon));
        }
    }
    if min.0 > max.0 {
        min = (0, 0);
        max = (0, 0);
    }
    let span = |lo: i32, hi: i32, cell: i32| (i64::from(hi) - i64::from(lo)) / i64::from(cell) + 1;
    let rows = u32::try_from(span(min.0, max.0, cell_lat)).map_err(|_| "grid too large")?;
    let cols = u32::try_from(span(min.1, max.1, cell_lon)).map_err(|_| "grid too large")?;
    let cell_count = (rows as usize)
        .checked_mul(cols as usize)
        .filter(|&c| c < u32::MAX as usize)
        .ok_or("grid too large")?;
    let meta = GridMeta {
        min_lat: min.0,
        min_lon: min.1,
        cell_lat,
        cell_lon,
        rows,
        cols,
    };

    let mut pairs: Vec<(u32, u32)> = Vec::new();
    for (id, e) in indexed() {
        let id = id as u32;
        for w in polyline(e)?.windows(2) {
            // Split into pieces no longer than half a cell each way, so each
            // piece's bounding box covers at most 2×2 cells.
            let (a, b) = (w[0], w[1]);
            let dlat = (i64::from(b.lat) - i64::from(a.lat)).abs();
            let dlon = (i64::from(b.lon) - i64::from(a.lon)).abs();
            let pieces = (2 * dlat / i64::from(cell_lat))
                .max(2 * dlon / i64::from(cell_lon))
                .max(0)
                + 1;
            let at = |i: i64| {
                let f = i as f64 / pieces as f64;
                (
                    f64::from(a.lat) + f * (f64::from(b.lat) - f64::from(a.lat)),
                    f64::from(a.lon) + f * (f64::from(b.lon) - f64::from(a.lon)),
                )
            };
            for i in 0..pieces {
                let (p, q) = (at(i), at(i + 1));
                let r0 = cell_index(p.0.min(q.0), meta.min_lat, cell_lat, rows);
                let r1 = cell_index(p.0.max(q.0), meta.min_lat, cell_lat, rows);
                let c0 = cell_index(p.1.min(q.1), meta.min_lon, cell_lon, cols);
                let c1 = cell_index(p.1.max(q.1), meta.min_lon, cell_lon, cols);
                for r in r0..=r1 {
                    for c in c0..=c1 {
                        pairs.push((r * cols + c, id));
                    }
                }
            }
        }
    }
    pairs.sort_unstable();
    pairs.dedup();

    let mut cells = vec![0u32; cell_count + 1];
    for &(cell, _) in &pairs {
        cells[cell as usize + 1] += 1;
    }
    for i in 0..cell_count {
        cells[i + 1] += cells[i];
    }
    let cell_edges = pairs.into_iter().map(|(_, e)| e).collect();
    Ok((meta, cells, cell_edges))
}

/// Cell index along one axis, clamped to the grid.
fn cell_index(v: f64, min: i32, cell: i32, count: u32) -> u32 {
    let i = ((v - f64::from(min)) / f64::from(cell)).floor();
    i.clamp(0.0, f64::from(count - 1)) as u32
}
