// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Recorded rides (PRD R4, ADR-0006): GPS points as the phone reports them,
//! stored in batches while riding.

use crate::geo::haversine_m;
use crate::{CoreError, LatLon};

/// Most points one track may hold: over 55 hours at one fix per second
/// (the map matcher's limit).
pub const MAX_TRACK_POINTS: usize = crate::matching::MAX_TRACK_POINTS;
/// Most points one append may carry.
pub const MAX_BATCH_POINTS: usize = 10_000;
/// Latest accepted fix time: 2100-01-01, in milliseconds since the epoch.
const MAX_TIME_MS: i64 = 4_102_444_800_000;
/// Fastest plausible speed, in m/s (720 km/h).
const MAX_SPEED_MPS: f64 = 200.0;
/// Worst accuracy worth keeping a fix for, in metres.
const MAX_ACCURACY_M: f64 = 10_000.0;
/// Fixes closer than this to the last one counted add no distance: at a
/// standstill GPS noise would otherwise add up.
const DISTANCE_STEP_M: f64 = 10.0;

/// One GPS fix.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TrackPoint {
    /// Milliseconds since the Unix epoch.
    pub time_ms: i64,
    pub position: LatLon,
    /// Estimated horizontal accuracy (68 %), in metres, if known.
    pub accuracy_m: Option<f64>,
    pub speed_mps: Option<f64>,
    /// Direction of travel, degrees clockwise from north, if known.
    pub bearing_deg: Option<f64>,
}

impl TrackPoint {
    pub fn validate(&self) -> Result<(), CoreError> {
        self.position.validate()?;
        let bad = |what: &str| {
            Err(CoreError::InvalidArgument(format!(
                "track point: invalid {what}"
            )))
        };
        if !(0..=MAX_TIME_MS).contains(&self.time_ms) {
            return bad("time");
        }
        if self
            .accuracy_m
            .is_some_and(|a| !(0.0..=MAX_ACCURACY_M).contains(&a))
        {
            return bad("accuracy");
        }
        if self
            .speed_mps
            .is_some_and(|s| !(0.0..=MAX_SPEED_MPS).contains(&s))
        {
            return bad("speed");
        }
        if self.bearing_deg.is_some_and(|b| !(0.0..360.0).contains(&b)) {
            return bad("bearing");
        }
        Ok(())
    }
}

/// A recorded ride.
#[derive(Debug, Clone, PartialEq)]
pub struct Track {
    pub id: i64,
    pub rider_id: String,
    /// Seconds since the Unix epoch.
    pub started_at: i64,
    /// `None` while recording, or if the app died before the ride ended.
    pub ended_at: Option<i64>,
    pub point_count: u64,
    /// Length ridden, counted when the track is finished.
    pub distance_m: f64,
}

/// What an append did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Appended {
    /// Points stored by this call; points not newer than the track's last
    /// one (e.g. a batch sent again after a crash) are skipped.
    pub added: u64,
    pub point_count: u64,
}

/// Length of a ride in metres, ignoring moves under [`DISTANCE_STEP_M`].
pub fn ridden_distance_m(points: &[TrackPoint]) -> f64 {
    let mut total = 0.0;
    let mut last: Option<LatLon> = None;
    for p in points {
        match last {
            None => last = Some(p.position),
            Some(q) => {
                let d = haversine_m(q, p.position);
                if d >= DISTANCE_STEP_M {
                    total += d;
                    last = Some(p.position);
                }
            }
        }
    }
    total
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub fn fix(time_ms: i64, lat: f64, lon: f64) -> TrackPoint {
        TrackPoint {
            time_ms,
            position: LatLon { lat, lon },
            accuracy_m: Some(5.0),
            speed_mps: Some(20.0),
            bearing_deg: Some(90.0),
        }
    }

    #[test]
    fn accepts_ordinary_fixes() {
        fix(1_790_000_000_000, 55.7, 13.2).validate().unwrap();
        let bare = TrackPoint {
            accuracy_m: None,
            speed_mps: None,
            bearing_deg: None,
            ..fix(0, -90.0, 180.0)
        };
        bare.validate().unwrap();
    }

    #[test]
    fn rejects_impossible_fixes() {
        let ok = fix(1_790_000_000_000, 55.7, 13.2);
        let bad = [
            TrackPoint { time_ms: -1, ..ok },
            TrackPoint {
                time_ms: MAX_TIME_MS + 1,
                ..ok
            },
            TrackPoint {
                position: LatLon {
                    lat: f64::NAN,
                    lon: 13.2,
                },
                ..ok
            },
            TrackPoint {
                accuracy_m: Some(-1.0),
                ..ok
            },
            TrackPoint {
                accuracy_m: Some(f64::INFINITY),
                ..ok
            },
            TrackPoint {
                speed_mps: Some(f64::NAN),
                ..ok
            },
            TrackPoint {
                speed_mps: Some(500.0),
                ..ok
            },
            TrackPoint {
                bearing_deg: Some(360.0),
                ..ok
            },
            TrackPoint {
                bearing_deg: Some(-0.5),
                ..ok
            },
        ];
        for p in bad {
            assert!(p.validate().is_err(), "{p:?}");
        }
    }

    #[test]
    fn distance_ignores_standstill_jitter() {
        // 0.001° of latitude is about 111 m.
        let ride: Vec<TrackPoint> = (0..11)
            .map(|i| fix(i, 55.7 + f64::from(i as i32) * 0.001, 13.2))
            .collect();
        assert!((ridden_distance_m(&ride) - 1112.0).abs() < 2.0);
        // Standing still with 3 m of noise adds nothing.
        let still: Vec<TrackPoint> = (0..100)
            .map(|i| fix(i, 55.7 + if i % 2 == 0 { 0.0 } else { 3e-5 }, 13.2))
            .collect();
        assert_eq!(ridden_distance_m(&still), 0.0);
        assert_eq!(ridden_distance_m(&[]), 0.0);
    }
}
