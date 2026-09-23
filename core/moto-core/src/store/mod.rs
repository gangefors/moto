// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! The rider's own data in one SQLite database (ADR-0006): favourite
//! sections now; tags and tracks join in later migrations.
//!
//! Security: every statement is a fixed SQL string with bound parameters;
//! extensions cannot be loaded (rusqlite is built without that feature);
//! SQLite runs in defensive mode with untrusted schema objects disabled;
//! deleted data is overwritten on disk. Everything read back is validated,
//! so a damaged or tampered database gives errors, never panics.

use std::path::Path;

use rusqlite::config::DbConfig;
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use crate::region::format::COORD_SCALE;
use crate::section::{
    Direction, NewSection, Rating, Section, SectionUpdate, Source, Status, WaySpan, validate_name,
};
use crate::{CoreError, LatLon};

/// Schema migrations, oldest first. Migration `i` takes the database from
/// `user_version` `i` to `i + 1`. Never edit a released migration; add one.
const MIGRATIONS: &[&str] = &[
    // 1: sections with their OSM way spans and own geometry.
    "CREATE TABLE sections (
        id          INTEGER PRIMARY KEY,
        rider_id    TEXT    NOT NULL,
        name        TEXT    NOT NULL,
        rating      INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 3),
        direction   INTEGER NOT NULL CHECK (direction IN (0, 1)),
        source      INTEGER NOT NULL CHECK (source BETWEEN 0 AND 3),
        status      INTEGER NOT NULL CHECK (status BETWEEN 0 AND 2),
        created_at  INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        min_lat     INTEGER NOT NULL,
        min_lon     INTEGER NOT NULL,
        max_lat     INTEGER NOT NULL,
        max_lon     INTEGER NOT NULL,
        geometry    BLOB    NOT NULL
    ) STRICT;
    CREATE INDEX sections_area ON sections (min_lat, max_lat, min_lon, max_lon);
    CREATE TABLE section_ways (
        section_id  INTEGER NOT NULL REFERENCES sections (id) ON DELETE CASCADE,
        seq         INTEGER NOT NULL,
        way_id      INTEGER NOT NULL,
        from_idx    INTEGER NOT NULL CHECK (from_idx BETWEEN 0 AND 4294967295),
        to_idx      INTEGER NOT NULL CHECK (to_idx BETWEEN 0 AND 4294967295),
        PRIMARY KEY (section_id, seq)
    ) STRICT;
    CREATE INDEX section_ways_way ON section_ways (way_id);",
];

/// The schema version this build writes.
pub const SCHEMA_VERSION: i64 = MIGRATIONS.len() as i64;

fn db_err(e: rusqlite::Error) -> CoreError {
    CoreError::Storage(e.to_string())
}

/// An open rider database.
#[derive(Debug)]
pub struct Store {
    conn: Connection,
}

