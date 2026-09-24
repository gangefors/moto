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
mod draft;
mod engine;
mod error;
pub mod exchange;
pub mod favourites;
#[cfg(any(test, feature = "fixtures"))]
pub mod fixture;
pub mod geo;
pub mod gpx;
pub mod matching;
pub mod region;
pub mod rematch;
mod route;
pub mod scoring;
pub mod section;
mod snap;
pub mod store;
mod suggest;
pub mod tag;
pub mod track;
mod types;

pub use draft::SectionDraft;
pub use engine::{Engine, SNAP_MAX_DISTANCE_M};
pub use error::CoreError;
pub use favourites::Favourites;
pub use geo::LatLon;
pub use suggest::TAG_REACH_M;
pub use types::{Avoid, RoadPoint, RoundTripTarget, Route, RouteOptions};
