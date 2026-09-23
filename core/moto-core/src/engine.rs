// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The routing engine: loads a region file and answers snap/route queries.
//!
//! The public API is the one agreed in ADR-0001. Opening and snapping work
//! on the ADR-0005 region file; routing returns `NotImplemented` for now.

use std::path::Path;

use crate::region::Region;
use crate::{CoreError, LatLon, RoadPoint, RoundTripTarget, Route, RouteOptions};

/// How far from a road a point may be and still snap to it.
pub const SNAP_MAX_DISTANCE_M: f64 = 500.0;

#[derive(Debug)]
pub struct Engine {
    region: Region,
}

impl Engine {
    /// Opens a region file built by `moto-regionbuild`.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, CoreError> {
        let path = path.as_ref();
        if !path.is_file() {
            return Err(CoreError::Region(format!(
                "no region file at {}",
                path.display()
            )));
        }
        Ok(Self::from_region(Region::open(path)?))
    }

    /// Wraps an already opened region.
    pub fn from_region(region: Region) -> Self {
        Self { region }
    }

    pub fn region(&self) -> &Region {
        &self.region
    }

    /// Snaps a point to the nearest routable road.
    pub fn snap(&self, point: LatLon) -> Result<RoadPoint, CoreError> {
        point.validate()?;
        crate::snap::snap(&self.region, point, SNAP_MAX_DISTANCE_M)
    }

    /// One-way route from `from` to `to` (PRD R6).
    pub fn route(&self, from: LatLon, to: LatLon, opts: &RouteOptions) -> Result<Route, CoreError> {
        from.validate()?;
        to.validate()?;
        opts.validate()?;
        Err(CoreError::NotImplemented("route"))
    }

    /// Alternative loops starting and ending at `start` (PRD R7).
    pub fn round_trip(
        &self,
        start: LatLon,
        target: RoundTripTarget,
        opts: &RouteOptions,
    ) -> Result<Vec<Route>, CoreError> {
        start.validate()?;
        target.validate()?;
        opts.validate()?;
        Err(CoreError::NotImplemented("round_trip"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fixture;
    use crate::geo::haversine_m;

    fn engine() -> Engine {
        let bytes = fixture::region().to_bytes().unwrap();
        Engine::from_region(Region::from_bytes(&bytes).unwrap())
    }

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    #[test]
    fn open_reports_missing_file() {
        let err = Engine::open("/definitely/not/here.region").unwrap_err();
        assert!(matches!(err, CoreError::Region(_)), "got {err:?}");
    }

    #[test]
    fn open_reads_a_written_file() {
        let path = std::env::temp_dir().join(format!("moto-engine-{}.region", std::process::id()));
        std::fs::write(&path, fixture::region().to_bytes().unwrap()).unwrap();
        let engine = Engine::open(&path);
        std::fs::remove_file(&path).unwrap();
        let engine = engine.unwrap();
        assert_eq!(engine.region().node_count(), 4);
        let p = engine.snap(ll(55.7001, 13.205)).unwrap();
        assert_eq!(p.edge, fixture::E_AB);
    }

    #[test]
    fn open_rejects_a_file_that_is_not_a_region() {
        let path = std::env::temp_dir().join(format!("moto-junk-{}.region", std::process::id()));
        std::fs::write(&path, vec![7u8; 10_000]).unwrap();
        let err = Engine::open(&path).unwrap_err();
        std::fs::remove_file(&path).unwrap();
        assert!(matches!(err, CoreError::Region(_)), "got {err:?}");
    }

    #[test]
    fn queries_validate_input_before_anything_else() {
        let engine = engine();
        let bad = ll(123.0, 0.0);
        assert!(matches!(
            engine.snap(bad),
            Err(CoreError::InvalidCoordinate { .. })
        ));

        let ok = ll(55.6, 13.0);
        let opts = RouteOptions {
            max_detour: -1.0,
            ..RouteOptions::default()
        };
        assert!(matches!(
            engine.route(ok, ok, &opts),
            Err(CoreError::InvalidArgument(_))
        ));
    }

    #[test]
    fn snaps_to_the_middle_of_a_straight_edge() {
        // A–B runs east along lat 55.7 from lon 13.20 to 13.21.
        let p = engine().snap(ll(55.7002, 13.205)).unwrap();
        assert_eq!(p.edge, fixture::E_AB);
        assert!((p.offset - 0.5).abs() < 1e-3, "got {p:?}");
        assert!((p.position.lat - 55.7).abs() < 1e-7);
        assert!((p.position.lon - 13.205).abs() < 1e-7);
        assert!((p.distance_m - 22.2).abs() < 0.5, "got {p:?}");
    }

    #[test]
    fn snaps_past_the_end_to_the_endpoint() {
        // West of A: the nearest road point is A itself.
        let p = engine().snap(ll(55.7, 13.199)).unwrap();
        assert_eq!(p.edge, fixture::E_AB);
        assert_eq!(p.offset, 0.0);
        assert!((p.distance_m - haversine_m(ll(55.7, 13.199), ll(55.7, 13.2))).abs() < 1e-6);
    }

    #[test]
    fn snaps_onto_a_bent_edge_with_offset_along_the_shape() {
        // B–C goes north-east to the bend at (55.705, 13.212), then north-west.
        let bend = ll(55.705, 13.212);
        let p = engine().snap(ll(55.705, 13.2125)).unwrap();
        assert_eq!(p.edge, fixture::E_BC);
        assert!(haversine_m(p.position, bend) < 1.0, "got {p:?}");
        // The bend is halfway along B–C by construction (symmetric legs).
        assert!((p.offset - 0.5).abs() < 0.01, "got {p:?}");
    }

    #[test]
    fn prefers_the_closest_of_several_roads() {
        // Near D, on the one-way B→D, not on A–B.
        let p = engine().snap(ll(55.6999, 13.219)).unwrap();
        assert_eq!(p.edge, fixture::E_BD);
        assert!((p.offset - 0.9).abs() < 0.01, "got {p:?}");
    }

    #[test]
    fn finds_roads_several_cells_away() {
        // 300 m north of A–B's middle, several 55 m cells from any road.
        let e = Engine::from_region(
            Region::from_bytes(&fixture::region_with_cell(5_000).to_bytes().unwrap()).unwrap(),
        );
        let p = e.snap(ll(55.7027, 13.203)).unwrap();
        assert_eq!(p.edge, fixture::E_AB);
        assert!((p.distance_m - 300.0).abs() < 1.0, "got {p:?}");
    }

    #[test]
    fn far_away_points_have_no_road() {
        let engine = engine();
        for far in [ll(55.8, 13.2), ll(10.0, -70.0), ll(-90.0, 180.0)] {
            assert!(
                matches!(engine.snap(far), Err(CoreError::NoRoadNearby { .. })),
                "{far:?}"
            );
        }
    }
}
