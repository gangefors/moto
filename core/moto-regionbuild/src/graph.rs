// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Routable ways + node positions → routing graph → [`RegionData`].
//!
//! Routing nodes are the OSM nodes where ways meet or end (and the cut at
//! the region boundary); everything between two routing nodes becomes the
//! geometry of one edge. Routing nodes are numbered along a Hilbert curve.

use moto_core::LatLon;
use moto_core::curvature::curvature_metrics;
use moto_core::geo::polyline_length_m;
use moto_core::region::format::{
    BBoxE7, COORD_SCALE, CurvatureMetrics, Edge, PointE7, WayRef, edge_flags,
};
use moto_core::region::{RegionData, RegionInfo};

use crate::hilbert;
use crate::tags::{Oneway, WayAttrs};

/// A routable way as read from OSM.
#[derive(Debug, Clone)]
pub struct RawWay {
    pub id: i64,
    pub refs: Vec<i64>,
    pub attrs: WayAttrs,
}

/// Node positions sorted by OSM id (only nodes inside the region).
pub struct NodeIndex {
    ids: Vec<i64>,
    pos: Vec<PointE7>,
}

impl NodeIndex {
    pub fn new(mut nodes: Vec<(i64, PointE7)>) -> Self {
        nodes.sort_unstable_by_key(|n| n.0);
        nodes.dedup_by_key(|n| n.0);
        let (ids, pos) = nodes.into_iter().unzip();
        Self { ids, pos }
    }

    fn find(&self, id: i64) -> Option<u32> {
        self.ids.binary_search(&id).ok().map(|i| i as u32)
    }

    pub fn len(&self) -> usize {
        self.ids.len()
    }
}

/// A stretch of a way between two routing nodes, as node-index positions.
struct Segment {
    way: u32,
    /// Index of the first node in the way's node list.
    first_idx: u32,
    /// Indices into the [`NodeIndex`].
    nodes: Vec<u32>,
}

/// Build statistics for the report.
#[derive(Debug, Default)]
pub struct GraphStats {
    pub ways: usize,
    pub segments: usize,
    pub nodes: usize,
    pub edges: usize,
    pub shape_points: usize,
}

