// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! What the region file knows about the road at a point: the answer to
//! the rider tapping a road on the map.

use crate::region::Region;
use crate::region::format::{RoadClass, Surface, edge_flags};
use crate::route::twin;
use crate::scoring::PARAMS;
use crate::{CoreError, RoadPoint};

/// The road at a point, for showing to the rider. Describes the stretch
/// between two junctions that the point snapped onto.
#[derive(Debug, Clone, PartialEq)]
pub struct RoadInfo {
    /// Where the point snapped to.
    pub point: RoadPoint,
    /// `None` for a class this build doesn't know (a newer region file).
    pub class: Option<RoadClass>,
    /// `None` for a surface this build doesn't know.
    pub surface: Option<Surface>,
    /// Speed the router assumes, in km/h.
    pub speed_kmh: u32,
    /// Only rideable one way.
    pub one_way: bool,
    pub toll: bool,
    pub ferry: bool,
    /// Access only to reach a destination.
    pub destination_only: bool,
    /// How curvy the stretch is, 0–1, as the router scores it (by road
    /// class, so a curvy service road counts for nothing).
    pub curviness: f64,
    /// Length of the stretch, in metres.
    pub length_m: f64,
    /// The OSM way the stretch belongs to.
    pub way_id: i64,
}

/// The road under an already snapped point. The point's edge must come
/// from `region` (a snap on it); anything else is a typed error.
pub(crate) fn road_info(region: &Region, point: RoadPoint) -> Result<RoadInfo, CoreError> {
    let id = point.edge;
    let bad = || CoreError::Region(format!("no edge {id}"));
    let e = *region.edges().get(id as usize).ok_or_else(bad)?;
    let m = *region.curvature().get(id as usize).ok_or_else(bad)?;
    let way = *region.way_refs().get(id as usize).ok_or_else(bad)?;
    let length_m = f64::from(e.length_dm) / 10.0;
    Ok(RoadInfo {
        point,
        class: RoadClass::from_u8(e.class),
        surface: Surface::from_u8(e.surface),
        speed_kmh: u32::from(e.speed_kmh),
        one_way: twin(region, id).is_none(),
        toll: e.flags & edge_flags::TOLL != 0,
        ferry: e.flags & edge_flags::FERRY != 0,
        destination_only: e.flags & edge_flags::DESTINATION != 0,
        curviness: PARAMS.curviness(&m, e.class, length_m),
        length_m,
        way_id: way.way_id,
    })
}

#[cfg(test)]
mod tests;
