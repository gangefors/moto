// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::*;
use crate::geo::destination;

const START: LatLon = LatLon {
    lat: 57.3,
    lon: 14.2,
};

/// A line from `from` through each (bearing, metres) leg, a point every
/// 20 m.
fn path(from: LatLon, legs: &[(f64, f64)]) -> Vec<LatLon> {
    let mut out = vec![from];
    let mut at = from;
    for &(bearing, length) in legs {
        let n = (length / 20.0).ceil() as usize;
        for _ in 0..n {
            at = destination(at, bearing, length / n as f64);
            out.push(at);
        }
    }
    out
}

fn fix(position: LatLon, time_s: i64) -> FollowFix {
    FollowFix {
        position,
        time_ms: time_s * 1000,
        accuracy_m: Some(5.0),
        speed_mps: Some(20.0),
        bearing_deg: None,
    }
}

fn heading(position: LatLon, time_s: i64, bearing: f64) -> FollowFix {
    FollowFix {
        bearing_deg: Some(bearing),
        ..fix(position, time_s)
    }
}

/// Rides `line` from metre `from` to `to` at 20 m/s, a fix a second, each
/// shifted `side_m` to the right of the way; the states after each fix.
fn ride(f: &mut RouteFollower, line: &[LatLon], t0: i64, side_m: f64) -> (i64, Vec<FollowState>) {
    let mut out = Vec::new();
    let mut t = t0;
    let along = f.along().to_vec();
    let total = *along.last().unwrap();
    let mut m = 0.0;
    while m <= total {
        let i = along.partition_point(|&a| a < m).min(line.len() - 1);
        let (a, b) = if i == 0 {
            (line[0], line[1])
        } else {
            (line[i - 1], line[i])
        };
        let br = bearing_deg(a, b);
        let p = destination(line[i], br + 90.0, side_m);
        out.push(f.update(heading(p, t, br)).unwrap());
        t += 1;
        m += 20.0;
    }
    (t, out)
}

#[test]
fn rejects_bad_lines() {
    assert!(RouteFollower::new(vec![START], &[], &[], 10.0).is_err());
    let bad = LatLon {
        lat: f64::NAN,
        lon: 0.0,
    };
    assert!(RouteFollower::new(vec![START, bad], &[], &[], 10.0).is_err());
    let far = LatLon {
        lat: 91.0,
        lon: 0.0,
    };
    assert!(RouteFollower::new(vec![START, far], &[], &[], 10.0).is_err());
    let line = path(START, &[(0.0, 500.0)]);
    assert!(RouteFollower::new(line.clone(), &[], &[], f64::NAN).is_err());
    assert!(RouteFollower::new(line.clone(), &[], &[], -1.0).is_err());
    assert!(RouteFollower::new(line.clone(), std::slice::from_ref(&line), &[], 10.0).is_err());
    assert!(RouteFollower::new(line.clone(), &[vec![START]], &[Rating::Good], 10.0).is_err());
    // Half the world in one line is too long to follow.
    let huge = vec![
        LatLon {
            lat: -80.0,
            lon: 0.0,
        },
        LatLon {
            lat: 80.0,
            lon: 0.0,
        },
    ];
    assert!(RouteFollower::new(huge, &[], &[], 10.0).is_err());
}

#[test]
fn rejects_bad_fixes() {
    let mut f = RouteFollower::new(path(START, &[(0.0, 500.0)]), &[], &[], 60.0).unwrap();
    for bad in [
        FollowFix {
            accuracy_m: Some(f64::NAN),
            ..fix(START, 1)
        },
        FollowFix {
            speed_mps: Some(-1.0),
            ..fix(START, 1)
        },
        FollowFix {
            bearing_deg: Some(f64::INFINITY),
            ..fix(START, 1)
        },
        fix(
            LatLon {
                lat: 0.0,
                lon: 200.0,
            },
            1,
        ),
    ] {
        assert!(f.update(bad).is_err());
    }
}

