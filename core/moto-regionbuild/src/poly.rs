// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! A country's border as a polygon (ADR-0009): read from an Osmosis
//! `.poly` file (the exact polygon of the country's OSM boundary relation)
//! and asked which side of it a point lies on.
//!
//! Neighbouring countries' polygons share their border vertices, and every
//! test here is exact integer arithmetic on 1e-7° coordinates, so two
//! builds agree on every point they share: a point is inside at most one
//! of them, or on the line of both.

use moto_core::region::format::{BBoxE7, COORD_SCALE, PointE7};

/// Largest `.poly` file read (Norway's, the largest Nordic one, is about
/// 0.6 MB).
pub const MAX_FILE_BYTES: usize = 64 << 20;
/// Most rings and points accepted.
const MAX_RINGS: usize = 100_000;
const MAX_POINTS: usize = 5_000_000;
/// Height of the latitude bands edges are sorted into, 1e-7°.
const BAND_E7: i64 = 100_000; // 0.01°, about 1.1 km
/// Most (edge, band) entries in the index; a real country needs far
/// fewer (Norway about 60 000), a crafted file can't make it huge.
const MAX_BAND_ENTRIES: u64 = 20_000_000;

/// Where a point lies relative to the border.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Inside,
    /// Exactly on the border line; counts as inside for both countries.
    OnLine,
    Outside,
}

/// A border: closed rings, holes included (even-odd rule), indexed by
/// latitude band.
pub struct Border {
    /// Edges as (lon1, lat1, lon2, lat2), in 1e-7°.
    edges: Vec<[i64; 4]>,
    min_lat: i64,
    /// Per band from `min_lat`: offsets into `band_edges`.
    band_offsets: Vec<u32>,
    band_edges: Vec<u32>,
    bbox: BBoxE7,
}

