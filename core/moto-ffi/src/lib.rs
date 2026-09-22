//! UniFFI bindings for `moto-core`.
//!
//! This is the only crate with FFI attributes. It mirrors the core types as
//! UniFFI records and keeps the surface coarse: one call per request, whole
//! results back, typed errors instead of panics.

use std::sync::Arc;

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, Copy, uniffi::Record)]
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

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MotoError {
    #[error("{message}")]
    InvalidInput { message: String },
    #[error("{message}")]
    Region { message: String },
    #[error("{message}")]
    NoRoadNearby { message: String },
    #[error("{message}")]
    NoRoute { message: String },
    #[error("{message}")]
    NotImplemented { message: String },
}

/// Default route options, so the app does not duplicate the core's defaults.
#[uniffi::export]
pub fn default_route_options() -> RouteOptions {
    moto_core::RouteOptions::default().into()
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
    fn default_options_round_trip_through_ffi_types() {
        let ffi = default_route_options();
        let core: moto_core::RouteOptions = ffi.into();
        assert_eq!(core, moto_core::RouteOptions::default());
    }
}
