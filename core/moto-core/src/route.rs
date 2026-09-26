// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Routes between two snapped road points: A* over travel time on the
//! region graph (M0), with the rider's favourite sections (M2a) and curvy
//! roads (M2b) pulling the route as hard as the time budget allows.

use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap, HashSet};

use crate::favourites::Favourites;
use crate::geo::{haversine_m, polyline_slice};
use crate::region::Region;
use crate::region::format::{COORD_SCALE, Edge, PointE7, RoadClass, Surface, edge_flags};
use crate::scoring::PARAMS;
use crate::{Avoid, CoreError, Gravel, LatLon, RoadPoint, Route, RouteOptions};

const NONE: u32 = u32::MAX;

pub(crate) fn latlon(p: PointE7) -> LatLon {
    LatLon {
        lat: f64::from(p.lat) / COORD_SCALE,
        lon: f64::from(p.lon) / COORD_SCALE,
    }
}

/// Travel time along a whole edge, in seconds.
fn time_s(e: &Edge) -> f64 {
    f64::from(e.length_dm) / 10.0 / (f64::from(e.speed_kmh) / 3.6)
}

/// Gravel and other unpaved roads.
pub(crate) fn is_unpaved(e: &Edge) -> bool {
    Surface::from_u8(e.surface).is_some_and(|s| !s.is_paved())
}

/// The kinds of road a search keeps off where it can.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Off {
    avoid: Avoid,
    unpaved: bool,
}

impl Off {
    pub(crate) fn of(opts: &RouteOptions) -> Self {
        Self {
            avoid: opts.avoid,
            unpaved: opts.gravel == Gravel::Avoid,
        }
    }
}

/// The extra cost of edge `e` for being a kind of road to avoid:
/// `avoid_penalty - 1` times its travel time, else 0. It is added after
/// the fun factor, so no pull makes an avoided road cheaper than that.
fn avoid_extra(off: &Off, e: &Edge) -> f64 {
    let motorway = RoadClass::from_u8(e.class) == Some(RoadClass::Motorway);
    let unpaved = is_unpaved(e);
    let ferry = e.flags & edge_flags::FERRY != 0;
    let avoided =
        off.avoid.motorways && motorway || off.unpaved && unpaved || off.avoid.ferries && ferry;
    if avoided {
        time_s(e) * (PARAMS.avoid_penalty - 1.0)
    } else {
        0.0
    }
}

/// What makes a road worth riding (R5, R6): the rider's favourites
/// (not on gravel while gravel is avoided), when `curvy` curvature (see
/// `ScoringParams::curviness`), and when `gravel` (the rider prefers it)
/// unpaved roads.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Fun<'a> {
    region: &'a Region,
    favourites: &'a Favourites,
    curvy: bool,
    gravel: bool,
    /// Gravel is avoided: favourites on it count for nothing.
    no_gravel: bool,
}

impl<'a> Fun<'a> {
    pub(crate) fn new(region: &'a Region, favourites: &'a Favourites, opts: &RouteOptions) -> Self {
        Self {
            region,
            favourites,
            curvy: opts.curvy,
            gravel: opts.gravel == Gravel::Prefer,
            no_gravel: opts.gravel == Gravel::Avoid,
        }
    }

    /// Whether no road is worth more than another: plain fastest routes.
    fn is_empty(&self) -> bool {
        !self.curvy && !self.gravel && self.favourites.is_empty()
    }

    /// Whether favourites on edge `e` count: not on gravel while gravel
    /// is avoided (the map hides those sections then too).
    fn favourite_counts(&self, e: &Edge) -> bool {
        !(self.no_gravel && is_unpaved(e))
    }

    /// The favourite bonus of edge `id` as this search sees it.
    fn favourite_bonus(&self, id: u32, e: &Edge) -> f64 {
        if self.favourite_counts(e) {
            self.favourites.bonus(id)
        } else {
            0.0
        }
    }

    /// Whether edge `id` is part of a favourite that counts.
    pub(crate) fn is_favourite(&self, id: u32) -> bool {
        self.region
            .edges()
            .get(id as usize)
            .is_some_and(|e| self.favourite_bonus(id, e) > 0.0)
    }

    /// How curvy edge `id` is, 0–1, whether or not curvature pulls.
    fn curviness(&self, id: u32, e: &Edge) -> f64 {
        let m = self.region.curvature()[id as usize];
        PARAMS.curviness(&m, e.class, f64::from(e.length_dm) / 10.0)
    }

    /// What curvature adds to edge `id`'s worth: nothing when it is off.
    fn curve_worth(&self, id: u32, e: &Edge) -> f64 {
        if self.curvy {
            PARAMS.curve_weight * self.curviness(id, e)
        } else {
            0.0
        }
    }

    /// What gravel adds to an edge's worth: `gravel_weight` on unpaved
    /// roads when the rider prefers gravel, else nothing.
    fn gravel_worth(&self, e: &Edge) -> f64 {
        if self.gravel && is_unpaved(e) {
            PARAMS.gravel_weight
        } else {
            0.0
        }
    }

