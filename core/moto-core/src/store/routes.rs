// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Routes and loops the rider saved to ride again (PRD P1): a name and
//! the line as it was found, so it can be shown and shared again even
//! after the map or the favourites change.

use rusqlite::{OptionalExtension, params};

use super::{Store, db_err, decode_line, encode_geometry};
use crate::section::{LOCAL_RIDER, validate_name};
use crate::{CoreError, LatLon};

/// Most points a saved route may have: a 400 km loop has about 20 000.
pub const MAX_ROUTE_POINTS: usize = 200_000;

/// A ride that ends this close to where it started is saved as a loop,
/// when it also went out at least [`LOOP_OUT_M`].
pub const LOOP_END_M: f64 = 300.0;
/// How far out a ride must have gone to be saved as a loop: at least this,
/// and twice as far as it ended from its start, so a short ride that never
/// went anywhere is not one (as the app names rides).
pub const LOOP_OUT_M: f64 = 1_000.0;

/// Whether a ride along `line` went out and came back to where it began.
fn ride_is_loop(line: &[LatLon]) -> bool {
    let (Some(&first), Some(&last)) = (line.first(), line.last()) else {
        return false;
    };
    let gap = crate::geo::haversine_m(first, last);
    let out = line
        .iter()
        .map(|&p| crate::geo::haversine_m(first, p))
        .fold(0.0, f64::max);
    gap <= LOOP_END_M && out >= LOOP_OUT_M.max(2.0 * gap)
}

/// A route to save.
#[derive(Debug, Clone, PartialEq)]
pub struct NewRoute {
    pub name: String,
    /// A round trip, rather than a route from A to B.
    pub is_loop: bool,
    pub distance_m: f64,
    pub duration_s: f64,
    pub geometry: Vec<LatLon>,
}

/// A saved route, without its line (see [`Store::route_geometry`]).
#[derive(Debug, Clone, PartialEq)]
pub struct SavedRoute {
    pub id: i64,
    pub rider_id: String,
    pub name: String,
    pub is_loop: bool,
    /// Seconds since the Unix epoch.
    pub created_at: i64,
    pub distance_m: f64,
    pub duration_s: f64,
}

const COLUMNS: &str = "id, rider_id, name, is_loop, created_at, distance_m, duration_s";

fn read(row: &rusqlite::Row<'_>) -> rusqlite::Result<(i64, String, String, i64, i64, f64, f64)> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
        row.get(6)?,
    ))
}

fn to_route(r: (i64, String, String, i64, i64, f64, f64)) -> Result<SavedRoute, CoreError> {
    let (id, rider_id, name, is_loop, created_at, distance_m, duration_s) = r;
    let corrupt = |what: &str| CoreError::Storage(format!("route {id}: invalid {what}"));
    validate_name(&name).map_err(|_| corrupt("name"))?;
    let figure = |v: f64| v.is_finite() && v >= 0.0;
    if !(figure(distance_m) && figure(duration_s)) {
        return Err(corrupt("figures"));
    }
    Ok(SavedRoute {
        id,
        rider_id,
        name,
        is_loop: match is_loop {
            0 => false,
            1 => true,
            _ => return Err(corrupt("kind")),
        },
        created_at,
        distance_m,
        duration_s,
    })
}

impl NewRoute {
    pub fn validate(&self) -> Result<(), CoreError> {
        validate_name(&self.name)?;
        let bad = |why: String| Err(CoreError::InvalidArgument(format!("route: {why}")));
        for (what, v) in [("distance", self.distance_m), ("duration", self.duration_s)] {
            if !(v.is_finite() && v >= 0.0) {
                return bad(format!("invalid {what} {v}"));
            }
        }
        if !(2..=MAX_ROUTE_POINTS).contains(&self.geometry.len()) {
            return bad(format!("2–{MAX_ROUTE_POINTS} points"));
        }
        for p in &self.geometry {
            p.validate()?;
        }
        Ok(())
    }
}