impl Border {
    /// Parses an Osmosis `.poly` file: a name line, then rings (a name
    /// line, `lon lat` lines, `END`; a name starting with `!` is a hole),
    /// then `END`. Anything else is an error, not a panic.
    pub fn parse(text: &str) -> Result<Border, String> {
        if text.len() > MAX_FILE_BYTES {
            return Err("polygon file too large".into());
        }
        let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty());
        lines.next().ok_or("empty polygon file")?;
        let mut rings: Vec<Vec<(i64, i64)>> = Vec::new();
        let mut points = 0usize;
        loop {
            let head = lines.next().ok_or("polygon file ends without END")?;
            if head == "END" {
                break;
            }
            if rings.len() >= MAX_RINGS {
                return Err("too many rings".into());
            }
            let mut ring = Vec::new();
            loop {
                let line = lines.next().ok_or("ring ends without END")?;
                if line == "END" {
                    break;
                }
                points += 1;
                if points > MAX_POINTS {
                    return Err("too many points".into());
                }
                ring.push(parse_point(line)?);
            }
            if ring.len() < 3 {
                return Err(format!("ring '{head}' has fewer than 3 points"));
            }
            rings.push(ring);
        }
        if lines.next().is_some() {
            return Err("text after the final END".into());
        }
        if rings.is_empty() {
            return Err("polygon without rings".into());
        }
        Border::from_rings(&rings)
    }

    /// A border from closed rings of (lon, lat) in 1e-7° (the last point
    /// joins the first).
    pub fn from_rings(rings: &[Vec<(i64, i64)>]) -> Result<Border, String> {
        let mut edges = Vec::new();
        let (mut min_lat, mut max_lat) = (i64::MAX, i64::MIN);
        let (mut min_lon, mut max_lon) = (i64::MAX, i64::MIN);
        for ring in rings {
            for (i, &(x1, y1)) in ring.iter().enumerate() {
                let (x2, y2) = ring[(i + 1) % ring.len()];
                if (x1, y1) != (x2, y2) {
                    edges.push([x1, y1, x2, y2]);
                }
                min_lat = min_lat.min(y1);
                max_lat = max_lat.max(y1);
                min_lon = min_lon.min(x1);
                max_lon = max_lon.max(x1);
            }
        }
        if edges.is_empty() {
            return Err("polygon without edges".into());
        }
        let bands = ((max_lat - min_lat) / BAND_E7 + 1) as usize;
        let band_of = |lat: i64| ((lat - min_lat) / BAND_E7) as usize;
        let entries: u64 = edges
            .iter()
            .map(|e| (band_of(e[1].max(e[3])) - band_of(e[1].min(e[3])) + 1) as u64)
            .sum();
        if entries > MAX_BAND_ENTRIES {
            return Err("polygon too complex".into());
        }
        let mut count = vec![0u32; bands + 1];
        for e in &edges {
            let (lo, hi) = (e[1].min(e[3]), e[1].max(e[3]));
            for b in band_of(lo)..=band_of(hi) {
                count[b + 1] += 1;
            }
        }
        for b in 0..bands {
            count[b + 1] += count[b];
        }
        let mut next = count.clone();
        let mut band_edges = vec![0u32; count[bands] as usize];
        for (i, e) in edges.iter().enumerate() {
            let (lo, hi) = (e[1].min(e[3]), e[1].max(e[3]));
            for b in band_of(lo)..=band_of(hi) {
                band_edges[next[b] as usize] = i as u32;
                next[b] += 1;
            }
        }
        let clamp = |v: i64| v.clamp(i32::MIN as i64, i32::MAX as i64) as i32;
        Ok(Border {
            edges,
            min_lat,
            band_offsets: count,
            band_edges,
            bbox: BBoxE7 {
                min_lat: clamp(min_lat),
                min_lon: clamp(min_lon),
                max_lat: clamp(max_lat),
                max_lon: clamp(max_lon),
            },
        })
    }

    /// The polygon's bounding box.
    pub fn bbox(&self) -> BBoxE7 {
        self.bbox
    }

    /// Which side of the border `p` lies on: a ray to the east counts the
    /// edges it crosses (half-open in latitude, so a vertex on the ray
    /// counts once), in exact integer arithmetic.
    pub fn side(&self, p: PointE7) -> Side {
        let (px, py) = (i64::from(p.lon), i64::from(p.lat));
        let Some(band) = py
            .checked_sub(self.min_lat)
            .filter(|d| *d >= 0)
            .map(|d| (d / BAND_E7) as usize)
            .filter(|&b| b + 1 < self.band_offsets.len())
        else {
            return Side::Outside;
        };
        let (from, to) = (self.band_offsets[band], self.band_offsets[band + 1]);
        let mut inside = false;
        for &i in &self.band_edges[from as usize..to as usize] {
            let [x1, y1, x2, y2] = self.edges[i as usize];
            // Orient the edge upwards; `cross` > 0 means p is left of it.
            let (ax, ay, bx, by) = if y1 <= y2 {
                (x1, y1, x2, y2)
            } else {
                (x2, y2, x1, y1)
            };
            let cross = i128::from(bx - ax) * i128::from(py - ay)
                - i128::from(px - ax) * i128::from(by - ay);
            if cross == 0 && (ay..=by).contains(&py) && (ax.min(bx)..=ax.max(bx)).contains(&px) {
                return Side::OnLine;
            }
            if ay <= py && py < by && cross > 0 {
                inside = !inside;
            }
        }
        if inside { Side::Inside } else { Side::Outside }
    }
}

