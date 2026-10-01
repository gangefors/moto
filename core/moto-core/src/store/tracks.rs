// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Recorded rides in the rider database: started when recording begins,
//! filled in batches while riding, finished at the end (ADR-0006).

use rusqlite::{OptionalExtension, params};

use super::{Store, db_err, e7};
use crate::region::format::COORD_SCALE;
use crate::section::{LOCAL_RIDER, validate_name};
use crate::track::{
    Appended, MAX_BATCH_POINTS, MAX_TRACK_POINTS, Track, TrackPoint, ridden_distance_m,
};
use crate::{CoreError, LatLon};

/// How far apart in time two fixes may be and still count as the same
/// fix when rides are compared: GPX files often keep whole seconds.
pub const SAME_FIX_MS: i64 = 1000;

/// How far apart two fixes may be, in 1e-7 degrees (about a metre), and
/// still count as the same fix when rides are compared: other apps round
/// positions.
pub const SAME_FIX_E7: u32 = 100;

const TRACK_COLUMNS: &str = "id, rider_id, name, started_at, ended_at, point_count, distance_m";

type TrackRow = (i64, String, Option<String>, i64, Option<i64>, i64, f64);

fn read_track(row: &rusqlite::Row<'_>) -> rusqlite::Result<TrackRow> {
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

fn to_track(r: TrackRow) -> Result<Track, CoreError> {
    let (id, rider_id, name, started_at, ended_at, count, distance_m) = r;
    let corrupt = |what: &str| CoreError::Storage(format!("track {id}: invalid {what}"));
    if !(distance_m.is_finite() && distance_m >= 0.0) {
        return Err(corrupt("distance"));
    }
    if let Some(name) = &name {
        validate_name(name).map_err(|_| corrupt("name"))?;
    }
    Ok(Track {
        id,
        rider_id,
        name,
        started_at,
        ended_at,
        point_count: u64::try_from(count).map_err(|_| corrupt("point count"))?,
        distance_m,
    })
}

/// Stores `points` on track `id` from sequence number `first` on.
fn insert_points<'a>(
    tx: &rusqlite::Transaction<'_>,
    id: i64,
    first: usize,
    points: impl Iterator<Item = &'a TrackPoint>,
) -> Result<(), CoreError> {
    let mut stmt = tx
        .prepare(
            "INSERT INTO track_points
                (track_id, seq, time_ms, lat, lon, accuracy_m, speed_mps, bearing_deg)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        )
        .map_err(db_err)?;
    for (i, p) in points.enumerate() {
        let (lat, lon) = e7(p.position);
        stmt.execute(params![
            id,
            (first + i) as i64,
            p.time_ms,
            lat,
            lon,
            p.accuracy_m,
            p.speed_mps,
            p.bearing_deg
        ])
        .map_err(db_err)?;
    }
    Ok(())
}

fn unknown(id: i64) -> CoreError {
    CoreError::InvalidArgument(format!("no track {id}"))
}

impl Store {
    /// Starts a new, empty track for the local rider; `now` is seconds
    /// since the Unix epoch.
    pub fn start_track(&mut self, now: i64) -> Result<Track, CoreError> {
        self.conn
            .execute(
                "INSERT INTO tracks (rider_id, started_at) VALUES (?1, ?2)",
                params![LOCAL_RIDER, now],
            )
            .map_err(db_err)?;
        let id = self.conn.last_insert_rowid();
        self.get_track(id)?.ok_or_else(|| unknown(id))
    }

