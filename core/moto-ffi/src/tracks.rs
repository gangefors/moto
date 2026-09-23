// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Recorded rides across the FFI (PRD R4): storing GPS tracks in batches
//! while riding, and fitting them to the roads.

use crate::sections::now;
use crate::{Engine, LatLon, MotoError, SectionStore, WaySpan};
use moto_core::track as core;

/// One GPS fix.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct TrackPoint {
    /// Milliseconds since the Unix epoch.
    pub time_ms: i64,
    pub position: LatLon,
    /// Estimated horizontal accuracy in metres, if known.
    pub accuracy_m: Option<f64>,
    pub speed_mps: Option<f64>,
    /// Direction of travel, degrees clockwise from north, if known.
    pub bearing_deg: Option<f64>,
}

/// A recorded ride.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
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

/// What an append did: points stored now (older ones already stored are
/// skipped) and the track's total.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct TrackAppend {
    pub added: u64,
    pub point_count: u64,
}

#[uniffi::export]
impl SectionStore {
    /// Starts recording a new track.
    pub fn start_track(&self) -> Result<Track, MotoError> {
        Ok(self.store().start_track(now())?.into())
    }

    /// Stores a batch of fixes (at most 10 000) on a track that is still
    /// recording, in one transaction. Fixes not newer than the last stored
    /// one are skipped, so sending a batch again is harmless.
    pub fn append_track_points(
        &self,
        id: i64,
        points: Vec<TrackPoint>,
    ) -> Result<TrackAppend, MotoError> {
        let points: Vec<core::TrackPoint> = points.into_iter().map(Into::into).collect();
        let a = self.store().append_track_points(id, &points)?;
        Ok(TrackAppend {
            added: a.added,
            point_count: a.point_count,
        })
    }

    /// Ends a track and counts its length; `None` if there is no such track.
    pub fn finish_track(&self, id: i64) -> Result<Option<Track>, MotoError> {
        Ok(self.store().finish_track(id, now())?.map(Into::into))
    }

    pub fn get_track(&self, id: i64) -> Result<Option<Track>, MotoError> {
        Ok(self.store().get_track(id)?.map(Into::into))
    }

    /// All tracks, newest first.
    pub fn list_tracks(&self) -> Result<Vec<Track>, MotoError> {
        Ok(self
            .store()
            .list_tracks()?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// A track's fixes in recording order; `None` if there is no such track.
    pub fn track_points(&self, id: i64) -> Result<Option<Vec<TrackPoint>>, MotoError> {
        Ok(self
            .store()
            .track_points(id)?
            .map(|v| v.into_iter().map(Into::into).collect()))
    }

    /// Deletes a track and its fixes; `false` if it did not exist.
    pub fn delete_track(&self, id: i64) -> Result<bool, MotoError> {
        Ok(self.store().delete_track(id)?)
    }
}

impl From<TrackPoint> for core::TrackPoint {
    fn from(p: TrackPoint) -> Self {
        Self {
            time_ms: p.time_ms,
            position: p.position.into(),
            accuracy_m: p.accuracy_m,
            speed_mps: p.speed_mps,
            bearing_deg: p.bearing_deg,
        }
    }
}

impl From<core::TrackPoint> for TrackPoint {
    fn from(p: core::TrackPoint) -> Self {
        Self {
            time_ms: p.time_ms,
            position: p.position.into(),
            accuracy_m: p.accuracy_m,
            speed_mps: p.speed_mps,
            bearing_deg: p.bearing_deg,
        }
    }
}

impl From<core::Track> for Track {
    fn from(t: core::Track) -> Self {
        Self {
            id: t.id,
            rider_id: t.rider_id,
            started_at: t.started_at,
            ended_at: t.ended_at,
            point_count: t.point_count,
            distance_m: t.distance_m,
        }
    }
}

/// A stretch of a track matched to the roads.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MatchedPiece {
    /// Index of the first and last track points the piece covers.
    pub first_point: u64,
    pub last_point: u64,
    /// The OSM ways ridden, in order, each as a node range.
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
}

/// A track fitted to the roads, in pieces where it left the network.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MatchedTrack {
    pub pieces: Vec<MatchedPiece>,
}

