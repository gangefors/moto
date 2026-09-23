// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Snapping a point to the nearest road through the region's grid index.

use std::cell::Cell;
use std::collections::{HashMap, HashSet};

use crate::geo::{EARTH_RADIUS_M, haversine_m};
use crate::region::Region;
use crate::region::format::{COORD_SCALE, PointE7, edge_flags};
use crate::{CoreError, LatLon, RoadPoint};

fn to_latlon(p: PointE7) -> LatLon {
    LatLon {
        lat: f64::from(p.lat) / COORD_SCALE,
        lon: f64::from(p.lon) / COORD_SCALE,
    }
}

/// Metres per degree of latitude.
const M_PER_DEG: f64 = EARTH_RADIUS_M * std::f64::consts::PI / 180.0;

struct Best {
    dist_m: f64,
    edge: u32,
    segment: usize,
    t: f64,
}

/// Scans grid cells in square rings around `point` out to `max_distance_m`
/// and calls `visit` with the nearest point of every segment of every
/// non-ferry edge found (edge, segment, position along it, distance in
/// metres), each edge once. `done` gets the distance no unscanned cell can
/// be closer than, and stops the scan early when it returns true.
fn scan(
    region: &Region,
    point: LatLon,
    max_distance_m: f64,
    mut visit: impl FnMut(u32, usize, f64, f64),
    done: impl Fn(f64) -> bool,
) {
    let meta = *region.grid_meta();

    // Smallest cell side in metres, taking the narrowest longitude span in
    // the grid, so ring distances are lower bounds.
    let max_abs_lat = {
        let top = f64::from(meta.min_lat) + f64::from(meta.cell_lat) * f64::from(meta.rows);
        (f64::from(meta.min_lat).abs().max(top.abs()) / COORD_SCALE).min(90.0)
    };
    let cell_m = (f64::from(meta.cell_lat) / COORD_SCALE * M_PER_DEG)
        .min(f64::from(meta.cell_lon) / COORD_SCALE * M_PER_DEG * max_abs_lat.to_radians().cos());

    let row0 = ((point.lat * COORD_SCALE - f64::from(meta.min_lat)) / f64::from(meta.cell_lat))
        .floor() as i64;
    let col0 = ((point.lon * COORD_SCALE - f64::from(meta.min_lon)) / f64::from(meta.cell_lon))
        .floor() as i64;
    let (rows, cols) = (i64::from(meta.rows), i64::from(meta.cols));

    // Local plane around the query point, in metres.
    let k = point.lat.to_radians().cos();
    let xy = |p: PointE7| {
        let p = to_latlon(p);
        (
            (p.lon - point.lon) * k * M_PER_DEG,
            (p.lat - point.lat) * M_PER_DEG,
        )
    };

    let mut seen: HashSet<u32> = HashSet::new();
    for ring in 0i64.. {
        // Any cell in this ring is at least (ring - 1) cells away.
        let bound = (ring - 1).max(0) as f64 * cell_m;
        if bound > max_distance_m || done(bound) {
            break;
        }
        if row0 - ring < 0 && row0 + ring >= rows && col0 - ring < 0 && col0 + ring >= cols {
            break; // the ring lies entirely outside the grid
        }
        for r in (row0 - ring).max(0)..=(row0 + ring).min(rows - 1) {
            let edge_row = (r - row0).abs() == ring;
            let step = if edge_row { 1 } else { (2 * ring).max(1) };
            let mut c = col0 - ring;
            while c <= col0 + ring {
                if (0..cols).contains(&c) {
                    for &edge in region.grid_cell(r as u32, c as u32) {
                        if !seen.insert(edge) {
                            continue;
                        }
                        let Some(e) = region.edges().get(edge as usize) else {
                            continue;
                        };
                        // A tap at sea must not land on a ferry line.
                        if e.flags & edge_flags::FERRY != 0 {
                            continue;
                        }
                        let line = region.geometry(e.geometry);
                        for (segment, w) in line.windows(2).enumerate() {
                            let (a, b) = (xy(w[0]), xy(w[1]));
                            let (dx, dy) = (b.0 - a.0, b.1 - a.1);
                            let len2 = dx * dx + dy * dy;
                            let t = if len2 > 0.0 {
                                (-(a.0 * dx + a.1 * dy) / len2).clamp(0.0, 1.0)
                            } else {
                                0.0
                            };
                            let d = (a.0 + t * dx).hypot(a.1 + t * dy);
                            visit(edge, segment, t, d);
                        }
                    }
                }
                c += step;
            }
        }
    }
}

