// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Map matching tests on synthetic noisy tracks.

use super::*;
use crate::Engine;
use crate::fixture::{self, Road, build};
use crate::region::format::RoadClass;

fn engine(data: crate::region::RegionData) -> Engine {
    Engine::from_region(Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
}

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

fn span(way_id: i64, from_idx: u32, to_idx: u32) -> WaySpan {
    WaySpan {
        way_id,
        from_idx,
        to_idx,
    }
}

/// Deterministic pseudo-random numbers in [-1, 1) (xorshift64).
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> f64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 11) as f64 / (1u64 << 52) as f64 - 1.0
    }
}

/// Metres per degree of latitude.
const M_PER_DEG: f64 = 111_195.0;

/// A GPS track along `line`: a fix every `step_m` metres, each moved by up
/// to `noise_m` metres north and east (deterministic noise).
fn ride(line: &[LatLon], step_m: f64, noise_m: f64, seed: u64) -> Vec<LatLon> {
    let mut rng = Rng(seed);
    let total = crate::geo::polyline_length_m(line);
    let n = (total / step_m).floor() as usize;
    (0..=n)
        .map(|i| {
            let frac = (i as f64 * step_m / total).min(1.0);
            let p = *crate::geo::polyline_slice(line, 0.0, frac).last().unwrap();
            let k = p.lat.to_radians().cos();
            ll(
                p.lat + rng.next() * noise_m / M_PER_DEG,
                p.lon + rng.next() * noise_m / (M_PER_DEG * k),
            )
        })
        .collect()
}

#[test]
fn follows_a_noisy_ride_through_a_junction() {
    // Near A, east through B, then along the one-way B→D almost to D.
    let e = engine(fixture::region());
    let line = [ll(55.70, 13.201), ll(55.70, 13.21), ll(55.70, 13.219)];
    let track = ride(&line, 15.0, 8.0, 1);
    let m = e.match_track(&track).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    let piece = &m.pieces[0];
    assert_eq!(piece.ways, [span(100, 0, 1), span(300, 0, 1)]);
    // About 1.1 km ridden; the first and last fixes are noisy.
    assert!((piece.distance_m - 1130.0).abs() < 25.0, "{piece:?}");
    assert_eq!(piece.first_point, 0);
    assert!(piece.last_point >= track.len() - 2, "{piece:?}");
    // The geometry lies on the roads.
    for p in &piece.geometry {
        assert!((p.lat - 55.70).abs() < 1e-6, "{p:?}");
    }
}

#[test]
fn takes_the_branch_actually_ridden() {
    // From A through B, then north-east round the bend towards C.
    let e = engine(fixture::region());
    let line = [
        ll(55.70, 13.201),
        ll(55.70, 13.21),
        ll(55.705, 13.212),
        ll(55.7095, 13.2102),
    ];
    let m = e.match_track(&ride(&line, 20.0, 8.0, 2)).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    assert_eq!(m.pieces[0].ways, [span(100, 0, 1), span(200, 0, 2)]);
    assert!(
        m.pieces[0]
            .geometry
            .iter()
            .any(|p| (p.lat - 55.705).abs() < 1e-9 && (p.lon - 13.212).abs() < 1e-9),
        "keeps the bend"
    );
}

#[test]
fn records_the_direction_ridden() {
    // Westwards along A–B, whose way runs east: nodes 1 → 0.
    let e = engine(fixture::region());
    let line = [ll(55.70, 13.2095), ll(55.70, 13.2005)];
    let m = e.match_track(&ride(&line, 20.0, 6.0, 3)).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    assert_eq!(m.pieces[0].ways, [span(100, 1, 0)]);
    let g = &m.pieces[0].geometry;
    assert!(g[0].lon > g[g.len() - 1].lon);
}

