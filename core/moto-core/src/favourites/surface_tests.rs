// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The rider's surface corrections in the overlay (ADR-0018).

use super::*;
use crate::region::Region;
use crate::region::format::Surface;
use crate::route::is_unpaved;
use crate::section::{LOCAL_RIDER, Source};
use crate::surface::tests::{correction, north_draft, shaped_ladder};

fn engine(data: crate::region::RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

/// The shaped ladder's edge along way 1 starting at way node `from`.
fn edge_from(e: &Engine, from: u32, to: u32) -> u32 {
    (0..e.net().edge_count() as u32)
        .find(|&id| {
            let r = e.net().way_ref(id);
            r.way_id == 1 && r.from_idx == from && r.to_idx == to
        })
        .unwrap()
}

fn stretches(f: &Favourites, e: &Engine, id: u32) -> Vec<(f64, f64)> {
    f.unpaved_stretches(id, &e.net().edge(id))
        .iter()
        .map(|&(a, b)| (f64::from(a), f64::from(b)))
        .collect()
}

fn close(a: &[(f64, f64)], b: &[(f64, f64)]) -> bool {
    a.len() == b.len()
        && a.iter()
            .zip(b)
            .all(|(x, y)| (x.0 - y.0).abs() < 1e-3 && (x.1 - y.1).abs() < 1e-3)
}

#[test]
fn without_corrections_the_map_decides() {
    for surface in [Surface::Asphalt, Surface::Gravel, Surface::Unknown] {
        let e = engine(shaped_ladder(surface, 30));
        let f = Favourites::build_all(&e, &[], &[], &[]);
        assert!(f.surface_marked.is_empty() && f.surface.is_empty());
        for id in 0..e.net().edge_count() as u32 {
            let edge = e.net().edge(id);
            let want = if is_unpaved(&edge) { 1.0 } else { 0.0 };
            assert_eq!(f.unpaved_share(id, &edge), want);
            assert_eq!(f.is_unpaved_at(id, &edge), is_unpaved(&edge));
        }
    }
    // A correction that says what the map says changes nothing.
    let e = engine(shaped_ladder(Surface::Gravel, 30));
    let f = Favourites::build_all(
        &e,
        &[],
        &[],
        &[correction(1, Surface::Gravel, &north_draft(0, 8), 1)],
    );
    assert!(f.surface_marked.is_empty(), "{:?}", f.surface);
}

#[test]
fn a_correction_sets_the_share_both_ways() {
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let (fwd, back) = (edge_from(&e, 0, 4), edge_from(&e, 4, 0));
    // The whole first road.
    let f = Favourites::build_all(
        &e,
        &[],
        &[],
        &[correction(1, Surface::Gravel, &north_draft(0, 4), 1)],
    );
    for id in [fwd, back] {
        let edge = e.net().edge(id);
        assert_eq!(f.unpaved_share(id, &edge), 1.0);
        assert!(f.is_unpaved_at(id, &edge));
        assert_eq!(stretches(&f, &e, id), [(0.0, 1.0)]);
    }
    // Nodes 1–2 of 0–4: a quarter, in travel order on each direction.
    let f = Favourites::build_all(
        &e,
        &[],
        &[],
        &[correction(1, Surface::Gravel, &north_draft(2, 1), 1)],
    );
    let edge = e.net().edge(fwd);
    assert!((f.unpaved_share(fwd, &edge) - 0.25).abs() < 1e-3);
    assert!(!f.is_unpaved_at(fwd, &edge));
    assert!(close(&stretches(&f, &e, fwd), &[(0.25, 0.5)]));
    assert!(close(&stretches(&f, &e, back), &[(0.5, 0.75)]));
    // The other road is untouched.
    let other = edge_from(&e, 4, 8);
    assert_eq!(f.unpaved_share(other, &e.net().edge(other)), 0.0);
    assert!(stretches(&f, &e, other).is_empty());
}

#[test]
fn paved_over_gravel_newest_wins_and_waiting_ones_are_ignored() {
    let e = engine(shaped_ladder(Surface::Gravel, 30));
    let fwd = edge_from(&e, 0, 4);
    let edge = e.net().edge(fwd);
    let paved = correction(1, Surface::Paved, &north_draft(0, 4), 1);
    let f = Favourites::build_all(&e, &[], &[], std::slice::from_ref(&paved));
    assert_eq!(f.unpaved_share(fwd, &edge), 0.0);
    assert!(stretches(&f, &e, fwd).is_empty());

    // Older Paved on all, newer Gravel on nodes 1–2: gravel there only.
    let gravel = correction(2, Surface::Gravel, &north_draft(1, 2), 5);
    let f = Favourites::build_all(&e, &[], &[], &[gravel.clone(), paved.clone()]);
    assert!(close(&stretches(&f, &e, fwd), &[(0.25, 0.5)]));
    // The other way round in time: the Paved one decides it all.
    let mut newer_paved = paved.clone();
    newer_paved.updated_at = 9;
    let f = Favourites::build_all(&e, &[], &[], &[gravel.clone(), newer_paved]);
    assert!(stretches(&f, &e, fwd).is_empty());
    // Same time: the higher id is newer.
    let mut same = paved.clone();
    same.updated_at = 5;
    same.id = 3;
    let f = Favourites::build_all(&e, &[], &[], &[same, gravel]);
    assert!(stretches(&f, &e, fwd).is_empty());

    // Waiting for a re-match: not applied.
    let mut waiting = paved;
    waiting.status = Status::NeedsRematch;
    let f = Favourites::build_all(&e, &[], &[], &[waiting]);
    assert_eq!(f.unpaved_share(fwd, &edge), 1.0);
    assert!(f.surface_marked.is_empty());
}

#[test]
fn the_unpaved_speed_bound_and_the_region_check() {
    let e = engine(shaped_ladder(Surface::Asphalt, 40));
    assert_eq!(e.net().max_unpaved_kmh(), 0.0);
    let f = Favourites::build_all(
        &e,
        &[],
        &[],
        &[correction(1, Surface::Gravel, &north_draft(0, 1), 1)],
    );
    assert_eq!(f.max_unpaved_kmh(), 40.0);
    assert_eq!(f.corrected_edge_count(), 2);
    f.check(&e).unwrap();
    // Built for another region: refused.
    let other = engine(crate::fixture::grid(3));
    assert!(f.check(&other).is_err());
    // A surface bitset that doesn't fit the region: refused.
    let mut bad = f.clone();
    bad.surface_marked.push(0);
    assert!(bad.check(&e).is_err());
    let lone = Favourites {
        region_key: crate::rematch::region_key(&e),
        surface_marked: vec![1, 0],
        ..Favourites::default()
    };
    assert!(lone.check(&e).is_err());
}

#[test]
fn a_favourite_on_a_corrected_road_shows_its_gravel() {
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let d = north_draft(0, 8);
    let section = Section {
        surface: None,
        id: 7,
        rider_id: LOCAL_RIDER.into(),
        name: String::new(),
        rating: Rating::Great,
        direction: Direction::Both,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: d.ways.clone(),
        geometry: d.geometry.clone(),
    };
    let plain = Favourites::build(&e, std::slice::from_ref(&section));
    assert!(plain.gravel().is_empty());
    // A quarter of it corrected to gravel: nodes 3–5, across the junction.
    let f = Favourites::build_all(
        &e,
        std::slice::from_ref(&section),
        &[],
        &[correction(1, Surface::Gravel, &north_draft(3, 5), 1)],
    );
    let [g] = f.gravel() else {
        panic!("{:?}", f.gravel());
    };
    assert_eq!(g.section_id, 7);
    let want = 0.01 * 62_791.0;
    assert!((g.unpaved_m - want).abs() < 2.0, "{}", g.unpaved_m);
    assert!(g.unpaved_m < g.length_m / 2.0);
    assert_eq!(g.parts.len(), 2, "one piece per road");
    for p in &g.parts {
        assert!(
            p.iter()
                .all(|q| q.lon >= 13.415 - 1e-9 && q.lon <= 13.425 + 1e-9)
        );
    }
    // The whole of it gravel: mostly gravel, so it counts for nothing
    // while gravel is avoided.
    let f = Favourites::build_all(
        &e,
        std::slice::from_ref(&section),
        &[],
        &[correction(1, Surface::Gravel, &north_draft(0, 8), 1)],
    );
    let [g] = f.gravel() else { panic!() };
    assert!((g.unpaved_m - g.length_m).abs() < 2.0);
}

/// A matched favourite on the shaped ladder's north road, nodes `from`
/// to `to`, with its own `surface`.
fn own(id: i64, from: u32, to: u32, surface: Option<Surface>, direction: Direction) -> Section {
    let d = north_draft(from, to);
    Section {
        id,
        rider_id: LOCAL_RIDER.into(),
        name: String::new(),
        rating: Rating::Good,
        direction,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: id,
        ways: d.ways,
        geometry: d.geometry,
        surface,
    }
}

#[test]
fn a_favourites_own_surface_covers_its_spans_both_ways() {
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let (fwd, back) = (edge_from(&e, 0, 4), edge_from(&e, 4, 0));
    // One-way, nodes 1–2: gravel both ways all the same.
    let s = own(1, 1, 2, Some(Surface::Gravel), Direction::Forward);
    let f = Favourites::build_all(&e, std::slice::from_ref(&s), &[], &[]);
    assert!(close(&stretches(&f, &e, fwd), &[(0.25, 0.5)]));
    assert!(close(&stretches(&f, &e, back), &[(0.5, 0.75)]));
    // Map: nothing.
    let s = own(1, 1, 2, None, Direction::Both);
    let f = Favourites::build_all(&e, std::slice::from_ref(&s), &[], &[]);
    assert!(f.surface_marked.is_empty());
}

#[test]
fn a_favourites_own_surface_wins_over_a_standalone_one() {
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let fwd = edge_from(&e, 0, 4);
    // A newer standalone Gravel correction over all of the first road;
    // the favourite on nodes 1–3 says Paved.
    let gravel = correction(9, Surface::Gravel, &north_draft(0, 4), 1_000);
    let paved = own(1, 1, 3, Some(Surface::Paved), Direction::Both);
    let f = Favourites::build_all(
        &e,
        std::slice::from_ref(&paved),
        &[],
        std::slice::from_ref(&gravel),
    );
    assert!(close(&stretches(&f, &e, fwd), &[(0.0, 0.25), (0.75, 1.0)]));
    // Set to Map: the standalone one counts there.
    let map = own(1, 1, 3, None, Direction::Both);
    let f = Favourites::build_all(&e, std::slice::from_ref(&map), &[], &[gravel]);
    assert!(close(&stretches(&f, &e, fwd), &[(0.0, 1.0)]));
}

#[test]
fn a_favourites_own_surface_waits_while_it_does_not_fit() {
    let e = engine(shaped_ladder(Surface::Asphalt, 30));
    let fwd = edge_from(&e, 0, 4);
    for status in [Status::NeedsRematch, Status::Unmatched] {
        let mut s = own(1, 0, 4, Some(Surface::Gravel), Direction::Both);
        s.status = status;
        let f = Favourites::build_all(&e, std::slice::from_ref(&s), &[], &[]);
        assert!(f.surface_marked.is_empty(), "{status:?}");
    }
    // Matched again, or given a new line: it applies, on the new spans.
    let moved = own(1, 4, 8, Some(Surface::Gravel), Direction::Both);
    let f = Favourites::build_all(&e, std::slice::from_ref(&moved), &[], &[]);
    assert!(stretches(&f, &e, fwd).is_empty());
    let next = edge_from(&e, 4, 8);
    assert!(close(&stretches(&f, &e, next), &[(0.0, 1.0)]));
    // Deleted: nothing left.
    let f = Favourites::build_all(&e, &[], &[], &[]);
    assert!(f.surface_marked.is_empty());
}
