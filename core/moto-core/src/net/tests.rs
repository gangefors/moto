// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use std::collections::HashSet;

use super::*;
use crate::fixture::{Road, build};
use crate::region::RegionData;
use crate::region::format::{BorderNode, RoadClass};

fn region(data: RegionData) -> Region {
    Region::from_bytes(&data.to_bytes().unwrap()).unwrap()
}

fn bn(osm_id: i64, node: u32, outside: bool) -> BorderNode {
    BorderNode {
        osm_id,
        node,
        flags: if outside { border_flags::OUTSIDE } else { 0 },
    }
}

/// West: W — A — B, with B past the border (a stub). East: A — B — E,
/// with A past it. The road A–B crosses the border and is in both.
fn west() -> RegionData {
    let mut d = build(
        &[(55.70, 13.19), (55.70, 13.20), (55.70, 13.21)],
        &[
            Road::new(0, 1, RoadClass::Tertiary, 70, 2),
            Road::new(1, 2, RoadClass::Tertiary, 70, 1),
        ],
        50_000,
    );
    d.border = vec![bn(101, 1, false), bn(102, 2, true)];
    d
}

fn east() -> RegionData {
    let mut d = build(
        &[(55.70, 13.20), (55.70, 13.21), (55.70, 13.22)],
        &[
            Road::new(0, 1, RoadClass::Tertiary, 70, 1),
            Road::new(1, 2, RoadClass::Tertiary, 70, 3),
        ],
        50_000,
    );
    d.border = vec![bn(101, 0, true), bn(102, 1, false)];
    d
}

/// Nodes reachable from `from` along out edges.
fn reach(net: &Net, from: u32) -> HashSet<u32> {
    let mut seen = HashSet::from([from]);
    let mut todo = vec![from];
    while let Some(v) = todo.pop() {
        for e in net.out_edges(v) {
            let h = net.edge(e).head;
            if seen.insert(h) {
                todo.push(h);
            }
        }
    }
    seen
}

#[test]
fn one_region_is_the_region_itself() {
    let r = region(west());
    let edges = r.edges().to_vec();
    let net = Net::single(r);
    assert_eq!(net.edge_count(), edges.len());
    for (i, e) in edges.iter().enumerate() {
        assert_eq!(net.edge(i as u32), *e);
    }
    assert_eq!(net.link_count(), 0);
    assert_eq!(net.get_edge(edges.len() as u32), None);
}

#[test]
fn neighbours_link_at_their_stubs() {
    let net = Net::linked(vec![region(west()), region(east())]).unwrap();
    assert_eq!(net.node_count(), 6);
    assert_eq!(net.link_count(), 2, "each side's stub has a twin");
    // W (west 0) reaches E (east 2, global 5), and back.
    assert!(reach(&net, 0).contains(&5));
    assert!(reach(&net, 5).contains(&0));
    // No edge ends at a linked stub: they are replaced by their twins.
    for id in 0..net.edge_count() as u32 {
        let e = net.edge(id);
        assert!(e.head != 2 && e.head != 3, "{e:?}");
        assert!(e.tail != 2 && e.tail != 3, "{e:?}");
        // Geometries are global and join their nodes.
        let line = net.geometry(e.geometry);
        let ends = [line[0], line[line.len() - 1]];
        assert!(ends.contains(&net.node(e.tail)) && ends.contains(&net.node(e.head)));
    }
    // The order doesn't matter.
    let net = Net::linked(vec![region(east()), region(west())]).unwrap();
    assert!(reach(&net, 3).contains(&2), "W (now 3) reaches E (now 2)");
}

#[test]
fn a_neighbour_alone_ends_at_its_stub() {
    let net = Net::linked(vec![region(west())]).unwrap();
    assert_eq!(net.link_count(), 0);
    assert!(reach(&net, 0).contains(&2), "the stub is still a road end");
    // Two stubs for the same node never link each other: with B a stub on
    // both sides, only the east's stub A finds its twin, which is enough
    // to cross.
    let mut other = east();
    other.border = vec![bn(101, 0, true), bn(102, 1, true)];
    let net = Net::linked(vec![region(west()), region(other)]).unwrap();
    assert_eq!(
        net.link_count(),
        1,
        "only the east stub finds A inside the west"
    );
    assert!(reach(&net, 0).contains(&5));
    // Neither side's stub has a twin: no crossing.
    let mut apart = east();
    apart.border = vec![bn(102, 1, true), bn(201, 0, true)];
    let net = Net::linked(vec![region(west()), region(apart)]).unwrap();
    assert_eq!(net.link_count(), 0);
    assert!(!reach(&net, 0).contains(&5));
}

#[test]
fn refuses_no_regions_or_too_many() {
    assert!(Net::linked(vec![]).is_err());
    let many = (0..=MAX_REGIONS).map(|_| region(west())).collect();
    assert!(Net::linked(many).is_err());
}

#[test]
fn names_places_and_box_span_the_regions() {
    let net = Net::linked(vec![region(west()), region(east())]).unwrap();
    let b = net.bbox();
    let (w, e) = (region(west()).info().bbox, region(east()).info().bbox);
    assert_eq!((b.min_lon, b.max_lon), (w.min_lon, e.max_lon));
    assert_eq!(net.road_names(0), (None, None));
    assert_eq!(net.places().count(), 0);
    // In- and out-edges agree (for every node but the linked stubs, which
    // no edge reaches).
    for v in (0..net.node_count() as u32).filter(|&v| net.canonical(v) == v) {
        for e in net.in_edges(v) {
            assert_eq!(net.edge(e).head, v);
        }
        for e in net.out_edges(v) {
            assert_eq!(net.edge(e).tail, v);
        }
    }
}

#[test]
fn the_engine_routes_across_the_border() {
    use crate::{Engine, LatLon, RouteOptions};
    let e = Engine::from_regions(vec![region(west()), region(east())]).unwrap();
    let ll = |lat, lon| LatLon { lat, lon };
    let r = e
        .route(ll(55.70, 13.19), ll(55.70, 13.22), &RouteOptions::default())
        .unwrap();
    // W to E: 3 × 0.01° of longitude at 55.7°N, about 1.9 km, the road
    // across the border ridden once.
    assert!((r.distance_m - 1880.0).abs() < 30.0, "{}", r.distance_m);
    assert!(r.geometry.windows(2).all(|w| w[0] != w[1]));
    // Snapping works in both regions, and a point in the east snaps to an
    // east edge.
    assert!(e.snap(ll(55.70, 13.215)).unwrap().edge >= 4);
    // Alone, the west ends at its stub.
    let alone = Engine::from_regions(vec![region(west())]).unwrap();
    assert!(
        alone
            .route(ll(55.70, 13.19), ll(55.70, 13.22), &RouteOptions::default())
            .is_err()
    );
}
