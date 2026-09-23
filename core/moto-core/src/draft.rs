// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Proposing a section from the map (PRD R2): the road between two points,
//! as OSM way spans plus geometry, ready for the rider to name, rate and
//! save.

use crate::geo::haversine_m;
use crate::region::Region;
use crate::region::format::{COORD_SCALE, Edge, PointE7, edge_flags};
use crate::route::Partial;
use crate::section::{MAX_SECTION_POINTS, MAX_SECTION_WAYS, WaySpan};
use crate::{CoreError, LatLon};

/// A proposed section, not saved yet.
#[derive(Debug, Clone, PartialEq)]
pub struct SectionDraft {
    /// The OSM ways covered, in riding order, each as a node range.
    pub ways: Vec<WaySpan>,
    /// The road between the two points, starting and ending at them.
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
}

fn latlon(p: PointE7) -> LatLon {
    LatLon {
        lat: f64::from(p.lat) / COORD_SCALE,
        lon: f64::from(p.lon) / COORD_SCALE,
    }
}

/// An edge's shape in travel direction.
fn edge_line(region: &Region, e: &Edge) -> Vec<LatLon> {
    let mut line: Vec<LatLon> = region
        .geometry(e.geometry)
        .iter()
        .map(|&p| latlon(p))
        .collect();
    if e.flags & edge_flags::REVERSED != 0 {
        line.reverse();
    }
    line
}

/// Index of the shape point at or before fraction `frac` of the line's
/// length (`round_up`: at or after), so a node range rounded outwards
/// covers everything between two fractions.
fn point_index(line: &[LatLon], frac: f64, round_up: bool) -> usize {
    let lengths: Vec<f64> = line.windows(2).map(|w| haversine_m(w[0], w[1])).collect();
    let total: f64 = lengths.iter().sum();
    let target = frac.clamp(0.0, 1.0) * total;
    let mut walked = 0.0;
    for (i, &len) in lengths.iter().enumerate() {
        let end = walked + len;
        if round_up && target <= walked + 1e-9 {
            return i;
        }
        if round_up && target <= end + 1e-9 {
            return i + 1;
        }
        if !round_up && target < end - 1e-9 {
            return i;
        }
        walked = end;
    }
    line.len().saturating_sub(1)
}

/// Builds a draft from path pieces (in travel order), refusing one too
/// small or too big to save as a section.
pub(crate) fn from_path(region: &Region, parts: &[Partial]) -> Result<SectionDraft, CoreError> {
    let draft = trace(region, parts);
    if draft.geometry.len() < 2 || draft.distance_m <= 0.0 {
        return Err(CoreError::InvalidArgument(
            "pick two different points along the road".into(),
        ));
    }
    if draft.geometry.len() > MAX_SECTION_POINTS || draft.ways.len() > MAX_SECTION_WAYS {
        return Err(CoreError::InvalidArgument(
            "that stretch is too long for one section".into(),
        ));
    }
    Ok(draft)
}