pub fn build(
    ways: &[RawWay],
    index: &NodeIndex,
    info: RegionInfo,
    grid_cell: (i32, i32),
) -> (RegionData, GraphStats) {
    // Pieces of ways inside the region: runs of consecutive known nodes.
    let mut pieces: Vec<(u32, u32, Vec<u32>)> = Vec::new(); // (way, first idx, nodes)
    for (w, way) in ways.iter().enumerate() {
        let mut run: Vec<u32> = Vec::new();
        let mut start = 0u32;
        for (i, &r) in way.refs.iter().enumerate() {
            match index.find(r) {
                Some(n) if run.last() == Some(&n) => {} // repeated node
                Some(n) => {
                    if run.is_empty() {
                        start = i as u32;
                    }
                    run.push(n);
                }
                None => {
                    if run.len() >= 2 {
                        pieces.push((w as u32, start, std::mem::take(&mut run)));
                    }
                    run.clear();
                }
            }
        }
        if run.len() >= 2 {
            pieces.push((w as u32, start, run));
        }
    }

    let pieces_ways = {
        let mut w: Vec<u32> = pieces.iter().map(|p| p.0).collect();
        w.dedup();
        w.len()
    };

    // Routing nodes: piece ends and nodes used more than once.
    let mut uses = vec![0u8; index.len()];
    let mut routing = vec![false; index.len()];
    for (_, _, nodes) in &pieces {
        for &n in nodes {
            uses[n as usize] = uses[n as usize].saturating_add(1);
        }
        routing[nodes[0] as usize] = true;
        routing[*nodes.last().unwrap() as usize] = true;
    }
    for (r, &u) in routing.iter_mut().zip(&uses) {
        *r |= u >= 2;
    }
    // A stretch that starts and ends at the same node would be a self-loop;
    // make its middle node a routing node so it becomes two edges.
    loop {
        let mut middles = Vec::new();
        for (_, _, nodes) in &pieces {
            for_each_split(nodes, &routing, |a, b| {
                if nodes[a] == nodes[b] && b - a >= 2 {
                    middles.push(nodes[(a + b) / 2]);
                }
            });
        }
        if middles.is_empty() {
            break;
        }
        for n in middles {
            routing[n as usize] = true;
        }
    }

    let mut segments: Vec<Segment> = Vec::new();
    for (way, first, nodes) in &pieces {
        for_each_split(nodes, &routing, |a, b| {
            segments.push(Segment {
                way: *way,
                first_idx: first + a as u32,
                nodes: nodes[a..=b].to_vec(),
            })
        });
    }

    // Number routing nodes along a Hilbert curve.
    let mut routing_nodes: Vec<u32> = (0..index.len() as u32)
        .filter(|&n| routing[n as usize] && uses[n as usize] > 0)
        .collect();
    let bbox = info.bbox;
    routing_nodes.sort_by_cached_key(|&n| hilbert::index(index.pos[n as usize], &bbox));
    let mut id_of = vec![u32::MAX; index.len()];
    for (id, &n) in routing_nodes.iter().enumerate() {
        id_of[n as usize] = id as u32;
    }
    let nodes: Vec<PointE7> = routing_nodes
        .iter()
        .map(|&n| index.pos[n as usize])
        .collect();

    // Geometries in the order of their first node, for locality.
    segments.sort_by_key(|s| (id_of[s.nodes[0] as usize], s.way, s.first_idx));

    struct Draft {
        edge: Edge,
        curvature: CurvatureMetrics,
        way_ref: WayRef,
    }
    let mut drafts: Vec<Draft> = Vec::new();
    let mut geometry_offsets = vec![0u32];
    let mut shape_points: Vec<PointE7> = Vec::new();
    for s in &segments {
        let way = &ways[s.way as usize];
        let attrs = way.attrs;
        let mut pts: Vec<PointE7> = s.nodes.iter().map(|&n| index.pos[n as usize]).collect();
        let last_idx = s.first_idx + s.nodes.len() as u32 - 1;
        let (mut from_idx, mut to_idx) = (s.first_idx, last_idx);
        let (mut tail, mut head) = (
            id_of[s.nodes[0] as usize],
            id_of[*s.nodes.last().unwrap() as usize],
        );
        // Store one-way-backward geometry in travel direction so the only
        // edge runs along it (the snapping grid indexes those edges).
        if attrs.oneway == Oneway::Backward {
            pts.reverse();
            std::mem::swap(&mut from_idx, &mut to_idx);
            std::mem::swap(&mut tail, &mut head);
        }
        let geometry = (geometry_offsets.len() - 1) as u32;
        let line: Vec<LatLon> = pts.iter().map(|&p| latlon(p)).collect();
        let length_dm = (polyline_length_m(&line) * 10.0).round() as u32;
        let curvature = curvature_metrics(&line);
        shape_points.extend_from_slice(&pts);
        geometry_offsets.push(shape_points.len() as u32);

        let edge = |tail, head, reversed: bool| Edge {
            tail,
            head,
            length_dm,
            geometry,
            speed_kmh: attrs.speed_kmh,
            class: attrs.class as u8,
            surface: attrs.surface as u8,
            flags: attrs.flags | if reversed { edge_flags::REVERSED } else { 0 },
        };
        drafts.push(Draft {
            edge: edge(tail, head, false),
            curvature,
            way_ref: WayRef {
                way_id: way.id,
                from_idx,
                to_idx,
            },
        });
        if attrs.oneway == Oneway::No {
            drafts.push(Draft {
                edge: edge(head, tail, true),
                curvature,
                way_ref: WayRef {
                    way_id: way.id,
                    from_idx: to_idx,
                    to_idx: from_idx,
                },
            });
        }
    }
    drafts.sort_by_key(|d| (d.edge.tail, d.edge.head, d.edge.geometry));

    let stats = GraphStats {
        ways: pieces_ways,
        segments: segments.len(),
        nodes: nodes.len(),
        edges: drafts.len(),
        shape_points: shape_points.len(),
    };
    let data = RegionData {
        info,
        nodes,
        edges: drafts.iter().map(|d| d.edge).collect(),
        geometry_offsets,
        shape_points,
        curvature: drafts.iter().map(|d| d.curvature).collect(),
        way_refs: drafts.iter().map(|d| d.way_ref).collect(),
        grid_cell,
    };
    (data, stats)
}

/// Calls `f(a, b)` for each stretch `nodes[a..=b]` between routing nodes.
fn for_each_split(nodes: &[u32], routing: &[bool], mut f: impl FnMut(usize, usize)) {
    let mut a = 0;
    for b in 1..nodes.len() {
        if routing[nodes[b] as usize] || b == nodes.len() - 1 {
            f(a, b);
            a = b;
        }
    }
}

fn latlon(p: PointE7) -> LatLon {
    LatLon {
        lat: f64::from(p.lat) / COORD_SCALE,
        lon: f64::from(p.lon) / COORD_SCALE,
    }
}

