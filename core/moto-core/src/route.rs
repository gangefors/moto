// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Fastest route between two snapped road points: A* over travel time on
//! the region graph (M0). Curvature and favourites join the cost in M2.

use std::cmp::Reverse;
use std::collections::BinaryHeap;

use crate::geo::{haversine_m, polyline_slice};
use crate::region::Region;
use crate::region::format::{COORD_SCALE, Edge, PointE7, RoadClass, Surface, edge_flags};
use crate::{Avoid, CoreError, LatLon, RoadPoint, Route};

/// Radius bins (see `RADIUS_BINS_M`) that count as "curvy" for
/// [`Route::curvy_share`]: turn radius up to 175 m. Provisional; the real
/// definition comes with curvature scoring in M2.
const CURVY_BINS: usize = 4;

/// Cost factor on roads the options ask to avoid. Avoiding is "where
/// possible", not a ban: a farm on a gravel road must still be reachable.
const AVOID_PENALTY: f64 = 10.0;

const NONE: u32 = u32::MAX;

fn latlon(p: PointE7) -> LatLon {
    LatLon {
        lat: f64::from(p.lat) / COORD_SCALE,
        lon: f64::from(p.lon) / COORD_SCALE,
    }
}

/// Travel time along a whole edge, in seconds.
fn time_s(e: &Edge) -> f64 {
    f64::from(e.length_dm) / 10.0 / (f64::from(e.speed_kmh) / 3.6)
}

/// Routing cost of a whole edge: travel time, times [`AVOID_PENALTY`] if
/// the options ask to avoid this kind of road.
fn cost_s(e: &Edge, avoid: &Avoid) -> f64 {
    let motorway = RoadClass::from_u8(e.class) == Some(RoadClass::Motorway);
    let unpaved = Surface::from_u8(e.surface).is_some_and(|s| !s.is_paved());
    let ferry = e.flags & edge_flags::FERRY != 0;
    let avoided = avoid.motorways && motorway || avoid.unpaved && unpaved || avoid.ferries && ferry;
    time_s(e) * if avoided { AVOID_PENALTY } else { 1.0 }
}

/// The edge running the other way along the same geometry, if any.
fn twin(region: &Region, id: u32) -> Option<u32> {
    let e = region.edges()[id as usize];
    region.out_edges(e.head).find(|&o| {
        let t = region.edges()[o as usize];
        o != id && t.head == e.tail && t.geometry == e.geometry
    })
}

/// Shape of an edge in its travel direction.
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

/// A way onto or off the graph from a point inside an edge: travel along
/// `edge` between fractions `from` and `to` (in the edge's direction).
#[derive(Debug, Clone, Copy)]
struct Partial {
    edge: u32,
    from: f64,
    to: f64,
}

/// Graph nodes paired with the partial edge that links them to a point.
type Links = Vec<(u32, Partial)>;

/// Ways to leave `p` (towards the head of its edge or of the twin) and ways
/// to arrive at it, each with the node it connects to.
fn partials(region: &Region, p: &RoadPoint) -> (Links, Links) {
    let e = region.edges()[p.edge as usize];
    let mut leave = vec![(
        e.head,
        Partial {
            edge: p.edge,
            from: p.offset,
            to: 1.0,
        },
    )];
    let mut arrive = vec![(
        e.tail,
        Partial {
            edge: p.edge,
            from: 0.0,
            to: p.offset,
        },
    )];
    if let Some(t) = twin(region, p.edge) {
        let back = 1.0 - p.offset;
        leave.push((
            e.tail,
            Partial {
                edge: t,
                from: back,
                to: 1.0,
            },
        ));
        arrive.push((
            e.head,
            Partial {
                edge: t,
                from: 0.0,
                to: back,
            },
        ));
    }
    (leave, arrive)
}

/// Accumulates a route's geometry and totals.
#[derive(Default)]
struct Builder {
    geometry: Vec<LatLon>,
    distance_m: f64,
    duration_s: f64,
    curvy_m: f64,
}

impl Builder {
    fn add(&mut self, region: &Region, id: u32, from: f64, to: f64) {
        let e = region.edges()[id as usize];
        let frac = (to - from).max(0.0);
        let length_m = f64::from(e.length_dm) / 10.0;
        self.distance_m += frac * length_m;
        self.duration_s += frac * time_s(&e);
        let c = region.curvature()[id as usize];
        let curvy: f64 = c.radius_len_m[..CURVY_BINS]
            .iter()
            .map(|&m| f64::from(m))
            .sum();
        self.curvy_m += frac * curvy.min(length_m);
        for p in polyline_slice(&edge_line(region, &e), from, to) {
            if self.geometry.last() != Some(&p) {
                self.geometry.push(p);
            }
        }
    }

    fn finish(self) -> Route {
        let share = |m: f64| {
            if self.distance_m > 0.0 {
                (m / self.distance_m).clamp(0.0, 1.0)
            } else {
                0.0
            }
        };
        Route {
            curvy_share: share(self.curvy_m),
            favourite_share: 0.0,
            geometry: self.geometry,
            distance_m: self.distance_m,
            duration_s: self.duration_s,
        }
    }
}

