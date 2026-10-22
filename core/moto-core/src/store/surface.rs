// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Surface corrections in the store (ADR-0018): saved, listed, looked up
//! near a point, deleted, and kept up to date with the region
//! ([`crate::rematch`]). Rows read back are validated like sections, so a
//! damaged or tampered row gives a storage error, never a panic.

use rusqlite::{OptionalExtension, Transaction, params};

use super::{Store, bounds, db_err, decode_geometry, e7, encode_geometry};
use crate::geo::distance_to_line;
use crate::section::{Status, WaySpan};
use crate::surface::{
    CorrectionAt, MAX_SURFACE_CORRECTIONS, NewSurfaceCorrection, SurfaceCorrection, SurfaceSaved,
    correction_surface, covers, validate_line,
};
use crate::{CoreError, LatLon};

const SELECT: &str = "SELECT id, rider_id, surface, status, created_at, updated_at, geometry
    FROM surface_corrections";

/// A `surface_corrections` row before validation.
pub(super) struct RawCorrection {
    id: i64,
    rider_id: String,
    surface: i64,
    status: i64,
    created_at: i64,
    updated_at: i64,
    geometry: Vec<u8>,
}

fn read_raw(row: &rusqlite::Row<'_>) -> rusqlite::Result<RawCorrection> {
    Ok(RawCorrection {
        id: row.get(0)?,
        rider_id: row.get(1)?,
        surface: row.get(2)?,
        status: row.get(3)?,
        created_at: row.get(4)?,
        updated_at: row.get(5)?,
        geometry: row.get(6)?,
    })
}

/// Metres to degrees of latitude, generously (a little more than the
/// real value, so a box widened by it never misses).
const M_PER_DEG_LAT: f64 = 111_000.0;

/// `p`'s box widened by `reach_m` on every side, south-west and
/// north-east, in 1e-7°.
pub(super) fn reach_box(p: LatLon, reach_m: f64) -> ((i32, i32), (i32, i32)) {
    let dlat = reach_m / M_PER_DEG_LAT;
    // Far north a degree of longitude is short; past 89° take all of it.
    let cos = p.lat.to_radians().cos();
    let dlon = if cos > 0.017 { dlat / cos } else { 360.0 };
    let clamp = |v: f64, lim: f64| v.clamp(-lim, lim);
    let sw = LatLon {
        lat: clamp(p.lat - dlat, 90.0),
        lon: clamp(p.lon - dlon, 180.0),
    };
    let ne = LatLon {
        lat: clamp(p.lat + dlat, 90.0),
        lon: clamp(p.lon + dlon, 180.0),
    };
    (e7(sw), e7(ne))
}

/// The way spans of correction `id`, validated.
fn read_ways(conn: &rusqlite::Connection, id: i64) -> Result<Vec<WaySpan>, CoreError> {
    let corrupt = || CoreError::Storage(format!("surface correction {id}: invalid way span"));
    let mut stmt = conn
        .prepare(
            "SELECT way_id, from_idx, to_idx FROM surface_correction_ways
             WHERE correction_id = ?1 ORDER BY seq",
        )
        .map_err(db_err)?;
    stmt.query_map([id], |row| {
        Ok((
            row.get::<_, i64>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, i64>(2)?,
        ))
    })
    .map_err(db_err)?
    .map(|w| {
        let (way_id, from, to) = w.map_err(db_err)?;
        if way_id <= 0 {
            return Err(corrupt());
        }
        Ok(WaySpan {
            way_id,
            from_idx: u32::try_from(from).map_err(|_| corrupt())?,
            to_idx: u32::try_from(to).map_err(|_| corrupt())?,
        })
    })
    .collect()
}

/// Validates a row and adds its way spans.
fn finish(conn: &rusqlite::Connection, r: RawCorrection) -> Result<SurfaceCorrection, CoreError> {
    let corrupt =
        |what: &str| CoreError::Storage(format!("surface correction {}: invalid {what}", r.id));
    let status = match Status::from_i64(r.status) {
        Some(s @ (Status::Ok | Status::NeedsRematch)) => s,
        _ => return Err(corrupt("status")),
    };
    Ok(SurfaceCorrection {
        id: r.id,
        surface: correction_surface(r.surface).ok_or_else(|| corrupt("surface"))?,
        status,
        created_at: r.created_at,
        updated_at: r.updated_at,
        ways: read_ways(conn, r.id)?,
        geometry: decode_geometry(&r.geometry).ok_or_else(|| corrupt("geometry"))?,
        rider_id: r.rider_id,
    })
}

fn insert_correction_ways(
    tx: &Transaction<'_>,
    id: i64,
    ways: &[WaySpan],
) -> Result<(), CoreError> {
    let mut stmt = tx
        .prepare(
            "INSERT INTO surface_correction_ways (correction_id, seq, way_id, from_idx, to_idx)
             VALUES (?1, ?2, ?3, ?4, ?5)",
        )
        .map_err(db_err)?;
    for (seq, w) in ways.iter().enumerate() {
        stmt.execute(params![id, seq as i64, w.way_id, w.from_idx, w.to_idx])
            .map_err(db_err)?;
    }
    Ok(())
}