impl Store {
    /// Opens (creating if needed) the database at `path` and brings it to
    /// the current schema. A database written by a newer app is refused
    /// untouched.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, CoreError> {
        Self::setup(Connection::open(path).map_err(db_err)?, MIGRATIONS)
    }

    /// A throwaway in-memory database, for tests and previews.
    pub fn open_in_memory() -> Result<Self, CoreError> {
        Self::setup(Connection::open_in_memory().map_err(db_err)?, MIGRATIONS)
    }

    fn setup(mut conn: Connection, migrations: &[&str]) -> Result<Self, CoreError> {
        conn.set_db_config(DbConfig::SQLITE_DBCONFIG_DEFENSIVE, true)
            .map_err(db_err)?;
        conn.set_db_config(DbConfig::SQLITE_DBCONFIG_TRUSTED_SCHEMA, false)
            .map_err(db_err)?;
        conn.pragma_update(None, "foreign_keys", true)
            .map_err(db_err)?;
        conn.pragma_update(None, "secure_delete", true)
            .map_err(db_err)?;
        // WAL keeps the database intact if the app dies mid-write. In-memory
        // databases report "memory", which is fine.
        conn.pragma_update_and_check(None, "journal_mode", "wal", |_| Ok(()))
            .map_err(db_err)?;
        migrate(&mut conn, migrations)?;
        Ok(Self { conn })
    }

    pub fn schema_version(&self) -> Result<i64, CoreError> {
        user_version(&self.conn)
    }

    /// Saves a new section; `now` is seconds since the Unix epoch.
    pub fn add_section(&mut self, s: &NewSection, now: i64) -> Result<Section, CoreError> {
        s.validate()?;
        let (min, max) = bounds(&s.geometry);
        let tx = self.conn.transaction().map_err(db_err)?;
        tx.execute(
            "INSERT INTO sections (rider_id, name, rating, direction, source, status,
                created_at, updated_at, min_lat, min_lon, max_lat, max_lon, geometry)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                s.rider_id,
                s.name,
                s.rating as i64,
                s.direction as i64,
                s.source as i64,
                Status::Ok as i64,
                now,
                min.0,
                min.1,
                max.0,
                max.1,
                encode_geometry(&s.geometry),
            ],
        )
        .map_err(db_err)?;
        let id = tx.last_insert_rowid();
        insert_ways(&tx, id, &s.ways)?;
        tx.commit().map_err(db_err)?;
        Ok(Section {
            id,
            rider_id: s.rider_id.clone(),
            name: s.name.clone(),
            rating: s.rating,
            direction: s.direction,
            source: s.source,
            status: Status::Ok,
            created_at: now,
            updated_at: now,
            ways: s.ways.clone(),
            // As stored: 1e-7° fixed point, so a reload gives the same values.
            geometry: decode_geometry(&encode_geometry(&s.geometry)).unwrap_or_default(),
        })
    }

    pub fn get_section(&self, id: i64) -> Result<Option<Section>, CoreError> {
        let row = self
            .conn
            .query_row(
                "SELECT id, rider_id, name, rating, direction, source, status, created_at,
                    updated_at, geometry FROM sections WHERE id = ?1",
                [id],
                read_row,
            )
            .optional()
            .map_err(db_err)?;
        row.map(|r| self.finish(r)).transpose()
    }

    /// All sections, or those whose bounding box overlaps `area`
    /// (south-west, north-east), oldest first.
    pub fn list_sections(&self, area: Option<(LatLon, LatLon)>) -> Result<Vec<Section>, CoreError> {
        let (sw, ne) = match area {
            Some((sw, ne)) => {
                sw.validate()?;
                ne.validate()?;
                (e7(sw), e7(ne))
            }
            None => ((i32::MIN, i32::MIN), (i32::MAX, i32::MAX)),
        };
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, rider_id, name, rating, direction, source, status, created_at,
                    updated_at, geometry FROM sections
                 WHERE max_lat >= ?1 AND min_lat <= ?3 AND max_lon >= ?2 AND min_lon <= ?4
                 ORDER BY id",
            )
            .map_err(db_err)?;
        let rows = stmt
            .query_map(params![sw.0, sw.1, ne.0, ne.1], read_row)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        rows.into_iter().map(|r| self.finish(r)).collect()
    }

    /// Changes name, rating or direction; returns the updated section, or
    /// `None` if there is no section with that id.
    pub fn update_section(
        &mut self,
        id: i64,
        update: &SectionUpdate,
        now: i64,
    ) -> Result<Option<Section>, CoreError> {
        if let Some(name) = &update.name {
            validate_name(name)?;
        }
        let changed = self
            .conn
            .execute(
                "UPDATE sections SET
                    name = coalesce(?2, name),
                    rating = coalesce(?3, rating),
                    direction = coalesce(?4, direction),
                    updated_at = ?5
                 WHERE id = ?1",
                params![
                    id,
                    update.name,
                    update.rating.map(|r| r as i64),
                    update.direction.map(|d| d as i64),
                    now
                ],
            )
            .map_err(db_err)?;
        if changed == 0 {
            return Ok(None);
        }
        self.get_section(id)
    }

    /// Flags a section's match state (e.g. after a region update).
    pub fn set_status(&mut self, id: i64, status: Status) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute(
                "UPDATE sections SET status = ?2 WHERE id = ?1",
                params![id, status as i64],
            )
            .map_err(db_err)?;
        Ok(changed > 0)
    }

    /// Deletes a section; `false` if it did not exist.
    pub fn delete_section(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM sections WHERE id = ?1", [id])
            .map_err(db_err)?;
        Ok(changed > 0)
    }

    /// Adds the way spans to a row read from `sections` and validates it.
    fn finish(&self, r: RawSection) -> Result<Section, CoreError> {
        let corrupt = |what: &str| CoreError::Storage(format!("section {}: invalid {what}", r.id));
        let mut stmt = self
            .conn
            .prepare("SELECT way_id, from_idx, to_idx FROM section_ways WHERE section_id = ?1 ORDER BY seq")
            .map_err(db_err)?;
        let ways = stmt
            .query_map([r.id], |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                ))
            })
            .map_err(db_err)?
            .map(|w| {
                let (way_id, from, to) = w.map_err(db_err)?;
                Ok(WaySpan {
                    way_id,
                    from_idx: u32::try_from(from).map_err(|_| corrupt("way span"))?,
                    to_idx: u32::try_from(to).map_err(|_| corrupt("way span"))?,
                })
            })
            .collect::<Result<Vec<_>, CoreError>>()?;
        Ok(Section {
            id: r.id,
            rider_id: r.rider_id,
            name: r.name,
            rating: Rating::from_i64(r.rating).ok_or_else(|| corrupt("rating"))?,
            direction: Direction::from_i64(r.direction).ok_or_else(|| corrupt("direction"))?,
            source: Source::from_i64(r.source).ok_or_else(|| corrupt("source"))?,
            status: Status::from_i64(r.status).ok_or_else(|| corrupt("status"))?,
            created_at: r.created_at,
            updated_at: r.updated_at,
            ways,
            geometry: decode_geometry(&r.geometry).ok_or_else(|| corrupt("geometry"))?,
        })
    }
}