/// Nearest point on any edge within `max_distance_m`, ferries excluded.
///
/// Stops scanning once no unscanned cell can hold anything closer than the
/// best match so far.
pub(crate) fn snap(
    region: &Region,
    point: LatLon,
    max_distance_m: f64,
) -> Result<RoadPoint, CoreError> {
    let none = || CoreError::NoRoadNearby { max_distance_m };
    // The best distance so far, read by the stop test while `visit` updates it.
    let best_m = Cell::new(f64::INFINITY);
    let mut found: Option<Best> = None;
    scan(
        region,
        point,
        max_distance_m,
        |edge, segment, t, d| {
            let better = match &found {
                None => true,
                Some(b) => d < b.dist_m || (d == b.dist_m && edge < b.edge),
            };
            if better {
                found = Some(Best {
                    dist_m: d,
                    edge,
                    segment,
                    t,
                });
                best_m.set(d);
            }
        },
        |bound| best_m.get() <= bound,
    );
    let best = found
        .filter(|b| b.dist_m <= max_distance_m)
        .ok_or_else(none)?;
    Ok(road_point(region, point, &best))
}

/// The nearest point of each road within `radius_m` of `point`, nearest
/// first, at most `max` of them (ferries excluded). The two directions of a
/// road share a geometry and count once, as the lower edge id.
pub(crate) fn nearby(region: &Region, point: LatLon, radius_m: f64, max: usize) -> Vec<RoadPoint> {
    let mut per_geometry: HashMap<u32, Best> = HashMap::new();
    scan(
        region,
        point,
        radius_m,
        |edge, segment, t, d| {
            if d > radius_m {
                return;
            }
            let g = region.edges()[edge as usize].geometry;
            let better = per_geometry
                .get(&g)
                .is_none_or(|b| d < b.dist_m || (d == b.dist_m && edge < b.edge));
            if better {
                per_geometry.insert(
                    g,
                    Best {
                        dist_m: d,
                        edge,
                        segment,
                        t,
                    },
                );
            }
        },
        |_| false,
    );
    let mut found: Vec<Best> = per_geometry.into_values().collect();
    found.sort_by(|a, b| a.dist_m.total_cmp(&b.dist_m).then(a.edge.cmp(&b.edge)));
    found.truncate(max);
    found.iter().map(|b| road_point(region, point, b)).collect()
}

/// The road point for a scan result.
fn road_point(region: &Region, point: LatLon, best: &Best) -> RoadPoint {
    let e = region.edges()[best.edge as usize];
    let line: Vec<LatLon> = region
        .geometry(e.geometry)
        .iter()
        .map(|&p| to_latlon(p))
        .collect();
    let (a, b) = (line[best.segment], line[best.segment + 1]);
    let position = LatLon {
        lat: a.lat + best.t * (b.lat - a.lat),
        lon: a.lon + best.t * (b.lon - a.lon),
    };
    let lengths: Vec<f64> = line.windows(2).map(|w| haversine_m(w[0], w[1])).collect();
    let total: f64 = lengths.iter().sum();
    let along: f64 = lengths[..best.segment].iter().sum::<f64>() + best.t * lengths[best.segment];
    let mut offset = if total > 0.0 {
        (along / total).clamp(0.0, 1.0)
    } else {
        0.0
    };
    if e.flags & edge_flags::REVERSED != 0 {
        offset = 1.0 - offset;
    }
    RoadPoint {
        position,
        distance_m: haversine_m(point, position),
        edge: best.edge,
        offset,
    }
}