impl Store {
    /// Saves a route for the local rider; `now` is seconds since the Unix
    /// epoch.
    pub fn save_route(&mut self, route: &NewRoute, now: i64) -> Result<SavedRoute, CoreError> {
        route.validate()?;
        self.conn
            .execute(
                "INSERT INTO routes
                    (rider_id, name, is_loop, created_at, distance_m, duration_s, geometry)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    LOCAL_RIDER,
                    route.name,
                    route.is_loop,
                    now,
                    route.distance_m,
                    route.duration_s,
                    encode_geometry(&route.geometry)
                ],
            )
            .map_err(db_err)?;
        let id = self.conn.last_insert_rowid();
        self.get_route(id)?
            .ok_or_else(|| CoreError::Storage(format!("route {id} vanished")))
    }

    /// Saves a finished ride as a route to ride again, named `name`: its
    /// line (every fix, or evenly thinned to [`MAX_ROUTE_POINTS`]), its
    /// length and riding time, and a loop when it ends within
    /// [`LOOP_END_M`] of where it started after going out at least
    /// [`LOOP_OUT_M`]. `None` if there is no such ride.
    pub fn save_track_as_route(
        &mut self,
        track_id: i64,
        name: &str,
        now: i64,
    ) -> Result<Option<SavedRoute>, CoreError> {
        let Some(track) = self.get_track(track_id)? else {
            return Ok(None);
        };
        if track.ended_at.is_none() {
            return Err(CoreError::InvalidArgument(format!(
                "ride {track_id} is still recording"
            )));
        }
        let points = self.track_points(track_id)?.unwrap_or_default();
        let (Some(first), Some(last)) = (points.first(), points.last()) else {
            return Err(CoreError::InvalidArgument(format!(
                "ride {track_id} has no points"
            )));
        };
        let step = points.len().div_ceil(MAX_ROUTE_POINTS).max(1);
        let mut geometry: Vec<LatLon> = points.iter().step_by(step).map(|p| p.position).collect();
        if geometry.last() != Some(&last.position) {
            geometry.push(last.position);
        }
        let route = NewRoute {
            name: name.to_owned(),
            is_loop: ride_is_loop(&geometry),
            distance_m: track.distance_m,
            duration_s: (last.time_ms - first.time_ms).max(0) as f64 / 1000.0,
            geometry,
        };
        self.save_route(&route, now).map(Some)
    }

    pub fn get_route(&self, id: i64) -> Result<Option<SavedRoute>, CoreError> {
        self.conn
            .query_row(
                &format!("SELECT {COLUMNS} FROM routes WHERE id = ?1"),
                [id],
                read,
            )
            .optional()
            .map_err(db_err)?
            .map(to_route)
            .transpose()
    }

    /// All saved routes, newest first.
    pub fn list_routes(&self) -> Result<Vec<SavedRoute>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(&format!("SELECT {COLUMNS} FROM routes ORDER BY id DESC"))
            .map_err(db_err)?;
        let rows = stmt
            .query_map([], read)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        rows.into_iter().map(to_route).collect()
    }

    /// A saved route's line; `None` if there is no such route.
    pub fn route_geometry(&self, id: i64) -> Result<Option<Vec<LatLon>>, CoreError> {
        let blob: Option<Vec<u8>> = self
            .conn
            .query_row("SELECT geometry FROM routes WHERE id = ?1", [id], |r| {
                r.get(0)
            })
            .optional()
            .map_err(db_err)?;
        blob.map(|b| {
            decode_line(&b, MAX_ROUTE_POINTS)
                .ok_or_else(|| CoreError::Storage(format!("route {id}: invalid geometry")))
        })
        .transpose()
    }

    /// Renames a saved route; `false` if there is no such route.
    pub fn rename_route(&mut self, id: i64, name: &str) -> Result<bool, CoreError> {
        validate_name(name)?;
        let changed = self
            .conn
            .execute(
                "UPDATE routes SET name = ?2 WHERE id = ?1",
                params![id, name],
            )
            .map_err(db_err)?;
        Ok(changed > 0)
    }

    /// Deletes a saved route; `false` if it did not exist.
    pub fn delete_route(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM routes WHERE id = ?1", [id])
            .map_err(db_err)?;
        Ok(changed > 0)
    }
}

#[cfg(test)]
mod tests;
