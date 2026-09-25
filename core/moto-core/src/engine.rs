// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The routing engine: loads a region file and answers snap/route queries.
//!
//! The public API is the one agreed in ADR-0001, on the ADR-0005 region
//! file. Routes and round trips take the rider's favourites and curvy
//! roads into account (M2, M3).

use std::path::Path;

use crate::draft::SectionDraft;
use crate::favourites::Favourites;
use crate::matching::MatchedTrack;
use crate::region::Region;
use crate::region::format::COORD_SCALE;
use crate::{CoreError, LatLon, LoopOptions, RoadPoint, RoundTripTarget, Route, RouteOptions};

/// How far from a road a point may be and still snap to it.
pub const SNAP_MAX_DISTANCE_M: f64 = 500.0;

#[derive(Debug)]
pub struct Engine {
    region: Region,
    /// Highest edge speed in the region, for the A* estimate.
    max_speed_kmh: f64,
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
        let max_speed_kmh = region
            .edges()
            .iter()
            .map(|e| f64::from(e.speed_kmh))
            .fold(1.0, f64::max);
        Self {
            region,
            max_speed_kmh,
        }
    }

    pub fn region(&self) -> &Region {
        &self.region
    }

    /// The region's bounding box as (south-west, north-east) corners.
    pub fn bounds(&self) -> (LatLon, LatLon) {
        let b = self.region.info().bbox;
        let ll = |lat: i32, lon: i32| LatLon {
            lat: f64::from(lat) / COORD_SCALE,
            lon: f64::from(lon) / COORD_SCALE,
        };
        (ll(b.min_lat, b.min_lon), ll(b.max_lat, b.max_lon))
    }

    /// Snaps a point to the nearest routable road. Points outside the
    /// region's bounding box are refused with `OutsideRegion`.
    pub fn snap(&self, point: LatLon) -> Result<RoadPoint, CoreError> {
        point.validate()?;
        let (sw, ne) = self.bounds();
        let inside =
            (sw.lat..=ne.lat).contains(&point.lat) && (sw.lon..=ne.lon).contains(&point.lon);
        if !inside {
            return Err(CoreError::OutsideRegion {
                lat: point.lat,
                lon: point.lon,
            });
        }
        crate::snap::snap(&self.region, point, SNAP_MAX_DISTANCE_M)
    }

    /// Fastest route from `from` to `to` under `opts.avoid`: no
    /// favourites, no curvature, the budget unused. The reference that
    /// fun routes are measured against.
    pub fn route(&self, from: LatLon, to: LatLon, opts: &RouteOptions) -> Result<Route, CoreError> {
        let fastest = RouteOptions {
            curvy: false,
            ..opts.clone()
        };
        self.route_with(from, to, &fastest, &Favourites::none())
    }

    /// Route from `from` to `to` over as much of the rider's `favourites`
    /// and curvy roads (unless `opts.curvy` is off) as the time budget
    /// `opts.budget` buys (PRD R5, R6).
    /// Both points are snapped to the nearest road first. Favourites built
    /// for another region are refused.
    pub fn route_with(
        &self,
        from: LatLon,
        to: LatLon,
        opts: &RouteOptions,
        favourites: &Favourites,
    ) -> Result<Route, CoreError> {
        from.validate()?;
        to.validate()?;
        opts.validate()?;
        favourites.check(self)?;
        let start = self.snap(from)?;
        let end = self.snap(to)?;
        crate::route::route(
            &self.region,
            &start,
            &end,
            opts,
            favourites,
            self.max_speed_kmh,
        )
    }

    /// Proposes a section along the road between two points the rider picked
    /// on the map (PRD R2): the shortest road connection between them, with
    /// nothing avoided, as OSM way spans plus geometry.
    pub fn section_between(&self, from: LatLon, to: LatLon) -> Result<SectionDraft, CoreError> {
        from.validate()?;
        to.validate()?;
        let start = self.snap(from)?;
        let end = self.snap(to)?;
        let parts = crate::route::path(
            &self.region,
            &start,
            &end,
            crate::route::Cost::Shortest,
            self.max_speed_kmh,
        )?;
        crate::draft::from_path(&self.region, &parts)
    }

    /// Fits a recorded GPS track (points in recording order) to the roads
    /// (PRD R4): the matched pieces as OSM way spans plus geometry. Points
    /// outside the region or far from any road are skipped; where the
    /// track can't be followed along the roads it splits into pieces.
    pub fn match_track(&self, points: &[LatLon]) -> Result<MatchedTrack, CoreError> {
        crate::matching::match_track(&self.region, self.bounds(), points)
    }

    /// The section suggested for a quick-tag (PRD R3): about 1 km of road
    /// on each side of it, from its ride's fixes if `track` is given and
    /// covers the tag's time, otherwise by following the road in the
    /// tag's heading.
    pub fn suggest_from_tag(
        &self,
        tag: &crate::tag::Tag,
        track: Option<&[crate::track::TrackPoint]>,
    ) -> Result<SectionDraft, CoreError> {
        crate::suggest::from_tag(self, tag, track)
    }

    /// Alternative loops starting and ending at `start` (PRD R7), with no
    /// favourites.
    pub fn round_trip(
        &self,
        start: LatLon,
        target: RoundTripTarget,
        opts: &RouteOptions,
    ) -> Result<Vec<Route>, CoreError> {
        self.round_trip_with(
            start,
            target,
            opts,
            &Favourites::none(),
            &LoopOptions::default(),
        )
    }

    /// Up to three different loops from `start` of about `target` (±15 %),
    /// over the rider's `favourites` and curvy roads, best first (PRD R7,
    /// ADR-0007). `opts.budget` doesn't apply: the target is the budget;
    /// `shape` gives other sets of loops (a seed).
    pub fn round_trip_with(
        &self,
        start: LatLon,
        target: RoundTripTarget,
        opts: &RouteOptions,
        favourites: &Favourites,
        shape: &LoopOptions,
    ) -> Result<Vec<Route>, CoreError> {
        crate::roundtrip::loops(self, start, target, opts, favourites, shape)
    }

    /// The highest edge speed, which bounds the A* estimate.
    pub(crate) fn max_speed_kmh(&self) -> f64 {
        self.max_speed_kmh
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
            budget: crate::TimeBudget::Extra(-1.0),
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
    fn points_outside_the_region_are_refused() {
        let engine = engine();
        for far in [
            ll(55.8, 13.2),
            ll(10.0, -70.0),
            ll(-90.0, 180.0),
            ll(55.705, 13.19),
            ll(55.72, 13.21),
        ] {
            assert!(
                matches!(engine.snap(far), Err(CoreError::OutsideRegion { .. })),
                "{far:?}"
            );
        }
        let (sw, ne) = engine.bounds();
        assert_eq!((sw, ne), (ll(55.695, 13.195), ll(55.715, 13.225)));
    }

    #[test]
    fn never_snaps_onto_ferries() {
        use crate::fixture::{Road, build};
        use crate::region::format::{RoadClass, edge_flags};
        // A ferry across the water and a road 300 m beyond its far end.
        let ferry = Road {
            flags: edge_flags::FERRY,
            ..Road::new(0, 1, RoadClass::Ferry, 15, 1)
        };
        let road = Road::new(2, 3, RoadClass::Tertiary, 70, 2);
        let nodes = [
            (55.50, 13.00),
            (55.50, 13.05),
            (55.5027, 13.05),
            (55.5027, 13.07),
        ];
        let e = Engine::from_region(
            Region::from_bytes(&build(&nodes, &[ferry, road], 50_000).to_bytes().unwrap()).unwrap(),
        );
        // Right on the ferry line: the road 300 m north wins.
        let p = e.snap(ll(55.5001, 13.0495)).unwrap();
        assert_eq!(
            e.region().edges()[p.edge as usize].class,
            RoadClass::Tertiary as u8
        );
        // Mid-crossing, far from the road: nothing.
        assert!(matches!(
            e.snap(ll(55.5, 13.02)),
            Err(CoreError::NoRoadNearby { .. })
        ));
    }

    #[test]
    fn points_in_the_region_far_from_roads_have_no_road() {
        // North-east corner of the box: over 800 m from C and B–D.
        assert!(matches!(
            engine().snap(ll(55.7145, 13.2245)),
            Err(CoreError::NoRoadNearby { .. })
        ));
    }
}
