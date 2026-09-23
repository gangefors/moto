// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Platform-independent core of the moto routing app.
//!
//! Everything here is pure logic with no platform APIs, so the same crate
//! backs the Android app today and an iOS app later (see `docs/adr/0001`).
//! Bindings live in the separate `moto-ffi` crate; this crate has no FFI
//! attributes.

#![deny(unsafe_code)]

pub mod curvature;
mod engine;
mod error;
#[cfg(any(test, feature = "fixtures"))]
pub mod fixture;
pub mod geo;
pub mod region;
mod route;
pub mod section;
mod snap;
pub mod store;
mod types;

pub use engine::{Engine, SNAP_MAX_DISTANCE_M};
pub use error::CoreError;
pub use geo::LatLon;
pub use types::{Avoid, RoadPoint, RoundTripTarget, Route, RouteOptions};