/// Inserts a validated correction with its way spans; returns its id.
pub(super) fn insert_correction(
    tx: &Transaction<'_>,
    c: &NewSurfaceCorrection,
    created_at: i64,
    updated_at: i64,
    status: Status,
) -> Result<i64, CoreError> {
    let (min, max) = bounds(&c.geometry);
    tx.execute(
        "INSERT INTO surface_corrections (rider_id, surface, status, created_at, updated_at,
            min_lat, min_lon, max_lat, max_lon, geometry)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
        params![
            c.rider_id,
            c.surface as i64,
            status as i64,
            created_at,
            updated_at,
            min.0,
            min.1,
            max.0,
            max.1,
            encode_geometry(&c.geometry)
        ],
    )
    .map_err(db_err)?;
    let id = tx.last_insert_rowid();
    insert_correction_ways(tx, id, &c.ways)?;
    Ok(id)
}

/// How many corrections there are.
fn count(conn: &rusqlite::Connection) -> Result<usize, CoreError> {
    let n: i64 = conn
        .query_row("SELECT count(*) FROM surface_corrections", [], |r| r.get(0))
        .map_err(db_err)?;
    Ok(usize::try_from(n).unwrap_or(usize::MAX))
}

/// The `ok` corrections sharing a way with `ways` whose every span lies
/// within one of `ways`.
fn covered_by(conn: &rusqlite::Connection, ways: &[WaySpan]) -> Result<Vec<i64>, CoreError> {
    let mut candidates = std::collections::BTreeSet::new();
    {
        let mut stmt = conn
            .prepare(
                "SELECT DISTINCT w.correction_id FROM surface_correction_ways w
                 JOIN surface_corrections c ON c.id = w.correction_id
                 WHERE w.way_id = ?1 AND c.status = ?2",
            )
            .map_err(db_err)?;
        let way_ids: std::collections::BTreeSet<i64> = ways.iter().map(|w| w.way_id).collect();
        for way in way_ids {
            let ids = stmt
                .query_map(params![way, Status::Ok as i64], |r| r.get::<_, i64>(0))
                .map_err(db_err)?
                .collect::<Result<Vec<_>, _>>()
                .map_err(db_err)?;
            candidates.extend(ids);
        }
    }
    let mut out = Vec::new();
    for id in candidates {
        if covers(ways, &read_ways(conn, id)?) {
            out.push(id);
        }
    }
    Ok(out)
}

impl Store {
    /// Saves a correction, in one transaction: first deletes `replaces`
    /// (the correction being edited; a missing id is no error) and every
    /// older `ok` correction the new one fully covers, then inserts it as
    /// `ok`, made and changed at `now`. Refused when the store already
    /// holds [`MAX_SURFACE_CORRECTIONS`].
    pub fn add_surface_correction(
        &mut self,
        c: &NewSurfaceCorrection,
        replaces: Option<i64>,
        now: i64,
    ) -> Result<SurfaceSaved, CoreError> {
        c.validate()?;
        let tx = self.conn.transaction().map_err(db_err)?;
        let mut replaced = Vec::new();
        if let Some(id) = replaces
            && tx
                .execute("DELETE FROM surface_corrections WHERE id = ?1", [id])
                .map_err(db_err)?
                > 0
        {
            replaced.push(id);
        }
        for id in covered_by(&tx, &c.ways)? {
            tx.execute("DELETE FROM surface_corrections WHERE id = ?1", [id])
                .map_err(db_err)?;
            replaced.push(id);
        }
        if count(&tx)? >= MAX_SURFACE_CORRECTIONS {
            return Err(CoreError::InvalidArgument(format!(
                "at most {MAX_SURFACE_CORRECTIONS} surface corrections"
            )));
        }
        let id = insert_correction(&tx, c, now, now, Status::Ok)?;
        tx.commit().map_err(db_err)?;
        replaced.sort_unstable();
        Ok(SurfaceSaved {
            correction: SurfaceCorrection {
                id,
                rider_id: c.rider_id.clone(),
                surface: c.surface,
                status: Status::Ok,
                created_at: now,
                updated_at: now,
                ways: c.ways.clone(),
                // As stored: 1e-7° fixed point.
                geometry: decode_geometry(&encode_geometry(&c.geometry)).unwrap_or_default(),
            },
            replaced,
        })
    }

    /// Every correction, whatever its status, oldest first.
    pub fn surface_corrections(&self) -> Result<Vec<SurfaceCorrection>, CoreError> {
        self.corrections_where("", [])
    }

    /// Corrections with `status`, oldest first.
    pub fn surface_corrections_with_status(
        &self,
        status: Status,
    ) -> Result<Vec<SurfaceCorrection>, CoreError> {
        self.corrections_where(" WHERE status = ?1", [status as i64])
    }

    fn corrections_where<P: rusqlite::Params>(
        &self,
        filter: &str,
        p: P,
    ) -> Result<Vec<SurfaceCorrection>, CoreError> {
        // `filter` is one of this file's own fixed strings.
        let sql = format!("{SELECT}{filter} ORDER BY id");
        let mut stmt = self.conn.prepare(&sql).map_err(db_err)?;
        let rows = stmt
            .query_map(p, read_raw)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        rows.into_iter().map(|r| finish(&self.conn, r)).collect()
    }

