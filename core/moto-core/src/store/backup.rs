// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Store support for restoring a backup ([`crate::backup`], ADR-0012):
//! everything a restore adds goes through one transaction, so a restore
//! lands whole or not at all. Reads through the [`Store`] while it is open
//! see what it has added so far (same connection).

use rusqlite::{Transaction, params};

use super::tracks::insert_points;
use super::{Store, db_err, e7, encode_geometry, insert_section_at};
use crate::CoreError;
use crate::section::{LOCAL_RIDER, NewSection, Status, validate_name};
use crate::store::NewRoute;
use crate::tag::NewTag;
use crate::track::{MAX_TRACK_POINTS, TrackPoint, ridden_distance_m};

/// Latest time a backup may give, in seconds since the Unix epoch (the
/// start of 2100), so nothing absurd lands in the database.
const MAX_TIME_S: i64 = 4_102_444_800;

/// A restore in progress. Dropping it without [`Restore::commit`] undoes
/// everything it added.
pub struct Restore<'a> {
    tx: Transaction<'a>,
}

fn bad(why: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(why.into())
}

fn valid_time_s(t: i64) -> bool {
    (0..=MAX_TIME_S).contains(&t)
}

impl Store {
    /// Starts a restore. Reads through `self` stay possible while it is
    /// open.
    pub fn begin_restore(&self) -> Result<Restore<'_>, CoreError> {
        Ok(Restore {
            tx: self.conn.unchecked_transaction().map_err(db_err)?,
        })
    }

    /// Whether a saved route has `name` and exactly the line `geometry`
    /// (to 1e-7 degrees).
    pub fn same_route(&self, name: &str, geometry: &[crate::LatLon]) -> Result<bool, CoreError> {
        let mut stmt = self
            .conn
            .prepare("SELECT geometry FROM routes WHERE name = ?1")
            .map_err(db_err)?;
        let want = encode_geometry(geometry);
        let lines = stmt
            .query_map([name], |r| r.get::<_, Vec<u8>>(0))
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        Ok(lines.contains(&want))
    }

    /// Whether a tag at `time_ms` and `position` (to 1e-7 degrees) is saved.
    pub fn same_tag(&self, time_ms: i64, position: crate::LatLon) -> Result<bool, CoreError> {
        let (lat, lon) = e7(position);
        self.conn
            .query_row(
                "SELECT EXISTS (SELECT 1 FROM tags WHERE time_ms = ?1 AND lat = ?2 AND lon = ?3)",
                params![time_ms, lat, lon],
                |r| r.get::<_, bool>(0),
            )
            .map_err(db_err)
    }
}

impl Restore<'_> {
    /// Adds sections (validated) created and changed at the times given,
    /// flagged to be fitted to the map, after deleting `remove` (shorter
    /// sections an added one covers, as an import does). Returns the new
    /// ids.
    pub fn add_sections(
        &self,
        add: &[(NewSection, i64, i64)],
        remove: &[i64],
    ) -> Result<Vec<i64>, CoreError> {
        for (s, created, updated) in add {
            s.validate()?;
            if !valid_time_s(*created) || !valid_time_s(*updated) {
                return Err(bad("favourite section: invalid time"));
            }
        }
        for id in remove {
            self.tx
                .execute("DELETE FROM sections WHERE id = ?1", [id])
                .map_err(db_err)?;
        }
        add.iter()
            .map(|(s, created, updated)| {
                insert_section_at(&self.tx, s, *created, *updated, Status::NeedsRematch)
            })
            .collect()
    }

    /// Adds a finished ride: its fixes in `segments` (a new segment where
    /// recording started again after a gap), started and ended at the
    /// given times (seconds), named `name` if given. The fixes must be
    /// valid, at least two in all, at most [`MAX_TRACK_POINTS`], and later
    /// one after another. Returns its id.
    pub fn add_ride(
        &self,
        segments: &[Vec<TrackPoint>],
        name: Option<&str>,
        started_at: i64,
        ended_at: i64,
    ) -> Result<i64, CoreError> {
        let count: usize = segments.iter().map(Vec::len).sum();
        if !(2..=MAX_TRACK_POINTS).contains(&count) || segments.iter().any(Vec::is_empty) {
            return Err(bad("ride: 2 or more fixes, no empty segment"));
        }
        let all = || segments.iter().flatten();
        for p in all() {
            p.validate()?;
        }
        let times: Vec<i64> = all().map(|p| p.time_ms).collect();
        if times.windows(2).any(|w| w[1] <= w[0]) {
            return Err(bad("ride: fix times must increase"));
        }
        if !valid_time_s(started_at) || !valid_time_s(ended_at) || ended_at < started_at {
            return Err(bad("ride: invalid start or end"));
        }
        if let Some(name) = name {
            validate_name(name)?;
        }
        let last = times[times.len() - 1];
        let distance: f64 = segments.iter().map(|s| ridden_distance_m(s)).sum();
        self.tx
            .execute(
                "INSERT INTO tracks
                    (rider_id, name, started_at, ended_at, point_count, last_time_ms, distance_m)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    LOCAL_RIDER,
                    name,
                    started_at,
                    ended_at,
                    count as i64,
                    last,
                    distance
                ],
            )
            .map_err(db_err)?;
        let id = self.tx.last_insert_rowid();
        insert_points(&self.tx, id, 0, all())?;
        let mut seq = 0i64;
        for s in &segments[..segments.len() - 1] {
            seq += s.len() as i64;
            self.tx
                .execute(
                    "INSERT INTO track_breaks (track_id, seq) VALUES (?1, ?2)",
                    params![id, seq],
                )
                .map_err(db_err)?;
        }
        Ok(id)
    }

    /// Adds a saved route, saved at `created_at` (seconds). Returns its id.
    pub fn add_route(&self, route: &NewRoute, created_at: i64) -> Result<i64, CoreError> {
        route.validate()?;
        if !valid_time_s(created_at) {
            return Err(bad("route: invalid time"));
        }
        self.tx
            .execute(
                "INSERT INTO routes
                    (rider_id, name, is_loop, created_at, distance_m, duration_s, geometry)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    LOCAL_RIDER,
                    route.name,
                    route.is_loop,
                    created_at,
                    route.distance_m,
                    route.duration_s,
                    encode_geometry(&route.geometry)
                ],
            )
            .map_err(db_err)?;
        Ok(self.tx.last_insert_rowid())
    }

    /// Adds a tag, waiting for review; its ride, if any, must exist.
    /// Returns its id.
    pub fn add_tag(&self, t: &NewTag) -> Result<i64, CoreError> {
        t.validate()?;
        let (lat, lon) = e7(t.position);
        self.tx
            .execute(
                "INSERT INTO tags
                    (rider_id, time_ms, lat, lon, heading_deg, speed_mps, track_id)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    LOCAL_RIDER,
                    t.time_ms,
                    lat,
                    lon,
                    t.heading_deg,
                    t.speed_mps,
                    t.track_id
                ],
            )
            .map_err(db_err)?;
        Ok(self.tx.last_insert_rowid())
    }

    /// Keeps everything added.
    pub fn commit(self) -> Result<(), CoreError> {
        self.tx.commit().map_err(db_err)
    }
}
