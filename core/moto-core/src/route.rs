// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Routes between two snapped road points: A* over travel time on the
//! region graph (M0), with the rider's favourite sections (M2a) and curvy
//! roads (M2b) pulling the route as hard as the time budget allows.

use std::cmp::Reverse;
use std::collections::{BinaryHeap, HashMap, HashSet};

use crate::favourites::Favourites;
use crate::geo::{haversine_m, polyline_slice};
use crate::net::Net;
use crate::region::format::{COORD_SCALE, Edge, PointE7, RoadClass, Surface, edge_flags};
use crate::scoring::PARAMS;
use crate::section::Rating;
use crate::{Avoid, CoreError, FavouritesMode, Gravel, LatLon, RoadPoint, Route, RouteOptions};

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

/// A ferry.
fn is_ferry(e: &Edge) -> bool {
    e.flags & edge_flags::FERRY != 0
}

/// A road a motorcycle pays toll on; a ferry counts as a ferry.
pub(crate) fn is_toll(e: &Edge) -> bool {
    e.flags & edge_flags::TOLL != 0 && !is_ferry(e)
}

/// Motorways, ferries and toll roads: the kinds a rider can avoid, and
/// the least fun roads when allowed.
fn is_travel_road(e: &Edge) -> bool {
    RoadClass::from_u8(e.class) == Some(RoadClass::Motorway) || is_ferry(e) || is_toll(e)
}

/// The extra cost of edge `id` (`e`) for being a kind of road to avoid:
/// the largest factor that applies (`avoid_penalty`, `toll_penalty` for a
/// toll road, or `fun`'s for an avoided favourite; they never add up)
/// less 1, times its travel time; else 0. It is added after the fun
/// factor, so no pull makes an avoided road cheaper than that.
fn avoid_extra(off: &Off, fun: &Fun, id: u32, e: &Edge) -> f64 {
    let motorway = RoadClass::from_u8(e.class) == Some(RoadClass::Motorway);
    let mut factor: f64 = 1.0;
    if off.avoid.motorways && motorway
        || off.unpaved && is_unpaved(e)
        || off.avoid.ferries && is_ferry(e)
    {
        factor = PARAMS.avoid_penalty;
    }
    if off.avoid.tolls && is_toll(e) {
        factor = factor.max(PARAMS.toll_penalty);
    }
    factor = factor.max(fun.shun_factor(id));
    time_s(e) * (factor - 1.0)
}

/// What makes a road worth riding (R5, R6): the rider's favourites
/// (not on gravel while gravel is avoided, nor while they are avoided),
/// when `curvy` curvature (see `ScoringParams::curviness`), and when
/// `gravel` (the rider prefers it) unpaved roads. While favourites are
/// avoided, nothing on them is worth riding.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Fun<'a> {
    region: &'a Net,
    favourites: &'a Favourites,
    curvy: bool,
    gravel: bool,
    /// Gravel is avoided: favourites on it count for nothing.
    no_gravel: bool,
    /// Favourites are avoided (`FavouritesMode::Avoid`): they count for
    /// nothing and cost more.
    shun: bool,
    /// Unridden roads are preferred (`UnriddenMode::Prefer`): roads the
    /// rider's rides have been on keep `ridden_worth` of their curvy
    /// worth.
    unridden: bool,
    /// The kinds of road the rider avoids.
    avoid: crate::Avoid,
}

impl<'a> Fun<'a> {
    pub(crate) fn new(region: &'a Net, favourites: &'a Favourites, opts: &RouteOptions) -> Self {
        Self {
            region,
            favourites,
            curvy: opts.curvy,
            gravel: opts.gravel == Gravel::Prefer,
            no_gravel: opts.gravel == Gravel::Avoid,
            shun: opts.favourites == FavouritesMode::Avoid,
            unridden: opts.unridden == crate::UnriddenMode::Prefer,
            avoid: opts.avoid,
        }
    }

    /// Whether `e` is a paid crossing the rider avoids: a toll road or a
    /// ferry.
    pub(crate) fn avoided_paid(&self, e: &Edge) -> bool {
        self.avoid.tolls && is_toll(e) || self.avoid.ferries && is_ferry(e)
    }

    /// Whether no road is worth more than another: plain fastest routes.
    fn is_empty(&self) -> bool {
        !self.curvy && !self.gravel && (self.shun || self.favourites.is_empty())
    }

    /// Whether favourites on edge `e` count: not while they are avoided,
    /// nor on gravel while gravel is avoided (the map hides those sections
    /// then too).
    fn favourite_counts(&self, e: &Edge) -> bool {
        !self.shun && !(self.no_gravel && is_unpaved(e))
    }

    /// Share of edge `id` that is worth nothing for lying on an avoided
    /// favourite (either way): 0 unless favourites are avoided.
    fn shunned(&self, id: u32) -> f64 {
        if self.shun {
            self.favourites.share(id)
        } else {
            0.0
        }
    }

    /// Cost factor of edge `id` for lying on an avoided favourite (see
    /// `avoid_favourite_penalty`): 1 unless favourites are avoided.
    fn shun_factor(&self, id: u32) -> f64 {
        1.0 + self.shunned(id) * (PARAMS.avoid_favourite_penalty - 1.0)
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
            .get_edge(id)
            .is_some_and(|e| self.favourite_bonus(id, &e) > 0.0)
    }

    /// How curvy edge `id` is, 0–1, whether or not curvature pulls. Toll
    /// roads count as not curvy (motorways and ferries are not by class):
    /// they are the least fun roads.
    fn curviness(&self, id: u32, e: &Edge) -> f64 {
        if is_toll(e) {
            return 0.0;
        }
        let m = self.region.curvature(id);
        PARAMS.curviness(
            &m,
            e.class,
            e.speed_kmh,
            e.flags,
            f64::from(e.length_dm) / 10.0,
        )
    }

    /// Share of its curvy worth edge `id` keeps: `ridden_worth` on a road
    /// the rider's rides have been on while unridden roads are preferred,
    /// else all of it (ADR-0010).
    fn ridden_factor(&self, id: u32) -> f64 {
        if self.unridden && self.favourites.is_ridden(id) {
            PARAMS.ridden_worth
        } else {
            1.0
        }
    }