    /// The dullness penalty of edge `id` for its speed band (see
    /// `ScoringParams::fast_kmh`), before curves spare it: 1 on 51–79
    /// km/h roads, on the rider's own favourites (marked as fun, whatever
    /// their speed) and whenever nothing but favourites pulls.
    fn speed_penalty(&self, id: u32, e: &Edge) -> f64 {
        if !(self.curvy || self.gravel) || self.favourite_bonus(id, e) > 0.0 {
            1.0
        } else if e.speed_kmh >= PARAMS.fast_kmh {
            PARAMS.fast_penalty
        } else if e.speed_kmh >= PARAMS.brisk_kmh {
            PARAMS.brisk_penalty
        } else if e.speed_kmh <= PARAMS.slow_kmh && !(self.gravel && is_unpaved(e)) {
            PARAMS.slow_penalty
        } else {
            1.0
        }
    }

    /// Cost factor of edge `id` at `pull`: cheaper by its worth, dearer by
    /// its dullness (the speed penalty, less the curvier the road is).
    /// Worth per second is 0–1: an epic favourite is 1, a fully curvy road
    /// `curve_weight`, a preferred gravel road `gravel_weight`; they add
    /// up, capped at 1. Curviness is looked up at most once.
    fn factor(&self, id: u32, e: &Edge, pull: f64) -> f64 {
        let penalty = self.speed_penalty(id, e);
        let curviness = if self.curvy || penalty > 1.0 {
            self.curviness(id, e)
        } else {
            0.0
        };
        let curve = if self.curvy {
            PARAMS.curve_weight * curviness
        } else {
            0.0
        };
        let worth =
            (self.favourite_bonus(id, e) / PARAMS.max_pull + curve + self.gravel_worth(e)).min(1.0);
        let dullness = 1.0 + (penalty - 1.0) * (1.0 - curviness);
        (1.0 - pull * PARAMS.max_pull * worth) * (1.0 + pull * (dullness - 1.0))
    }

    /// How dull edge `id` is over 1 (0 on 51–99 km/h roads, or when
    /// nothing but favourites pulls; less the curvier it is).
    fn dullness(&self, id: u32, e: &Edge) -> f64 {
        let penalty = self.speed_penalty(id, e);
        if penalty > 1.0 {
            (penalty - 1.0) * (1.0 - self.curviness(id, e))
        } else {
            0.0
        }
    }

    /// What curvature and gravel add to edge `id`'s worth.
    fn road_worth(&self, id: u32, e: &Edge) -> f64 {
        self.curve_worth(id, e) + self.gravel_worth(e)
    }

    /// The most any edge can be worth.
    fn max_worth(&self) -> f64 {
        let curve = if self.curvy { PARAMS.curve_weight } else { 0.0 };
        let gravel = if self.gravel {
            PARAMS.gravel_weight
        } else {
            0.0
        };
        (self.favourites.max_bonus() / PARAMS.max_pull + curve + gravel).min(1.0)
    }
}

/// What a path search minimises.
#[derive(Debug, Clone, Copy)]
pub(crate) enum Cost<'a> {
    /// Travel time, with avoided kinds of road costing more, and roads
    /// worth riding cheaper by their worth times `max_pull` times the pull
    /// (0–1; 0 is the fastest route).
    Favoured(Off, Fun<'a>, f64),
    /// As `Favoured`, with roads the loop already rides (either way, by
    /// geometry) costing `reuse_penalty` times more (round trips).
    Loop(Off, Fun<'a>, f64, &'a HashSet<u32>),
    /// Distance along the road, nothing avoided: the road the rider points
    /// at, not a faster one nearby (marking sections).
    Shortest,
}

impl Cost<'_> {
    /// Cost of whole edge `id`.
    fn edge(&self, id: u32, e: &Edge) -> f64 {
        match self {
            Cost::Favoured(off, fun, pull) => {
                time_s(e) * fun.factor(id, e, *pull) + avoid_extra(off, e)
            }
            Cost::Loop(off, fun, pull, used) => {
                let reused = if used.contains(&e.geometry) {
                    PARAMS.reuse_penalty
                } else {
                    1.0
                };
                (time_s(e) * fun.factor(id, e, *pull) + avoid_extra(off, e)) * reused
            }
            Cost::Shortest => f64::from(e.length_dm) / 10.0,
        }
    }

    /// Cost of a fraction of an edge the path starts or ends on (neither
    /// penalised nor favoured: the rider chose that road).
    fn partial(&self, e: &Edge, frac: f64) -> f64 {
        frac * match self {
            Cost::Favoured(..) | Cost::Loop(..) => time_s(e),
            Cost::Shortest => f64::from(e.length_dm) / 10.0,
        }
    }

    /// Lower bound of the cost of `metres` in a straight line.
    fn estimate(&self, metres: f64, max_mps: f64) -> f64 {
        match self {
            // `max_pull` is below 1, so the bound stays positive.
            // The reuse and dullness penalties only add cost, so the bound
            // still holds.
            Cost::Favoured(_, fun, pull) | Cost::Loop(_, fun, pull, _) => {
                metres / max_mps * (1.0 - pull * PARAMS.max_pull * fun.max_worth())
            }
            Cost::Shortest => metres,
        }
    }
}

