// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Hilbert curve index, used to order routing nodes so that nodes close on
//! the map are close in the file (ADR-0005).

use moto_core::region::format::{BBoxE7, PointE7};

/// Bits per axis.
const ORDER: u32 = 16;

/// Position of `p` along a Hilbert curve over `bbox` (points outside are
/// clamped to it).
pub fn index(p: PointE7, bbox: &BBoxE7) -> u64 {
    let side = (1u64 << ORDER) - 1;
    let scale = |v: i32, lo: i32, hi: i32| -> u64 {
        let span = (i64::from(hi) - i64::from(lo)).max(1) as f64;
        let f = ((i64::from(v) - i64::from(lo)) as f64 / span).clamp(0.0, 1.0);
        (f * side as f64).round() as u64
    };
    xy2d(
        scale(p.lon, bbox.min_lon, bbox.max_lon),
        scale(p.lat, bbox.min_lat, bbox.max_lat),
    )
}

/// Classic Hilbert xy → distance on a 2^ORDER square.
fn xy2d(mut x: u64, mut y: u64) -> u64 {
    let n = 1u64 << ORDER;
    let mut d = 0;
    let mut s = n / 2;
    while s > 0 {
        let rx = u64::from(x & s > 0);
        let ry = u64::from(y & s > 0);
        d += s * s * ((3 * rx) ^ ry);
        if ry == 0 {
            if rx == 1 {
                x = n - 1 - x;
                y = n - 1 - y;
            }
            std::mem::swap(&mut x, &mut y);
        }
        s /= 2;
    }
    d
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visits_every_cell_of_a_small_square_once_with_unit_steps() {
        // On the full 2^16 grid, check the first 4×4 block: the curve's first
        // 16 positions stay within it and neighbours are adjacent.
        let mut cells: Vec<(u64, u64, u64)> = (0..4)
            .flat_map(|x| (0..4).map(move |y| (xy2d(x, y), x, y)))
            .collect();
        cells.sort();
        assert_eq!(
            cells.iter().map(|c| c.0).collect::<Vec<_>>(),
            (0..16).collect::<Vec<_>>()
        );
        for w in cells.windows(2) {
            let d = w[0].1.abs_diff(w[1].1) + w[0].2.abs_diff(w[1].2);
            assert_eq!(d, 1, "{w:?}");
        }
    }

    #[test]
    fn clamps_points_outside_the_box() {
        let bbox = BBoxE7 {
            min_lat: 0,
            min_lon: 0,
            max_lat: 100,
            max_lon: 100,
        };
        let far = PointE7 {
            lat: 1_000,
            lon: -1_000,
        };
        let corner = PointE7 { lat: 100, lon: 0 };
        assert_eq!(index(far, &bbox), index(corner, &bbox));
    }
}
