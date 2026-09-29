// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Where a region has roads (ADR-0005, format 1.1): outlines traced from
//! the road network itself, so the app can show where routing works (a
//! country's roads, not its bounding box).
//!
//! The roads are rasterised on cells of about 2 km, widened by one cell so
//! there are no gaps between villages, holes are filled (a lake inside
//! the covered land is covered too), and each covered area's outline is
//! traced along the cell edges and simplified. Ferry lines don't count, so
//! the outline doesn't follow them across the sea; an island with roads
//! gets its own outline.

use std::collections::{HashMap, VecDeque};

use super::format::{BBoxE7, Edge, PointE7, RoadClass};

/// Cell height: 0.02° of latitude, about 2.2 km.
pub const CELL_LAT_E7: i32 = 200_000;
/// Simplification tolerance in cells: removes the staircase of the cell
/// edges without moving the outline by more than about 1 km.
const TOLERANCE_CELLS: f64 = 0.5;
/// Most cells rasterised; a larger region gets no coverage (the app then
/// shows its bounding box, as before).
const MAX_CELLS: u64 = 20_000_000;

/// Outlines as ring count + 1 offsets into closed rings of points
/// (counter-clockwise, first point repeated last), clamped to `bbox`.
/// Empty when there are no roads.
pub fn trace(
    edges: &[Edge],
    geometry_offsets: &[u32],
    shape_points: &[PointE7],
    bbox: BBoxE7,
) -> (Vec<u32>, Vec<PointE7>) {
    let lines = road_lines(edges, geometry_offsets, shape_points);
    let Some(raster) = Raster::new(&lines) else {
        return (vec![0], Vec::new());
    };
    let mut cells = raster.mark(&lines);
    cells = raster.dilate(&cells);
    raster.fill_holes(&mut cells);
    let mut offsets = vec![0u32];
    let mut points = Vec::new();
    for ring in raster.outlines(&cells) {
        let ring = simplify(&ring);
        if ring.len() < 4 {
            continue;
        }
        points.extend(ring.iter().map(|&(x, y)| raster.point(x, y, bbox)));
        offsets.push(u32::try_from(points.len()).unwrap_or(u32::MAX));
    }
    (offsets, points)
}

/// One outline for several regions' outlines together (ADR-0009): the
/// areas they cover, rasterised on one grid, joined where they touch or
/// overlap and with the gaps they enclose filled, traced as in [`trace`].
/// Each region's rings are read with the even–odd rule, as in
/// [`contains`]. `None` when the regions are too large for the grid.
pub fn merge(regions: &[Vec<&[PointE7]>], bbox: BBoxE7) -> Option<(Vec<u32>, Vec<PointE7>)> {
    let lines: Vec<&[PointE7]> = regions.iter().flatten().copied().collect();
    let Some(raster) = Raster::new(&lines) else {
        return lines.is_empty().then(|| (vec![0], Vec::new()));
    };
    // The cells the outlines run through, so outlines that meet or come
    // within a cell of each other join, and the cells inside them.
    let mut cells = raster.mark(&lines);
    for rings in regions {
        raster.fill_rings(rings, &mut cells);
    }
    raster.fill_holes(&mut cells);
    let mut offsets = vec![0u32];
    let mut points = Vec::new();
    for ring in raster.outlines(&cells) {
        let ring = simplify(&ring);
        if ring.len() < 4 {
            continue;
        }
        points.extend(ring.iter().map(|&(x, y)| raster.point(x, y, bbox)));
        offsets.push(u32::try_from(points.len()).ok()?);
    }
    Some((offsets, points))
}

/// The polylines of the region's roads (each geometry once), ferries left out.
fn road_lines<'a>(
    edges: &[Edge],
    geometry_offsets: &[u32],
    shape_points: &'a [PointE7],
) -> Vec<&'a [PointE7]> {
    let geometries = geometry_offsets.len().saturating_sub(1);
    let mut seen = vec![false; geometries];
    let mut lines = Vec::new();
    for e in edges {
        let g = e.geometry as usize;
        if e.class == RoadClass::Ferry as u8 || seen.get(g).copied().unwrap_or(true) {
            continue;
        }
        seen[g] = true;
        let (a, b) = (
            geometry_offsets[g] as usize,
            geometry_offsets[g + 1] as usize,
        );
        if let Some(line) = shape_points.get(a..b) {
            lines.push(line);
        }
    }
    lines
}