/// The edge running the other way along the same geometry, if any.
pub(crate) fn twin(region: &Region, id: u32) -> Option<u32> {
    let e = region.edges()[id as usize];
    region.out_edges(e.head).find(|&o| {
        let t = region.edges()[o as usize];
        o != id && t.head == e.tail && t.geometry == e.geometry
    })
}

/// Shape of an edge in its travel direction.
pub(crate) fn edge_line(region: &Region, e: &Edge) -> Vec<LatLon> {
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

/// A piece of a path: travel along `edge` between fractions `from` and
/// `to` of its length (in the edge's direction). Whole edges are 0.0–1.0.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct Partial {
    pub edge: u32,
    pub from: f64,
    pub to: f64,
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
    favourite_m: f64,
    /// What riding the route is worth: seconds on favourites weighted by
    /// rating (epic 1) plus seconds on curvy road weighted by curviness
    /// and `curve_weight`.
    value_s: f64,
    favourite_parts: Vec<Vec<LatLon>>,
    unpaved_m: f64,
    unpaved_parts: Vec<Vec<LatLon>>,
}

/// Adds `piece` to `parts`, continuing the last part when the piece
/// starts where it ends (a stretch running on across a junction).
fn push_part(parts: &mut Vec<Vec<LatLon>>, piece: Vec<LatLon>) {
    match parts.last_mut() {
        Some(part) if part.last() == piece.first() => part.extend(piece.into_iter().skip(1)),
        _ if piece.len() >= 2 => parts.push(piece),
        _ => {}
    }
}

/// One route from routes run end to end (through via points): lines
/// joined where one ends and the next starts, figures added up, shares
/// weighted by distance, and stretches that run on across a via point
/// kept as one part.
pub(crate) fn join(legs: Vec<Route>) -> Route {
    let mut out = Route {
        geometry: Vec::new(),
        distance_m: 0.0,
        duration_s: 0.0,
        favourite_share: 0.0,
        curvy_share: 0.0,
        fastest_duration_s: 0.0,
        favourite_parts: Vec::new(),
        unpaved_m: 0.0,
        unpaved_parts: Vec::new(),
    };
    let (mut favourite_m, mut curvy_m) = (0.0, 0.0);
    for leg in legs {
        let skip = usize::from(
            out.geometry.last().is_some() && out.geometry.last() == leg.geometry.first(),
        );
        out.geometry.extend(leg.geometry.into_iter().skip(skip));
        out.distance_m += leg.distance_m;
        out.duration_s += leg.duration_s;
        out.fastest_duration_s += leg.fastest_duration_s;
        out.unpaved_m += leg.unpaved_m;
        favourite_m += leg.favourite_share * leg.distance_m;
        curvy_m += leg.curvy_share * leg.distance_m;
        for p in leg.favourite_parts {
            push_part(&mut out.favourite_parts, p);
        }
        for p in leg.unpaved_parts {
            push_part(&mut out.unpaved_parts, p);
        }
    }
    if out.distance_m > 0.0 {
        out.favourite_share = (favourite_m / out.distance_m).clamp(0.0, 1.0);
        out.curvy_share = (curvy_m / out.distance_m).clamp(0.0, 1.0);
    }
    out
}

impl Builder {
    fn add(&mut self, fun: &Fun, id: u32, from: f64, to: f64) {
        let (region, favourites) = (fun.region, fun.favourites);
        let e = region.edges()[id as usize];
        let frac = (to - from).max(0.0);
        let length_m = f64::from(e.length_dm) / 10.0;
        self.distance_m += frac * length_m;
        self.duration_s += frac * time_s(&e);
        // Curvature is spread evenly over the edge (the metrics are per
        // edge) and always measured; it adds to what the stretch is worth
        // only when it pulls. Worth caps at 1 per second, as in the cost.
        self.curvy_m += frac * length_m * fun.curviness(id, &e);
        self.favourite_m += length_m * favourites.covered_between(id, from, to);
        let favourite_value = if fun.favourite_counts(&e) {
            favourites.value_between(id, from, to)
        } else {
            0.0
        };
        // Time on dull roads counts against the worth (see `dull_worth`).
        let dull = frac * PARAMS.dull_worth * fun.dullness(id, &e);
        self.value_s +=
            time_s(&e) * ((favourite_value + frac * fun.road_worth(id, &e)).min(frac) - dull);
        let line = edge_line(region, &e);
        if let Some((lo, hi)) = favourites.covered_part(id, from, to) {
            push_part(&mut self.favourite_parts, polyline_slice(&line, lo, hi));
        }
        if is_unpaved(&e) && to > from {
            self.unpaved_m += frac * length_m;
            push_part(&mut self.unpaved_parts, polyline_slice(&line, from, to));
        }
        for p in polyline_slice(&line, from, to) {
            if self.geometry.last() != Some(&p) {
                self.geometry.push(p);
            }
        }
    }