/// Fastest route from `from` to `to`, keeping off what `avoid` asks for
/// where possible (see [`AVOID_PENALTY`]); the stretches of road the two
/// points lie on count at plain travel time. `max_speed_kmh` bounds every
/// edge's speed and keeps the A* estimate admissible.
pub(crate) fn fastest(
    region: &Region,
    from: &RoadPoint,
    to: &RoadPoint,
    avoid: &Avoid,
    max_speed_kmh: f64,
) -> Result<Route, CoreError> {
    let (leave, _) = partials(region, from);
    let (_, arrive) = partials(region, to);

    // Both points on the same geometry, the second one ahead: no graph needed.
    let mut best_cost = f64::INFINITY;
    let mut best: Option<(u32, Partial)> = None; // (entry node or NONE, final partial)
    let mut direct: Option<Partial> = None;
    for &(_, l) in &leave {
        for &(_, a) in &arrive {
            if l.edge == a.edge && a.to >= l.from {
                let cost = (a.to - l.from) * time_s(&region.edges()[l.edge as usize]);
                if cost < best_cost {
                    best_cost = cost;
                    direct = Some(Partial {
                        edge: l.edge,
                        from: l.from,
                        to: a.to,
                    });
                }
            }
        }
    }

    let n = region.node_count();
    let mut dist = vec![f64::INFINITY; n];
    let mut parent = vec![NONE; n];
    let mut heap = BinaryHeap::new();
    let target = to.position;
    let max_mps = max_speed_kmh.max(1.0) / 3.6;
    let h = |v: u32| haversine_m(latlon(region.nodes()[v as usize]), target) / max_mps;
    // Keys are non-negative f64s, whose bit patterns sort like the values.
    let key = |cost: f64| cost.to_bits();

    for &(node, l) in &leave {
        let cost = (l.to - l.from) * time_s(&region.edges()[l.edge as usize]);
        if cost < dist[node as usize] {
            dist[node as usize] = cost;
            heap.push(Reverse((key(cost + h(node)), node)));
        }
    }
    while let Some(Reverse((k, v))) = heap.pop() {
        let g = dist[v as usize];
        if f64::from_bits(k) >= best_cost {
            break;
        }
        if f64::from_bits(k) > g + h(v) + 1e-9 {
            continue; // stale entry
        }
        for &(node, a) in &arrive {
            if node == v {
                let cost = g + (a.to - a.from) * time_s(&region.edges()[a.edge as usize]);
                if cost < best_cost {
                    best_cost = cost;
                    best = Some((v, a));
                    direct = None;
                }
            }
        }
        for id in region.out_edges(v) {
            let e = region.edges()[id as usize];
            let cost = g + cost_s(&e, avoid);
            let w = e.head as usize;
            if cost < dist[w] {
                dist[w] = cost;
                parent[w] = id;
                heap.push(Reverse((key(cost + h(e.head)), e.head)));
            }
        }
    }

    let mut route = Builder::default();
    if let Some(d) = direct {
        route.add(region, d.edge, d.from, d.to);
        return Ok(route.finish());
    }
    let Some((entry, last)) = best else {
        return Err(CoreError::NoRoute(
            "no road connection between the two points with the current options".into(),
        ));
    };
    // Walk back from the entry node to a start node.
    let mut path = Vec::new();
    let mut v = entry;
    while parent[v as usize] != NONE {
        let id = parent[v as usize];
        path.push(id);
        v = region.edges()[id as usize].tail;
    }
    path.reverse();
    let first = leave
        .iter()
        .find(|(node, _)| *node == v)
        .map(|&(_, l)| l)
        .ok_or_else(|| CoreError::NoRoute("internal: route has no start".into()))?;
    route.add(region, first.edge, first.from, first.to);
    for id in path {
        route.add(region, id, 0.0, 1.0);
    }
    route.add(region, last.edge, last.from, last.to);
    Ok(route.finish())
}

#[cfg(test)]
mod tests {
    use crate::fixture::{self, L_S};
    use crate::geo::haversine_m;
    use crate::region::Region;
    use crate::region::format::{COORD_SCALE, Surface};
    use crate::{Avoid, CoreError, Engine, LatLon, RouteOptions};

    fn engine(data: crate::region::RegionData) -> Engine {
        Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
    }

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    fn opts(motorways: bool, unpaved: bool) -> RouteOptions {
        RouteOptions {
            avoid: Avoid {
                motorways,
                unpaved,
                ferries: false,
            },
            ..RouteOptions::default()
        }
    }

    fn passes(route: &crate::Route, p: LatLon) -> bool {
        route.geometry.iter().any(|&q| haversine_m(q, p) < 1.0)
    }

    /// Degrees of longitude at 55.7°N, in metres.
    const M_PER_DEG_LON: f64 = 62_742.0;