/// A grid of cells over the roads with a margin of one cell each side.
struct Raster {
    lat0: i64,
    lon0: i64,
    cell_lat: i64,
    cell_lon: i64,
    rows: usize,
    cols: usize,
}

impl Raster {
    fn new(lines: &[&[PointE7]]) -> Option<Self> {
        let mut pts = lines.iter().flat_map(|l| l.iter());
        let first = pts.next()?;
        let (mut min, mut max) = ((first.lat, first.lon), (first.lat, first.lon));
        for p in pts {
            min = (min.0.min(p.lat), min.1.min(p.lon));
            max = (max.0.max(p.lat), max.1.max(p.lon));
        }
        let mid_lat = (f64::from(min.0) + f64::from(max.0)) / 2.0 / 1e7;
        let cell_lat = i64::from(CELL_LAT_E7);
        // Square-ish cells at the middle latitude, at most 5 times as wide.
        let cell_lon =
            ((cell_lat as f64 / mid_lat.to_radians().cos().max(0.2)).round() as i64).max(cell_lat);
        let lat0 = i64::from(min.0) - cell_lat;
        let lon0 = i64::from(min.1) - cell_lon;
        let rows = ((i64::from(max.0) - lat0) / cell_lat + 2) as usize;
        let cols = ((i64::from(max.1) - lon0) / cell_lon + 2) as usize;
        if (rows as u64).saturating_mul(cols as u64) > MAX_CELLS {
            return None;
        }
        Some(Self {
            lat0,
            lon0,
            cell_lat,
            cell_lon,
            rows,
            cols,
        })
    }

    /// Cell coordinates (column, row) of a point, as fractions.
    fn at(&self, p: PointE7) -> (f64, f64) {
        (
            (i64::from(p.lon) - self.lon0) as f64 / self.cell_lon as f64,
            (i64::from(p.lat) - self.lat0) as f64 / self.cell_lat as f64,
        )
    }

    fn index(&self, x: usize, y: usize) -> usize {
        y * self.cols + x
    }

    /// Cells a road runs through, sampled finer than a cell along each segment.
    fn mark(&self, lines: &[&[PointE7]]) -> Vec<bool> {
        let mut cells = vec![false; self.rows * self.cols];
        let mut set = |(x, y): (f64, f64)| {
            let (x, y) = (x.floor(), y.floor());
            if x >= 0.0 && y >= 0.0 && (x as usize) < self.cols && (y as usize) < self.rows {
                let i = self.index(x as usize, y as usize);
                cells[i] = true;
            }
        };
        for line in lines {
            for w in line.windows(2) {
                let (a, b) = (self.at(w[0]), self.at(w[1]));
                let steps = ((b.0 - a.0).abs().max((b.1 - a.1).abs()) * 2.0)
                    .ceil()
                    .max(1.0);
                for i in 0..=(steps as usize) {
                    let t = i as f64 / steps;
                    set((a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t));
                }
            }
            if let [p] = line {
                set(self.at(*p));
            }
        }
        cells
    }

    /// Marks the cells whose centre lies inside `rings` (even–odd rule),
    /// row by row where the rings cross the row's middle.
    fn fill_rings(&self, rings: &[&[PointE7]], cells: &mut [bool]) {
        let mut xs: Vec<f64> = Vec::new();
        for y in 0..self.rows {
            let lat = self.lat0 as f64 + (y as f64 + 0.5) * self.cell_lat as f64;
            xs.clear();
            for ring in rings {
                for e in ring.windows(2) {
                    let (a, b) = (e[0], e[1]);
                    let (ay, by) = (f64::from(a.lat), f64::from(b.lat));
                    if (ay > lat) != (by > lat) {
                        let (ax, bx) = (f64::from(a.lon), f64::from(b.lon));
                        let lon = ax + (lat - ay) * (bx - ax) / (by - ay);
                        xs.push((lon - self.lon0 as f64) / self.cell_lon as f64);
                    }
                }
            }
            xs.sort_unstable_by(f64::total_cmp);
            for pair in xs.as_chunks::<2>().0 {
                // Cells whose middle (x + 0.5) lies between the crossings.
                let from = (pair[0] - 0.5).ceil().max(0.0);
                let to = (pair[1] - 0.5).floor().min(self.cols as f64 - 1.0);
                if from > to {
                    continue;
                }
                for x in from as usize..=to as usize {
                    let i = self.index(x, y);
                    cells[i] = true;
                }
            }
        }
    }

