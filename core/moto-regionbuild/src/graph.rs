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
use std::collections::HashMap;

use moto_core::region::format::{
    BBoxE7, BorderNode, COORD_SCALE, CurvatureMetrics, Edge, GeometryName, NO_NAME, Place,
    PlaceKind, PointE7, WayRef, border_flags, edge_flags,
};
use moto_core::region::{RegionData, RegionInfo, RoadNames};

use crate::hilbert;
use crate::poly::{Border, Side};
use crate::tags::{Oneway, WayAttrs};

/// A routable way as read from OSM.
#[derive(Debug, Clone)]
pub struct RawWay {
    pub id: i64,
    pub refs: Vec<i64>,
    pub attrs: WayAttrs,
    /// Its road number (`ref`) and name, cleaned.
    pub road_ref: Option<String>,
    pub name: Option<String>,
}

/// Numbers the strings of the names, each once.
#[derive(Default)]
struct Strings {
    ids: HashMap<String, u32>,
    list: Vec<String>,
}

impl Strings {
    fn from(list: Vec<String>) -> Self {
        let ids = list
            .iter()
            .enumerate()
            .map(|(i, s)| (s.clone(), i as u32))
            .collect();
        Self { ids, list }
    }

    fn id(&mut self, s: Option<&str>) -> u32 {
        let Some(s) = s else { return NO_NAME };
        if let Some(&id) = self.ids.get(s) {
            return id;
        }
        let id = self.list.len() as u32;
        self.ids.insert(s.to_owned(), id);
        self.list.push(s.to_owned());
        id
    }
}

/// Adds the named `places` to `names`, sorted by latitude (then by
/// position and name, so the file doesn't depend on input order).
pub fn add_places(names: &mut RoadNames, places: &[(PointE7, PlaceKind, String)]) {
    let mut strings = Strings::from(std::mem::take(&mut names.strings));
    let mut list: Vec<Place> = places
        .iter()
        .map(|(pos, kind, name)| Place {
            pos: *pos,
            name: strings.id(Some(name)),
            kind: *kind as u8,
            reserved: [0; 3],
        })
        .collect();
    list.sort_by_key(|p| (p.pos.lat, p.pos.lon, p.kind, p.name));
    list.dedup();
    names.places = list;
    names.strings = strings.list;
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
    /// Small road networks cut off from the rest, dropped, and their
    /// total length in metres.
    pub fragments: usize,
    pub fragment_m: f64,
    pub ways: usize,
    pub segments: usize,
    pub nodes: usize,
    pub edges: usize,
    pub shape_points: usize,
}

/// Which side of the region's border each node lies on, worked out once
/// per node as ways ask (ADR-0009). Without a border every node is inside.
struct Sides<'a> {
    border: Option<&'a Border>,
    index: &'a NodeIndex,
    /// 0 = not asked yet, else `Side as u8 + 1`.
    cache: Vec<u8>,
}

impl Sides<'_> {
    fn of(&mut self, n: u32) -> Side {
        let Some(border) = self.border else {
            return Side::Inside;
        };
        let c = &mut self.cache[n as usize];
        if *c == 0 {
            *c = match border.side(self.index.pos[n as usize]) {
                Side::Inside => 1,
                Side::OnLine => 2,
                Side::Outside => 3,
            };
        }
        match *c {
            1 => Side::Inside,
            2 => Side::OnLine,
            _ => Side::Outside,
        }
    }
}