/// A `sections` row before validation.
struct RawSection {
    id: i64,
    rider_id: String,
    name: String,
    rating: i64,
    direction: i64,
    source: i64,
    status: i64,
    created_at: i64,
    updated_at: i64,
    geometry: Vec<u8>,
}

fn read_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<RawSection> {
    Ok(RawSection {
        id: row.get(0)?,
        rider_id: row.get(1)?,
        name: row.get(2)?,
        rating: row.get(3)?,
        direction: row.get(4)?,
        source: row.get(5)?,
        status: row.get(6)?,
        created_at: row.get(7)?,
        updated_at: row.get(8)?,
        geometry: row.get(9)?,
    })
}

fn insert_ways(tx: &Transaction<'_>, section_id: i64, ways: &[WaySpan]) -> Result<(), CoreError> {
    let mut stmt = tx
        .prepare(
            "INSERT INTO section_ways (section_id, seq, way_id, from_idx, to_idx)
             VALUES (?1, ?2, ?3, ?4, ?5)",
        )
        .map_err(db_err)?;
    for (seq, w) in ways.iter().enumerate() {
        stmt.execute(params![
            section_id, seq as i64, w.way_id, w.from_idx, w.to_idx
        ])
        .map_err(db_err)?;
    }
    Ok(())
}

fn user_version(conn: &Connection) -> Result<i64, CoreError> {
    conn.query_row("PRAGMA user_version", [], |r| r.get(0))
        .map_err(db_err)
}

/// Runs the migrations the database has not seen yet, each in its own
/// transaction together with the version bump.
fn migrate(conn: &mut Connection, migrations: &[&str]) -> Result<(), CoreError> {
    let current = user_version(conn)?;
    let target = migrations.len() as i64;
    if current > target {
        return Err(CoreError::Storage(format!(
            "the database is from a newer version of the app (schema {current}, this app knows {target})"
        )));
    }
    if current < 0 {
        return Err(CoreError::Storage(format!(
            "invalid schema version {current}"
        )));
    }
    for (i, sql) in migrations.iter().enumerate().skip(current as usize) {
        let tx = conn.transaction().map_err(db_err)?;
        tx.execute_batch(sql).map_err(db_err)?;
        // PRAGMA takes no parameters; the value is our own integer.
        tx.execute_batch(&format!("PRAGMA user_version = {}", i + 1))
            .map_err(db_err)?;
        tx.commit().map_err(db_err)?;
    }
    Ok(())
}

fn e7(p: LatLon) -> (i32, i32) {
    (
        (p.lat * COORD_SCALE).round() as i32,
        (p.lon * COORD_SCALE).round() as i32,
    )
}

fn bounds(points: &[LatLon]) -> ((i32, i32), (i32, i32)) {
    let mut min = (i32::MAX, i32::MAX);
    let mut max = (i32::MIN, i32::MIN);
    for &p in points {
        let (lat, lon) = e7(p);
        min = (min.0.min(lat), min.1.min(lon));
        max = (max.0.max(lat), max.1.max(lon));
    }
    (min, max)
}

/// Geometry as little-endian i32 pairs in 1e-7 degrees, like the region file.
fn encode_geometry(points: &[LatLon]) -> Vec<u8> {
    let mut out = Vec::with_capacity(points.len() * 8);
    for &p in points {
        let (lat, lon) = e7(p);
        out.extend_from_slice(&lat.to_le_bytes());
        out.extend_from_slice(&lon.to_le_bytes());
    }
    out
}

/// Inverse of [`encode_geometry`]; `None` unless the bytes are whole,
/// in-range points, at least two of them.
fn decode_geometry(bytes: &[u8]) -> Option<Vec<LatLon>> {
    let (pairs, rest) = bytes.as_chunks::<8>();
    if !rest.is_empty() || pairs.len() < 2 || pairs.len() > crate::section::MAX_SECTION_POINTS {
        return None;
    }
    pairs
        .iter()
        .map(|b| {
            let lat = i32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            let lon = i32::from_le_bytes([b[4], b[5], b[6], b[7]]);
            LatLon::new(f64::from(lat) / COORD_SCALE, f64::from(lon) / COORD_SCALE).ok()
        })
        .collect()
}

#[cfg(test)]
mod tests;
