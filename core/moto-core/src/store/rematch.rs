// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Store support for re-matching sections to a new region
//! ([`crate::rematch`]).

use rusqlite::{OptionalExtension, params};

use super::{Store, bounds, db_err, encode_geometry, insert_ways, read_row};
use crate::section::{MAX_SECTION_POINTS, MAX_SECTION_WAYS, Section, Status, WaySpan};
use crate::{CoreError, LatLon};

const REGION_KEY: &str = "region";
/// Longest region key kept (the region header's fields are short).
const MAX_KEY_BYTES: usize = 512;

impl Store {
    /// The region the sections were last matched to, if any.
    pub fn region_key(&self) -> Result<Option<String>, CoreError> {
        self.conn
            .query_row("SELECT value FROM meta WHERE key = ?1", [REGION_KEY], |r| {
                r.get(0)
            })
            .optional()
            .map_err(db_err)
    }

    pub fn set_region_key(&mut self, key: &str) -> Result<(), CoreError> {
        if key.len() > MAX_KEY_BYTES {
            return Err(CoreError::InvalidArgument("region key too long".into()));
        }
        self.conn
            .execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2)
                 ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                params![REGION_KEY, key],
            )
            .map_err(db_err)?;
        Ok(())
    }

    /// Flags every section `needs_rematch`, in one transaction.
    pub fn flag_all_for_rematch(&mut self) -> Result<(), CoreError> {
        self.conn
            .execute(
                "UPDATE sections SET status = ?1",
                [Status::NeedsRematch as i64],
            )
            .map_err(db_err)?;
        Ok(())
    }

    /// Sections with `status`, oldest first.
    pub fn sections_with_status(&self, status: Status) -> Result<Vec<Section>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, rider_id, name, rating, direction, source, status, created_at,
                    updated_at, geometry FROM sections WHERE status = ?1 ORDER BY id",
            )
            .map_err(db_err)?;
        let rows = stmt
            .query_map([status as i64], read_row)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        rows.into_iter().map(|r| self.finish(r)).collect()
    }

    /// Saves a section's fit to a new region: its way spans and geometry,
    /// status `ok`, in one transaction. The rider's own fields (rating,
    /// direction, name, `updated_at`) stay as they are. `false` if there is
    /// no such section.
    pub fn save_match(
        &mut self,
        id: i64,
        ways: &[WaySpan],
        geometry: &[LatLon],
    ) -> Result<bool, CoreError> {
        if geometry.len() < 2
            || geometry.len() > MAX_SECTION_POINTS
            || ways.len() > MAX_SECTION_WAYS
        {
            return Err(CoreError::InvalidArgument(
                "re-matched section out of bounds".into(),
            ));
        }
        for p in geometry {
            p.validate()?;
        }
        let (min, max) = bounds(geometry);
        let tx = self.conn.transaction().map_err(db_err)?;
        let changed = tx
            .execute(
                "UPDATE sections SET status = ?2, min_lat = ?3, min_lon = ?4, max_lat = ?5,
                    max_lon = ?6, geometry = ?7 WHERE id = ?1",
                params![
                    id,
                    Status::Ok as i64,
                    min.0,
                    min.1,
                    max.0,
                    max.1,
                    encode_geometry(geometry)
                ],
            )
            .map_err(db_err)?;
        if changed == 0 {
            return Ok(false);
        }
        tx.execute("DELETE FROM section_ways WHERE section_id = ?1", [id])
            .map_err(db_err)?;
        insert_ways(&tx, id, ways)?;
        tx.commit().map_err(db_err)?;
        Ok(true)
    }
}