/// The OSM way spans, geometry and length of path pieces (in travel order).
pub(crate) fn trace(region: &Region, parts: &[Partial]) -> SectionDraft {
    let mut ways: Vec<WaySpan> = Vec::new();
    let mut geometry: Vec<LatLon> = Vec::new();
    let mut distance_m = 0.0;
    for part in parts {
        let e = region.edges()[part.edge as usize];
        let frac = (part.to - part.from).max(0.0);
        distance_m += frac * f64::from(e.length_dm) / 10.0;
        let line = edge_line(region, &e);
        for p in crate::geo::polyline_slice(&line, part.from, part.to) {
            if geometry.last() != Some(&p) {
                geometry.push(p);
            }
        }

        // Shape point i of the edge (in travel order) is way node
        // from_idx ± i, per the region file's way refs.
        let r = region.way_refs()[part.edge as usize];
        let (i, j) = (
            point_index(&line, part.from, false),
            point_index(&line, part.to, true),
        );
        if j <= i {
            continue; // an empty piece
        }
        let node = |k: usize| -> u32 {
            let k = u32::try_from(k).unwrap_or(u32::MAX);
            if r.to_idx >= r.from_idx {
                r.from_idx.saturating_add(k).min(r.to_idx)
            } else {
                r.from_idx.saturating_sub(k).max(r.to_idx)
            }
        };
        let span = WaySpan {
            way_id: r.way_id,
            from_idx: node(i),
            to_idx: node(j),
        };
        // Pieces of one way that follow on from each other become one span.
        match ways.last_mut() {
            Some(prev)
                if prev.way_id == span.way_id
                    && prev.to_idx == span.from_idx
                    && (prev.to_idx >= prev.from_idx) == (span.to_idx >= span.from_idx) =>
            {
                prev.to_idx = span.to_idx;
            }
            _ => ways.push(span),
        }
    }
    SectionDraft {
        ways,
        geometry,
        distance_m,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixture::{self, Road};
    use crate::region::format::{RoadClass, Surface};
    use crate::{Avoid, Engine, RouteOptions};

    fn engine(data: crate::region::RegionData) -> Engine {
        Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
    }

    fn span(way_id: i64, from_idx: u32, to_idx: u32) -> WaySpan {
        WaySpan {
            way_id,
            from_idx,
            to_idx,
        }
    }

    /// Degrees of longitude at 55.7°N, in metres.
    const M_PER_DEG_LON: f64 = 62_742.0;

    #[test]
    fn follows_the_road_between_two_points() {
        // From A–B near A, through B, along the one-way B→D.
        let e = engine(fixture::region());
        let d = e
            .section_between(ll(55.7001, 13.202), ll(55.7001, 13.219))
            .unwrap();
        assert_eq!(d.ways, [span(100, 0, 1), span(300, 0, 1)]);
        assert!((d.distance_m - 0.017 * M_PER_DEG_LON).abs() < 5.0, "{d:?}");
        assert!((d.geometry[0].lon - 13.202).abs() < 1e-6);
        assert!((d.geometry.last().unwrap().lon - 13.219).abs() < 1e-6);
        assert!(
            d.geometry.iter().any(|p| (p.lon - 13.21).abs() < 1e-9),
            "passes B"
        );
    }

    #[test]
    fn records_direction_against_the_way() {
        // Westwards along A–B, whose OSM way runs east: nodes 1 → 0.
        let e = engine(fixture::region());
        let d = e
            .section_between(ll(55.7001, 13.208), ll(55.7001, 13.202))
            .unwrap();
        assert_eq!(d.ways, [span(100, 1, 0)]);
        assert!(d.geometry[0].lon > d.geometry[1].lon);
    }

    #[test]
    fn covers_the_nodes_of_a_partly_ridden_bend() {
        // B–C has one shape node (the bend); both points are between B and
        // C, one on each side of the bend: the span covers nodes 0..2.
        let e = engine(fixture::region());
        let d = e
            .section_between(ll(55.7025, 13.211), ll(55.7075, 13.211))
            .unwrap();
        assert_eq!(d.ways, [span(200, 0, 2)]);
        assert!(
            d.geometry
                .iter()
                .any(|p| (p.lat - 55.705).abs() < 1e-9 && (p.lon - 13.212).abs() < 1e-9)
        );
    }

    #[test]
    fn merges_pieces_of_one_way() {
        // The ladder's north road is one OSM way split at the middle node.
        let e = engine(fixture::ladder(Surface::Asphalt));
        let d = e
            .section_between(ll(55.7201, 13.405), ll(55.7201, 13.435))
            .unwrap();
        assert_eq!(d.ways, [span(1, 0, 2)]);
    }

    #[test]
    fn stays_on_the_picked_road_not_a_faster_one() {
        // With the north road at walking pace, the fastest way along it is
        // via the motorway in the south; a section stays on the road picked.
        let mut data = fixture::ladder(Surface::Asphalt);
        for edge in &mut data.edges {
            if edge.class == RoadClass::Residential as u8 {
                edge.speed_kmh = 5;
            }
        }
        let e = engine(data);
        let (a, b) = (ll(55.7201, 13.401), ll(55.7201, 13.439));
        let anything = RouteOptions {
            avoid: Avoid {
                motorways: false,
                unpaved: false,
                ferries: false,
            },
            ..RouteOptions::default()
        };
        let fast = e.route(a, b, &anything).unwrap();
        assert!(
            fast.geometry.iter().any(|p| p.lat < 55.71),
            "the route goes south"
        );
        let d = e.section_between(a, b).unwrap();
        assert!(d.geometry.iter().all(|p| p.lat > 55.719), "{d:?}");
        assert_eq!(d.ways, [span(1, 0, 2)]);
    }

    #[test]
    fn rejects_empty_and_impossible_sections() {
        let e = engine(fixture::region());
        let p = ll(55.7001, 13.205);
        assert!(matches!(
            e.section_between(p, p),
            Err(CoreError::InvalidArgument(_))
        ));
        assert!(matches!(
            e.section_between(p, ll(56.5, 14.0)),
            Err(CoreError::OutsideRegion { .. })
        ));
        assert!(matches!(
            e.section_between(ll(91.0, 0.0), p),
            Err(CoreError::InvalidCoordinate { .. })
        ));
        // Backwards along the one-way B→D.
        assert!(matches!(
            e.section_between(ll(55.7001, 13.218), ll(55.7001, 13.212)),
            Err(CoreError::NoRoute(_))
        ));
    }

    #[test]
    fn splits_way_spans_at_way_changes() {
        // A two-way road built from two OSM ways meeting in the middle.
        let nodes = [(55.0, 13.0), (55.0, 13.01), (55.0, 13.02)];
        let roads = [
            Road::new(0, 1, RoadClass::Tertiary, 70, 7),
            Road {
                way_start: 4,
                ..Road::new(1, 2, RoadClass::Tertiary, 70, 8)
            },
        ];
        let e = engine(fixture::build(&nodes, &roads, 50_000));
        let d = e
            .section_between(ll(55.0001, 13.001), ll(55.0001, 13.019))
            .unwrap();
        assert_eq!(d.ways, [span(7, 0, 1), span(8, 4, 5)]);
    }

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    #[test]
    fn point_index_rounds_outwards() {
        // Three equal segments.
        let line = [
            ll(55.0, 13.0),
            ll(55.0, 13.01),
            ll(55.0, 13.02),
            ll(55.0, 13.03),
        ];
        assert_eq!(point_index(&line, 0.0, false), 0);
        assert_eq!(point_index(&line, 0.0, true), 0);
        assert_eq!(point_index(&line, 0.5, false), 1);
        assert_eq!(point_index(&line, 0.5, true), 2);
        assert_eq!(point_index(&line, 1.0 / 3.0, true), 1);
        assert_eq!(point_index(&line, 1.0, false), 3);
        assert_eq!(point_index(&line, 1.0, true), 3);
        assert_eq!(point_index(&line[..1], 0.5, true), 0);
    }
}