/// `lon lat` in degrees → (lon, lat) in 1e-7°, in range.
fn parse_point(line: &str) -> Result<(i64, i64), String> {
    let mut it = line.split_whitespace();
    let (Some(x), Some(y), None) = (it.next(), it.next(), it.next()) else {
        return Err(format!("bad polygon line '{line}'"));
    };
    let deg = |s: &str, max: f64| -> Result<i64, String> {
        let v: f64 = s
            .parse()
            .map_err(|_| format!("bad coordinate '{s}' in polygon"))?;
        if !v.is_finite() || v.abs() > max {
            return Err(format!("coordinate '{s}' out of range"));
        }
        Ok((v * COORD_SCALE).round() as i64)
    };
    Ok((deg(x, 180.0)?, deg(y, 90.0)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(lat: f64, lon: f64) -> PointE7 {
        PointE7 {
            lat: (lat * COORD_SCALE).round() as i32,
            lon: (lon * COORD_SCALE).round() as i32,
        }
    }

    /// Two countries side by side, sharing the line lon = 13 from lat 55
    /// to 56, with a hole in the western one and an island to its north.
    const WEST: &str = "west\n1\n 12.0 55.0\n 13.0 55.0\n 13.0 56.0\n 12.0 56.0\nEND\n\
        !hole\n 12.2 55.2\n 12.4 55.2\n 12.4 55.4\n 12.2 55.4\nEND\n\
        island\n 12.0 57.0\n 12.5 57.0\n 12.5 57.5\nEND\nEND\n";
    const EAST: &str = "east\n1\n 13.0 56.0\n 13.0 55.0\n 14.0 55.0\n 14.0 56.0\nEND\nEND\n";

    #[test]
    fn tells_inside_from_outside_with_holes_and_islands() {
        let w = Border::parse(WEST).unwrap();
        assert_eq!(w.side(p(55.5, 12.5)), Side::Inside);
        assert_eq!(w.side(p(55.3, 12.3)), Side::Outside, "in the hole");
        assert_eq!(w.side(p(57.1, 12.2)), Side::Inside, "on the island");
        assert_eq!(w.side(p(55.5, 13.5)), Side::Outside);
        assert_eq!(w.side(p(54.0, 12.5)), Side::Outside, "south of it all");
        assert_eq!(w.side(p(60.0, 12.5)), Side::Outside, "north of it all");
        assert_eq!(w.bbox().min_lat, 550_000_000);
        assert_eq!(w.bbox().max_lat, 575_000_000);
    }

    #[test]
    fn neighbours_agree_on_every_point() {
        let (w, e) = (Border::parse(WEST).unwrap(), Border::parse(EAST).unwrap());
        // A dense grid across the shared line, between the outer edges:
        // no point is inside both, none between them, and the line is
        // both's.
        for i in 1..200 {
            for j in 0..=200 {
                let q = PointE7 {
                    lat: 550_000_000 + i * 50_000,
                    lon: 129_000_000 + j * 10_000,
                };
                let (a, b) = (w.side(q), e.side(q));
                if q.lon == 130_000_000 {
                    assert_eq!((a, b), (Side::OnLine, Side::OnLine), "{q:?}");
                } else {
                    assert!(
                        (a == Side::Inside) != (b == Side::Inside),
                        "{q:?}: {a:?} {b:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn vertices_and_edges_are_on_the_line() {
        let w = Border::parse(WEST).unwrap();
        assert_eq!(w.side(p(55.0, 12.0)), Side::OnLine);
        assert_eq!(w.side(p(55.0, 12.5)), Side::OnLine);
        assert_eq!(w.side(p(55.2, 12.3)), Side::OnLine, "the hole's edge");
        // The island's diagonal edge from (12.0, 57.0) to (12.5, 57.5).
        assert_eq!(w.side(p(57.25, 12.25)), Side::OnLine);
    }

    #[test]
    fn rejects_malformed_files_without_panicking() {
        for bad in [
            "",
            "name\n",
            "name\n1\n 12 55\n 13 55\n 13 56\n",
            "name\n1\n 12 55\n 13 55\nEND\nEND\n",
            "name\n1\n 12 55\n 13 55\n 13 56\nEND\n",
            "name\n1\n 12 55 7\n 13 55\n 13 56\nEND\nEND\n",
            "name\n1\n 12 x\n 13 55\n 13 56\nEND\nEND\n",
            "name\n1\n 12 95\n 13 55\n 13 56\nEND\nEND\n",
            "name\n1\n 200 55\n 13 55\n 13 56\nEND\nEND\n",
            "name\n1\n NaN 55\n 13 55\n 13 56\nEND\nEND\n",
            "name\n1\n 12 55\n 13 55\n 13 56\nEND\nEND\ntrailing\n",
            "name\nEND\n",
        ] {
            assert!(Border::parse(bad).is_err(), "{bad:?}");
        }
        assert!(Border::parse(&"x".repeat(MAX_FILE_BYTES + 1)).is_err());
    }

    #[test]
    fn crafted_bytes_never_panic() {
        // Every prefix of a valid file, and every single-byte change.
        for n in 0..WEST.len() {
            let _ = Border::parse(&WEST[..n]);
        }
        let bytes = WEST.as_bytes();
        for i in 0..bytes.len() {
            for &b in b"09-. \nE!" {
                let mut v = bytes.to_vec();
                v[i] = b;
                if let Ok(border) = Border::parse(std::str::from_utf8(&v).unwrap()) {
                    let _ = border.side(p(55.5, 12.5));
                    let _ = border.side(PointE7 {
                        lat: i32::MIN,
                        lon: i32::MAX,
                    });
                }
            }
        }
    }
}