/// Converts degrees to a fixed-point bounding box.
pub fn bbox_e7(min_lat: f64, min_lon: f64, max_lat: f64, max_lon: f64) -> BBoxE7 {
    let e7 = |v: f64| (v * COORD_SCALE).round() as i32;
    BBoxE7 {
        min_lat: e7(min_lat),
        min_lon: e7(min_lon),
        max_lat: e7(max_lat),
        max_lon: e7(max_lon),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use moto_core::region::Region;
    use moto_core::region::format::{RoadClass, Surface};

    fn attrs(oneway: Oneway) -> WayAttrs {
        WayAttrs {
            class: RoadClass::Tertiary,
            speed_kmh: 60,
            surface: Surface::Asphalt,
            flags: 0,
            oneway,
        }
    }

    fn p(lat: f64, lon: f64) -> PointE7 {
        PointE7 {
            lat: (lat * COORD_SCALE).round() as i32,
            lon: (lon * COORD_SCALE).round() as i32,
        }
    }

    fn info() -> RegionInfo {
        RegionInfo {
            bbox: bbox_e7(55.0, 13.0, 56.0, 14.0),
            ..RegionInfo::default()
        }
    }

    /// OSM nodes 1..=9 on a small grid; node 99 lies outside the region.
    fn index() -> NodeIndex {
        NodeIndex::new(vec![
            (1, p(55.50, 13.50)),
            (2, p(55.50, 13.51)),
            (3, p(55.50, 13.52)),
            (4, p(55.50, 13.53)),
            (5, p(55.51, 13.51)),
            (6, p(55.52, 13.51)),
            (7, p(55.51, 13.52)),
            (8, p(55.51, 13.53)),
            (9, p(55.52, 13.53)),
        ])
    }

    fn build_region(ways: &[RawWay]) -> (Region, GraphStats) {
        let (data, stats) = build(ways, &index(), info(), (5_000, 5_000));
        (
            Region::from_bytes(&data.to_bytes().unwrap()).unwrap(),
            stats,
        )
    }

    #[test]
    fn collapses_degree_two_nodes_into_geometry() {
        // 1-2-3-4 with a side road 2-5-6: routing nodes 1, 2, 4, 6.
        let ways = [
            RawWay {
                id: 10,
                refs: vec![1, 2, 3, 4],
                attrs: attrs(Oneway::No),
            },
            RawWay {
                id: 11,
                refs: vec![2, 5, 6],
                attrs: attrs(Oneway::No),
            },
        ];
        let (r, stats) = build_region(&ways);
        assert_eq!(stats.nodes, 4);
        assert_eq!(stats.segments, 3);
        assert_eq!(r.edge_count(), 6);
        // The 2→4 edge carries node 3 as a shape point.
        let e = r
            .edges()
            .iter()
            .zip(r.way_refs())
            .find(|(_, w)| w.way_id == 10 && (w.from_idx, w.to_idx) == (1, 3))
            .unwrap();
        assert_eq!(r.geometry(e.0.geometry).len(), 3);
        assert!(
            (e.0.length_dm as f64 - 2.0 * 6_300.0).abs() < 50.0,
            "{:?}",
            e.0
        );
    }

    #[test]
    fn oneway_ways_get_one_edge_in_travel_direction() {
        let ways = [
            RawWay {
                id: 20,
                refs: vec![1, 2],
                attrs: attrs(Oneway::Forward),
            },
            RawWay {
                id: 21,
                refs: vec![3, 4],
                attrs: attrs(Oneway::Backward),
            },
        ];
        let (r, _) = build_region(&ways);
        assert_eq!(r.edge_count(), 2);
        for (e, w) in r.edges().iter().zip(r.way_refs()) {
            assert_eq!(e.flags & edge_flags::REVERSED, 0);
            let (from, to) = (r.nodes()[e.tail as usize], r.nodes()[e.head as usize]);
            match w.way_id {
                20 => assert_eq!(
                    (from, to, w.from_idx, w.to_idx),
                    (p(55.5, 13.5), p(55.5, 13.51), 0, 1)
                ),
                21 => assert_eq!(
                    (from, to, w.from_idx, w.to_idx),
                    (p(55.5, 13.53), p(55.5, 13.52), 1, 0)
                ),
                _ => unreachable!(),
            }
        }
    }

    #[test]
    fn splits_closed_loops_and_cuts_at_the_region_edge() {
        // A roundabout-like loop 5-6-9-8-7-5 attached at 5, and a way that
        // leaves the region at node 99.
        let ways = [
            RawWay {
                id: 30,
                refs: vec![5, 6, 9, 8, 7, 5],
                attrs: attrs(Oneway::Forward),
            },
            RawWay {
                id: 31,
                refs: vec![1, 2, 99, 3, 4],
                attrs: attrs(Oneway::No),
            },
        ];
        let (r, _) = build_region(&ways);
        // Loop: 5 is the only junction, so its middle node 9 is added.
        let loop_edges: Vec<_> = r
            .edges()
            .iter()
            .zip(r.way_refs())
            .filter(|(_, w)| w.way_id == 30)
            .collect();
        assert_eq!(loop_edges.len(), 2);
        assert!(loop_edges.iter().all(|(e, _)| e.tail != e.head));
        // Way 31 becomes two separate pieces, 1–2 and 3–4.
        let cut: Vec<_> = r.way_refs().iter().filter(|w| w.way_id == 31).collect();
        assert_eq!(cut.len(), 4);
        assert!(cut.iter().any(|w| (w.from_idx, w.to_idx) == (3, 4)));
        assert!(cut.iter().any(|w| (w.from_idx, w.to_idx) == (0, 1)));
    }

    #[test]
    fn nodes_follow_the_hilbert_curve() {
        let ways = [RawWay {
            id: 40,
            refs: vec![1, 2, 3, 4],
            attrs: attrs(Oneway::No),
        }];
        let (data, _) = build(&ways, &index(), info(), (5_000, 5_000));
        let keys: Vec<u64> = data
            .nodes
            .iter()
            .map(|&n| hilbert::index(n, &info().bbox))
            .collect();
        assert!(keys.windows(2).all(|w| w[0] <= w[1]));
    }
}
