// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! What the map has along a line's way spans (ADR-0020).

use super::tests::{engine, north_draft, shaped_ladder};
use super::*;
use crate::section::MAX_SECTION_WAYS;

/// [`shaped_ladder`] with the north road's two halves (way 1, nodes 0–4
/// and 4–8, equally long) on `west` and `east`.
fn mixed(west: Surface, east: Surface) -> Engine {
    use crate::fixture::{L_N, L_NE, L_NW, L_S, L_SE, L_SW, Road, build};
    use crate::region::format::RoadClass;
    let nodes = [
        (55.72, 13.40),
        (55.72, 13.42),
        (55.72, 13.44),
        (55.70, 13.40),
        (55.70, 13.42),
        (55.70, 13.44),
    ];
    let north_road = |from, to, start: u32, lon: f64, surface| Road {
        surface,
        way_start: start,
        via: (1..4)
            .map(|k| (55.72, lon + 0.005 * f64::from(k)))
            .collect(),
        ..Road::new(from, to, RoadClass::Residential, 30, 1)
    };
    let roads = [
        north_road(L_NW, L_N, 0, 13.40, west),
        north_road(L_N, L_NE, 4, 13.42, east),
        Road::new(L_SW, L_S, RoadClass::Motorway, 110, 2),
        Road::new(L_S, L_SE, RoadClass::Motorway, 110, 2),
        Road::new(L_NW, L_SW, RoadClass::Unclassified, 50, 3),
        Road::new(L_NE, L_SE, RoadClass::Unclassified, 50, 4),
    ];
    engine(build(&nodes, &roads, 50_000))
}

fn span(way_id: i64, from_idx: u32, to_idx: u32) -> WaySpan {
    WaySpan {
        way_id,
        from_idx,
        to_idx,
    }
}

fn along(e: &Engine, ways: &[WaySpan]) -> Option<MapSurface> {
    e.map_surface_along(ways).unwrap()
}

#[test]
fn the_map_surface_along_a_line_is_the_longest_share() {
    // Ported from the overlay's per-favourite measure: the whole north
    // road on one surface.
    for (surface, want) in [
        (Surface::Asphalt, MapSurface::Paved),
        (Surface::Concrete, MapSurface::Paved),
        (Surface::Gravel, MapSurface::Gravel),
        (Surface::Dirt, MapSurface::Gravel),
        (Surface::Unknown, MapSurface::Untagged),
    ] {
        let e = engine(shaped_ladder(surface, 30));
        assert_eq!(
            along(&e, &north_draft(0, 8).ways),
            Some(want),
            "{surface:?}"
        );
    }
    // Two kinds: the longer share wins, whichever way the line runs.
    let e = mixed(Surface::Gravel, Surface::Asphalt);
    for ways in [[span(1, 0, 6)], [span(1, 6, 0)]] {
        assert_eq!(along(&e, &ways), Some(MapSurface::Gravel), "{ways:?}");
    }
    for ways in [[span(1, 3, 8)], [span(1, 8, 3)]] {
        assert_eq!(along(&e, &ways), Some(MapSurface::Paved), "{ways:?}");
    }
    let e = mixed(Surface::Unknown, Surface::Gravel);
    assert_eq!(along(&e, &[span(1, 0, 5)]), Some(MapSurface::Untagged));
    assert_eq!(along(&e, &[span(1, 3, 8)]), Some(MapSurface::Gravel));
}

#[test]
fn partial_spans_count_only_their_stretch_of_an_edge() {
    // Nodes 1–2 of the gravel half and 4–8, all of the paved half: paved.
    let e = mixed(Surface::Gravel, Surface::Asphalt);
    assert_eq!(
        along(&e, &[span(1, 1, 2), span(1, 4, 8)]),
        Some(MapSurface::Paved)
    );
    // Nodes 1–4 of the gravel half (three quarters) against nodes 4–6 of
    // the paved one (a half): gravel.
    assert_eq!(along(&e, &[span(1, 1, 6)]), Some(MapSurface::Gravel));
    // Only inside one edge: its surface.
    assert_eq!(along(&e, &[span(1, 2, 3)]), Some(MapSurface::Gravel));
    assert_eq!(along(&e, &[span(1, 7, 5)]), Some(MapSurface::Paved));
}