    pub fn surface_correction(&self, id: i64) -> Result<Option<SurfaceCorrection>, CoreError> {
        let row = self
            .conn
            .query_row(&format!("{SELECT} WHERE id = ?1"), [id], read_raw)
            .optional()
            .map_err(db_err)?;
        row.map(|r| finish(&self.conn, r)).transpose()
    }

    /// Deletes a correction (its way spans go with it); `false` if there
    /// was none.
    pub fn delete_surface_correction(&mut self, id: i64) -> Result<bool, CoreError> {
        let n = self
            .conn
            .execute("DELETE FROM surface_corrections WHERE id = ?1", [id])
            .map_err(db_err)?;
        Ok(n > 0)
    }

    /// What the rider set for the road at `point`: an `ok` favourite
    /// section with its own surface whose line passes within `reach_m`
    /// (the most recently changed wins), else the most recently changed
    /// `ok` standalone correction there.
    pub fn surface_correction_at(
        &self,
        point: LatLon,
        reach_m: f64,
    ) -> Result<Option<CorrectionAt>, CoreError> {
        point.validate()?;
        if !(reach_m.is_finite() && reach_m >= 0.0) {
            return Err(CoreError::InvalidArgument("reach must be 0 or more".into()));
        }
        if let Some(found) = self.favourite_surface_at(point, reach_m)? {
            return Ok(Some(found));
        }
        let (sw, ne) = reach_box(point, reach_m);
        let mut stmt = self
            .conn
            .prepare(&format!(
                "{SELECT} WHERE status = ?5
                    AND max_lat >= ?1 AND min_lat <= ?3 AND max_lon >= ?2 AND min_lon <= ?4
                 ORDER BY updated_at DESC, id DESC"
            ))
            .map_err(db_err)?;
        let rows = stmt
            .query_map(params![sw.0, sw.1, ne.0, ne.1, Status::Ok as i64], read_raw)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        for r in rows {
            let c = finish(&self.conn, r)?;
            if distance_to_line(point, &c.geometry) <= reach_m {
                return Ok(Some(CorrectionAt::Standalone(c)));
            }
        }
        Ok(None)
    }

    /// The `ok` favourite with its own surface nearest in time (most
    /// recently changed) whose line passes within `reach_m` of `point`.
    fn favourite_surface_at(
        &self,
        point: LatLon,
        reach_m: f64,
    ) -> Result<Option<CorrectionAt>, CoreError> {
        let (sw, ne) = reach_box(point, reach_m);
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, surface, geometry FROM sections
                 WHERE status = ?5 AND surface IS NOT NULL
                    AND max_lat >= ?1 AND min_lat <= ?3 AND max_lon >= ?2 AND min_lon <= ?4
                 ORDER BY updated_at DESC, id DESC",
            )
            .map_err(db_err)?;
        let rows = stmt
            .query_map(params![sw.0, sw.1, ne.0, ne.1, Status::Ok as i64], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, Vec<u8>>(2)?,
                ))
            })
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        for (id, code, blob) in rows {
            let corrupt = |what: &str| CoreError::Storage(format!("section {id}: invalid {what}"));
            let surface = correction_surface(code).ok_or_else(|| corrupt("surface"))?;
            let line = decode_geometry(&blob).ok_or_else(|| corrupt("geometry"))?;
            if distance_to_line(point, &line) <= reach_m {
                return Ok(Some(CorrectionAt::Favourite {
                    section_id: id,
                    surface,
                }));
            }
        }
        Ok(None)
    }

    /// Flags every correction `needs_rematch`.
    pub fn flag_corrections_for_rematch(&mut self) -> Result<(), CoreError> {
        self.conn
            .execute(
                "UPDATE surface_corrections SET status = ?1",
                [Status::NeedsRematch as i64],
            )
            .map_err(db_err)?;
        Ok(())
    }

    /// Saves a correction's fit to a new region: its way spans and line,
    /// status `ok`, in one transaction; its surface and times stay.
    /// `false` if there is no such correction.
    pub fn save_correction_match(
        &mut self,
        id: i64,
        ways: &[WaySpan],
        geometry: &[LatLon],
    ) -> Result<bool, CoreError> {
        validate_line(ways, geometry)?;
        let (min, max) = bounds(geometry);
        let tx = self.conn.transaction().map_err(db_err)?;
        let changed = tx
            .execute(
                "UPDATE surface_corrections SET status = ?2, min_lat = ?3, min_lon = ?4,
                    max_lat = ?5, max_lon = ?6, geometry = ?7 WHERE id = ?1",
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
        tx.execute(
            "DELETE FROM surface_correction_ways WHERE correction_id = ?1",
            [id],
        )
        .map_err(db_err)?;
        insert_correction_ways(&tx, id, ways)?;
        tx.commit().map_err(db_err)?;
        Ok(true)
    }
}

#[cfg(test)]
mod tests;
