// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Error)]
pub enum CoreError {
    #[error("invalid coordinate: lat {lat}, lon {lon}")]
    InvalidCoordinate { lat: f64, lon: f64 },

    #[error("invalid argument: {0}")]
    InvalidArgument(String),

    #[error("region file error: {0}")]
    Region(String),

    #[error("storage error: {0}")]
    Storage(String),

    #[error("{lat:.5}, {lon:.5} is outside the loaded region")]
    OutsideRegion { lat: f64, lon: f64 },

    #[error("no road within {max_distance_m} m of the given point")]
    NoRoadNearby { max_distance_m: f64 },

    #[error("no route satisfies the constraints: {0}")]
    NoRoute(String),

    #[error("not implemented yet: {0}")]
    NotImplemented(&'static str),
}