#[test]
fn a_tie_goes_to_paved_then_gravel() {
    // The two halves are equally long.
    for (west, east, want) in [
        (Surface::Asphalt, Surface::Gravel, MapSurface::Paved),
        (Surface::Gravel, Surface::Asphalt, MapSurface::Paved),
        (Surface::Asphalt, Surface::Unknown, MapSurface::Paved),
        (Surface::Gravel, Surface::Unknown, MapSurface::Gravel),
        (Surface::Unknown, Surface::Gravel, MapSurface::Gravel),
    ] {
        let e = mixed(west, east);
        assert_eq!(along(&e, &[span(1, 0, 8)]), Some(want), "{west:?} {east:?}");
        assert_eq!(along(&e, &[span(1, 8, 0)]), Some(want));
    }
}

#[test]
fn a_road_is_counted_once() {
    // Both directions of a two-way road, and the same span twice, are the
    // road once: nodes 0–4 gravel and 4–8 paved stay a tie.
    let e = mixed(Surface::Gravel, Surface::Asphalt);
    assert_eq!(
        along(
            &e,
            &[span(1, 0, 4), span(1, 4, 0), span(1, 0, 4), span(1, 4, 8)]
        ),
        Some(MapSurface::Paved)
    );
}

#[test]
fn off_the_map_is_none() {
    let e = mixed(Surface::Gravel, Surface::Asphalt);
    // An unknown way; a way of the region where the span covers no road.
    assert_eq!(along(&e, &[span(999_999, 0, 8)]), None);
    assert_eq!(along(&e, &[span(1, 20, 30)]), None);
    // Ways of the region that aren't spans count, unknown ones don't.
    assert_eq!(
        along(&e, &[span(999_999, 0, 8), span(1, 0, 3)]),
        Some(MapSurface::Gravel)
    );
}

#[test]
fn empty_or_oversized_input_is_an_error() {
    let e = mixed(Surface::Gravel, Surface::Asphalt);
    assert!(matches!(
        e.map_surface_along(&[]),
        Err(CoreError::InvalidArgument(_))
    ));
    let many = vec![span(1, 0, 8); MAX_SECTION_WAYS + 1];
    assert!(matches!(
        e.map_surface_along(&many),
        Err(CoreError::InvalidArgument(_))
    ));
    let most = vec![span(1, 0, 8); MAX_SECTION_WAYS];
    assert_eq!(along(&e, &most), Some(MapSurface::Paved));
}

#[test]
fn hostile_indexes_never_panic() {
    let e = mixed(Surface::Gravel, Surface::Asphalt);
    let max = u32::MAX;
    // Equal indexes cover nothing.
    for ways in [
        vec![span(1, 3, 3)],
        vec![span(1, max, max)],
        vec![span(1, 0, 0)],
        vec![span(i64::MIN, 0, max)],
        vec![span(0, 0, 8)],
        vec![span(-1, 0, 8)],
    ] {
        assert_eq!(along(&e, &ways), None, "{ways:?}");
    }
    // Past the way's last node: clipped to the road there is.
    assert_eq!(along(&e, &[span(1, 0, max)]), Some(MapSurface::Paved));
    assert_eq!(along(&e, &[span(1, max, 0)]), Some(MapSurface::Paved));
    assert_eq!(along(&e, &[span(1, 5, max)]), Some(MapSurface::Paved));
    assert_eq!(along(&e, &[span(1, max, 1)]), Some(MapSurface::Paved));
    assert_eq!(along(&e, &[span(1, max - 1, max)]), None);
    // Every way of the region, any indexes: no panic.
    for way_id in [1, 2, 3, 4] {
        for (a, b) in [(0, max), (max, 0), (max / 2, max), (1, 0)] {
            let _ = along(&e, &[span(way_id, a, b)]);
        }
    }
}
