//! The routing engine: loads a region file and answers snap/route queries.
//!
//! This is the M0 skeleton. The public API is the one agreed in ADR-0001;
//! the bodies return `NotImplemented` until the region file format is
//! decided (the blocking open question in the PRD).

use std::path::Path;

use crate::{CoreError, LatLon, RoadPoint, RoundTripTarget, Route, RouteOptions};

#[derive(Debug)]
pub struct Engine {
    _private: (),
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
        Err(CoreError::NotImplemented("region file loading"))
    }

    /// Snaps a point to the nearest routable road.
    pub fn snap(&self, point: LatLon) -> Result<RoadPoint, CoreError> {
        point.validate()?;
        Err(CoreError::NotImplemented("snap"))
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

    #[test]
    fn open_reports_missing_file() {
        let err = Engine::open("/definitely/not/here.region").unwrap_err();
        assert!(matches!(err, CoreError::Region(_)), "got {err:?}");
    }

    #[test]
    fn queries_validate_input_before_anything_else() {
        let engine = Engine { _private: () };
        let bad = LatLon {
            lat: 123.0,
            lon: 0.0,
        };
        assert!(matches!(
            engine.snap(bad),
            Err(CoreError::InvalidCoordinate { .. })
        ));

        let ok = LatLon {
            lat: 55.6,
            lon: 13.0,
        };
        let opts = RouteOptions {
            max_detour: -1.0,
            ..RouteOptions::default()
        };
        assert!(matches!(
            engine.route(ok, ok, &opts),
            Err(CoreError::InvalidArgument(_))
        ));
    }
}