    /// Appends GPS points to a track that is still recording, in one
    /// transaction. Points not newer than the last stored one are skipped,
    /// so sending a batch again (after a crash) is harmless. The whole batch
    /// is refused if any point is invalid or the track would grow past
    /// [`MAX_TRACK_POINTS`].
    pub fn append_track_points(
        &mut self,
        id: i64,
        points: &[TrackPoint],
    ) -> Result<Appended, CoreError> {
        if points.len() > MAX_BATCH_POINTS {
            return Err(CoreError::InvalidArgument(format!(
                "at most {MAX_BATCH_POINTS} points per batch, got {}",
                points.len()
            )));
        }
        for p in points {
            p.validate()?;
        }
        let tx = self.conn.transaction().map_err(db_err)?;
        let (ended, count, last_time): (Option<i64>, i64, Option<i64>) = tx
            .query_row(
                "SELECT ended_at, point_count, last_time_ms FROM tracks WHERE id = ?1",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()
            .map_err(db_err)?
            .ok_or_else(|| unknown(id))?;
        if ended.is_some() {
            return Err(CoreError::InvalidArgument(format!(
                "track {id} is finished"
            )));
        }
        let mut last = last_time.unwrap_or(i64::MIN);
        let fresh: Vec<&TrackPoint> = points
            .iter()
            .filter(|p| {
                let newer = p.time_ms > last;
                if newer {
                    last = p.time_ms;
                }
                newer
            })
            .collect();
        let count = usize::try_from(count)
            .map_err(|_| CoreError::Storage(format!("track {id}: invalid point count")))?;
        if count + fresh.len() > MAX_TRACK_POINTS {
            return Err(CoreError::InvalidArgument(format!(
                "a track may have at most {MAX_TRACK_POINTS} points"
            )));
        }
        insert_points(&tx, id, count, fresh.iter().copied())?;
        let total = count + fresh.len();
        if let Some(p) = fresh.last() {
            tx.execute(
                "UPDATE tracks SET point_count = ?2, last_time_ms = ?3 WHERE id = ?1",
                params![id, total as i64, p.time_ms],
            )
            .map_err(db_err)?;
        }
        tx.commit().map_err(db_err)?;
        Ok(Appended {
            added: fresh.len() as u64,
            point_count: total as u64,
        })
    }

    /// Stores a finished ride from elsewhere (an imported GPX file) in one
    /// transaction: started and ended at its first and last fix. The
    /// points must be valid, at least two and at most
    /// [`MAX_TRACK_POINTS`], and strictly later one after another.
    pub fn import_track(&mut self, points: &[TrackPoint]) -> Result<Track, CoreError> {
        let bad = |why: &str| Err(CoreError::InvalidArgument(format!("imported ride: {why}")));
        let (Some(first), Some(last)) = (points.first(), points.last()) else {
            return bad("no points");
        };
        if points.len() < 2 {
            return bad("fewer than two points");
        }
        if points.len() > MAX_TRACK_POINTS {
            return bad("too many points");
        }
        for p in points {
            p.validate()?;
        }
        if points.windows(2).any(|w| w[1].time_ms <= w[0].time_ms) {
            return bad("fix times must increase");
        }
        let tx = self.conn.transaction().map_err(db_err)?;
        tx.execute(
            "INSERT INTO tracks
                (rider_id, started_at, ended_at, point_count, last_time_ms, distance_m)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                LOCAL_RIDER,
                first.time_ms.div_euclid(1000),
                last.time_ms.div_euclid(1000),
                points.len() as i64,
                last.time_ms,
                ridden_distance_m(points)
            ],
        )
        .map_err(db_err)?;
        let id = tx.last_insert_rowid();
        insert_points(&tx, id, 0, points.iter())?;
        tx.commit().map_err(db_err)?;
        self.get_track(id)?.ok_or_else(|| unknown(id))
    }

