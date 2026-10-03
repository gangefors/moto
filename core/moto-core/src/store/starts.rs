// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Where each saved route and ride starts, for listing them nearest first
//! (Routes & rides, sorted by Nearest). Read in one query each, without
//! reading whole lines; anything stored that is out of range is left out.

use super::{Store, db_err};
use crate::region::format::COORD_SCALE;
use crate::{CoreError, LatLon};

/// A saved route's or ride's id and where it starts.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Start {
    pub id: i64,
    pub position: LatLon,
}

fn position(lat: i64, lon: i64) -> Option<LatLon> {
    let lat = i32::try_from(lat).ok()?;
    let lon = i32::try_from(lon).ok()?;
    LatLon::new(f64::from(lat) / COORD_SCALE, f64::from(lon) / COORD_SCALE).ok()
}

impl Store {
    /// The first point of every saved route.
    pub fn route_starts(&self) -> Result<Vec<Start>, CoreError> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, substr(geometry, 1, 8) FROM routes")
            .map_err(db_err)?;
        let rows = stmt
            .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Vec<u8>>(1)?)))
            .map_err(db_err)?;
        let mut out = Vec::new();
        for row in rows {
            let (id, head) = row.map_err(db_err)?;
            let Ok(b) = <[u8; 8]>::try_from(head.as_slice()) else {
                continue;
            };
            let lat = i32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            let lon = i32::from_le_bytes([b[4], b[5], b[6], b[7]]);
            if let Some(p) = position(i64::from(lat), i64::from(lon)) {
                out.push(Start { id, position: p });
            }
        }
        Ok(out)
    }

    /// The first fix of every ride that has one.
    pub fn track_starts(&self) -> Result<Vec<Start>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT p.track_id, p.lat, p.lon FROM track_points p
                 JOIN (SELECT track_id, MIN(seq) AS seq FROM track_points GROUP BY track_id) f
                   ON f.track_id = p.track_id AND f.seq = p.seq",
            )
            .map_err(db_err)?;
        let rows = stmt
            .query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, i64>(2)?,
                ))
            })
            .map_err(db_err)?;
        let mut out = Vec::new();
        for row in rows {
            let (id, lat, lon) = row.map_err(db_err)?;
            if let Some(p) = position(lat, lon) {
                out.push(Start { id, position: p });
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests;
