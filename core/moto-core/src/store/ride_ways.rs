// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The roads each finished ride was matched to (ADR-0010): its OSM way
//! spans and the region key it was matched against, so a ride is matched
//! once per map, not on every start.

use rusqlite::{OptionalExtension, params};

use super::{Store, db_err};
use crate::CoreError;
use crate::section::WaySpan;

/// Most way spans one ride may store: far more than a day's riding (a
/// 1 000 km ride crosses a few thousand ways), and a bound on what a
/// damaged database can make the overlay build read.
pub const MAX_RIDE_WAYS: usize = 100_000;

impl Store {
    /// The finished rides whose roads were not matched against the region
    /// with key `key` (never matched, or matched to another map), oldest
    /// first. Rides still being recorded are left out.
    pub fn rides_to_match(&self, key: &str) -> Result<Vec<i64>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id FROM tracks
                 WHERE ended_at IS NOT NULL AND (ways_key IS NULL OR ways_key != ?1)
                 ORDER BY id",
            )
            .map_err(db_err)?;
        stmt.query_map([key], |row| row.get(0))
            .map_err(db_err)?
            .collect::<Result<Vec<i64>, _>>()
            .map_err(db_err)
    }

    /// Replaces ride `id`'s way spans with `ways`, matched against the
    /// region with key `key`, in one transaction. `false` if there is no
    /// such ride.
    pub fn save_ride_ways(
        &mut self,
        id: i64,
        key: &str,
        ways: &[WaySpan],
    ) -> Result<bool, CoreError> {
        if ways.len() > MAX_RIDE_WAYS {
            return Err(CoreError::InvalidArgument(format!(
                "a ride may have at most {MAX_RIDE_WAYS} way spans, got {}",
                ways.len()
            )));
        }
        let tx = self.conn.transaction().map_err(db_err)?;
        let found = tx
            .execute(
                "UPDATE tracks SET ways_key = ?2 WHERE id = ?1",
                params![id, key],
            )
            .map_err(db_err)?;
        if found == 0 {
            return Ok(false);
        }
        tx.execute("DELETE FROM track_ways WHERE track_id = ?1", [id])
            .map_err(db_err)?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT INTO track_ways (track_id, seq, way_id, from_idx, to_idx)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                )
                .map_err(db_err)?;
            for (seq, w) in ways.iter().enumerate() {
                stmt.execute(params![id, seq as i64, w.way_id, w.from_idx, w.to_idx])
                    .map_err(db_err)?;
            }
        }
        tx.commit().map_err(db_err)?;
        Ok(true)
    }

    /// The region key ride `id`'s roads were matched against, if any.
    pub fn ride_ways_key(&self, id: i64) -> Result<Option<String>, CoreError> {
        Ok(self
            .conn
            .query_row("SELECT ways_key FROM tracks WHERE id = ?1", [id], |row| {
                row.get::<_, Option<String>>(0)
            })
            .optional()
            .map_err(db_err)?
            .flatten())
    }

    /// The way spans of every ride matched against the region with key
    /// `key`, ride by ride. Every row read back is validated; a ride with
    /// more than [`MAX_RIDE_WAYS`] spans is refused as damaged.
    pub fn ride_ways(&self, key: &str) -> Result<Vec<Vec<WaySpan>>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT w.track_id, w.way_id, w.from_idx, w.to_idx
                 FROM track_ways w JOIN tracks t ON t.id = w.track_id
                 WHERE t.ways_key = ?1
                 ORDER BY w.track_id, w.seq",
            )
            .map_err(db_err)?;
        let mut rows = stmt.query([key]).map_err(db_err)?;
        let mut rides: Vec<Vec<WaySpan>> = Vec::new();
        let mut current: Option<i64> = None;
        while let Some(row) = rows.next().map_err(db_err)? {
            let (track, way_id, from, to): (i64, i64, i64, i64) = (
                row.get(0).map_err(db_err)?,
                row.get(1).map_err(db_err)?,
                row.get(2).map_err(db_err)?,
                row.get(3).map_err(db_err)?,
            );
            let corrupt = || CoreError::Storage(format!("ride {track}: invalid way span"));
            if current != Some(track) {
                current = Some(track);
                rides.push(Vec::new());
            }
            let ride = rides.last_mut().ok_or_else(corrupt)?;
            if ride.len() >= MAX_RIDE_WAYS {
                return Err(corrupt());
            }
            ride.push(WaySpan {
                way_id,
                from_idx: u32::try_from(from).map_err(|_| corrupt())?,
                to_idx: u32::try_from(to).map_err(|_| corrupt())?,
            });
        }
        Ok(rides)
    }
}

#[cfg(test)]
mod tests;
