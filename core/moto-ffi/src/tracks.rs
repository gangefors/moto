// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Recorded rides across the FFI (PRD R4): fitting a GPS track to the
//! roads.

use crate::{Engine, LatLon, MotoError, WaySpan};

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
