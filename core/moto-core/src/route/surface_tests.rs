// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Routing by the rider's surface corrections (ADR-0018).

use super::{Cost, Fun, Off};
use crate::fixture;
use crate::geo::haversine_m;
use crate::region::Region;
use crate::region::format::{RoadClass, Surface};
use crate::surface::tests::{correction, north_draft, shaped_ladder};
use crate::{Engine, Favourites, Gravel, LatLon, RouteOptions};

fn engine(data: crate::region::RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

const FROM: LatLon = LatLon {
    lat: 55.71,
    lon: 13.3995,
};
const TO: LatLon = LatLon {
    lat: 55.71,
    lon: 13.4405,
};
/// The middle of the ladder's motorway.
const SOUTH: LatLon = LatLon {
    lat: 55.70,
    lon: 13.42,
};
/// Metres per degree of longitude at lat 55.72.
const M_PER_DEG_LON: f64 = 62_791.0;

/// The ladder with its north road at 70 km/h.
fn fast_ladder(north: Surface) -> Engine {
    let mut data = fixture::ladder(north);
    for e in &mut data.edges {
        if e.class == RoadClass::Residential as u8 {
            e.speed_kmh = 70;
        }
    }
    engine(data)
}

fn options(gravel: Gravel, curvy: bool) -> RouteOptions {
    RouteOptions {
        gravel,
        curvy,
        ..RouteOptions::default()
    }
}

fn no_motorways(gravel: Gravel) -> RouteOptions {
    let mut o = options(gravel, false);
    o.avoid.motorways = true;
    o
}

fn passes(route: &crate::Route, p: LatLon) -> bool {
    route.geometry.iter().any(|&q| haversine_m(q, p) < 1.0)
}

/// The overlay of `engine` with `corrections` alone.
fn overlay(engine: &Engine, corrections: &[crate::surface::SurfaceCorrection]) -> Favourites {
    Favourites::build_all(engine, &[], &[], corrections)
}

#[test]
fn routes_without_corrections_keep_their_figures() {
    // Pinned on the code before surface corrections: every figure to the
    // bit, with no overlay and with an empty one.
    let pins: [(Gravel, bool, [u64; 4]); 3] = [
        (
            Gravel::Prefer,
            true,
            [
                0x40b279199999999a,
                0x40720f5ac172bf1c,
                0x40a3926666666666,
                0x40720f5ac172bf1c,
            ],
        ),
        (
            Gravel::Avoid,
            false,
            [
                0x40b27a4ccccccccd,
                0x406e44bffd9deb60,
                0x0,
                0x406e44bffd9deb60,
            ],
        ),
        (
            Gravel::Allow,
            true,
            [
                0x40b279199999999a,
                0x40720f5ac172bf1c,
                0x40a3926666666666,
                0x40720f5ac172bf1c,
            ],
        ),
    ];
    let bits = |r: &crate::Route| {
        [
            r.distance_m.to_bits(),
            r.duration_s.to_bits(),
            r.unpaved_m.to_bits(),
            r.fastest_duration_s.to_bits(),
        ]
    };
    let e = fast_ladder(Surface::Gravel);
    let none = Favourites::none();
    let empty = overlay(&e, &[]);
    for (g, curvy, pin) in pins {
        let r = e.route_with(FROM, TO, &options(g, curvy), &none).unwrap();
        assert_eq!(bits(&r), pin, "{g:?}");
        let again = e.route_with(FROM, TO, &options(g, curvy), &empty).unwrap();
        assert_eq!(again, r, "{g:?}");
    }
    let e = engine(fixture::ladder(Surface::Gravel));
    let mut o = options(Gravel::Prefer, true);
    o.avoid.motorways = true;
    let r = e.route_with(FROM, TO, &o, &none).unwrap();
    assert_eq!(
        bits(&r),
        [
            0x40b279199999999a,
            0x407ccbeab367a0f9,
            0x40a3926666666666,
            0x407ccbeab367a0f9
        ]
    );
}

#[test]
fn a_road_corrected_to_gravel_is_avoided_and_preferred() {
    // The north road is asphalt on the map; the rider says gravel.
    let e = fast_ladder(Surface::Asphalt);
    let whole = e
        .section_between(
            LatLon {
                lat: 55.7201,
                lon: 13.4001,
            },
            LatLon {
                lat: 55.7201,
                lon: 13.4399,
            },
        )
        .unwrap();
    let fav = overlay(&e, &[correction(1, Surface::Gravel, &whole, 10)]);
    // Motorways and gravel avoided: the motorway is the lesser evil now.
    let o = no_motorways(Gravel::Avoid);
    let plain = e.route_with(FROM, TO, &o, &Favourites::none()).unwrap();
    assert!(!passes(&plain, SOUTH), "{plain:?}");
    let r = e.route_with(FROM, TO, &o, &fav).unwrap();
    assert!(passes(&r, SOUTH), "{r:?}");
    // Preferred, it pulls the route north although the motorway is faster.
    let o = options(Gravel::Prefer, false);
    assert_eq!(
        e.route_with(FROM, TO, &o, &Favourites::none())
            .unwrap()
            .unpaved_m,
        0.0
    );
    let r = e.route_with(FROM, TO, &o, &fav).unwrap();
    assert!(!passes(&r, SOUTH), "{r:?}");
    let north_m = 0.04 * M_PER_DEG_LON;
    assert!((r.unpaved_m - north_m).abs() < 30.0, "{r:?}");
}

#[test]
fn gravel_figures_cover_the_corrected_part_only() {
    // A quarter of the north road, nodes 2–6 (lon 13.41–13.43), across
    // the junction in its middle.
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let fav = overlay(
        &e,
        &[correction(1, Surface::Gravel, &north_draft(2, 6), 10)],
    );
    let r = e
        .route_with(FROM, TO, &no_motorways(Gravel::Allow), &fav)
        .unwrap();
    assert!(!passes(&r, SOUTH));
    let want = 0.02 * M_PER_DEG_LON;
    assert!(
        (r.unpaved_m - want).abs() < 5.0,
        "{} vs {want}",
        r.unpaved_m
    );
    assert_eq!(r.unpaved_parts.len(), 1, "{:?}", r.unpaved_parts);
    let part = &r.unpaved_parts[0];
    let lons: Vec<f64> = part.iter().map(|p| p.lon).collect();
    let (lo, hi) = (
        lons.iter().copied().fold(f64::INFINITY, f64::min),
        lons.iter().copied().fold(f64::NEG_INFINITY, f64::max),
    );
    assert!(
        (lo - 13.41).abs() < 1e-6 && (hi - 13.43).abs() < 1e-6,
        "{lo} {hi}"
    );
    // The other way round, the same stretch.
    let back = e
        .route_with(TO, FROM, &no_motorways(Gravel::Allow), &fav)
        .unwrap();
    assert!((back.unpaved_m - want).abs() < 5.0, "{}", back.unpaved_m);
}

#[test]
fn mapped_gravel_corrected_to_paved_is_ridden_while_gravel_is_avoided() {
    let e = engine(fixture::ladder(Surface::Gravel));
    let whole = e
        .section_between(
            LatLon {
                lat: 55.7201,
                lon: 13.4001,
            },
            LatLon {
                lat: 55.7201,
                lon: 13.4399,
            },
        )
        .unwrap();
    let fav = overlay(&e, &[correction(1, Surface::Paved, &whole, 10)]);
    let o = no_motorways(Gravel::Avoid);
    let plain = e.route_with(FROM, TO, &o, &Favourites::none()).unwrap();
    assert!(passes(&plain, SOUTH), "{plain:?}");
    let r = e.route_with(FROM, TO, &o, &fav).unwrap();
    assert!(!passes(&r, SOUTH), "{r:?}");
    assert_eq!(r.unpaved_m, 0.0);
}

#[test]
fn the_estimate_stays_below_every_edge_cost() {
    // The 110 km/h motorway corrected to gravel, gravel preferred: it is
    // now the fastest gravel, faster than any the map has.
    let e = engine(fixture::ladder(Surface::Gravel));
    let motorway = e
        .section_between(
            LatLon {
                lat: 55.7001,
                lon: 13.4001,
            },
            LatLon {
                lat: 55.7001,
                lon: 13.4399,
            },
        )
        .unwrap();
    let fav = overlay(&e, &[correction(1, Surface::Gravel, &motorway, 10)]);
    assert!(fav.max_unpaved_kmh() > e.net().max_unpaved_kmh());
    for curvy in [false, true] {
        let o = options(Gravel::Prefer, curvy);
        let fun = Fun::new(e.net(), &fav, &o);
        let off = Off::of(&o);
        for pull in [0.0, 0.5, 1.0] {
            let least = fun.least_cost_per_m(pull, e.max_speed_kmh());
            for id in 0..e.net().edge_count() as u32 {
                let edge = e.net().edge(id);
                let per_m = Cost::Favoured(off, fun, pull).edge(id, &edge)
                    / (f64::from(edge.length_dm) / 10.0);
                assert!(
                    least <= per_m + 1e-12,
                    "edge {id} at {pull}: {per_m} < {least}"
                );
            }
        }
    }
}

#[test]
fn partly_corrected_edges_cost_by_their_share() {
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let fav = overlay(
        &e,
        &[correction(1, Surface::Gravel, &north_draft(0, 1), 10)],
    );
    let o = options(Gravel::Avoid, false);
    let fun = Fun::new(e.net(), &fav, &o);
    let id = (0..e.net().edge_count() as u32)
        .find(|&id| e.net().way_ref(id).way_id == 1 && e.net().way_ref(id).from_idx == 0)
        .unwrap();
    let edge = e.net().edge(id);
    let share = fav.unpaved_share(id, &edge);
    assert!((share - 0.25).abs() < 1e-3, "{share}");
    let time = f64::from(edge.length_dm) / 10.0 / (f64::from(edge.speed_kmh) / 3.6);
    let extra = super::avoid_extra(&Off::of(&o), &fun, id, &edge);
    let want = time * share * (crate::scoring::PARAMS.avoid_penalty - 1.0);
    assert!((extra - want).abs() < 1e-6, "{extra} vs {want}");
    let preferred = options(Gravel::Prefer, false);
    let fun = Fun::new(e.net(), &fav, &preferred);
    let worth = fun.gravel_worth(id, &edge);
    assert!((worth - share * crate::scoring::PARAMS.gravel_weight).abs() < 1e-9);
}

#[test]
fn the_fastest_route_goes_by_the_corrected_surface_alone() {
    let e = fast_ladder(Surface::Asphalt);
    let whole = e
        .section_between(
            LatLon {
                lat: 55.7201,
                lon: 13.4001,
            },
            LatLon {
                lat: 55.7201,
                lon: 13.4399,
            },
        )
        .unwrap();
    let section = crate::section::Section {
        surface: None,
        id: 1,
        rider_id: crate::section::LOCAL_RIDER.into(),
        name: String::new(),
        rating: crate::section::Rating::Epic,
        direction: crate::section::Direction::Both,
        source: crate::section::Source::Map,
        status: crate::section::Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: whole.ways.clone(),
        geometry: whole.geometry.clone(),
    };
    let fav = Favourites::build_all(
        &e,
        std::slice::from_ref(&section),
        &[],
        &[correction(1, Surface::Gravel, &whole, 10)],
    );
    let o = no_motorways(Gravel::Avoid);
    assert!(!passes(&e.route(FROM, TO, &o).unwrap(), SOUTH));
    // The correction counts, the epic favourite on it doesn't pull.
    let r = e.fastest_route(FROM, TO, &o, &fav).unwrap();
    assert!(passes(&r, SOUTH), "{r:?}");
    assert!(fav.surface_only().is_empty());
    assert_eq!(
        e.fastest_route(FROM, TO, &o, &Favourites::none()).unwrap(),
        e.route(FROM, TO, &o).unwrap()
    );
}