    #[test]
    fn follows_roads_through_junctions() {
        // From A–B near A, through B, along the one-way to near D.
        let e = engine(fixture::region());
        let r = e
            .route(ll(55.7001, 13.201), ll(55.7001, 13.219), &opts(true, true))
            .unwrap();
        assert!((r.distance_m - 0.018 * M_PER_DEG_LON).abs() < 5.0, "{r:?}");
        assert!(passes(&r, ll(55.70, 13.21)), "{r:?}");
        assert!((r.geometry[0].lon - 13.201).abs() < 1e-6);
        assert!((r.geometry.last().unwrap().lon - 13.219).abs() < 1e-6);
        // 70 km/h throughout.
        assert!(
            (r.duration_s - r.distance_m / (70.0 / 3.6)).abs() < 0.5,
            "{r:?}"
        );
        assert_eq!(r.favourite_share, 0.0);
    }

    #[test]
    fn keeps_the_shape_of_bent_roads() {
        let e = engine(fixture::region());
        let r = e
            .route(ll(55.7001, 13.201), ll(55.7099, 13.2101), &opts(true, true))
            .unwrap();
        assert!(passes(&r, ll(55.705, 13.212)), "{r:?}");
    }

    #[test]
    fn respects_one_way_roads() {
        let e = engine(fixture::region());
        // D is only reachable along B→D, and nothing leaves it.
        assert!(matches!(
            e.route(ll(55.7001, 13.219), ll(55.7001, 13.201), &opts(true, true)),
            Err(CoreError::NoRoute(_))
        ));
        // Backwards along the one-way itself.
        assert!(matches!(
            e.route(ll(55.7001, 13.218), ll(55.7001, 13.212), &opts(true, true)),
            Err(CoreError::NoRoute(_))
        ));
        // Forwards along it is fine.
        let r = e
            .route(ll(55.7001, 13.212), ll(55.7001, 13.218), &opts(true, true))
            .unwrap();
        assert!((r.distance_m - 0.006 * M_PER_DEG_LON).abs() < 2.0, "{r:?}");
    }

    #[test]
    fn routes_within_one_road_both_ways() {
        let e = engine(fixture::region());
        let there = e
            .route(ll(55.7001, 13.202), ll(55.7001, 13.208), &opts(true, true))
            .unwrap();
        let back = e
            .route(ll(55.7001, 13.208), ll(55.7001, 13.202), &opts(true, true))
            .unwrap();
        for r in [&there, &back] {
            assert!((r.distance_m - 0.006 * M_PER_DEG_LON).abs() < 2.0, "{r:?}");
            assert_eq!(r.geometry.len(), 2, "{r:?}");
        }
        assert!(there.geometry[0].lon < there.geometry[1].lon);
        assert!(back.geometry[0].lon > back.geometry[1].lon);
    }

    #[test]
    fn prefers_the_faster_road_and_honours_avoid() {
        // From the middle of the west connector to the middle of the east
        // one: the motorway in the south is faster than the 30 km/h street.
        let (from, to) = (ll(55.71, 13.3995), ll(55.71, 13.4405));
        let e = engine(fixture::ladder(Surface::Asphalt));
        let s_node = e.region().nodes()[L_S as usize];
        let south = ll(
            f64::from(s_node.lat) / COORD_SCALE,
            f64::from(s_node.lon) / COORD_SCALE,
        );

        let fast = e.route(from, to, &opts(false, true)).unwrap();
        assert!(passes(&fast, south), "{fast:?}");

        let slow = e.route(from, to, &opts(true, true)).unwrap();
        assert!(!passes(&slow, south), "{slow:?}");
        assert!(slow.duration_s > fast.duration_s + 100.0);
        assert!((slow.distance_m - fast.distance_m).abs() < 50.0);

        // With the north road unpaved too, both options are avoided; the
        // route takes the lesser evil (the motorway is far shorter in time).
        let e = engine(fixture::ladder(Surface::Gravel));
        let r = e.route(from, to, &opts(true, true)).unwrap();
        assert!(passes(&r, south), "{r:?}");
        assert!(
            (r.duration_s - fast.duration_s).abs() < 1.0,
            "reported time is real time"
        );
        let r = e.route(from, to, &opts(false, false)).unwrap();
        assert!(passes(&r, south), "{r:?}");
    }

    #[test]
    fn avoided_roads_are_used_when_there_is_no_other_way() {
        // Both ends on the motorway, motorways avoided: the motorway is
        // still the only sensible way.
        let e = engine(fixture::ladder(Surface::Asphalt));
        let r = e
            .route(ll(55.7001, 13.405), ll(55.7001, 13.435), &opts(true, true))
            .unwrap();
        assert!((r.distance_m - 0.03 * M_PER_DEG_LON).abs() < 30.0, "{r:?}");
    }

    #[test]
    fn route_ends_must_be_in_the_region_and_near_a_road() {
        let e = engine(fixture::region());
        assert!(matches!(
            e.route(ll(55.7001, 13.201), ll(56.5, 13.5), &opts(true, true)),
            Err(CoreError::OutsideRegion { .. })
        ));
        assert!(matches!(
            e.route(ll(55.7145, 13.2245), ll(55.7001, 13.201), &opts(true, true)),
            Err(CoreError::NoRoadNearby { .. })
        ));
    }
}