    /// What curvature adds to edge `id`'s worth: nothing when it is off,
    /// less on a road already ridden while unridden roads are preferred.
    fn curve_worth(&self, id: u32, e: &Edge) -> f64 {
        if self.curvy {
            PARAMS.curve_weight * self.curviness(id, e) * self.ridden_factor(id)
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
    /// their speed) and whenever nothing but favourites pulls. Roads in
    /// built-up areas cost at least `built_up_penalty`.
    fn speed_penalty(&self, id: u32, e: &Edge) -> f64 {
        if !(self.curvy || self.gravel) || self.favourite_bonus(id, e) > 0.0 {
            return 1.0;
        }
        let band = if e.speed_kmh >= PARAMS.fast_kmh {
            PARAMS.fast_penalty
        } else if e.speed_kmh >= PARAMS.brisk_kmh {
            PARAMS.brisk_penalty
        } else if e.speed_kmh <= PARAMS.slow_kmh && !(self.gravel && is_unpaved(e)) {
            PARAMS.slow_penalty
        } else {
            1.0
        };
        // Motorways, ferries and toll roads, when allowed, are the least
        // fun roads (Stefan): as dull as the fastest band, whatever their
        // speed.
        let band = if is_travel_road(e) {
            band.max(PARAMS.fast_penalty)
        } else {
            band
        };
        if e.flags & edge_flags::BUILT_UP != 0 {
            band.max(PARAMS.built_up_penalty)
        } else {
            band
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
            PARAMS.curve_weight * curviness * self.ridden_factor(id)
        } else {
            0.0
        };
        let road = (curve + self.gravel_worth(e)) * (1.0 - self.shunned(id));
        let worth = (self.favourite_bonus(id, e) / PARAMS.max_pull + road).min(1.0);
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

    /// What curvature and gravel add to edge `id`'s worth (nothing on
    /// avoided favourites).
    fn road_worth(&self, id: u32, e: &Edge) -> f64 {
        (self.curve_worth(id, e) + self.gravel_worth(e)) * (1.0 - self.shunned(id))
    }

    /// The least any metre of road can cost at `pull` (seconds), for the
    /// search's estimate: an edge costs at least its travel time times
    /// (1 - `pull` · `max_pull` · its worth), and no road is both worth the
    /// most and fast. Paved roads are worth at most their curvature; only
    /// preferred gravel, no faster than the fastest unpaved road, and
    /// favourites, no faster than the fastest favourite edge, can be worth
    /// more. `max_speed_kmh` bounds every edge's speed.
    fn least_cost_per_m(&self, pull: f64, max_speed_kmh: f64) -> f64 {
        let k = pull * PARAMS.max_pull;
        let per_m = |worth: f64, kmh: f64| {
            (1.0 - k * worth.min(1.0)) / (kmh.min(max_speed_kmh).max(1.0) / 3.6)
        };
        let curve = if self.curvy { PARAMS.curve_weight } else { 0.0 };
        let gravel = if self.gravel {
            PARAMS.gravel_weight
        } else {
            0.0
        };
        let mut least = per_m(curve, max_speed_kmh);
        if self.gravel && self.region.max_unpaved_kmh() > 0.0 {
            least = least.min(per_m(curve + gravel, self.region.max_unpaved_kmh()));
        }
        if !self.shun && self.favourites.max_bonus() > 0.0 {
            let worth = self.favourites.max_bonus() / PARAMS.max_pull + curve + gravel;
            least = least.min(per_m(worth, self.favourites.max_speed_kmh()));
        }
        least
    }
}

/// What a path search minimises.
#[derive(Debug, Clone, Copy)]
pub(crate) enum Cost<'a> {
    /// Travel time, with avoided kinds of road costing more, and roads
    /// worth riding cheaper by their worth times `max_pull` times the pull
    /// (0–1; 0 is the fastest route).
    Favoured(Off, Fun<'a>, f64),
    /// As `Favoured`, with roads already ridden (either way, by geometry)
    /// costing `reuse_penalty` times more. With the flag (round trips) a
    /// road ridden again also earns nothing for being worth riding: a
    /// favourite is only worth riding once, however cheap it is.
    Loop(Off, Fun<'a>, f64, &'a HashSet<u32>, bool),
    /// Distance along the road, nothing avoided: the road the rider points
    /// at, not a faster one nearby (marking sections).
    Shortest,
}

impl Cost<'_> {
    /// Cost of whole edge `id`.
    fn edge(&self, id: u32, e: &Edge) -> f64 {
        match self {
            Cost::Favoured(off, fun, pull) => {
                time_s(e) * fun.factor(id, e, *pull) + avoid_extra(off, fun, id, e)
            }
            Cost::Loop(off, fun, pull, used, once) => {
                let factor = fun.factor(id, e, *pull);
                if !used.contains(&e.geometry) {
                    time_s(e) * factor + avoid_extra(off, fun, id, e)
                } else if *once {
                    (time_s(e) * factor.max(1.0) + avoid_extra(off, fun, id, e))
                        * PARAMS.reuse_penalty
                } else {
                    (time_s(e) * factor + avoid_extra(off, fun, id, e)) * PARAMS.reuse_penalty
                }
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

    /// The least cost of a metre, so that a straight-line distance times
    /// it is a lower bound of the cost to go (the A* estimate).
    fn least_per_m(&self, max_speed_kmh: f64) -> f64 {
        match self {
            // `max_pull` is below 1, so the bound stays positive.
            // The reuse and dullness penalties and avoided roads only add
            // cost, so the bound still holds.
            Cost::Favoured(_, fun, pull) | Cost::Loop(_, fun, pull, _, _) => {
                fun.least_cost_per_m(*pull, max_speed_kmh)
            }
            Cost::Shortest => 1.0,
        }
    }
}

/// The edge running the other way along the same geometry, if any.
pub(crate) fn twin(region: &Net, id: u32) -> Option<u32> {
    let e = region.edge(id);
    region.out_edges(e.head).find(|&o| {
        let t = region.edge(o);
        o != id && t.head == e.tail && t.geometry == e.geometry
    })
}

/// Shape of an edge in its travel direction.
pub(crate) fn edge_line(region: &Net, e: &Edge) -> Vec<LatLon> {
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
fn partials(region: &Net, p: &RoadPoint) -> (Links, Links) {
    let e = region.edge(p.edge);
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
    /// Metres on roads none of the rider's rides has been on.
    unridden_m: f64,
    /// What riding the route is worth: seconds on favourites weighted by
    /// rating (epic 1) plus seconds on curvy road weighted by curviness
    /// and `curve_weight`.
    value_s: f64,
    favourite_parts: Vec<Vec<LatLon>>,
    favourite_ratings: Vec<Rating>,
    unpaved_m: f64,
    unpaved_parts: Vec<Vec<LatLon>>,
    toll_m: f64,
    paid_s: f64,
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

/// As [`push_part`] for favourite parts and their ratings: a piece only
/// continues the last part when it is on a section of the same rating.
fn push_rated(
    parts: &mut Vec<Vec<LatLon>>,
    ratings: &mut Vec<Rating>,
    piece: Vec<LatLon>,
    rating: Rating,
) {
    match (parts.last_mut(), ratings.last()) {
        (Some(part), Some(&r)) if r == rating && part.last() == piece.first() => {
            part.extend(piece.into_iter().skip(1))
        }
        _ if piece.len() >= 2 => {
            parts.push(piece);
            ratings.push(rating);
        }
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
        unridden_share: 1.0,
        fastest_duration_s: 0.0,
        favourite_parts: Vec::new(),
        favourite_ratings: Vec::new(),
        unpaved_m: 0.0,
        unpaved_parts: Vec::new(),
        toll_m: 0.0,
        suggested: false,
    };
    let (mut favourite_m, mut curvy_m, mut unridden_m) = (0.0, 0.0, 0.0);
    for leg in legs {
        let skip = usize::from(
            out.geometry.last().is_some() && out.geometry.last() == leg.geometry.first(),
        );
        out.geometry.extend(leg.geometry.into_iter().skip(skip));
        out.distance_m += leg.distance_m;
        out.duration_s += leg.duration_s;
        out.fastest_duration_s += leg.fastest_duration_s;
        out.unpaved_m += leg.unpaved_m;
        out.toll_m += leg.toll_m;
        favourite_m += leg.favourite_share * leg.distance_m;
        curvy_m += leg.curvy_share * leg.distance_m;
        unridden_m += leg.unridden_share * leg.distance_m;
        for (p, r) in leg.favourite_parts.into_iter().zip(leg.favourite_ratings) {
            push_rated(&mut out.favourite_parts, &mut out.favourite_ratings, p, r);
        }
        for p in leg.unpaved_parts {
            push_part(&mut out.unpaved_parts, p);
        }
    }
    if out.distance_m > 0.0 {
        out.favourite_share = (favourite_m / out.distance_m).clamp(0.0, 1.0);
        out.curvy_share = (curvy_m / out.distance_m).clamp(0.0, 1.0);
        out.unridden_share = (unridden_m / out.distance_m).clamp(0.0, 1.0);
    }
    out
}

impl Builder {
    fn add(&mut self, fun: &Fun, id: u32, from: f64, to: f64) {
        let (region, favourites) = (fun.region, fun.favourites);
        let e = region.edge(id);
        let frac = (to - from).max(0.0);
        let length_m = f64::from(e.length_dm) / 10.0;
        self.distance_m += frac * length_m;
        self.duration_s += frac * time_s(&e);
        // Curvature is spread evenly over the edge (the metrics are per
        // edge) and always measured; it adds to what the stretch is worth
        // only when it pulls. Worth caps at 1 per second, as in the cost.
        self.curvy_m += frac * length_m * fun.curviness(id, &e);
        if !favourites.is_ridden(id) {
            self.unridden_m += frac * length_m;
        }
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
        if let Some((lo, hi, rating)) = favourites.covered_part(id, from, to) {
            push_rated(
                &mut self.favourite_parts,
                &mut self.favourite_ratings,
                polyline_slice(&line, lo, hi),
                rating,
            );
        }
        if is_unpaved(&e) && to > from {
            self.unpaved_m += frac * length_m;
            push_part(&mut self.unpaved_parts, polyline_slice(&line, from, to));
        }
        if is_toll(&e) {
            self.toll_m += frac * length_m;
        }
        // Time on an avoided toll road or ferry counts against the worth.
        if fun.avoided_paid(&e) {
            self.paid_s += frac * time_s(&e);
            self.value_s -= frac * time_s(&e) * PARAMS.paid_worth;
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
            paid_s: self.paid_s,
            route: Route {
                curvy_share: share(self.curvy_m),
                unridden_share: if self.distance_m > 0.0 {
                    share(self.unridden_m)
                } else {
                    1.0
                },
                favourite_share: share(self.favourite_m),
                geometry: self.geometry,
                distance_m: self.distance_m,
                duration_s: self.duration_s,
                fastest_duration_s: self.duration_s,
                favourite_parts: self.favourite_parts,
                favourite_ratings: self.favourite_ratings,
                unpaved_m: self.unpaved_m,
                unpaved_parts: self.unpaved_parts,
                toll_m: self.toll_m,
                suggested: false,
            },
        }
    }
}

/// A route and what riding it is worth.
pub(crate) struct Routed {
    pub(crate) route: Route,
    /// Seconds on favourites, curvy road and preferred gravel, weighted
    /// (see `Fun::worth`), less those on dull roads and avoided paid
    /// crossings (`dull_worth`, `paid_worth`).
    pub(crate) value_s: f64,
    /// Seconds on avoided toll roads and ferries.
    pub(crate) paid_s: f64,
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
    region: &Net,
    from: &RoadPoint,
    to: &RoadPoint,
    opts: &RouteOptions,
    favourites: &Favourites,
    max_speed_kmh: f64,
) -> Result<Route, CoreError> {
    let search = Search::new(region, from, to, opts, favourites, max_speed_kmh)?;
    let Some(best) = search.best()? else {
        return Ok(search.with_fastest(search.fastest.0.route.clone()));
    };
    Ok(search.with_fastest(best.0.route))
}

/// Most routes worth riding offered besides the fastest one.
pub const MAX_CHOICES: usize = 3;
/// Two routes are choices apart when they share less than this share of
/// the shorter one.
pub const MAX_CHOICE_OVERLAP: f64 = 0.5;
/// A choice is the same road as the fastest route when neither rides more
/// than this share of the longer one that the other doesn't, or more
/// than [`SAME_ROAD_M`] if that is more: a few metres out along a
/// favourite and back at a waypoint, say, which a rider can't tell apart
/// on the map.
pub const SAME_ROAD_SHARE: f64 = 0.05;
/// See [`SAME_ROAD_SHARE`]; the room short routes get.
pub const SAME_ROAD_M: f64 = 200.0;
/// Pulls tried, strongest first, for each further choice.
const CHOICE_PULLS: [f64; 3] = [1.0, 0.5, 0.25];

/// Routes to choose from between `from` and `to`, like a nav app offers:
/// up to [`MAX_CHOICES`] worth riding, then the fastest. The first is
/// [`route`]'s. Each further one is found with the roads the ones before
/// ride costing `reuse_penalty` times more (the strongest pull in
/// [`CHOICE_PULLS`] that passes), and kept when it stays within the time
/// budget, buys enough (`opts.min_gain` times `choice_gain`: the rider
/// picks, so a choice may buy less than the first) and shares less than
/// [`MAX_CHOICE_OVERLAP`] with every route kept and the fastest. Without
/// anything to pull (no favourites, curvy roads off, no gravel wanted)
/// only the fastest. The routes worth riding are `suggested`; a first
/// choice that is the fastest's road ([`same_road`]) is offered once, as
/// the fastest, which is then `suggested` too. The same inputs always
/// give the same routes.
pub(crate) fn route_choices(
    region: &Net,
    from: &RoadPoint,
    to: &RoadPoint,
    opts: &RouteOptions,
    favourites: &Favourites,
    max_speed_kmh: f64,
) -> Result<Vec<Route>, CoreError> {
    let search = Search::new(region, from, to, opts, favourites, max_speed_kmh)?;
    let mut kept: Vec<(Routed, Vec<Partial>)> = Vec::new();
    // The first choice, unless it is the fastest route's road (offered
    // last, as a suggestion too).
    let mut fastest_suggested = false;
    if let Some(best) = search.best()? {
        let same = best.0.route.geometry == search.fastest.0.route.geometry
            || same_road(
                &road_metres(region, &best.1),
                &road_metres(region, &search.fastest.1),
            );
        if same {
            fastest_suggested = true;
        } else {
            kept.push(best);
        }
    }
    if !search.fun.is_empty() {
        let roads_of = |parts: &[Partial]| road_metres(region, parts);
        let fastest_roads = roads_of(&search.fastest.1);
        let mut kept_roads: Vec<HashMap<u32, f64>> =
            kept.iter().map(|(_, p)| roads_of(p)).collect();
        let mut used: HashSet<u32> = kept_roads.iter().flat_map(|r| r.keys().copied()).collect();
        used.extend(fastest_roads.keys().copied());
        while kept.len() < MAX_CHOICES {
            let mut found = None;
            // The pulls side by side, taken in order (see `par`).
            let tried = crate::par::map(&CHOICE_PULLS, |&pull| {
                let cost = Cost::Loop(search.off, search.fun, pull, &used, false);
                path(region, from, to, cost, max_speed_kmh)
                    .map(|parts| (build(&search.fun, &parts), parts))
            });
            for t in tried {
                let (r, parts) = t?;
                let roads = roads_of(&parts);
                let apart = std::iter::once(&fastest_roads)
                    .chain(kept_roads.iter())
                    .all(|k| overlap_share(&roads, k) < MAX_CHOICE_OVERLAP);
                if search.passes_with(&r, PARAMS.choice_gain) && apart {
                    found = Some((r, parts, roads));
                    break;
                }
            }
            let Some((r, parts, roads)) = found else {
                break;
            };
            used.extend(roads.keys().copied());
            kept_roads.push(roads);
            kept.push((r, parts));
        }
    }
    let fastest_s = search.fastest.0.route.duration_s;
    let mut routes: Vec<Route> = kept
        .into_iter()
        .map(|(r, _)| Route {
            suggested: true,
            ..search.with_fastest(r.route)
        })
        .collect();
    let mut fastest = search.fastest.0.route;
    fastest.fastest_duration_s = fastest_s;
    fastest.suggested = fastest_suggested;
    routes.push(fastest);
    Ok(routes)
}

/// Whether two routes (as [`road_metres`]) ride the same road: neither
/// rides more than [`SAME_ROAD_SHARE`] of the longer one (at least
/// [`SAME_ROAD_M`]) off the other.
fn same_road(a: &HashMap<u32, f64>, b: &HashMap<u32, f64>) -> bool {
    let shared: f64 = a
        .iter()
        .filter_map(|(g, m)| b.get(g).map(|n| m.min(*n)))
        .sum();
    let (la, lb): (f64, f64) = (a.values().sum(), b.values().sum());
    let slack = (SAME_ROAD_SHARE * la.max(lb)).max(SAME_ROAD_M);
    la - shared <= slack && lb - shared <= slack
}

/// Metres of each road geometry that `parts` ride.
fn road_metres(region: &Net, parts: &[Partial]) -> HashMap<u32, f64> {
    let mut roads: HashMap<u32, f64> = HashMap::new();
    for p in parts {
        if let Some(e) = region.get_edge(p.edge) {
            *roads.entry(e.geometry).or_insert(0.0) +=
                (p.to - p.from).max(0.0) * f64::from(e.length_dm) / 10.0;
        }
    }
    roads
}

/// Share of the shorter of two routes (as [`road_metres`]) that the other
/// rides too.
fn overlap_share(a: &HashMap<u32, f64>, b: &HashMap<u32, f64>) -> f64 {
    let shared: f64 = a
        .iter()
        .filter_map(|(g, m)| b.get(g).map(|n| m.min(*n)))
        .sum();
    let (la, lb): (f64, f64) = (a.values().sum(), b.values().sum());
    shared / la.min(lb).max(1.0)
}

/// One routing request: the fastest route, and what the others are
/// measured against.
struct Search<'a> {
    region: &'a Net,
    from: &'a RoadPoint,
    to: &'a RoadPoint,
    opts: &'a RouteOptions,
    off: Off,
    fun: Fun<'a>,
    max_speed_kmh: f64,
    fastest: (Routed, Vec<Partial>),
    limit_s: f64,
}

impl<'a> Search<'a> {
    fn new(
        region: &'a Net,
        from: &'a RoadPoint,
        to: &'a RoadPoint,
        opts: &'a RouteOptions,
        favourites: &'a Favourites,
        max_speed_kmh: f64,
    ) -> Result<Self, CoreError> {
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
        let base_s = fastest.route.duration_s;
        let limit_s = base_s + opts.budget.extra_s(base_s) + 1e-6;
        Ok(Self {
            region,
            from,
            to,
            opts,
            off,
            fun,
            max_speed_kmh,
            fastest: (fastest, parts),
            limit_s,
        })
    }

    /// `r` with the fastest route's time to compare with.
    fn with_fastest(&self, mut r: Route) -> Route {
        r.fastest_duration_s = self.fastest.0.route.duration_s;
        r
    }

    /// Whether `r` stays within the budget and its extra time buys enough.
    fn passes(&self, r: &Routed) -> bool {
        self.passes_with(r, 1.0)
    }

    /// [`Self::passes`] with the guard `opts.min_gain` times `share`.
    fn passes_with(&self, r: &Routed, share: f64) -> bool {
        let (base_s, base_value_s) = (self.fastest.0.route.duration_s, self.fastest.0.value_s);
        let extra_s = r.route.duration_s - base_s;
        r.route.duration_s <= self.limit_s
            && (extra_s <= 1e-6
                || (r.value_s - base_value_s) >= self.opts.min_gain * share * extra_s)
    }

    fn pulled(&self, pull: f64) -> Result<(Routed, Vec<Partial>), CoreError> {
        let cost = Cost::Favoured(self.off, self.fun, pull);
        let parts = path(self.region, self.from, self.to, cost, self.max_speed_kmh)?;
        Ok((build(&self.fun, &parts), parts))
    }

    /// The route of the strongest pull that passes (see [`route`]), or
    /// `None` when nothing pulls or only the fastest passes.
    fn best(&self) -> Result<Option<(Routed, Vec<Partial>)>, CoreError> {
        if self.fun.is_empty() {
            return Ok(None);
        }
        // The bisection below, with each pull it needs found side by side
        // with the two it may need next (see `par`): the same pulls, so
        // the same route, in about half the time. The full pull comes with
        // the first two steps.
        type Found = Result<(Routed, Vec<Partial>), CoreError>;
        let mut ahead: Vec<(f64, Found)> = Vec::new();
        let find = |pulls: &[f64], ahead: &mut Vec<(f64, Found)>| {
            let want: Vec<f64> = pulls
                .iter()
                .copied()
                .filter(|p| !ahead.iter().any(|(q, _)| q.to_bits() == p.to_bits()))
                .collect();
            let found = crate::par::map(&want, |&p| self.pulled(p));
            ahead.extend(want.into_iter().zip(found));
        };
        let take = |pull: f64, ahead: &mut Vec<(f64, Found)>| -> Found {
            match ahead
                .iter()
                .position(|(q, _)| q.to_bits() == pull.to_bits())
            {
                Some(i) => ahead.swap_remove(i).1,
                None => self.pulled(pull),
            }
        };
        let steps = PARAMS.detour_steps;
        let (mut lo, mut hi) = (0.0_f64, 1.0_f64);
        let first = (lo + hi) / 2.0;
        if steps > 0 {
            find(
                &[1.0, first, (lo + first) / 2.0, (first + hi) / 2.0],
                &mut ahead,
            );
        }
        let full = take(1.0, &mut ahead)?;
        if self.passes(&full.0) {
            return Ok(Some(full));
        }
        let mut best = None;
        for step in 0..steps {
            let pull = (lo + hi) / 2.0;
            if step + 1 < steps && !ahead.iter().any(|(q, _)| q.to_bits() == pull.to_bits()) {
                find(&[pull, (lo + pull) / 2.0, (pull + hi) / 2.0], &mut ahead);
            }
            let r = take(pull, &mut ahead)?;
            if self.passes(&r.0) {
                lo = pull;
                best = Some(r);
            } else {
                hi = pull;
            }
        }
        Ok(best)
    }
}

/// Nodes per page of [`NodeState`] (as a power of two).
const PAGE_BITS: u32 = 12;
const PAGE_MASK: u32 = (1 << PAGE_BITS) - 1;

/// A search's cost so far and edge arrived by for each node it reaches,
/// in pages of 4096 nodes made when the search first reaches one. Node
/// ids follow the map (Hilbert order), so a search only makes the pages
/// around its route. Filling one array per node of every open region
/// before each search took longer than a short search itself: about
/// 20 ms per search over the four linked Nordic regions (4.7 M nodes),
/// and route choices run a dozen or more searches. Costs and edges are
/// kept in separate arrays, 12 bytes a node: as pairs they took 16, 4 of
/// them padding, and a long search reaches millions of nodes.
struct NodeState {
    pages: Vec<Option<Page>>,
}

/// One page of [`NodeState`]: cost so far and edge arrived by per node.
struct Page {
    dist: Box<[f64]>,
    parent: Box<[u32]>,
}

impl NodeState {
    fn new(nodes: usize) -> Self {
        let mut pages = Vec::new();
        pages.resize_with(nodes.div_ceil(1 << PAGE_BITS), || None);
        Self { pages }
    }

    /// Cost so far and edge arrived by of `v`: infinite and `NONE` until
    /// set. Panics for a node out of range, like a slice.
    #[cfg(test)]
    fn get(&self, v: u32) -> (f64, u32) {
        match &self.pages[(v >> PAGE_BITS) as usize] {
            Some(page) => {
                let i = (v & PAGE_MASK) as usize;
                (page.dist[i], page.parent[i])
            }
            None => (f64::INFINITY, NONE),
        }
    }

    #[inline]
    fn dist(&self, v: u32) -> f64 {
        match &self.pages[(v >> PAGE_BITS) as usize] {
            Some(page) => page.dist[(v & PAGE_MASK) as usize],
            None => f64::INFINITY,
        }
    }

    #[inline]
    fn parent(&self, v: u32) -> u32 {
        match &self.pages[(v >> PAGE_BITS) as usize] {
            Some(page) => page.parent[(v & PAGE_MASK) as usize],
            None => NONE,
        }
    }

    #[inline]
    fn set(&mut self, v: u32, dist: f64, parent: u32) {
        let page = self.pages[(v >> PAGE_BITS) as usize].get_or_insert_with(|| Page {
            dist: vec![f64::INFINITY; 1 << PAGE_BITS].into_boxed_slice(),
            parent: vec![NONE; 1 << PAGE_BITS].into_boxed_slice(),
        });
        let i = (v & PAGE_MASK) as usize;
        page.dist[i] = dist;
        page.parent[i] = parent;
    }
}

/// Cheapest path from `from` to `to` under `cost`, as edge pieces in
/// travel order. The stretches of road the two points lie on count at their
/// plain cost. `max_speed_kmh` bounds every edge's speed and keeps the A*
/// estimate admissible.
pub(crate) fn path(
    region: &Net,
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
                let c = cost.partial(&region.edge(l.edge), a.to - l.from);
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

    let mut state = NodeState::new(region.node_count());
    let mut heap = BinaryHeap::new();
    let target = to.position;
    let per_m = cost.least_per_m(max_speed_kmh);
    let h = |v: u32| haversine_m(latlon(region.node(v)), target) * per_m;
    // Keys are non-negative f64s, whose bit patterns sort like the values.
    let key = |cost: f64| cost.to_bits();

    for &(node, l) in &leave {
        let c = cost.partial(&region.edge(l.edge), l.to - l.from);
        if c < state.dist(node) {
            state.set(node, c, NONE);
            heap.push(Reverse((key(c + h(node)), node)));
        }
    }
    while let Some(Reverse((k, v))) = heap.pop() {
        let g = state.dist(v);
        if f64::from_bits(k) >= best_cost {
            break;
        }
        if f64::from_bits(k) > g + h(v) + 1e-9 {
            continue; // stale entry
        }
        for &(node, a) in &arrive {
            if node == v {
                let c = g + cost.partial(&region.edge(a.edge), a.to - a.from);
                if c < best_cost {
                    best_cost = c;
                    best = Some((v, a));
                    direct = None;
                }
            }
        }
        for id in region.out_edges(v) {
            let e = region.edge(id);
            let c = g + cost.edge(id, &e);
            if c < state.dist(e.head) {
                state.set(e.head, c, id);
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
    while state.parent(v) != NONE {
        let id = state.parent(v);
        path.push(id);
        v = region.edge(id).tail;
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
    region: &Net,
    from: &RoadPoint,
    targets: &[RoadPoint],
    limit_m: f64,
) -> Vec<Option<(f64, Vec<Partial>)>> {
    let cost = Cost::Shortest;
    let (leave, _) = partials(region, from);
    let edge = |id: u32| region.edge(id);

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
    use super::{HashMap, same_road};
    use crate::fixture::{self, L_S};
    use crate::geo::haversine_m;
    use crate::region::Region;
    use crate::region::format::{COORD_SCALE, Surface};
    use crate::region::format::{Edge, RoadClass, edge_flags};
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
                tolls: false,
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
    fn the_same_road_allows_a_short_spur_but_not_a_detour() {
        let m = |v: &[(u32, f64)]| v.iter().copied().collect::<HashMap<u32, f64>>();
        let fastest = m(&[(1, 1000.0), (2, 900.0)]);
        assert!(same_road(&fastest, &fastest));
        // 25 m out along the next road and back.
        assert!(same_road(
            &m(&[(1, 1000.0), (2, 900.0), (3, 50.0)]),
            &fastest
        ));
        // A little further along a road it rides anyway.
        assert!(same_road(&m(&[(1, 1000.0), (2, 1080.0)]), &fastest));
        // Another road for half the way.
        assert!(!same_road(&m(&[(1, 1000.0), (3, 1000.0)]), &fastest));
        // Long routes: 5 % of the length.
        let long = m(&[(1, 20_000.0)]);
        assert!(same_road(&m(&[(1, 20_000.0), (4, 900.0)]), &long));
        assert!(!same_road(&m(&[(1, 20_000.0), (4, 1500.0)]), &long));
        assert!(!same_road(&m(&[(4, 20_000.0)]), &long));
    }

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
        let s_node = e.net().node(L_S);
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

    /// As [`twisty`], with the same winding road mirrored to the south
    /// (way 24): two equally fun ways round.
    fn twisty_twice() -> Engine {
        use crate::fixture::{Road, build};
        use crate::region::format::RoadClass;
        let nodes = [
            (55.70, 13.39),
            (55.70, 13.40),
            (55.70, 13.48),
            (55.70, 13.49),
        ];
        let bends = |side: f64| -> Vec<(f64, f64)> {
            (1..160)
                .map(|i| {
                    let t = f64::from(i) / 160.0;
                    let lat = 55.70
                        + side * 0.0055 * (std::f64::consts::PI * t).sin()
                        + 0.00055 * (t * 40.0 * std::f64::consts::PI).sin();
                    (lat, 13.40 + 0.08 * t)
                })
                .collect()
        };
        let north = Road {
            via: bends(1.0),
            ..Road::new(1, 2, RoadClass::Tertiary, 80, 21)
        };
        let south = Road {
            via: bends(-1.0),
            ..Road::new(1, 2, RoadClass::Tertiary, 80, 24)
        };
        engine(build(
            &nodes,
            &[
                Road::new(0, 1, RoadClass::Primary, 90, 20),
                Road::new(1, 2, RoadClass::Primary, 90, 22),
                north,
                south,
                Road::new(2, 3, RoadClass::Primary, 90, 23),
            ],
            50_000,
        ))
    }

    #[test]
    fn unridden_curvy_roads_pull_harder_than_ridden_ones() {
        use crate::favourites::Favourites;
        use crate::section::WaySpan;
        let e = twisty_twice();
        let (from, to) = (ll(55.70, 13.395), ll(55.70, 13.485));
        let opts = |unridden| RouteOptions {
            budget: crate::TimeBudget::Extra(1.0),
            min_gain: 0.5,
            unridden,
            ..RouteOptions::default()
        };
        let side = |r: &crate::Route| {
            if r.geometry.iter().any(|p| p.lat > 55.703) {
                21
            } else if r.geometry.iter().any(|p| p.lat < 55.697) {
                24
            } else {
                0
            }
        };
        // No rides: one of the winding roads, all of it unridden.
        let none = Favourites::none();
        let any = e
            .route_with(from, to, &opts(crate::UnriddenMode::Any), &none)
            .unwrap();
        let ridden_way = side(&any);
        assert_ne!(ridden_way, 0, "{any:?}");
        assert_eq!(any.unridden_share, 1.0);
        // Preferring unridden roads with no rides changes nothing.
        let prefer = e
            .route_with(from, to, &opts(crate::UnriddenMode::Prefer), &none)
            .unwrap();
        assert_eq!(prefer, any);

        // A ride along that winding road.
        let rides = vec![vec![WaySpan {
            way_id: ridden_way,
            from_idx: 0,
            to_idx: 160,
        }]];
        let fav = Favourites::build_with_rides(&e, &[], &rides);
        assert_eq!(fav.ridden_edge_count(), 2, "one road, both ways");
        // Any: the same route, now mostly ridden.
        let again = e
            .route_with(from, to, &opts(crate::UnriddenMode::Any), &fav)
            .unwrap();
        assert_eq!(again.geometry, any.geometry);
        assert!(again.unridden_share < 0.3, "{again:?}");
        // Prefer: the other winding road, all of it unridden.
        let new = e
            .route_with(from, to, &opts(crate::UnriddenMode::Prefer), &fav)
            .unwrap();
        assert_ne!(side(&new), ridden_way, "{new:?}");
        assert_ne!(side(&new), 0, "{new:?}");
        assert_eq!(new.unridden_share, 1.0);
        assert!((new.curvy_share - any.curvy_share).abs() < 0.02, "{new:?}");
    }

    #[test]
    fn a_road_counts_as_ridden_from_half_of_it() {
        use crate::favourites::Favourites;
        use crate::section::WaySpan;
        let e = twisty_twice();
        let ride = |to_idx| {
            vec![vec![WaySpan {
                way_id: 21,
                from_idx: 0,
                to_idx,
            }]]
        };
        // Way 21 is one edge each way, nodes 0–160 along it.
        assert_eq!(
            Favourites::build_with_rides(&e, &[], &ride(60)).ridden_edge_count(),
            0
        );
        assert_eq!(
            Favourites::build_with_rides(&e, &[], &ride(100)).ridden_edge_count(),
            2
        );
        // Ridden against the way: the same.
        let back = vec![vec![WaySpan {
            way_id: 21,
            from_idx: 160,
            to_idx: 20,
        }]];
        assert_eq!(
            Favourites::build_with_rides(&e, &[], &back).ridden_edge_count(),
            2
        );
        // Ways the region doesn't have, and empty spans, mark nothing.
        let stray = vec![vec![
            WaySpan {
                way_id: 999,
                from_idx: 0,
                to_idx: 5,
            },
            WaySpan {
                way_id: 21,
                from_idx: 7,
                to_idx: 7,
            },
        ]];
        let none = Favourites::build_with_rides(&e, &[], &stray);
        assert_eq!(none.ridden_edge_count(), 0);
        assert!(none.is_empty());
        // Ridden roads built for one region are refused on another.
        let fav = Favourites::build_with_rides(&e, &[], &ride(160));
        let other = twisty();
        assert!(matches!(
            other.route_with(
                ll(55.70, 13.395),
                ll(55.70, 13.485),
                &RouteOptions::default(),
                &fav
            ),
            Err(CoreError::InvalidArgument(_))
        ));
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
    fn a_favourite_ridden_again_earns_nothing_in_a_loop() {
        use super::{Cost, Fun, Off, time_s};
        use crate::scoring::PARAMS;
        use crate::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};
        use std::collections::HashSet;
        // An epic favourite along A-B: cheaper than its time the first
        // time. Ridden again in a loop it costs the reuse penalty on its
        // full time; route choices only scale its favoured cost.
        let e = engine(fixture::region());
        let d = e
            .section_between(ll(55.7001, 13.201), ll(55.7001, 13.209))
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
        let o = opts(false, false);
        let fun = Fun::new(e.net(), &fav, &o);
        let edge = e.net().edge(fixture::E_AB);
        let time = time_s(&edge);
        let none = HashSet::new();
        let used: HashSet<u32> = [edge.geometry].into();
        let cost =
            |used, once| Cost::Loop(Off::of(&o), fun, 1.0, used, once).edge(fixture::E_AB, &edge);
        assert!(cost(&none, true) < time);
        assert!((cost(&used, true) - time * PARAMS.reuse_penalty).abs() < 1e-9);
        assert!(cost(&used, false) < time * PARAMS.reuse_penalty);
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
            unridden_share: share,
            fastest_duration_s: 50.0,
            favourite_ratings: vec![crate::section::Rating::Great; parts.len()],
            favourite_parts: parts.clone(),
            unpaved_m: 0.0,
            unpaved_parts: parts,
            toll_m: 100.0,
            suggested: false,
        };
        let r = super::join(vec![
            leg(55.0, 55.1, 1.0, vec![vec![p(55.05), p(55.1)]]),
            leg(55.1, 55.2, 0.0, vec![vec![p(55.1), p(55.15)]]),
        ]);
        assert_eq!(r.geometry, [p(55.0), p(55.1), p(55.2)]);
        assert_eq!(r.favourite_parts, [vec![p(55.05), p(55.1), p(55.15)]]);
        assert_eq!(r.favourite_ratings, [crate::section::Rating::Great]);
        assert_eq!(r.unpaved_parts.len(), 1);
        assert_eq!(r.toll_m, 200.0);
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

    fn edge(class: RoadClass, flags: u8) -> Edge {
        Edge {
            tail: 0,
            head: 1,
            length_dm: 10_000,
            geometry: 0,
            speed_kmh: 72,
            class: class as u8,
            surface: Surface::Asphalt as u8,
            flags,
        }
    }

    fn off(motorways: bool, ferries: bool, tolls: bool) -> super::Off {
        super::Off {
            avoid: Avoid {
                motorways,
                ferries,
                tolls,
            },
            unpaved: false,
        }
    }

    #[test]
    fn avoided_kinds_cost_their_factor_once() {
        use crate::scoring::PARAMS;
        let all = off(true, true, true);
        let region = toll_or_detour(true);
        let none = crate::Favourites::none();
        let o = RouteOptions::default();
        let fun = super::Fun::new(region.net(), &none, &o);
        let factor =
            |o: &super::Off, e: &Edge| 1.0 + super::avoid_extra(o, &fun, 0, e) / super::time_s(e);
        let close = |a: f64, b: f64| (a - b).abs() < 1e-9;
        let (tertiary, motorway, ferry) =
            (RoadClass::Tertiary, RoadClass::Motorway, RoadClass::Ferry);
        assert!(close(factor(&all, &edge(tertiary, 0)), 1.0));
        assert!(close(
            factor(&all, &edge(motorway, 0)),
            PARAMS.avoid_penalty
        ));
        assert!(close(
            factor(&all, &edge(ferry, edge_flags::FERRY)),
            PARAMS.avoid_penalty
        ));
        assert!(close(
            factor(&all, &edge(tertiary, edge_flags::TOLL)),
            PARAMS.toll_penalty
        ));
        // A tolled motorway (a bridge) counts once, at the larger factor.
        assert!(close(
            factor(&all, &edge(motorway, edge_flags::TOLL)),
            PARAMS.toll_penalty
        ));
        // A ferry with a fare counts as a ferry, avoided or not.
        let paid_ferry = edge(ferry, edge_flags::FERRY | edge_flags::TOLL);
        assert!(close(factor(&all, &paid_ferry), PARAMS.avoid_penalty));
        assert!(close(factor(&off(false, false, true), &paid_ferry), 1.0));
        // Allowed: no extra.
        let none = off(false, false, false);
        for e in [
            edge(motorway, edge_flags::TOLL),
            edge(tertiary, edge_flags::TOLL),
            paid_ferry,
        ] {
            assert!(close(factor(&none, &e), 1.0));
        }
    }

    /// A toll road straight east (70 km/h, about 6 km) and a free road
    /// round by the north, about twice as long, between short free roads
    /// at each end; without `detour` the free road is left out.
    fn toll_or_detour(detour: bool) -> Engine {
        let nodes = [
            (55.70, 13.40),
            (55.70, 13.50),
            (55.75, 13.45),
            (55.70, 13.39),
            (55.70, 13.51),
        ];
        let mut toll = fixture::Road::new(0, 1, RoadClass::Tertiary, 70, 1);
        toll.flags = edge_flags::TOLL;
        let mut roads = vec![
            toll,
            fixture::Road::new(3, 0, RoadClass::Tertiary, 70, 4),
            fixture::Road::new(1, 4, RoadClass::Tertiary, 70, 5),
        ];
        if detour {
            roads.push(fixture::Road::new(0, 2, RoadClass::Tertiary, 70, 2));
            roads.push(fixture::Road::new(2, 1, RoadClass::Tertiary, 70, 3));
        }
        engine(fixture::build(&nodes, &roads, 50_000))
    }

    #[test]
    fn toll_roads_are_avoided_unless_there_is_no_other_way() {
        let (a, b) = (ll(55.70, 13.391), ll(55.70, 13.509));
        let plain = |tolls: bool| RouteOptions {
            avoid: Avoid {
                motorways: true,
                ferries: true,
                tolls,
            },
            curvy: false,
            ..RouteOptions::default()
        };
        let e = toll_or_detour(true);
        let around = e.route(a, b, &plain(true)).unwrap();
        assert!(around.geometry.iter().any(|p| p.lat > 55.74), "{around:?}");
        assert_eq!(around.toll_m, 0.0);
        let over = e.route(a, b, &plain(false)).unwrap();
        assert!(over.geometry.iter().all(|p| p.lat < 55.71), "{over:?}");
        assert!((over.toll_m - 6300.0).abs() < 100.0, "{over:?}");
        assert!(over.duration_s < around.duration_s);
        // The only way: ridden even when avoided, and counted.
        let only = toll_or_detour(false).route(a, b, &plain(true)).unwrap();
        assert!(only.toll_m > 6000.0, "{only:?}");
    }

    #[test]
    fn allowed_travel_roads_are_the_least_fun() {
        use crate::scoring::PARAMS;
        let e = toll_or_detour(true);
        let none = crate::Favourites::none();
        let o = RouteOptions::default();
        let fun = super::Fun::new(e.net(), &none, &o);
        for (i, road) in e.net().regions()[0].edges().iter().enumerate() {
            let id = i as u32;
            if road.flags & edge_flags::TOLL != 0 {
                assert_eq!(fun.speed_penalty(id, road), PARAMS.fast_penalty);
                assert_eq!(fun.curviness(id, road), 0.0);
            } else {
                assert_eq!(fun.speed_penalty(id, road), 1.0, "a 70 road is not dull");
            }
        }
        let motorway = edge(RoadClass::Motorway, 0);
        let ferry = edge(RoadClass::Ferry, edge_flags::FERRY);
        assert_eq!(fun.speed_penalty(0, &motorway), PARAMS.fast_penalty);
        assert_eq!(fun.speed_penalty(0, &ferry), PARAMS.fast_penalty);
    }

    #[test]
    fn node_state_makes_pages_only_where_a_search_goes() {
        use super::{NONE, NodeState};
        // Two and a bit pages of nodes.
        let mut state = NodeState::new(2 * 4096 + 10);
        assert_eq!(state.pages.len(), 3);
        assert_eq!(state.get(5), (f64::INFINITY, NONE));
        state.set(4096 + 7, 12.5, 3);
        assert_eq!(state.dist(4096 + 7), 12.5);
        assert_eq!(state.parent(4096 + 7), 3);
        // Only the page reached exists; the rest still reads as unreached.
        assert!(state.pages[0].is_none() && state.pages[1].is_some() && state.pages[2].is_none());
        assert_eq!(state.get(4096 + 8), (f64::INFINITY, NONE));
        // The last node of the partial last page.
        state.set(2 * 4096 + 9, 1.0, NONE);
        assert_eq!(state.get(2 * 4096 + 9), (1.0, NONE));
    }

    #[test]
    fn the_estimate_never_exceeds_a_roads_cost() {
        use crate::section::{Direction, LOCAL_RIDER, Rating, Section, Source, Status};
        use crate::{Favourites, FavouritesMode};
        // A slow gravel road, a fast motorway and roads joining them; with
        // and without an epic favourite on the gravel road.
        let e = engine(fixture::ladder(Surface::Gravel));
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
        let fav = Favourites::build(&e, &[s]);
        assert!(fav.max_speed_kmh() > 0.0 && fav.max_speed_kmh() < e.max_speed_kmh());
        let none = Favourites::none();
        let edges = e.net().regions()[0].edges().to_vec();
        for favourites in [&none, &fav] {
            for gravel in [Gravel::Avoid, Gravel::Allow, Gravel::Prefer] {
                for (curvy, mode) in [
                    (true, FavouritesMode::Prefer),
                    (false, FavouritesMode::Prefer),
                    (true, FavouritesMode::Avoid),
                ] {
                    let o = RouteOptions {
                        gravel,
                        curvy,
                        favourites: mode,
                        ..RouteOptions::default()
                    };
                    let fun = super::Fun::new(e.net(), favourites, &o);
                    for pull in [0.0, 0.25, 0.5, 1.0] {
                        let cost = super::Cost::Favoured(super::Off::of(&o), fun, pull);
                        let per_m = cost.least_per_m(e.max_speed_kmh());
                        for (i, road) in edges.iter().enumerate() {
                            let length_m = f64::from(road.length_dm) / 10.0;
                            let c = cost.edge(i as u32, road);
                            assert!(
                                c + 1e-9 >= length_m * per_m,
                                "edge {i}, {gravel:?}, curvy {curvy}, {mode:?}, pull {pull}: {c} < {}",
                                length_m * per_m
                            );
                        }
                    }
                }
            }
        }
        // Preferred gravel no faster than the motorway's cars: the estimate
        // is tighter than one assuming the most worth at the top speed.
        let o = RouteOptions {
            gravel: Gravel::Prefer,
            ..RouteOptions::default()
        };
        let fun = super::Fun::new(e.net(), &none, &o);
        let per_m =
            super::Cost::Favoured(super::Off::of(&o), fun, 1.0).least_per_m(e.max_speed_kmh());
        let loose = (1.0 - crate::scoring::PARAMS.max_pull) / (e.max_speed_kmh() / 3.6);
        assert!(per_m > loose * 1.2, "{per_m} vs {loose}");
    }
}