    fn finish(self) -> Routed {
        let share = |m: f64| {
            if self.distance_m > 0.0 {
                (m / self.distance_m).clamp(0.0, 1.0)
            } else {
                0.0
            }
        };
        Routed {
            value_s: self.value_s,
            route: Route {
                curvy_share: share(self.curvy_m),
                favourite_share: share(self.favourite_m),
                geometry: self.geometry,
                distance_m: self.distance_m,
                duration_s: self.duration_s,
                fastest_duration_s: self.duration_s,
                favourite_parts: self.favourite_parts,
                unpaved_m: self.unpaved_m,
                unpaved_parts: self.unpaved_parts,
            },
        }
    }
}

/// A route and what riding it is worth.
pub(crate) struct Routed {
    pub(crate) route: Route,
    /// Seconds on favourites, curvy road and preferred gravel, weighted
    /// (see `Fun::worth`).
    pub(crate) value_s: f64,
}

/// The route with the path pieces `parts`.
pub(crate) fn build(fun: &Fun, parts: &[Partial]) -> Routed {
    let mut route = Builder::default();
    for p in parts {
        route.add(fun, p.edge, p.from, p.to);
    }
    route.finish()
}

/// Route from `from` to `to`, keeping off what `opts.avoid` asks for where
/// possible: the fastest one, or with favourites the one that rides as
/// much of them as the time budget buys. The budget sets how hard
/// favourites pull: full pull is tried first, and if that route takes
/// more than the budget allows, or its extra time buys too little
/// favourite riding (`opts.min_gain`), the pull is bisected and the route
/// of the strongest pull that passes wins (the fastest route if none
/// does). The same inputs always give the same route.
pub(crate) fn route(
    region: &Region,
    from: &RoadPoint,
    to: &RoadPoint,
    opts: &RouteOptions,
    favourites: &Favourites,
    max_speed_kmh: f64,
) -> Result<Route, CoreError> {
    let off = Off::of(opts);
    let fun = Fun::new(region, favourites, opts);
    // The fastest route: pull 0 is travel time alone.
    let parts = path(
        region,
        from,
        to,
        Cost::Favoured(off, fun, 0.0),
        max_speed_kmh,
    )?;
    let fastest = build(&fun, &parts);
    if fun.is_empty() {
        return Ok(fastest.route);
    }
    let fastest_s = fastest.route.duration_s;
    let with_fastest = |mut r: Route| {
        r.fastest_duration_s = fastest_s;
        r
    };
    let (base_s, base_value_s) = (fastest.route.duration_s, fastest.value_s);
    let limit_s = base_s + opts.budget.extra_s(base_s) + 1e-6;
    let passes = |r: &Routed| {
        let extra_s = r.route.duration_s - base_s;
        r.route.duration_s <= limit_s
            && (extra_s <= 1e-6 || (r.value_s - base_value_s) >= opts.min_gain * extra_s)
    };
    let pulled = |pull: f64| -> Result<Routed, CoreError> {
        let cost = Cost::Favoured(off, fun, pull);
        let parts = path(region, from, to, cost, max_speed_kmh)?;
        Ok(build(&fun, &parts))
    };
    let full = pulled(1.0)?;
    if passes(&full) {
        return Ok(with_fastest(full.route));
    }
    let mut best = fastest;
    let (mut lo, mut hi) = (0.0, 1.0);
    for _ in 0..PARAMS.detour_steps {
        let pull = (lo + hi) / 2.0;
        let r = pulled(pull)?;
        if passes(&r) {
            lo = pull;
            best = r;
        } else {
            hi = pull;
        }
    }
    Ok(with_fastest(best.route))
}