/// Builds the routing graph of `ways`. Networks of connected roads
/// shorter than `min_network_m` in all are left out (see
/// [`drop_fragments`]). With a `border` (ADR-0009) roads are cut one node
/// past it: that node is a stub, and it, points on the line and their
/// neighbours along the road are routing nodes, listed in the border
/// table so the phone can link the region to its neighbours.
pub fn build(
    ways: &[RawWay],
    index: &NodeIndex,
    info: RegionInfo,
    grid_cell: (i32, i32),
    min_network_m: f64,
    border: Option<&Border>,
) -> (RegionData, GraphStats) {
    let mut sides = Sides {
        border,
        index,
        cache: if border.is_some() {
            vec![0; index.len()]
        } else {
            Vec::new()
        },
    };
    // Pieces of ways inside the region: runs of consecutive known nodes
    // that lie inside the border or right next to a node that does.
    let mut pieces: Vec<(u32, u32, Vec<u32>)> = Vec::new(); // (way, first idx, nodes)
    for (w, way) in ways.iter().enumerate() {
        let known: Vec<Option<(u32, bool)>> = way
            .refs
            .iter()
            .map(|&r| index.find(r).map(|n| (n, sides.of(n) != Side::Outside)))
            .collect();
        let inside_at = |i: usize| known.get(i).copied().flatten().is_some_and(|k| k.1);
        let mut run: Vec<u32> = Vec::new();
        let mut start = 0u32;
        for (i, k) in known.iter().enumerate() {
            let keep = k.filter(|&(_, inside)| {
                inside || inside_at(i + 1) || i.checked_sub(1).is_some_and(inside_at)
            });
            match keep {
                Some((n, _)) if run.last() == Some(&n) => {} // repeated node
                Some((n, _)) => {
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

    let (fragments, fragment_m) = drop_fragments(&mut pieces, index, min_network_m);

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
    // At the border: stubs, points on the line and their neighbours along
    // the road are routing nodes, so every node a neighbour's stub stands
    // for is one here.
    let mut at_border = vec![false; if border.is_some() { index.len() } else { 0 }];
    if border.is_some() {
        for (_, _, nodes) in &pieces {
            for (k, &n) in nodes.iter().enumerate() {
                if sides.of(n) != Side::Inside {
                    let (lo, hi) = (k.saturating_sub(1), (k + 1).min(nodes.len() - 1));
                    for &m in &nodes[lo..=hi] {
                        at_border[m as usize] = true;
                        routing[m as usize] = true;
                    }
                }
            }
        }
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
    let mut strings = Strings::default();
    let mut geometry_names: Vec<GeometryName> = Vec::new();
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
        geometry_names.push(GeometryName {
            road_ref: strings.id(way.road_ref.as_deref()),
            name: strings.id(way.name.as_deref()),
        });

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
        fragments,
        fragment_m,
        ways: pieces_ways,
        segments: segments.len(),
        nodes: nodes.len(),
        edges: drafts.len(),
        shape_points: shape_points.len(),
    };
    // The border table, sorted by OSM id as the node index is.
    let border_nodes: Vec<BorderNode> = at_border
        .iter()
        .enumerate()
        .filter(|&(n, &b)| b && id_of[n] != u32::MAX)
        .map(|(n, _)| BorderNode {
            osm_id: index.ids[n],
            node: id_of[n],
            flags: if sides.of(n as u32) == Side::Outside {
                border_flags::OUTSIDE
            } else {
                0
            },
        })
        .collect();
    let data = RegionData {
        info,
        nodes,
        edges: drafts.iter().map(|d| d.edge).collect(),
        geometry_offsets,
        shape_points,
        curvature: drafts.iter().map(|d| d.curvature).collect(),
        way_refs: drafts.iter().map(|d| d.way_ref).collect(),
        grid_cell,
        names: RoadNames {
            strings: strings.list,
            geometry_names,
            places: Vec::new(),
        },
        border: border_nodes,
        meta: None,
    };
    (data, stats)
}

/// Removes the way pieces of every network (roads connected to each
/// other, whatever their direction) shorter than `min_m` in all: a
/// car park, a gated estate or a road cut by the region's edge, which a
/// tap or a route end would snap to and then find no way out of. The
/// largest network always stays, however short (a small test region).
/// Returns how many networks were dropped and their total length.
fn drop_fragments(
    pieces: &mut Vec<(u32, u32, Vec<u32>)>,
    index: &NodeIndex,
    min_m: f64,
) -> (usize, f64) {
    if min_m.is_nan() || min_m <= 0.0 || pieces.is_empty() {
        return (0, 0.0);
    }
    fn root(parent: &mut [u32], mut x: u32) -> u32 {
        while parent[x as usize] != x {
            let up = parent[parent[x as usize] as usize];
            parent[x as usize] = up;
            x = up;
        }
        x
    }
    let mut parent: Vec<u32> = (0..index.len() as u32).collect();
    for (_, _, nodes) in pieces.iter() {
        for &n in &nodes[1..] {
            let (a, b) = (root(&mut parent, nodes[0]), root(&mut parent, n));
            if a != b {
                parent[a as usize] = b;
            }
        }
    }
    let mut length = vec![0.0f64; index.len()];
    let piece_m: Vec<f64> = pieces
        .iter()
        .map(|(_, _, nodes)| {
            let line: Vec<LatLon> = nodes
                .iter()
                .map(|&n| latlon(index.pos[n as usize]))
                .collect();
            polyline_length_m(&line)
        })
        .collect();
    for ((_, _, nodes), &m) in pieces.iter().zip(&piece_m) {
        length[root(&mut parent, nodes[0]) as usize] += m;
    }
    let largest = (0..length.len())
        .max_by(|&a, &b| length[a].total_cmp(&length[b]))
        .unwrap_or(0);
    let mut dropped = std::collections::HashSet::new();
    let mut dropped_m = 0.0;
    let mut keep = Vec::with_capacity(pieces.len());
    for (piece, m) in pieces.drain(..).zip(piece_m) {
        let r = root(&mut parent, piece.2[0]);
        if length[r as usize] < min_m && r as usize != largest {
            dropped.insert(r);
            dropped_m += m;
        } else {
            keep.push(piece);
        }
    }
    *pieces = keep;
    (dropped.len(), dropped_m)
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
        let (data, stats) = build(ways, &index(), info(), (5_000, 5_000), 0.0, None);
        (
            Region::from_bytes(&data.to_bytes().unwrap()).unwrap(),
            stats,
        )
    }

    /// A box from lon `w` to `e` (degrees) around the test grid.
    fn country(w: f64, e: f64) -> Border {
        let d = |v: f64| (v * COORD_SCALE).round() as i64;
        Border::from_rings(&[vec![
            (d(w), d(55.4)),
            (d(e), d(55.4)),
            (d(e), d(55.6)),
            (d(w), d(55.4) + d(0.2)),
        ]])
        .unwrap()
    }

    /// The border table as (OSM id, stub).
    fn table(ways: &[RawWay], border: &Border) -> Vec<(i64, bool)> {
        let (data, _) = build(ways, &index(), info(), (5_000, 5_000), 0.0, Some(border));
        let region = Region::from_bytes(&data.to_bytes().unwrap()).unwrap();
        region
            .border_nodes()
            .iter()
            .map(|b| {
                assert!((b.node as usize) < region.nodes().len());
                (b.osm_id, b.flags & border_flags::OUTSIDE != 0)
            })
            .collect()
    }

    /// Every stub on one side stands for a node that is a routing node,
    /// not a stub, on the other.
    fn stubs_link(a: &[(i64, bool)], b: &[(i64, bool)]) {
        for (x, y) in [(a, b), (b, a)] {
            for &(id, stub) in x {
                if stub {
                    assert!(y.contains(&(id, false)), "stub {id} has no twin in {y:?}");
                }
            }
        }
    }

    #[test]
    fn cuts_roads_one_node_past_the_border() {
        // The road 1-2-3-4 crosses a border between 2 and 3; the side road
        // 2-5-6 stays in the west.
        let ways = [
            RawWay {
                id: 10,
                refs: vec![1, 2, 3, 4],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
            },
            RawWay {
                id: 11,
                refs: vec![2, 5, 6],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
            },
        ];
        let (west, east) = (country(13.4, 13.515), country(13.515, 13.6));
        let w = table(&ways, &west);
        let e = table(&ways, &east);
        // The west keeps 3 as its stub; the east keeps 2 as its stub.
        assert_eq!(w, vec![(2, false), (3, true)]);
        assert_eq!(e, vec![(2, true), (3, false)]);
        stubs_link(&w, &e);
        // The west has no node past its stub, the east none of the side road.
        let (data, _) = build(&ways, &index(), info(), (5_000, 5_000), 0.0, Some(&east));
        assert_eq!(data.nodes.len(), 3, "east: 2 (stub), 3 (next to it) and 4");
        let (data, _) = build(&ways, &index(), info(), (5_000, 5_000), 0.0, Some(&west));
        assert_eq!(data.nodes.len(), 4, "west: 1, 2, 3 (stub) and 6");
    }

    #[test]
    fn a_node_on_the_line_belongs_to_both() {
        // The border runs through node 3 itself.
        let ways = [RawWay {
            id: 10,
            refs: vec![1, 2, 3, 4],
            attrs: attrs(Oneway::No),
            road_ref: None,
            name: None,
        }];
        let (west, east) = (country(13.4, 13.52), country(13.52, 13.6));
        let w = table(&ways, &west);
        let e = table(&ways, &east);
        assert_eq!(w, vec![(2, false), (3, false), (4, true)]);
        assert_eq!(e, vec![(2, true), (3, false), (4, false)]);
        stubs_link(&w, &e);
    }

    #[test]
    fn without_a_border_there_is_no_table() {
        let ways = [RawWay {
            id: 10,
            refs: vec![1, 2, 3, 4],
            attrs: attrs(Oneway::No),
            road_ref: None,
            name: None,
        }];
        let (data, _) = build(&ways, &index(), info(), (5_000, 5_000), 0.0, None);
        assert!(data.border.is_empty());
        assert_eq!(data.nodes.len(), 2);
    }

    #[test]
    fn collapses_degree_two_nodes_into_geometry() {
        // 1-2-3-4 with a side road 2-5-6: routing nodes 1, 2, 4, 6.
        let ways = [
            RawWay {
                id: 10,
                refs: vec![1, 2, 3, 4],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
            },
            RawWay {
                id: 11,
                refs: vec![2, 5, 6],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
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
                road_ref: None,
                name: None,
            },
            RawWay {
                id: 21,
                refs: vec![3, 4],
                attrs: attrs(Oneway::Backward),
                road_ref: None,
                name: None,
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
                road_ref: None,
                name: None,
            },
            RawWay {
                id: 31,
                refs: vec![1, 2, 99, 3, 4],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
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
            road_ref: None,
            name: None,
        }];
        let (data, _) = build(&ways, &index(), info(), (5_000, 5_000), 0.0, None);
        let keys: Vec<u64> = data
            .nodes
            .iter()
            .map(|&n| hilbert::index(n, &info().bbox))
            .collect();
        assert!(keys.windows(2).all(|w| w[0] <= w[1]));
    }

    #[test]
    fn small_separate_networks_are_dropped() {
        // A main road 1-2-3-4 (about 2 km) and, not touching it, a short
        // road 7-8 (about 630 m): with a 1 km minimum only the main road
        // stays; with none, both.
        let ways = [
            RawWay {
                id: 10,
                refs: vec![1, 2, 3, 4],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
            },
            RawWay {
                id: 11,
                refs: vec![7, 8],
                attrs: attrs(Oneway::No),
                road_ref: None,
                name: None,
            },
        ];
        let (_, all) = build(&ways, &index(), info(), (5_000, 5_000), 0.0, None);
        assert_eq!((all.fragments, all.segments), (0, 2));
        let (data, kept) = build(&ways, &index(), info(), (5_000, 5_000), 1_000.0, None);
        assert_eq!((kept.fragments, kept.segments, kept.nodes), (1, 1, 2));
        assert!((kept.fragment_m - 630.0).abs() < 10.0, "{kept:?}");
        let r = Region::from_bytes(&data.to_bytes().unwrap()).unwrap();
        assert!(r.way_refs().iter().all(|w| w.way_id == 10));
        // Joined to the main road (2-5 then 5-7), the short road stays.
        let joined = [
            ways[0].clone(),
            ways[1].clone(),
            RawWay {
                id: 12,
                refs: vec![2, 5, 7],
                attrs: attrs(Oneway::Forward),
                road_ref: None,
                name: None,
            },
        ];
        let (_, s) = build(&joined, &index(), info(), (5_000, 5_000), 1_000.0, None);
        assert_eq!(s.fragments, 0);
        // Everything too short: the largest network still stays.
        let (_, one) = build(&ways, &index(), info(), (5_000, 5_000), 1e9, None);
        assert_eq!((one.fragments, one.segments), (1, 1));
    }

    #[test]
    fn names_each_geometry_once_per_string() {
        let named = |id, refs: Vec<i64>, r: Option<&str>, n: Option<&str>| RawWay {
            id,
            refs,
            attrs: attrs(Oneway::No),
            road_ref: r.map(Into::into),
            name: n.map(Into::into),
        };
        let ways = [
            named(10, vec![1, 2, 3, 4], Some("13"), Some("Storgatan")),
            named(11, vec![2, 5, 6], Some("13"), None),
            named(12, vec![4, 8], None, None),
        ];
        let (mut data, _) = build(&ways, &index(), info(), (5_000, 5_000), 0.0, None);
        add_places(
            &mut data.names,
            &[
                (p(55.52, 13.53), PlaceKind::Village, "Dalby".into()),
                (p(55.50, 13.50), PlaceKind::Town, "Lund".into()),
                (p(55.50, 13.50), PlaceKind::Town, "Lund".into()),
            ],
        );
        let r = Region::from_bytes(&data.to_bytes().unwrap()).unwrap();
        // "13" is stored once, for both roads.
        assert_eq!(data.names.strings, ["13", "Storgatan", "Dalby", "Lund"]);
        for (e, w) in r.edges().iter().zip(r.way_refs()) {
            let g = r.geometry_name(e.geometry);
            let got = (r.string(g.road_ref), r.string(g.name));
            let want = match w.way_id {
                10 => (Some("13"), Some("Storgatan")),
                11 => (Some("13"), None),
                _ => (None, None),
            };
            assert_eq!(got, want, "way {}", w.way_id);
        }
        // Places sorted by latitude, duplicates dropped.
        let places: Vec<_> = r.places().iter().map(|q| r.string(q.name)).collect();
        assert_eq!(places, [Some("Lund"), Some("Dalby")]);
    }
}
