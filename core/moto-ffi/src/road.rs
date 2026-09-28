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
    /// Its number as signed (`13`, `E22`) and its name, when the region
    /// file has them.
    pub road_ref: Option<String>,
    pub name: Option<String>,
}

/// How big a place is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlaceKind {
    City,
    Town,
    Village,
    Hamlet,
}

/// A road a line runs on, by number (as signed), name, or both, and how
/// much of the line runs on it (0–1).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RoadLabel {
    pub road_ref: Option<String>,
    pub name: Option<String>,
    pub share: f64,
}

/// A place near one end of a line, and how far from it (metres).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PlaceName {
    pub name: String,
    pub kind: PlaceKind,
    pub distance_m: f64,
}

/// A line in words: the roads it runs on most (the most first, at most
/// two), and the places nearest its start and end (none on a region
/// file without names); and how curvy it is, 0–1, as routes count it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Description {
    pub roads: Vec<RoadLabel>,
    pub start: Option<PlaceName>,
    pub end: Option<PlaceName>,
    pub curvy_share: f64,
}

#[uniffi::export]
impl Engine {
    /// Snaps `point` to the nearest road and describes that road.
    pub fn road_at(&self, point: LatLon) -> Result<RoadInfo, MotoError> {
        Ok(self.inner.road_at(point.into())?.into())
    }

    /// Names `line` (a section's, route's or ride's geometry) by its roads
    /// and the places at its ends.
    pub fn describe(&self, line: Vec<LatLon>) -> Result<Description, MotoError> {
        let line: Vec<moto_core::LatLon> = line.into_iter().map(Into::into).collect();
        Ok(self.inner.describe(&line)?.into())
    }
}

impl From<moto_core::Description> for Description {
    fn from(d: moto_core::Description) -> Self {
        let place = |p: moto_core::describe::PlaceName| PlaceName {
            name: p.name,
            kind: match p.kind {
                core::PlaceKind::City => PlaceKind::City,
                core::PlaceKind::Town => PlaceKind::Town,
                core::PlaceKind::Village => PlaceKind::Village,
                core::PlaceKind::Hamlet => PlaceKind::Hamlet,
            },
            distance_m: p.distance_m,
        };
        Self {
            roads: d
                .roads
                .into_iter()
                .map(|r| RoadLabel {
                    road_ref: r.road_ref,
                    name: r.name,
                    share: r.share,
                })
                .collect(),
            start: d.start.map(place),
            end: d.end.map(place),
            curvy_share: d.curvy_share,
        }
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
            road_ref: r.road_ref,
            name: r.name,
        }
    }
}