    /// The finished ride with the same fixes as `points`, if there is one:
    /// as many fixes, and each within [`SAME_FIX_MS`] and [`SAME_FIX_E7`]
    /// of its twin, so a file imported twice, or a ride exported and
    /// imported again (times and positions rounded), is found. Only rides
    /// with as many fixes and the same last time are read (a recorded
    /// ride starts when Record is pressed, before its first fix).
    pub fn same_track(&self, points: &[TrackPoint]) -> Result<Option<i64>, CoreError> {
        let Some(last) = points.last() else {
            return Ok(None);
        };
        let count = i64::try_from(points.len()).unwrap_or(i64::MAX);
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id FROM tracks
                 WHERE ended_at IS NOT NULL AND point_count = ?1
                   AND last_time_ms BETWEEN ?2 - ?3 AND ?2 + ?3
                 ORDER BY id",
            )
            .map_err(db_err)?;
        let ids = stmt
            .query_map(params![count, last.time_ms, SAME_FIX_MS], |r| {
                r.get::<_, i64>(0)
            })
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        for id in ids {
            let Some(theirs) = self.track_points(id)? else {
                continue;
            };
            let same = theirs.len() == points.len()
                && theirs.iter().zip(points).all(|(a, b)| {
                    let ((alat, alon), (blat, blon)) = (e7(a.position), e7(b.position));
                    a.time_ms.abs_diff(b.time_ms) <= SAME_FIX_MS.unsigned_abs()
                        && alat.abs_diff(blat) <= SAME_FIX_E7
                        && alon.abs_diff(blon) <= SAME_FIX_E7
                });
            if same {
                return Ok(Some(id));
            }
        }
        Ok(None)
    }

    /// As [`Self::import_track`], unless the same ride is already saved
    /// ([`Self::same_track`]): then `None`, and nothing is added.
    pub fn import_track_once(&mut self, points: &[TrackPoint]) -> Result<Option<Track>, CoreError> {
        if self.same_track(points)?.is_some() {
            return Ok(None);
        }
        self.import_track(points).map(Some)
    }

    /// Ends a track and counts its length. Finishing a finished track
    /// changes nothing. `None` if there is no such track.
    pub fn finish_track(&mut self, id: i64, now: i64) -> Result<Option<Track>, CoreError> {
        let Some(track) = self.get_track(id)? else {
            return Ok(None);
        };
        if track.ended_at.is_some() {
            return Ok(Some(track));
        }
        let points = self.track_points(id)?.unwrap_or_default();
        self.conn
            .execute(
                "UPDATE tracks SET ended_at = ?2, distance_m = ?3 WHERE id = ?1",
                params![id, now, ridden_distance_m(&points)],
            )
            .map_err(db_err)?;
        self.get_track(id)
    }

    pub fn get_track(&self, id: i64) -> Result<Option<Track>, CoreError> {
        self.conn
            .query_row(
                &format!("SELECT {TRACK_COLUMNS} FROM tracks WHERE id = ?1"),
                [id],
                read_track,
            )
            .optional()
            .map_err(db_err)?
            .map(to_track)
            .transpose()
    }

    /// All tracks, newest first.
    pub fn list_tracks(&self) -> Result<Vec<Track>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(&format!(
                "SELECT {TRACK_COLUMNS} FROM tracks ORDER BY id DESC"
            ))
            .map_err(db_err)?;
        let rows = stmt
            .query_map([], read_track)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        rows.into_iter().map(to_track).collect()
    }

    /// How often each saved section was ridden, and when last, on the
    /// finished rides (see [`crate::ridden`]), in the order of
    /// [`Self::list_sections`]. Reads every ride's points: call off the
    /// main thread.
    pub fn ridden_stats(&self) -> Result<Vec<crate::ridden::Ridden>, CoreError> {
        let sections = self.list_sections(None)?;
        let mut lines: Vec<(i64, Vec<crate::LatLon>)> = Vec::new();
        for t in self
            .list_tracks()?
            .into_iter()
            .filter(|t| t.ended_at.is_some())
        {
            if let Some(points) = self.track_points(t.id)? {
                lines.push((
                    t.started_at,
                    points.into_iter().map(|p| p.position).collect(),
                ));
            }
        }
        let rides: Vec<crate::ridden::RideLine<'_>> = lines
            .iter()
            .map(|(started_at, line)| crate::ridden::RideLine {
                started_at: *started_at,
                line,
            })
            .collect();
        Ok(crate::ridden::ridden(&sections, &rides))
    }

    /// A track's points in recording order; `None` if there is no such
    /// track. Every point read back is validated.
    pub fn track_points(&self, id: i64) -> Result<Option<Vec<TrackPoint>>, CoreError> {
        if self.get_track(id)?.is_none() {
            return Ok(None);
        }
        let mut stmt = self
            .conn
            .prepare(
                "SELECT time_ms, lat, lon, accuracy_m, speed_mps, bearing_deg
                 FROM track_points WHERE track_id = ?1 ORDER BY seq",
            )
            .map_err(db_err)?;
        let rows = stmt
            .query_map([id], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, Option<f64>>(3)?,
                    r.get::<_, Option<f64>>(4)?,
                    r.get::<_, Option<f64>>(5)?,
                ))
            })
            .map_err(db_err)?;
        let mut points = Vec::new();
        for row in rows {
            let (time_ms, lat, lon, accuracy_m, speed_mps, bearing_deg) = row.map_err(db_err)?;
            let corrupt = || CoreError::Storage(format!("track {id}: invalid point"));
            let coord = |v: i64| {
                i32::try_from(v)
                    .map(|v| f64::from(v) / COORD_SCALE)
                    .map_err(|_| corrupt())
            };
            let p = TrackPoint {
                time_ms,
                position: LatLon {
                    lat: coord(lat)?,
                    lon: coord(lon)?,
                },
                accuracy_m,
                speed_mps,
                bearing_deg,
            };
            p.validate().map_err(|_| corrupt())?;
            points.push(p);
        }
        Ok(Some(points))
    }

    /// Names a ride; an empty or blank `name` takes the name away again,
    /// so the ride shows its start time. `false` if there is no such ride.
    pub fn rename_track(&mut self, id: i64, name: &str) -> Result<bool, CoreError> {
        let name = name.trim();
        validate_name(name)?;
        let changed = self
            .conn
            .execute(
                "UPDATE tracks SET name = ?2 WHERE id = ?1",
                params![id, (!name.is_empty()).then_some(name)],
            )
            .map_err(db_err)?;
        Ok(changed > 0)
    }

    /// Deletes a track and its points; `false` if it did not exist.
    pub fn delete_track(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM tracks WHERE id = ?1", [id])
            .map_err(db_err)?;
        Ok(changed > 0)
    }
}

#[cfg(test)]
mod tests;