    /// Every cell next to (or on) a marked one, diagonals included.
    fn dilate(&self, cells: &[bool]) -> Vec<bool> {
        let mut out = vec![false; cells.len()];
        for y in 0..self.rows {
            for x in 0..self.cols {
                if !cells[self.index(x, y)] {
                    continue;
                }
                for ny in y.saturating_sub(1)..=(y + 1).min(self.rows - 1) {
                    for nx in x.saturating_sub(1)..=(x + 1).min(self.cols - 1) {
                        let i = self.index(nx, ny);
                        out[i] = true;
                    }
                }
            }
        }
        out
    }

    /// Covers every empty cell that can't be reached from the grid's edge
    /// (a lake or a forest without roads inside covered land).
    fn fill_holes(&self, cells: &mut [bool]) {
        let mut outside = vec![false; cells.len()];
        let mut queue = VecDeque::new();
        for y in 0..self.rows {
            for x in 0..self.cols {
                let border = x == 0 || y == 0 || x == self.cols - 1 || y == self.rows - 1;
                let i = self.index(x, y);
                if border && !cells[i] {
                    outside[i] = true;
                    queue.push_back((x, y));
                }
            }
        }
        while let Some((x, y)) = queue.pop_front() {
            let mut visit = |nx: usize, ny: usize| {
                let i = self.index(nx, ny);
                if !cells[i] && !outside[i] {
                    outside[i] = true;
                    queue.push_back((nx, ny));
                }
            };
            if x > 0 {
                visit(x - 1, y);
            }
            if y > 0 {
                visit(x, y - 1);
            }
            if x + 1 < self.cols {
                visit(x + 1, y);
            }
            if y + 1 < self.rows {
                visit(x, y + 1);
            }
        }
        for (c, o) in cells.iter_mut().zip(outside) {
            *c = !o;
        }
    }

    /// The closed outlines of the covered cells, along the cell edges,
    /// counter-clockwise (covered on the left), in (column, row) corners.
    fn outlines(&self, cells: &[bool]) -> Vec<Vec<(i64, i64)>> {
        let covered = |x: i64, y: i64| {
            x >= 0
                && y >= 0
                && (x as usize) < self.cols
                && (y as usize) < self.rows
                && cells[self.index(x as usize, y as usize)]
        };
        // Directed boundary edges, keyed by their start corner.
        let mut out: HashMap<(i64, i64), Vec<(i64, i64)>> = HashMap::new();
        for y in 0..self.rows as i64 {
            for x in 0..self.cols as i64 {
                if !covered(x, y) {
                    continue;
                }
                let mut add = |a: (i64, i64), b: (i64, i64)| out.entry(a).or_default().push(b);
                if !covered(x, y - 1) {
                    add((x, y), (x + 1, y));
                }
                if !covered(x + 1, y) {
                    add((x + 1, y), (x + 1, y + 1));
                }
                if !covered(x, y + 1) {
                    add((x + 1, y + 1), (x, y + 1));
                }
                if !covered(x - 1, y) {
                    add((x, y + 1), (x, y));
                }
            }
        }
        // A deterministic order: rings start at their lowest corner.
        let mut starts: Vec<(i64, i64)> = out.keys().copied().collect();
        starts.sort_unstable_by_key(|&(x, y)| (y, x));
        let mut rings = Vec::new();
        for start in starts {
            while let Some(first) = out.get_mut(&start).and_then(Vec::pop) {
                let mut ring = vec![start];
                let (mut prev, mut at) = (start, first);
                while at != start {
                    ring.push(at);
                    let dir = (at.0 - prev.0, at.1 - prev.1);
                    let Some(next) = out.get_mut(&at).and_then(|nexts| take_turn(nexts, at, dir))
                    else {
                        break;
                    };
                    (prev, at) = (at, next);
                }
                if at == start {
                    ring.push(start);
                    rings.push(ring);
                }
            }
        }
        rings
    }

