// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The road at a point, for the road info sheet.

use moto_core::region::format as core;

use crate::{Engine, LatLon, MotoError, RoadPoint};

/// Road class, from OSM `highway=*`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RoadClass {
    Motorway,
    Trunk,
    Primary,
    Secondary,
    Tertiary,
    Unclassified,
    Residential,
    LivingStreet,
    Service,
    Track,
    Ferry,
    /// A class this build doesn't know (a newer region file).
    Other,
}

/// Road surface, from OSM `surface=*`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Surface {
    /// Not tagged, or a surface this build doesn't know; the router
    /// treats it as paved.
    Unknown,
    Asphalt,
    Concrete,
    Paved,
    Sett,
    Compacted,
    Gravel,
    Dirt,
}

/// The stretch of road between two junctions under a point.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RoadInfo {
    pub point: RoadPoint,
    pub class: RoadClass,
    pub surface: Surface,
    /// Whether the router counts the surface as paved.
    pub paved: bool,
    pub speed_kmh: u32,
    pub one_way: bool,
    pub toll: bool,
    pub ferry: bool,
    pub destination_only: bool,
    /// 0–1, as the router scores it.
    pub curviness: f64,
    pub length_m: f64,
    pub way_id: i64,
}

#[uniffi::export]
impl Engine {
    /// Snaps `point` to the nearest road and describes that road.
    pub fn road_at(&self, point: LatLon) -> Result<RoadInfo, MotoError> {
        Ok(self.inner.road_at(point.into())?.into())
    }
}

impl From<Option<core::RoadClass>> for RoadClass {
    fn from(c: Option<core::RoadClass>) -> Self {
        use core::RoadClass as C;
        match c {
            Some(C::Motorway) => Self::Motorway,
            Some(C::Trunk) => Self::Trunk,
            Some(C::Primary) => Self::Primary,
            Some(C::Secondary) => Self::Secondary,
            Some(C::Tertiary) => Self::Tertiary,
            Some(C::Unclassified) => Self::Unclassified,
            Some(C::Residential) => Self::Residential,
            Some(C::LivingStreet) => Self::LivingStreet,
            Some(C::Service) => Self::Service,
            Some(C::Track) => Self::Track,
            Some(C::Ferry) => Self::Ferry,
            None => Self::Other,
        }
    }
}

impl From<Option<core::Surface>> for Surface {
    fn from(s: Option<core::Surface>) -> Self {
        use core::Surface as S;
        match s {
            Some(S::Asphalt) => Self::Asphalt,
            Some(S::Concrete) => Self::Concrete,
            Some(S::Paved) => Self::Paved,
            Some(S::Sett) => Self::Sett,
            Some(S::Compacted) => Self::Compacted,
            Some(S::Gravel) => Self::Gravel,
            Some(S::Dirt) => Self::Dirt,
            Some(S::Unknown) | None => Self::Unknown,
        }
    }
}

impl From<moto_core::RoadInfo> for RoadInfo {
    fn from(r: moto_core::RoadInfo) -> Self {
        Self {
            point: r.point.into(),
            class: r.class.into(),
            surface: r.surface.into(),
            paved: r.surface.is_none_or(core::Surface::is_paved),
            speed_kmh: r.speed_kmh,
            one_way: r.one_way,
            toll: r.toll,
            ferry: r.ferry,
            destination_only: r.destination_only,
            curviness: r.curviness,
            length_m: r.length_m,
            way_id: r.way_id,
        }
    }
}