/// Two parallel east–west roads 40 m apart, joined only at their west
/// ends: south (way 1) along lat 55.60, north (way 2) 40 m north of it.
fn parallel() -> Engine {
    let north = 55.60 + 40.0 / M_PER_DEG;
    let nodes = [
        (55.60, 13.00),
        (55.60, 13.03),
        (north, 13.00),
        (north, 13.03),
    ];
    let roads = [
        Road::new(0, 1, RoadClass::Secondary, 80, 1),
        Road::new(2, 3, RoadClass::Secondary, 80, 2),
        Road::new(0, 2, RoadClass::Unclassified, 50, 3),
    ];
    engine(build(&nodes, &roads, 50_000))
}

#[test]
fn stays_on_the_road_when_fixes_stray_towards_a_parallel_one() {
    let e = parallel();
    let line = [ll(55.60, 13.005), ll(55.60, 13.025)];
    let mut track = ride(&line, 25.0, 5.0, 4);
    // Two fixes drift 26 m north: nearer the north road than the south one.
    for i in [10, 20] {
        track[i].lat = 55.60 + 26.0 / M_PER_DEG;
    }
    let m = e.match_track(&track).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    assert_eq!(m.pieces[0].ways, [span(1, 0, 1)]);
}

#[test]
fn a_ride_along_the_other_road_matches_that_road() {
    let e = parallel();
    let north = 55.60 + 40.0 / M_PER_DEG;
    let line = [ll(north, 13.005), ll(north, 13.025)];
    let m = e.match_track(&ride(&line, 25.0, 5.0, 5)).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    assert_eq!(m.pieces[0].ways, [span(2, 0, 1)]);
}

#[test]
fn never_rides_a_one_way_road_backwards() {
    // D towards B, against the one-way B→D: nothing connects the fixes.
    let e = engine(fixture::region());
    let line = [ll(55.70, 13.2195), ll(55.70, 13.2105)];
    let m = e.match_track(&ride(&line, 20.0, 4.0, 6)).unwrap();
    for piece in &m.pieces {
        assert!(
            piece
                .ways
                .iter()
                .all(|w| w.way_id != 300 || w.to_idx > w.from_idx),
            "{piece:?}"
        );
    }
}

#[test]
fn bridges_a_short_excursion_off_the_roads() {
    // East along A–B, 300 m north (no road there) and back, on to D: the
    // fixes far from roads are skipped and the ride stays one piece.
    let e = engine(fixture::region());
    let mut track = ride(&[ll(55.70, 13.201), ll(55.70, 13.205)], 20.0, 5.0, 7);
    track.extend(ride(
        &[ll(55.7027, 13.2052), ll(55.7027, 13.2062)],
        20.0,
        5.0,
        8,
    ));
    track.extend(ride(&[ll(55.70, 13.206), ll(55.70, 13.2185)], 20.0, 5.0, 9));
    let m = e.match_track(&track).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    assert_eq!(m.pieces[0].ways, [span(100, 0, 1), span(300, 0, 1)]);
}

#[test]
fn splits_where_the_roads_dont_connect() {
    // East along the south road, then (off the network) over to the north
    // road and on east: the roads only meet 1.5 km back west.
    let e = parallel();
    let north = 55.60 + 40.0 / M_PER_DEG;
    let mut track = ride(&[ll(55.60, 13.002), ll(55.60, 13.02)], 25.0, 5.0, 12);
    let jump = track.len();
    track.extend(ride(
        &[ll(north, 13.0205), ll(north, 13.028)],
        25.0,
        5.0,
        13,
    ));
    let m = e.match_track(&track).unwrap();
    assert_eq!(m.pieces.len(), 2, "{m:?}");
    assert!(m.pieces[0].last_point < jump, "{m:?}");
    assert_eq!(m.pieces[1].first_point, jump, "{m:?}");
    assert_eq!(m.pieces[0].ways, [span(1, 0, 1)]);
    assert_eq!(m.pieces[1].ways, [span(2, 0, 1)]);
}