/// Cheapest path from `from` to `to` under `cost`, as edge pieces in
/// travel order. The stretches of road the two points lie on count at their
/// plain cost. `max_speed_kmh` bounds every edge's speed and keeps the A*
/// estimate admissible.
pub(crate) fn path(
    region: &Region,
    from: &RoadPoint,
    to: &RoadPoint,
    cost: Cost,
    max_speed_kmh: f64,
) -> Result<Vec<Partial>, CoreError> {
    let (leave, _) = partials(region, from);
    let (_, arrive) = partials(region, to);

    // Both points on the same geometry, the second one ahead: no graph needed.
    let mut best_cost = f64::INFINITY;
    let mut best: Option<(u32, Partial)> = None; // (entry node or NONE, final partial)
    let mut direct: Option<Partial> = None;
    for &(_, l) in &leave {
        for &(_, a) in &arrive {
            if l.edge == a.edge && a.to >= l.from {
                let c = cost.partial(&region.edges()[l.edge as usize], a.to - l.from);
                if c < best_cost {
                    best_cost = c;
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
    let h = |v: u32| {
        cost.estimate(
            haversine_m(latlon(region.nodes()[v as usize]), target),
            max_mps,
        )
    };
    // Keys are non-negative f64s, whose bit patterns sort like the values.
    let key = |cost: f64| cost.to_bits();

    for &(node, l) in &leave {
        let c = cost.partial(&region.edges()[l.edge as usize], l.to - l.from);
        if c < dist[node as usize] {
            dist[node as usize] = c;
            heap.push(Reverse((key(c + h(node)), node)));
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
                let c = g + cost.partial(&region.edges()[a.edge as usize], a.to - a.from);
                if c < best_cost {
                    best_cost = c;
                    best = Some((v, a));
                    direct = None;
                }
            }
        }
        for id in region.out_edges(v) {
            let e = region.edges()[id as usize];
            let c = g + cost.edge(id, &e);
            let w = e.head as usize;
            if c < dist[w] {
                dist[w] = c;
                parent[w] = id;
                heap.push(Reverse((key(c + h(e.head)), e.head)));
            }
        }
    }

    if let Some(d) = direct {
        return Ok(vec![d]);
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
    let mut parts = vec![first];
    parts.extend(path.into_iter().map(|edge| Partial {
        edge,
        from: 0.0,
        to: 1.0,
    }));
    parts.push(last);
    Ok(parts)
}

/// Shortest paths along the road (in metres, respecting one-way roads)
/// from `from` to each of `targets`, searching no further than `limit_m`.
/// For each target: its distance and path pieces in travel order, or `None`
/// when it is further than the limit or unreachable. One bounded Dijkstra
/// search serves all targets; its memory grows with the area searched, not
/// with the region.
pub(crate) fn shortest_within(
    region: &Region,
    from: &RoadPoint,
    targets: &[RoadPoint],
    limit_m: f64,
) -> Vec<Option<(f64, Vec<Partial>)>> {
    let cost = Cost::Shortest;
    let (leave, _) = partials(region, from);
    let edge = |id: u32| region.edges()[id as usize];

    // node -> (distance, edge arrived by, or NONE for a start node)
    let mut settled: HashMap<u32, (f64, u32)> = HashMap::new();
    let mut best: HashMap<u32, (f64, u32)> = HashMap::new();
    let mut heap = BinaryHeap::new();
    let key = |c: f64| c.to_bits();
    for &(node, l) in &leave {
        let c = cost.partial(&edge(l.edge), l.to - l.from);
        if c <= limit_m && best.get(&node).is_none_or(|&(d, _)| c < d) {
            best.insert(node, (c, NONE));
            heap.push(Reverse((key(c), node)));
        }
    }
    while let Some(Reverse((k, v))) = heap.pop() {
        let g = f64::from_bits(k);
        if settled.contains_key(&v) {
            continue;
        }
        let Some(&(d, via)) = best.get(&v) else {
            continue;
        };
        if g > d {
            continue; // stale entry
        }
        settled.insert(v, (d, via));
        for id in region.out_edges(v) {
            let c = g + cost.edge(id, &edge(id));
            let w = edge(id).head;
            if c <= limit_m && !settled.contains_key(&w) && best.get(&w).is_none_or(|&(d, _)| c < d)
            {
                best.insert(w, (c, id));
                heap.push(Reverse((key(c), w)));
            }
        }
    }

    targets
        .iter()
        .map(|to| {
            let (_, arrive) = partials(region, to);
            let mut found: Option<(f64, Option<u32>, Partial)> = None;
            // Straight along the same geometry, the target ahead.
            for &(_, l) in &leave {
                for &(_, a) in &arrive {
                    if l.edge == a.edge && a.to >= l.from {
                        let c = cost.partial(&edge(l.edge), a.to - l.from);
                        if c <= limit_m && found.as_ref().is_none_or(|f| c < f.0) {
                            let direct = Partial {
                                edge: l.edge,
                                from: l.from,
                                to: a.to,
                            };
                            found = Some((c, None, direct));
                        }
                    }
                }
            }
            for &(node, a) in &arrive {
                if let Some(&(d, _)) = settled.get(&node) {
                    let c = d + cost.partial(&edge(a.edge), a.to - a.from);
                    if c <= limit_m && found.as_ref().is_none_or(|f| c < f.0) {
                        found = Some((c, Some(node), a));
                    }
                }
            }
            let (c, entry, last) = found?;
            let Some(entry) = entry else {
                return Some((c, vec![last]));
            };
            // Walk back from the entry node to a start node.
            let mut path = Vec::new();
            let mut v = entry;
            loop {
                let &(_, via) = settled.get(&v)?;
                if via == NONE {
                    break;
                }
                path.push(via);
                v = edge(via).tail;
            }
            path.reverse();
            let first = leave.iter().find(|(node, _)| *node == v).map(|&(_, l)| l)?;
            let mut parts = vec![first];
            parts.extend(path.into_iter().map(|edge| Partial {
                edge,
                from: 0.0,
                to: 1.0,
            }));
            parts.push(last);
            Some((c, parts))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use crate::fixture::{self, L_S};
    use crate::geo::haversine_m;
    use crate::region::Region;
    use crate::region::format::{COORD_SCALE, Surface};
    use crate::{Avoid, CoreError, Engine, Gravel, LatLon, RouteOptions};

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
                ferries: false,
            },
            gravel: if unpaved {
                Gravel::Avoid
            } else {
                Gravel::Allow
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
    fn unpaved_stretches_are_reported() {
        // Motorways avoided, gravel allowed: the route takes the north road.
        let (from, to) = (ll(55.71, 13.3995), ll(55.71, 13.4405));
        let e = engine(fixture::ladder(Surface::Gravel));
        let r = e.route(from, to, &opts(true, false)).unwrap();
        // The north road runs 0.04° of longitude along lat 55.72.
        let north_m = 0.04 * M_PER_DEG_LON;
        assert!((r.unpaved_m - north_m).abs() < 30.0, "{r:?}");
        assert!(r.unpaved_m < r.distance_m);
        assert_eq!(r.unpaved_parts.len(), 1, "one piece across the junction");
        let part = &r.unpaved_parts[0];
        assert!(part.len() >= 2 && part.iter().all(|p| (p.lat - 55.72).abs() < 1e-6));
        // The same route on asphalt has none.
        let e = engine(fixture::ladder(Surface::Asphalt));
        let r = e.route(from, to, &opts(true, false)).unwrap();
        assert_eq!(r.unpaved_m, 0.0);
        assert!(r.unpaved_parts.is_empty());
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

    /// A straight primary road W–E (5 km at 90 km/h, 200 s) and a winding
    /// tertiary road beside it (sine bends every 400 m, at 80 km/h), with
    /// stubs to start and end on.
    fn twisty() -> Engine {
        use crate::fixture::{Road, build};
        use crate::region::format::RoadClass;
        let nodes = [
            (55.70, 13.39),
            (55.70, 13.40),
            (55.70, 13.48),
            (55.70, 13.49),
        ];
        let bends: Vec<(f64, f64)> = (1..160)
            .map(|i| {
                let t = f64::from(i) / 160.0;
                // Up to about 600 m north, with 60 m wiggles.
                let lat = 55.70
                    + 0.0055 * (std::f64::consts::PI * t).sin()
                    + 0.00055 * (t * 40.0 * std::f64::consts::PI).sin();
                (lat, 13.40 + 0.08 * t)
            })
            .collect();
        let winding = Road {
            via: bends,
            ..Road::new(1, 2, RoadClass::Tertiary, 80, 21)
        };
        engine(build(
            &nodes,
            &[
                Road::new(0, 1, RoadClass::Primary, 90, 20),
                Road::new(1, 2, RoadClass::Primary, 90, 22),
                winding,
                Road::new(2, 3, RoadClass::Primary, 90, 23),
            ],
            50_000,
        ))
    }

    fn winds(r: &crate::Route) -> bool {
        r.geometry.iter().any(|p| p.lat > 55.703)
    }

    #[test]
    fn curvy_roads_pull_within_the_budget() {
        use crate::favourites::Favourites;
        let e = twisty();
        let (from, to) = (ll(55.70, 13.395), ll(55.70, 13.485));
        let none = Favourites::none();
        let budget = |ratio: f64, curvy: bool| RouteOptions {
            budget: crate::TimeBudget::Extra(ratio),
            min_gain: 0.5,
            curvy,
            ..RouteOptions::default()
        };
        let fastest = e.route(from, to, &budget(1.0, true)).unwrap();
        assert!(!winds(&fastest), "{fastest:?}");
        assert!(fastest.curvy_share < 0.05, "{fastest:?}");
        // Measured on the winding road even when curvature doesn't pull.
        let on_it = e
            .route(ll(55.7055, 13.44), ll(55.7054, 13.442), &budget(0.0, false))
            .unwrap();
        assert!(on_it.curvy_share > 0.3, "{on_it:?}");

        let r = e.route_with(from, to, &budget(1.0, true), &none).unwrap();
        assert!(winds(&r), "{r:?}");
        assert!(r.curvy_share > 0.5, "{r:?}");
        assert!(r.duration_s > fastest.duration_s && r.duration_s <= 2.0 * fastest.duration_s);
        assert_eq!(r.fastest_duration_s, fastest.duration_s);
        assert_eq!(r.favourite_share, 0.0);

        // No budget, or curvature off: the fastest route.
        assert_eq!(
            e.route_with(from, to, &budget(0.0, true), &none).unwrap(),
            fastest
        );
        assert_eq!(
            e.route_with(from, to, &budget(1.0, false), &none).unwrap(),
            fastest
        );
    }

    /// The gravel ladder with the north road at 70 km/h, so preferring it
    /// is worth a short detour; motorways allowed (the south road is
    /// faster).
    fn fast_gravel_ladder() -> Engine {
        let mut data = fixture::ladder(Surface::Gravel);
        for e in &mut data.edges {
            if e.class == crate::region::format::RoadClass::Residential as u8 {
                e.speed_kmh = 70;
            }
        }
        engine(data)
    }

    fn gravel(g: Gravel) -> RouteOptions {
        RouteOptions {
            gravel: g,
            ..opts(false, false)
        }
    }

    const LADDER_FROM: LatLon = LatLon {
        lat: 55.71,
        lon: 13.3995,
    };
    const LADDER_TO: LatLon = LatLon {
        lat: 55.71,
        lon: 13.4405,
    };

    #[test]
    fn preferred_gravel_pulls_the_route_within_the_budget() {
        let e = fast_gravel_ladder();
        let none = crate::Favourites::none();
        let (from, to) = (LADDER_FROM, LADDER_TO);
        for g in [Gravel::Avoid, Gravel::Allow] {
            let r = e.route_with(from, to, &gravel(g), &none).unwrap();
            assert_eq!(r.unpaved_m, 0.0, "{g:?}: the motorway is faster");
        }
        let r = e
            .route_with(from, to, &gravel(Gravel::Prefer), &none)
            .unwrap();
        assert!(r.unpaved_m > 2000.0, "{r:?}");
        assert!(r.duration_s > r.fastest_duration_s);
        assert!(r.duration_s <= r.fastest_duration_s * 1.4 + 1e-6);
        // The plain fastest route only allows gravel.
        let plain = e.route(from, to, &gravel(Gravel::Prefer)).unwrap();
        assert_eq!(plain.unpaved_m, 0.0, "{plain:?}");
        assert_eq!(plain.duration_s, r.fastest_duration_s);
        // No budget: the fastest route, gravel or not.
        let tight = RouteOptions {
            budget: crate::TimeBudget::Extra(0.0),
            ..gravel(Gravel::Prefer)
        };
        let r = e.route_with(from, to, &tight, &none).unwrap();
        assert_eq!(r.unpaved_m, 0.0, "{r:?}");
    }

    #[test]
    fn gravel_favourites_count_only_when_gravel_is_not_avoided() {
        use crate::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};
        // An epic favourite on the gravel road in the north; the motorway
        // in the south is much faster. With room in the budget, the
        // favourite pulls the route north when gravel is allowed, and
        // counts for nothing when gravel is avoided.
        let e = engine(fixture::ladder(Surface::Gravel));
        let (from, to) = (LADDER_FROM, LADDER_TO);
        let d = e
            .section_between(ll(55.7201, 13.401), ll(55.7201, 13.439))
            .unwrap();
        let s = Section {
            id: 1,
            rider_id: LOCAL_RIDER.into(),
            name: String::new(),
            rating: Rating::Epic,
            direction: Direction::Both,
            source: Source::Map,
            status: Status::Ok,
            created_at: 0,
            updated_at: 0,
            ways: d.ways,
            geometry: d.geometry,
        };
        let fav = crate::Favourites::build(&e, &[s]);
        let with = |g: Gravel| RouteOptions {
            budget: crate::TimeBudget::Extra(1.0),
            curvy: false,
            gravel: g,
            ..opts(false, false)
        };
        let r = e.route_with(from, to, &with(Gravel::Allow), &fav).unwrap();
        assert!(r.favourite_share > 0.5 && r.unpaved_m > 2000.0, "{r:?}");
        let r = e.route_with(from, to, &with(Gravel::Avoid), &fav).unwrap();
        assert_eq!(r.unpaved_m, 0.0, "{r:?}");
        assert_eq!(r.favourite_share, 0.0);
    }

    #[test]
    fn a_route_passes_its_via_points_in_order() {
        // From near A to near D, through the bend towards C: the one-way
        // B→D then comes after a detour up the bend and back.
        let e = engine(fixture::region());
        let none = crate::Favourites::none();
        let o = opts(true, true);
        let (from, to) = (ll(55.7001, 13.201), ll(55.7001, 13.219));
        let direct = e.route_with(from, to, &o, &none).unwrap();
        let bend = ll(55.705, 13.2119);
        let r = e.route_via(from, &[bend], to, &o, &none).unwrap();
        assert!(
            r.geometry.iter().any(|&q| haversine_m(q, bend) < 10.0),
            "{r:?}"
        );
        assert!(r.distance_m > direct.distance_m + 1000.0);
        // The legs, joined: figures add up and the line has no repeat at
        // the via point.
        let a = e.route_with(from, bend, &o, &none).unwrap();
        let b = e.route_with(bend, to, &o, &none).unwrap();
        assert!((r.distance_m - a.distance_m - b.distance_m).abs() < 1e-6);
        assert!((r.duration_s - a.duration_s - b.duration_s).abs() < 1e-6);
        assert!(r.geometry.windows(2).all(|w| w[0] != w[1]));
        assert_eq!(r.geometry.len(), a.geometry.len() + b.geometry.len() - 1);
        // No via points: the plain route.
        assert_eq!(e.route_via(from, &[], to, &o, &none).unwrap(), direct);
    }

    #[test]
    fn via_points_are_checked() {
        let e = engine(fixture::region());
        let none = crate::Favourites::none();
        let o = opts(true, true);
        let (from, to) = (ll(55.7001, 13.201), ll(55.7001, 13.219));
        let many = vec![ll(55.7001, 13.205); crate::MAX_VIA_POINTS + 1];
        assert!(matches!(
            e.route_via(from, &many, to, &o, &none),
            Err(CoreError::InvalidArgument(_))
        ));
        assert!(matches!(
            e.route_via(from, &[ll(56.5, 13.5)], to, &o, &none),
            Err(CoreError::OutsideRegion { .. })
        ));
        assert!(
            e.route_via(from, &[ll(f64::NAN, 13.2)], to, &o, &none)
                .is_err()
        );
    }

    #[test]
    fn joined_parts_run_on_across_a_via_point() {
        let p = |lat| LatLon { lat, lon: 13.2 };
        let leg = |a: f64, b: f64, share: f64, parts: Vec<Vec<LatLon>>| crate::Route {
            geometry: vec![p(a), p(b)],
            distance_m: 1000.0,
            duration_s: 60.0,
            favourite_share: share,
            curvy_share: share,
            fastest_duration_s: 50.0,
            favourite_parts: parts.clone(),
            unpaved_m: 0.0,
            unpaved_parts: parts,
        };
        let r = super::join(vec![
            leg(55.0, 55.1, 1.0, vec![vec![p(55.05), p(55.1)]]),
            leg(55.1, 55.2, 0.0, vec![vec![p(55.1), p(55.15)]]),
        ]);
        assert_eq!(r.geometry, [p(55.0), p(55.1), p(55.2)]);
        assert_eq!(r.favourite_parts, [vec![p(55.05), p(55.1), p(55.15)]]);
        assert_eq!(r.unpaved_parts.len(), 1);
        assert_eq!(
            (r.distance_m, r.duration_s, r.fastest_duration_s),
            (2000.0, 120.0, 100.0)
        );
        assert!((r.favourite_share - 0.5).abs() < 1e-12);
        assert_eq!(super::join(vec![]).distance_m, 0.0);
    }

    /// Two parallel roads between the same stubs: a straight 100 km/h
    /// primary road in the south and a slightly longer 70 km/h road in
    /// the north; `north_kmh` sets the north road's speed.
    fn fast_and_fun(north_kmh: u8) -> Engine {
        use crate::fixture::Road;
        let nodes = [
            (55.70, 13.39),
            (55.70, 13.40),
            (55.70, 13.44),
            (55.70, 13.45),
        ];
        let north = Road {
            via: vec![(55.701, 13.41), (55.701, 13.43)],
            ..Road::new(
                1,
                2,
                crate::region::format::RoadClass::Tertiary,
                north_kmh,
                3,
            )
        };
        engine(fixture::build(
            &nodes,
            &[
                Road::new(0, 1, crate::region::format::RoadClass::Tertiary, 70, 1),
                Road::new(1, 2, crate::region::format::RoadClass::Primary, 100, 2),
                north,
                Road::new(2, 3, crate::region::format::RoadClass::Tertiary, 70, 4),
            ],
            50_000,
        ))
    }

    #[test]
    fn fun_routes_keep_off_fast_and_slow_roads() {
        let none = crate::Favourites::none();
        let (from, to) = (ll(55.7, 13.395), ll(55.7, 13.445));
        let north = |r: &crate::Route| r.geometry.iter().any(|p| p.lat > 55.7005);
        // The plain fastest route takes the 100 km/h road; a fun route
        // the 70 km/h one, for a little more time.
        let e = fast_and_fun(70);
        let fastest = e.route(from, to, &RouteOptions::default()).unwrap();
        assert!(!north(&fastest), "{fastest:?}");
        let fun = e
            .route_with(from, to, &RouteOptions::default(), &none)
            .unwrap();
        assert!(north(&fun), "{fun:?}");
        assert!(fun.duration_s > fastest.duration_s);
        assert!(fun.duration_s <= fastest.duration_s * 1.4);
        // Curvature off and nothing else pulling: plain fastest.
        let flat = RouteOptions {
            curvy: false,
            ..RouteOptions::default()
        };
        assert!(!north(&e.route_with(from, to, &flat, &none).unwrap()));
        // A 40 km/h north road is dull too: the fast road wins again.
        let e = fast_and_fun(40);
        assert!(!north(
            &e.route_with(from, to, &RouteOptions::default(), &none)
                .unwrap()
        ));
    }
}
