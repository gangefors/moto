//! Platform-independent core of the moto routing app.
//!
//! Everything here is pure logic with no platform APIs, so the same crate
//! backs the Android app today and an iOS app later (see `docs/adr/0001`).
//! Bindings live in the separate `moto-ffi` crate; this crate has no FFI
//! attributes.

mod engine;
mod error;
pub mod geo;
mod types;

pub use engine::Engine;
pub use error::CoreError;
pub use geo::LatLon;
pub use types::{Avoid, RoadPoint, RoundTripTarget, Route, RouteOptions};
