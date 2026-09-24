// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Quick-tags (PRD R3): a tap while riding that marks "this road is good",
//! turned into a suggested section after the ride (ADR-0006).

use crate::track::TrackPoint;
use crate::{CoreError, LatLon};

/// Where a tag stands in the post-ride review.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TagStatus {
    /// Not reviewed yet.
    Pending = 0,
    /// Saved as a section.
    Used = 1,
    Discarded = 2,
}

impl TagStatus {
    pub fn from_i64(v: i64) -> Option<Self> {
        match v {
            0 => Some(Self::Pending),
            1 => Some(Self::Used),
            2 => Some(Self::Discarded),
            _ => None,
        }
    }
}

/// A tag as the app creates it: the rider's fix when the button was
/// pressed, and the ride being recorded, if any.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NewTag {
    pub time_ms: i64,
    pub position: LatLon,
    /// Direction of travel, degrees clockwise from north, if known.
    pub heading_deg: Option<f64>,
    pub speed_mps: Option<f64>,
    pub track_id: Option<i64>,
}

impl NewTag {
    /// Validated like a track fix (time, position, speed, heading ranges).
    pub fn validate(&self) -> Result<(), CoreError> {
        TrackPoint {
            time_ms: self.time_ms,
            position: self.position,
            accuracy_m: None,
            speed_mps: self.speed_mps,
            bearing_deg: self.heading_deg,
        }
        .validate()
        .map_err(|_| CoreError::InvalidArgument("invalid tag".into()))
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Tag {
    pub id: i64,
    pub rider_id: String,
    pub time_ms: i64,
    pub position: LatLon,
    pub heading_deg: Option<f64>,
    pub speed_mps: Option<f64>,
    /// The ride it was made on; `None` if none was recording, or the ride
    /// has been deleted.
    pub track_id: Option<i64>,
    pub status: TagStatus,
}
