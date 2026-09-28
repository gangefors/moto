// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::section::{Direction, LOCAL_RIDER, Rating, Source, Status};

fn ll(lat: f64, lon: f64) -> LatLon {
    LatLon { lat, lon }
}

/// A section along lat `lat` from lon 13.20 to 13.22 (about 1.26 km).
fn section(id: i64, lat: f64) -> Section {
    Section {
        id,
        rider_id: LOCAL_RIDER.into(),
        name: String::new(),
        rating: Rating::Great,
        direction: Direction::Both,
        source: Source::Map,
        status: Status::Ok,
        created_at: 0,
        updated_at: 0,
        ways: Vec::new(),
        geometry: vec![ll(lat, 13.20), ll(lat, 13.21), ll(lat, 13.22)],
    }
}

/// Fixes every `step` degrees of longitude along lat `lat`, `off` north.
fn ride(lat: f64, from: f64, to: f64, step: f64) -> Vec<LatLon> {
    let n = ((to - from) / step).round() as usize;
    (0..=n).map(|i| ll(lat, from + i as f64 * step)).collect()
}

#[test]
fn counts_rides_along_a_section_and_the_latest() {
    let s = [section(1, 55.70), section(2, 55.80)];
    let along = ride(55.7001, 13.19, 13.23, 0.0005);
    let back: Vec<LatLon> = along.iter().rev().copied().collect();
    let elsewhere = ride(55.75, 13.19, 13.23, 0.0005);
    let rides = [
        RideLine {
            started_at: 100,
            line: &along,
        },
        RideLine {
            started_at: 300,
            line: &back,
        },
        RideLine {
            started_at: 200,
            line: &elsewhere,
        },
    ];
    let r = ridden(&s, &rides);
    assert_eq!(
        r,
        [
            Ridden {
                section_id: 1,
                times: 2,
                last_at: Some(300)
            },
            Ridden {
                section_id: 2,
                times: 0,
                last_at: None
            },
        ]
    );
}

#[test]
fn part_of_a_section_is_not_riding_it() {
    let s = [section(1, 55.70)];
    // Half of it only.
    let half = ride(55.70, 13.19, 13.21, 0.0005);
    assert_eq!(
        ridden(
            &s,
            &[RideLine {
                started_at: 1,
                line: &half
            }]
        )[0]
        .times,
        0
    );
    // All but its last few metres: ridden.
    let most = ride(55.70, 13.19, 13.219, 0.0005);
    assert_eq!(
        ridden(
            &s,
            &[RideLine {
                started_at: 1,
                line: &most
            }]
        )[0]
        .times,
        1
    );
    // A parallel road 100 m north: not this one.
    let beside = ride(55.7009, 13.19, 13.23, 0.0005);
    assert_eq!(
        ridden(
            &s,
            &[RideLine {
                started_at: 1,
                line: &beside
            }]
        )[0]
        .times,
        0
    );
}

#[test]
fn sparse_fixes_are_filled_in_but_long_gaps_are_not() {
    let s = [section(1, 55.70)];
    // A fix every 250 m: still along it.
    let sparse = ride(55.70, 13.19, 13.23, 0.004);
    assert_eq!(
        ridden(
            &s,
            &[RideLine {
                started_at: 1,
                line: &sparse
            }]
        )[0]
        .times,
        1
    );
    // Two fixes 2.5 km apart, either side: a gap, not a ride along it.
    let gap = [ll(55.70, 13.19), ll(55.70, 13.23)];
    assert_eq!(
        ridden(
            &s,
            &[RideLine {
                started_at: 1,
                line: &gap
            }]
        )[0]
        .times,
        0
    );
}

#[test]
fn odd_input_never_panics() {
    let s = [
        section(1, 55.70),
        Section {
            geometry: vec![ll(55.7, 13.2)],
            ..section(2, 55.7)
        },
    ];
    let bad = [ll(f64::NAN, 13.2), ll(55.7, f64::INFINITY), ll(91.0, 0.0)];
    let one = [ll(55.70, 13.21)];
    // Fixes around the world: far apart, never filled in.
    let jumps: Vec<LatLon> = (0..1000)
        .map(|i| ll(if i % 2 == 0 { -80.0 } else { 80.0 }, 13.0))
        .collect();
    let rides = [
        RideLine {
            started_at: 1,
            line: &[],
        },
        RideLine {
            started_at: 2,
            line: &bad,
        },
        RideLine {
            started_at: 3,
            line: &one,
        },
        RideLine {
            started_at: 4,
            line: &jumps,
        },
    ];
    let r = ridden(&s, &rides);
    assert_eq!(r.len(), 2);
    assert_eq!(r[0].times, 0);
    assert!(ridden(&[], &rides).is_empty());
}