#[test]
fn standing_still_or_single_fixes_match_nothing() {
    let e = engine(fixture::region());
    assert_eq!(e.match_track(&[]).unwrap(), MatchedTrack::default());
    assert!(
        e.match_track(&[ll(55.70, 13.205)])
            .unwrap()
            .pieces
            .is_empty()
    );
    // A hundred fixes within a few metres: one point used, no piece.
    let still = ride(&[ll(55.70, 13.205), ll(55.70, 13.20505)], 0.03, 3.0, 10);
    assert!(still.len() > 100);
    assert!(e.match_track(&still).unwrap().pieces.is_empty());
}

#[test]
fn skips_fixes_outside_the_region() {
    let e = engine(fixture::region());
    let mut track = vec![ll(10.0, 10.0), ll(-45.0, 170.0)];
    track.extend(ride(&[ll(55.70, 13.201), ll(55.70, 13.209)], 20.0, 5.0, 11));
    track.push(ll(56.5, 13.2));
    let m = e.match_track(&track).unwrap();
    assert_eq!(m.pieces.len(), 1, "{m:?}");
    assert_eq!(m.pieces[0].first_point, 2);
    assert_eq!(m.pieces[0].ways, [span(100, 0, 1)]);
}

#[test]
fn bad_input_is_a_typed_error() {
    let e = engine(fixture::region());
    for bad in [f64::NAN, f64::INFINITY, 91.0, -1000.0] {
        let track = [ll(55.70, 13.201), ll(bad, 13.205)];
        assert!(
            matches!(
                e.match_track(&track),
                Err(CoreError::InvalidCoordinate { .. })
            ),
            "{bad}"
        );
    }
    let too_many = vec![ll(55.70, 13.201); MAX_TRACK_POINTS + 1];
    assert!(matches!(
        e.match_track(&too_many),
        Err(CoreError::InvalidArgument(_))
    ));
}

#[test]
fn random_tracks_never_panic() {
    // Wild jumps, repeated points and points on and off the roads.
    let e = engine(fixture::region());
    let (sw, ne) = e.bounds();
    for seed in 1..200u64 {
        let mut rng = Rng(seed.wrapping_mul(0x9e37_79b9_7f4a_7c15));
        let len = ((rng.next() + 1.0) * 60.0) as usize;
        let track: Vec<LatLon> = (0..len)
            .map(|_| {
                let (a, b) = ((rng.next() + 1.0) / 2.0, (rng.next() + 1.0) / 2.0);
                ll(
                    sw.lat - 0.002 + a * (ne.lat - sw.lat + 0.004),
                    sw.lon - 0.002 + b * (ne.lon - sw.lon + 0.004),
                )
            })
            .collect();
        let m = e.match_track(&track).unwrap();
        for piece in &m.pieces {
            assert!(piece.first_point < piece.last_point && piece.last_point < len);
            assert!(piece.geometry.len() >= 2 && piece.distance_m > 0.0);
        }
    }
}

#[test]
fn candidates_are_each_road_once_nearest_first() {
    let e = parallel();
    // 10 m north of the south road: both roads, south first; each two-way
    // road once, as its lower edge id.
    let c = nearby(e.region(), ll(55.60 + 10.0 / M_PER_DEG, 13.01), 50.0, 8);
    assert_eq!(c.len(), 2, "{c:?}");
    assert!((c[0].distance_m - 10.0).abs() < 0.1 && (c[1].distance_m - 30.0).abs() < 0.1);
    let ways: Vec<i64> = c
        .iter()
        .map(|p| e.region().way_refs()[p.edge as usize].way_id)
        .collect();
    assert_eq!(ways, [1, 2]);
    // A smaller radius or count leaves the far road out.
    assert_eq!(
        nearby(e.region(), ll(55.60 + 10.0 / M_PER_DEG, 13.01), 20.0, 8).len(),
        1
    );
    assert_eq!(
        nearby(e.region(), ll(55.60 + 10.0 / M_PER_DEG, 13.01), 50.0, 1).len(),
        1
    );
    // Nothing within reach.
    assert!(nearby(e.region(), ll(55.61, 13.01), 50.0, 8).is_empty());
}