#[test]
fn rides_a_straight_route_to_the_end() {
    let line = path(START, &[(30.0, 5_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 300.0).unwrap();
    let (_, states) = ride(&mut f, &line, 0, 8.0);
    assert_eq!(states[0].phase, FollowPhase::OnRoute);
    let mut last = 0.0;
    for s in &states {
        assert!(s.along_m >= last, "progress went back");
        last = s.along_m;
        assert_ne!(s.phase, FollowPhase::OffRoute);
    }
    let end = states.last().unwrap();
    assert_eq!(end.phase, FollowPhase::Finished);
    assert_eq!(end.left_m, 0.0);
    assert_eq!(end.left_s, 0.0);
    // Halfway: half the time left.
    let mid = &states[states.len() / 2];
    assert!((mid.left_s - 150.0).abs() < 5.0, "{}", mid.left_s);
}

#[test]
fn takes_the_right_pass_out_and_back() {
    // 3 km north, then back the same road.
    let line = path(START, &[(0.0, 3_000.0), (180.0, 3_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 600.0).unwrap();
    let (_, states) = ride(&mut f, &line, 0, 0.0);
    // Going out, the rider is on the first half.
    let out_mid = &states[75];
    assert!(
        (out_mid.along_m - 1_500.0).abs() < 50.0,
        "{}",
        out_mid.along_m
    );
    // Coming back past the same place, on the second half.
    let back_mid = &states[225];
    assert!(
        (back_mid.along_m - 4_500.0).abs() < 50.0,
        "{}",
        back_mid.along_m
    );
    assert_eq!(states.last().unwrap().phase, FollowPhase::Finished);
}

#[test]
fn a_loop_is_not_finished_at_its_start() {
    // A square loop of 4 km back to the start.
    let line = path(
        START,
        &[
            (0.0, 1_000.0),
            (90.0, 1_000.0),
            (180.0, 1_000.0),
            (270.0, 1_000.0),
        ],
    );
    let mut f = RouteFollower::new(line.clone(), &[], &[], 240.0).unwrap();
    let s = f.update(fix(START, 0)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    let s = f.update(fix(START, 2)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    let (_, states) = ride(&mut f, &line, 3, 0.0);
    assert_eq!(states.last().unwrap().phase, FollowPhase::Finished);
}

#[test]
fn figure_eight_keeps_the_pass() {
    // Through a crossing twice, at right angles: north through it, round,
    // then east through it.
    let line = path(
        START,
        &[
            (0.0, 2_000.0),
            (90.0, 1_000.0),
            (180.0, 1_000.0),
            (270.0, 2_000.0),
        ],
    );
    let mut f = RouteFollower::new(line.clone(), &[], &[], 400.0).unwrap();
    let (_, states) = ride(&mut f, &line, 0, 5.0);
    for w in states.windows(2) {
        assert!(w[1].along_m >= w[0].along_m);
        assert!(
            w[1].along_m - w[0].along_m < 200.0,
            "jumped along the route"
        );
    }
    assert_eq!(states.last().unwrap().phase, FollowPhase::Finished);
}

#[test]
fn noise_never_takes_the_rider_off() {
    let line = path(START, &[(45.0, 4_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 240.0).unwrap();
    // 25 m to the side all the way, with 15 m accuracy.
    let along = f.along().to_vec();
    for (k, p) in line.iter().enumerate() {
        let side = if k % 2 == 0 { 25.0 } else { -25.0 };
        let q = destination(*p, 135.0, side);
        let s = f
            .update(FollowFix {
                accuracy_m: Some(15.0),
                ..fix(q, k as i64)
            })
            .unwrap();
        assert_ne!(s.phase, FollowPhase::OffRoute, "at {} m", along[k]);
    }
}

#[test]
fn leaving_the_route_takes_three_fixes_and_five_seconds() {
    let line = path(START, &[(0.0, 3_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 180.0).unwrap();
    f.update(fix(line[0], 0)).unwrap();
    f.update(fix(line[10], 1)).unwrap();
    // A side road: 150 m off.
    let off = |k: i64| destination(line[20], 90.0, 50.0 * k as f64 + 100.0);
    let s = f.update(fix(off(0), 2)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    let s = f.update(fix(off(1), 3)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    let s = f.update(fix(off(1), 4)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute, "three fixes but only 2 s");
    let s = f.update(fix(off(2), 7)).unwrap();
    assert_eq!(s.phase, FollowPhase::OffRoute);
    assert_eq!(s.off_since_ms, Some(2_000));
    assert!(s.off_m.is_some_and(|d| d > 100.0));
    // Back on the road further along: two close fixes.
    let s = f.update(fix(line[60], 30)).unwrap();
    assert_eq!(s.phase, FollowPhase::OffRoute);
    let s = f.update(fix(line[61], 31)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    assert!((s.along_m - f.along()[61]).abs() < 1.0, "skipped ahead");
    assert_eq!(s.off_m, None);
}

#[test]
fn poor_fixes_never_count() {
    let line = path(START, &[(0.0, 3_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 180.0).unwrap();
    f.update(fix(line[0], 0)).unwrap();
    for (k, q) in line.iter().enumerate().take(20).skip(1) {
        let p = destination(*q, 90.0, 300.0);
        let s = f
            .update(FollowFix {
                accuracy_m: Some(80.0),
                ..fix(p, k as i64)
            })
            .unwrap();
        assert_eq!(s.phase, FollowPhase::OnRoute);
    }
}

#[test]
fn a_gap_in_fixes_carries_on() {
    // A tunnel: no fixes for a minute, then the rider is 1.2 km on.
    let line = path(START, &[(0.0, 5_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 300.0).unwrap();
    f.update(fix(line[0], 0)).unwrap();
    f.update(fix(line[1], 1)).unwrap();
    let s = f.update(fix(line[61], 61)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    assert!((s.along_m - 1_220.0).abs() < 25.0, "{}", s.along_m);
}

#[test]
fn old_fixes_change_nothing() {
    let line = path(START, &[(0.0, 2_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 120.0).unwrap();
    f.update(fix(line[0], 0)).unwrap();
    let s = f.update(fix(line[20], 20)).unwrap();
    let again = f.update(fix(line[5], 20)).unwrap();
    assert_eq!(s, again);
    let earlier = f.update(fix(line[5], 10)).unwrap();
    assert_eq!(s, earlier);
}

#[test]
fn joins_from_away_and_then_follows() {
    let line = path(START, &[(0.0, 3_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 180.0).unwrap();
    // 150 m west of the start: joining, with the distance known.
    let s = f.update(fix(destination(START, 270.0, 150.0), 0)).unwrap();
    assert_eq!(s.phase, FollowPhase::Joining);
    assert!(s.off_m.is_some_and(|d| (d - 150.0).abs() < 5.0));
    // 2 km away: joining, no road of the route near.
    let s = f
        .update(fix(destination(START, 270.0, 2_000.0), 1))
        .unwrap();
    assert_eq!(s.phase, FollowPhase::Joining);
    assert_eq!(s.off_m, None);
    // Onto the route 400 m in.
    let s = f.update(fix(line[20], 100)).unwrap();
    assert_eq!(s.phase, FollowPhase::OnRoute);
    assert!((s.along_m - 400.0).abs() < 5.0);
}

#[test]
fn joining_ignores_the_route_after_its_first_kilometre() {
    let line = path(START, &[(0.0, 3_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 180.0).unwrap();
    let s = f.update(fix(line[100], 0)).unwrap();
    assert_eq!(s.phase, FollowPhase::Joining);
}

#[test]
fn favourites_ahead_and_under() {
    // 10 km north; an epic part from 3 to 4 km and a great one from 4.5
    // to 5 km, then a good one from 8 km.
    let line = path(START, &[(0.0, 10_000.0)]);
    let part = |from: usize, to: usize| line[from..=to].to_vec();
    let parts = [part(150, 200), part(225, 250), part(400, 450)];
    let ratings = [Rating::Epic, Rating::Great, Rating::Good];
    let mut f = RouteFollower::new(line.clone(), &parts, &ratings, 600.0).unwrap();
    f.update(fix(line[0], 0)).unwrap();
    // At 0.5 km: nothing within 2 km.
    let s = f.update(fix(line[25], 25)).unwrap();
    assert!(s.favourites.is_empty());
    // At 2.5 km: the epic one in 0.5 km; the great one 2 km on is in reach too.
    let s = f.update(fix(line[125], 125)).unwrap();
    assert_eq!(s.favourites.len(), 2);
    assert_eq!(s.favourites[0].rating, Rating::Epic);
    assert!(!s.favourites[0].on);
    assert!((s.favourites[0].distance_m - 500.0).abs() < 5.0);
    assert_eq!(s.favourites[1].rating, Rating::Great);
    assert!((s.favourites[1].distance_m - 2_000.0).abs() < 5.0);
    // At 3.4 km, on the epic one: 0.6 km of it left, the great one in 1.1.
    let s = f.update(fix(line[170], 170)).unwrap();
    assert!(s.favourites[0].on);
    assert!((s.favourites[0].distance_m - 600.0).abs() < 5.0);
    assert!((s.favourites[1].distance_m - 1_100.0).abs() < 5.0);
    // At 7 km: only the good one, 1 km ahead.
    let s = f.update(fix(line[350], 350)).unwrap();
    assert_eq!(s.favourites.len(), 1);
    assert_eq!(s.favourites[0].rating, Rating::Good);
}

#[test]
fn at_most_two_favourites_and_touching_ones_join() {
    let line = path(START, &[(0.0, 5_000.0)]);
    let part = |from: usize, to: usize| line[from..=to].to_vec();
    // Two epic parts touching (one stretch), then great, then good, all
    // within 2 km.
    let parts = [part(10, 20), part(20, 30), part(40, 50), part(60, 70)];
    let ratings = [Rating::Epic, Rating::Epic, Rating::Great, Rating::Good];
    let mut f = RouteFollower::new(line.clone(), &parts, &ratings, 300.0).unwrap();
    let s = f.update(fix(line[0], 0)).unwrap();
    assert_eq!(s.favourites.len(), MAX_NEAR_FAVOURITES);
    assert_eq!(s.favourites[0].rating, Rating::Epic);
    assert_eq!(s.favourites[1].rating, Rating::Great);
    let s = f.update(fix(line[15], 15)).unwrap();
    // On the joined epic stretch: 15 points (300 m) left of it.
    assert!(s.favourites[0].on);
    assert!((s.favourites[0].distance_m - 300.0).abs() < 5.0);
}

#[test]
fn parts_off_the_line_are_left_out() {
    let line = path(START, &[(0.0, 3_000.0)]);
    let elsewhere = path(destination(START, 90.0, 5_000.0), &[(0.0, 500.0)]);
    let mut f = RouteFollower::new(line.clone(), &[elsewhere], &[Rating::Epic], 180.0).unwrap();
    let s = f.update(fix(line[0], 0)).unwrap();
    assert!(s.favourites.is_empty());
}

#[test]
fn finished_takes_no_more_fixes() {
    let line = path(START, &[(0.0, 1_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 60.0).unwrap();
    let (t, states) = ride(&mut f, &line, 0, 0.0);
    assert_eq!(states.last().unwrap().phase, FollowPhase::Finished);
    let s = f.update(fix(line[0], t + 10)).unwrap();
    assert_eq!(s.phase, FollowPhase::Finished);
    assert_eq!(s.segment as usize, line.len() - 2);
}

#[test]
fn segment_and_t_point_at_the_rider() {
    let line = path(START, &[(0.0, 1_000.0)]);
    let mut f = RouteFollower::new(line.clone(), &[], &[], 60.0).unwrap();
    f.update(fix(line[0], 0)).unwrap();
    let mid = LatLon {
        lat: (line[10].lat + line[11].lat) / 2.0,
        lon: (line[10].lon + line[11].lon) / 2.0,
    };
    let s = f.update(fix(mid, 10)).unwrap();
    assert_eq!(s.segment, 10);
    assert!((s.segment_t - 0.5).abs() < 0.05);
}

#[test]
fn random_fixes_never_panic() {
    // A small deterministic generator: no new dependency for this.
    let mut x: u64 = 0x2545_f491_4f6c_dd1d;
    let mut next = || {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        x
    };
    let line = path(START, &[(10.0, 2_000.0), (200.0, 1_500.0), (100.0, 800.0)]);
    let mut f = RouteFollower::new(
        line.clone(),
        &[line[5..40].to_vec()],
        &[Rating::Great],
        300.0,
    )
    .unwrap();
    for t in 0..5_000 {
        let r = next();
        let lat = START.lat + ((r % 20_000) as f64 - 10_000.0) / 200_000.0;
        let lon = START.lon + (((r >> 16) % 20_000) as f64 - 10_000.0) / 100_000.0;
        let fix = FollowFix {
            position: LatLon { lat, lon },
            time_ms: if r % 13 == 0 { t / 2 } else { t * 700 },
            accuracy_m: if r % 3 == 0 {
                None
            } else {
                Some((r >> 32) as f64 % 120.0)
            },
            speed_mps: (r % 5 != 0).then(|| (r >> 40) as f64 % 60.0),
            bearing_deg: (r % 7 != 0).then(|| (r >> 20) as f64 % 720.0 - 360.0),
        };
        let s = f.update(fix).unwrap();
        assert!(s.along_m >= 0.0 && s.along_m <= s.total_m + 1e-6);
        assert!(s.left_m >= 0.0 && s.left_s >= 0.0);
        assert!(s.favourites.len() <= MAX_NEAR_FAVOURITES);
        assert!((s.segment as usize) < line.len() - 1);
        assert!((0.0..=1.0).contains(&s.segment_t));
    }
}

#[test]
fn a_degenerate_line_of_one_place_still_works() {
    // Two identical points: a zero-length route.
    let mut f = RouteFollower::new(vec![START, START], &[], &[], 0.0).unwrap();
    let s = f.update(fix(START, 0)).unwrap();
    assert!(s.left_m == 0.0 && s.left_s == 0.0);
}
