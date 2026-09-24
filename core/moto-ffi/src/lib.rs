// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! UniFFI bindings for `moto-core`.
//!
//! This is the only crate with FFI attributes. It mirrors the core types as
//! UniFFI records and keeps the surface coarse: one call per request, whole
//! results back, typed errors instead of panics.

use std::sync::Arc;

mod exchange;
mod sections;
mod tags;
mod tracks;
pub use exchange::*;
pub use sections::*;
pub use tags::*;
pub use tracks::*;

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LatLon {
    pub lat: f64,
    pub lon: f64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RoadPoint {
    pub position: LatLon,
    pub distance_m: f64,
    pub edge: u32,
    pub offset: f64,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct Avoid {
    pub motorways: bool,
    pub unpaved: bool,
    pub ferries: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RouteOptions {
    pub avoid: Avoid,
    pub max_detour: f64,
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum RoundTripTarget {
    DistanceM { meters: f64 },
    DurationS { seconds: f64 },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Route {
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
    pub duration_s: f64,
    pub favourite_share: f64,
    pub curvy_share: f64,
}

/// Crosses the FFI as a flat error: each variant becomes an exception class
/// whose message is the `Display` text. (A field named `message` would clash
/// with `Throwable.message` in the generated Kotlin.)
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum MotoError {
    #[error("{message}")]
    InvalidInput { message: String },
    #[error("{message}")]
    Region { message: String },
    #[error("{message}")]
    Storage { message: String },
    #[error("{message}")]
    OutsideRegion { message: String },
    #[error("{message}")]
    NoRoadNearby { message: String },
    #[error("{message}")]
    NoRoute { message: String },
    #[error("{message}")]
    NotImplemented { message: String },
}

/// What a loaded region covers.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RegionInfo {
    /// South-west corner of the region's bounding box.
    pub south_west: LatLon,
    /// North-east corner of the region's bounding box.
    pub north_east: LatLon,
    /// Timestamp of the OSM data, seconds since the Unix epoch (0 if unknown).
    pub osm_timestamp: i64,
    /// Where the data came from, e.g. the extract name and bounding box.
    pub source_name: String,
}

/// Default route options, so the app does not duplicate the core's defaults.
#[uniffi::export]
pub fn default_route_options() -> RouteOptions {
    moto_core::RouteOptions::default().into()
}

/// Checks a downloaded region file before it is installed: every section's
/// checksum, then the structure `Engine.open` relies on.
#[uniffi::export]
pub fn verify_region_file(path: String) -> Result<(), MotoError> {
    Ok(moto_core::region::verify_file(path)?)
}

/// A loaded routing region. Thread-safe; share one instance per region.
#[derive(Debug, uniffi::Object)]
pub struct Engine {
    inner: moto_core::Engine,
}

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, MotoError> {
        let inner = moto_core::Engine::open(path)?;
        Ok(Arc::new(Self { inner }))
    }

    /// The region's bounds and data source.
    pub fn info(&self) -> RegionInfo {
        let (sw, ne) = self.inner.bounds();
        let info = self.inner.region().info();
        RegionInfo {
            south_west: sw.into(),
            north_east: ne.into(),
            osm_timestamp: info.osm_timestamp,
            source_name: info.source_name.clone(),
        }
    }

    pub fn snap(&self, point: LatLon) -> Result<RoadPoint, MotoError> {
        Ok(self.inner.snap(point.into())?.into())
    }

    pub fn route(&self, from: LatLon, to: LatLon, opts: RouteOptions) -> Result<Route, MotoError> {
        Ok(self
            .inner
            .route(from.into(), to.into(), &opts.into())?
            .into())
    }

    pub fn round_trip(
        &self,
        start: LatLon,
        target: RoundTripTarget,
        opts: RouteOptions,
    ) -> Result<Vec<Route>, MotoError> {
        let routes = self
            .inner
            .round_trip(start.into(), target.into(), &opts.into())?;
        Ok(routes.into_iter().map(Into::into).collect())
    }
}

// --- conversions between FFI records and core types ---

impl From<LatLon> for moto_core::LatLon {
    fn from(p: LatLon) -> Self {
        Self {
            lat: p.lat,
            lon: p.lon,
        }
    }
}

impl From<moto_core::LatLon> for LatLon {
    fn from(p: moto_core::LatLon) -> Self {
        Self {
            lat: p.lat,
            lon: p.lon,
        }
    }
}

impl From<moto_core::RoadPoint> for RoadPoint {
    fn from(p: moto_core::RoadPoint) -> Self {
        Self {
            position: p.position.into(),
            distance_m: p.distance_m,
            edge: p.edge,
            offset: p.offset,
        }
    }
}

impl From<Avoid> for moto_core::Avoid {
    fn from(a: Avoid) -> Self {
        Self {
            motorways: a.motorways,
            unpaved: a.unpaved,
            ferries: a.ferries,
        }
    }
}

impl From<moto_core::Avoid> for Avoid {
    fn from(a: moto_core::Avoid) -> Self {
        Self {
            motorways: a.motorways,
            unpaved: a.unpaved,
            ferries: a.ferries,
        }
    }
}

impl From<RouteOptions> for moto_core::RouteOptions {
    fn from(o: RouteOptions) -> Self {
        Self {
            avoid: o.avoid.into(),
            max_detour: o.max_detour,
        }
    }
}

impl From<moto_core::RouteOptions> for RouteOptions {
    fn from(o: moto_core::RouteOptions) -> Self {
        Self {
            avoid: o.avoid.into(),
            max_detour: o.max_detour,
        }
    }
}

impl From<RoundTripTarget> for moto_core::RoundTripTarget {
    fn from(t: RoundTripTarget) -> Self {
        match t {
            RoundTripTarget::DistanceM { meters } => Self::DistanceM(meters),
            RoundTripTarget::DurationS { seconds } => Self::DurationS(seconds),
        }
    }
}

impl From<moto_core::Route> for Route {
    fn from(r: moto_core::Route) -> Self {
        Self {
            geometry: r.geometry.into_iter().map(Into::into).collect(),
            distance_m: r.distance_m,
            duration_s: r.duration_s,
            favourite_share: r.favourite_share,
            curvy_share: r.curvy_share,
        }
    }
}

impl From<moto_core::CoreError> for MotoError {
    fn from(e: moto_core::CoreError) -> Self {
        use moto_core::CoreError as C;
        let message = e.to_string();
        match e {
            C::InvalidCoordinate { .. } | C::InvalidArgument(_) => Self::InvalidInput { message },
            C::Region(_) => Self::Region { message },
            C::Storage(_) => Self::Storage { message },
            C::OutsideRegion { .. } => Self::OutsideRegion { message },
            C::NoRoadNearby { .. } => Self::NoRoadNearby { message },
            C::NoRoute(_) => Self::NoRoute { message },
            C::NotImplemented(_) => Self::NotImplemented { message },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_missing_region_is_a_typed_error() {
        let err = Engine::open("/definitely/not/here.region".into()).unwrap_err();
        assert!(matches!(err, MotoError::Region { .. }), "got {err:?}");
    }

    #[test]
    fn verify_missing_region_is_a_typed_error() {
        let err = verify_region_file("/definitely/not/here.region".into()).unwrap_err();
        assert!(matches!(err, MotoError::Region { .. }), "got {err:?}");
    }

    #[test]
    fn default_options_round_trip_through_ffi_types() {
        let ffi = default_route_options();
        let core: moto_core::RouteOptions = ffi.into();
        assert_eq!(core, moto_core::RouteOptions::default());
    }

    /// A region file on disk, removed when dropped.
    struct TempRegion(std::path::PathBuf);

    impl TempRegion {
        fn new(name: &str, bytes: &[u8]) -> Self {
            let path =
                std::env::temp_dir().join(format!("moto-ffi-{name}-{}.region", std::process::id()));
            std::fs::write(&path, bytes).unwrap();
            Self(path)
        }

        fn path(&self) -> String {
            self.0.to_string_lossy().into_owned()
        }
    }

    impl Drop for TempRegion {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn fixture_file(name: &str) -> TempRegion {
        TempRegion::new(name, &moto_core::fixture::region().to_bytes().unwrap())
    }

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    #[test]
    fn opens_verifies_and_describes_a_region() {
        let file = fixture_file("info");
        verify_region_file(file.path()).unwrap();
        let engine = Engine::open(file.path()).unwrap();
        let info = engine.info();
        assert_eq!((info.south_west.lat, info.south_west.lon), (55.695, 13.195));
        assert_eq!((info.north_east.lat, info.north_east.lon), (55.715, 13.225));
        assert_eq!(info.osm_timestamp, 1_790_000_000);
        assert_eq!(info.source_name, "hand-made test fixture");
    }

    #[test]
    fn verify_rejects_a_corrupted_file() {
        let mut bytes = moto_core::fixture::region().to_bytes().unwrap();
        let last = bytes.iter().rposition(|&b| b != 0).unwrap();
        bytes[last] ^= 0xff;
        let file = TempRegion::new("corrupt", &bytes);
        let err = verify_region_file(file.path()).unwrap_err();
        assert!(matches!(err, MotoError::Region { .. }), "got {err:?}");
        assert!(err.to_string().contains("checksum"), "{err}");
    }

    #[test]
    fn snaps_and_routes_through_the_ffi() {
        let file = fixture_file("route");
        let engine = Engine::open(file.path()).unwrap();
        let p = engine.snap(ll(55.7002, 13.205)).unwrap();
        assert!((p.offset - 0.5).abs() < 1e-3, "{p:?}");
        assert!((p.position.lat - 55.7).abs() < 1e-7);

        let route = engine
            .route(
                ll(55.7001, 13.201),
                ll(55.7001, 13.219),
                default_route_options(),
            )
            .unwrap();
        assert!(
            route.distance_m > 1_000.0 && route.distance_m < 1_200.0,
            "{route:?}"
        );
        assert!(route.duration_s > 0.0);
        assert!(route.geometry.len() >= 3);
        assert_eq!(route.favourite_share, 0.0);
    }

    #[test]
    fn core_errors_cross_as_typed_errors() {
        let file = fixture_file("errors");
        let engine = Engine::open(file.path()).unwrap();
        let opts = default_route_options();
        let outside = engine.snap(ll(56.5, 14.0)).unwrap_err();
        assert!(
            matches!(outside, MotoError::OutsideRegion { .. }),
            "{outside:?}"
        );
        let far = engine.snap(ll(55.7145, 13.2245)).unwrap_err();
        assert!(matches!(far, MotoError::NoRoadNearby { .. }), "{far:?}");
        let none = engine
            .route(ll(55.7001, 13.219), ll(55.7001, 13.201), opts.clone())
            .unwrap_err();
        assert!(matches!(none, MotoError::NoRoute { .. }), "{none:?}");
        let bad = engine.snap(ll(91.0, 0.0)).unwrap_err();
        assert!(matches!(bad, MotoError::InvalidInput { .. }), "{bad:?}");
        let todo = engine
            .round_trip(
                ll(55.7001, 13.201),
                RoundTripTarget::DistanceM { meters: 50_000.0 },
                opts,
            )
            .unwrap_err();
        assert!(matches!(todo, MotoError::NotImplemented { .. }), "{todo:?}");
        // Messages are the core's text, so the app can show them.
        assert!(
            outside.to_string().contains("outside the loaded region"),
            "{outside}"
        );
    }

    #[test]
    fn round_trip_targets_convert() {
        let d: moto_core::RoundTripTarget = RoundTripTarget::DistanceM { meters: 1.0 }.into();
        let t: moto_core::RoundTripTarget = RoundTripTarget::DurationS { seconds: 2.0 }.into();
        assert_eq!(d, moto_core::RoundTripTarget::DistanceM(1.0));
        assert_eq!(t, moto_core::RoundTripTarget::DurationS(2.0));
    }

    #[test]
    fn avoid_options_convert_both_ways() {
        let a = Avoid {
            motorways: false,
            unpaved: true,
            ferries: true,
        };
        let core: moto_core::Avoid = a.into();
        let back: Avoid = core.into();
        assert_eq!(
            (back.motorways, back.unpaved, back.ferries),
            (false, true, true)
        );
    }
}