    /// A corner as a point, clamped to `bbox` (when it is a real box) and
    /// to valid coordinates.
    fn point(&self, x: i64, y: i64, bbox: BBoxE7) -> PointE7 {
        let mut lat = self.lat0 + y * self.cell_lat;
        let mut lon = self.lon0 + x * self.cell_lon;
        if bbox.min_lat < bbox.max_lat && bbox.min_lon < bbox.max_lon {
            lat = lat.clamp(i64::from(bbox.min_lat), i64::from(bbox.max_lat));
            lon = lon.clamp(i64::from(bbox.min_lon), i64::from(bbox.max_lon));
        }
        PointE7 {
            lat: lat.clamp(-900_000_000, 900_000_000) as i32,
            lon: lon.clamp(-1_800_000_000, 1_800_000_000) as i32,
        }
    }
}

/// Takes the edge leaving `at` that turns most to the left from `dir`
/// (left, then straight, then right), so areas that only touch at a corner
/// get outlines of their own.
fn take_turn(nexts: &mut Vec<(i64, i64)>, at: (i64, i64), dir: (i64, i64)) -> Option<(i64, i64)> {
    let left = (-dir.1, dir.0);
    let right = (dir.1, -dir.0);
    for want in [left, dir, right] {
        if let Some(i) = nexts.iter().position(|&n| (n.0 - at.0, n.1 - at.1) == want) {
            return Some(nexts.swap_remove(i));
        }
    }
    None
}

/// Drops corners within [`TOLERANCE_CELLS`] of the line between their
/// neighbours (Douglas–Peucker on the closed ring, split at its first
/// point and the corner farthest from it). Keeps the ring closed.
fn simplify(ring: &[(i64, i64)]) -> Vec<(i64, i64)> {
    let n = ring.len();
    if n < 5 {
        return ring.to_vec();
    }
    let d2 = |a: (i64, i64), b: (i64, i64)| ((a.0 - b.0).pow(2) + (a.1 - b.1).pow(2)) as f64;
    let far = (1..n - 1)
        .max_by(|&i, &j| d2(ring[0], ring[i]).total_cmp(&d2(ring[0], ring[j])))
        .unwrap_or(n / 2);
    let mut keep = vec![false; n];
    keep[0] = true;
    keep[far] = true;
    keep[n - 1] = true;
    douglas_peucker(ring, 0, far, &mut keep);
    douglas_peucker(ring, far, n - 1, &mut keep);
    ring.iter()
        .zip(keep)
        .filter(|(_, k)| *k)
        .map(|(p, _)| *p)
        .collect()
}

fn douglas_peucker(pts: &[(i64, i64)], first: usize, last: usize, keep: &mut [bool]) {
    let mut stack = vec![(first, last)];
    while let Some((a, b)) = stack.pop() {
        if b <= a + 1 {
            continue;
        }
        let (p, q) = (pts[a], pts[b]);
        let (dx, dy) = ((q.0 - p.0) as f64, (q.1 - p.1) as f64);
        let len = dx.hypot(dy);
        let (mut worst, mut at) = (0.0, a);
        for (i, &r) in pts.iter().enumerate().take(b).skip(a + 1) {
            let (rx, ry) = ((r.0 - p.0) as f64, (r.1 - p.1) as f64);
            let d = if len == 0.0 {
                rx.hypot(ry)
            } else {
                (rx * dy - ry * dx).abs() / len
            };
            if d > worst {
                (worst, at) = (d, i);
            }
        }
        if worst > TOLERANCE_CELLS {
            keep[at] = true;
            stack.push((a, at));
            stack.push((at, b));
        }
    }
}

/// Whether `p` lies inside any of the rings (even–odd rule; the rings of
/// one region never overlap).
pub fn contains(offsets: &[u32], points: &[PointE7], p: PointE7) -> bool {
    let (px, py) = (f64::from(p.lon), f64::from(p.lat));
    let mut inside = false;
    for w in offsets.windows(2) {
        let Some(ring) = points.get(w[0] as usize..w[1] as usize) else {
            continue;
        };
        for e in ring.windows(2) {
            let (a, b) = (e[0], e[1]);
            let (ax, ay, bx, by) = (
                f64::from(a.lon),
                f64::from(a.lat),
                f64::from(b.lon),
                f64::from(b.lat),
            );
            if (ay > py) != (by > py) && px < ax + (py - ay) * (bx - ax) / (by - ay) {
                inside = !inside;
            }
        }
    }
    inside
}

#[cfg(test)]
mod tests;