#[uniffi::export]
impl Engine {
    /// Fits GPS points (in recording order) to the roads. Points outside
    /// the region or far from any road are skipped.
    pub fn match_track(&self, points: Vec<LatLon>) -> Result<MatchedTrack, MotoError> {
        let points: Vec<moto_core::LatLon> = points.into_iter().map(Into::into).collect();
        Ok(self.inner.match_track(&points)?.into())
    }
}

impl From<moto_core::matching::MatchedTrack> for MatchedTrack {
    fn from(t: moto_core::matching::MatchedTrack) -> Self {
        Self {
            pieces: t
                .pieces
                .into_iter()
                .map(|p| MatchedPiece {
                    first_point: p.first_point as u64,
                    last_point: p.last_point as u64,
                    ways: p.ways.into_iter().map(Into::into).collect(),
                    geometry: p.geometry.into_iter().map(Into::into).collect(),
                    distance_m: p.distance_m,
                })
                .collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    #[test]
    fn records_a_track_across_the_ffi() {
        let path = std::env::temp_dir().join(format!("moto-ffi-tracks-{}.db", std::process::id()));
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
        let store = SectionStore::open(path.to_string_lossy().into_owned()).unwrap();
        let t = store.start_track().unwrap();
        assert!(t.started_at > 1_700_000_000 && t.ended_at.is_none());
        let fix = |i: i64| TrackPoint {
            time_ms: 1_790_000_000_000 + i * 1000,
            position: ll(55.70 + (i as f64) * 1e-4, 13.2),
            accuracy_m: Some(4.0),
            speed_mps: None,
            bearing_deg: Some(0.0),
        };
        let batch: Vec<TrackPoint> = (0..10).map(fix).collect();
        assert_eq!(
            store.append_track_points(t.id, batch.clone()).unwrap(),
            TrackAppend {
                added: 10,
                point_count: 10
            }
        );
        assert_eq!(
            store
                .append_track_points(t.id, batch.clone())
                .unwrap()
                .added,
            0
        );
        let done = store.finish_track(t.id).unwrap().unwrap();
        assert!(done.ended_at.is_some() && done.distance_m > 90.0);
        // Stored at OSM precision (1e-7°).
        let read = store.track_points(t.id).unwrap().unwrap();
        assert_eq!(read.len(), batch.len());
        for (r, b) in read.iter().zip(&batch) {
            assert_eq!(
                (r.time_ms, r.accuracy_m, r.bearing_deg),
                (b.time_ms, b.accuracy_m, b.bearing_deg)
            );
            assert!((r.position.lat - b.position.lat).abs() < 1e-7);
        }
        assert_eq!(store.list_tracks().unwrap(), [done]);

        let mut bad = fix(20);
        bad.bearing_deg = Some(f64::NAN);
        let t2 = store.start_track().unwrap();
        assert!(matches!(
            store.append_track_points(t2.id, vec![bad]),
            Err(MotoError::InvalidInput { .. })
        ));
        assert!(store.delete_track(t.id).unwrap());
        drop(store);
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
    }

    #[test]
    fn matches_a_track_across_the_ffi() {
        let path =
            std::env::temp_dir().join(format!("moto-ffi-match-{}.region", std::process::id()));
        std::fs::write(&path, moto_core::fixture::region().to_bytes().unwrap()).unwrap();
        let engine = Engine::open(path.to_string_lossy().into_owned()).unwrap();
        std::fs::remove_file(&path).unwrap();

        // Along A–B, a fix every ~25 m, alternately 3 m north and south.
        let track: Vec<LatLon> = (0..22)
            .map(|i| {
                ll(
                    55.70 + if i % 2 == 0 { 3e-5 } else { -3e-5 },
                    13.2012 + f64::from(i) * 4e-4,
                )
            })
            .collect();
        let m = engine.match_track(track).unwrap();
        assert_eq!(m.pieces.len(), 1, "{m:?}");
        let piece = &m.pieces[0];
        assert_eq!((piece.first_point, piece.last_point), (0, 21));
        assert_eq!(
            piece.ways,
            [WaySpan {
                way_id: 100,
                from_idx: 0,
                to_idx: 1
            }]
        );

        assert!(matches!(
            engine.match_track(vec![ll(f64::NAN, 13.2)]),
            Err(MotoError::InvalidInput { .. })
        ));
    }
}
