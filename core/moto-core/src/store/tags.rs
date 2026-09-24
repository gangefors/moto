// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Quick-tags in the rider database (PRD R3, ADR-0006).

use rusqlite::{OptionalExtension, params};

use super::{Store, db_err, e7};
use crate::region::format::COORD_SCALE;
use crate::section::LOCAL_RIDER;
use crate::tag::{NewTag, Tag, TagStatus};
use crate::{CoreError, LatLon};

const COLUMNS: &str = "id, rider_id, time_ms, lat, lon, heading_deg, speed_mps, track_id, status";

type Row = (
    i64,
    String,
    i64,
    i64,
    i64,
    Option<f64>,
    Option<f64>,
    Option<i64>,
    i64,
);

fn read(r: &rusqlite::Row<'_>) -> rusqlite::Result<Row> {
    Ok((
        r.get(0)?,
        r.get(1)?,
        r.get(2)?,
        r.get(3)?,
        r.get(4)?,
        r.get(5)?,
        r.get(6)?,
        r.get(7)?,
        r.get(8)?,
    ))
}

/// A stored row as a tag, validated like a new one.
fn to_tag(r: Row) -> Result<Tag, CoreError> {
    let (id, rider_id, time_ms, lat, lon, heading_deg, speed_mps, track_id, status) = r;
    let corrupt = || CoreError::Storage(format!("tag {id}: invalid data"));
    let coord = |v: i64| {
        i32::try_from(v)
            .map(|v| f64::from(v) / COORD_SCALE)
            .map_err(|_| corrupt())
    };
    let tag = Tag {
        id,
        rider_id,
        time_ms,
        position: LatLon {
            lat: coord(lat)?,
            lon: coord(lon)?,
        },
        heading_deg,
        speed_mps,
        track_id,
        status: TagStatus::from_i64(status).ok_or_else(corrupt)?,
    };
    NewTag {
        time_ms,
        position: tag.position,
        heading_deg,
        speed_mps,
        track_id,
    }
    .validate()
    .map_err(|_| corrupt())?;
    Ok(tag)
}

impl Store {
    /// Saves a tag for the local rider, pending review. The ride it names
    /// must exist.
    pub fn add_tag(&mut self, t: &NewTag) -> Result<Tag, CoreError> {
        t.validate()?;
        if let Some(track) = t.track_id
            && self.get_track(track)?.is_none()
        {
            return Err(CoreError::InvalidArgument(format!("no track {track}")));
        }
        let (lat, lon) = e7(t.position);
        self.conn
            .execute(
                "INSERT INTO tags (rider_id, time_ms, lat, lon, heading_deg, speed_mps, track_id)
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
        let id = self.conn.last_insert_rowid();
        self.get_tag(id)?
            .ok_or_else(|| CoreError::Storage(format!("tag {id} vanished")))
    }

    pub fn get_tag(&self, id: i64) -> Result<Option<Tag>, CoreError> {
        self.conn
            .query_row(
                &format!("SELECT {COLUMNS} FROM tags WHERE id = ?1"),
                [id],
                read,
            )
            .optional()
            .map_err(db_err)?
            .map(to_tag)
            .transpose()
    }

    /// Tags with `status` (all if `None`), oldest first.
    pub fn list_tags(&self, status: Option<TagStatus>) -> Result<Vec<Tag>, CoreError> {
        let mut stmt = self
            .conn
            .prepare(&format!(
                "SELECT {COLUMNS} FROM tags WHERE ?1 IS NULL OR status = ?1 ORDER BY time_ms, id"
            ))
            .map_err(db_err)?;
        let rows = stmt
            .query_map([status.map(|s| s as i64)], read)
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        rows.into_iter().map(to_tag).collect()
    }

    /// Marks a tag used or discarded (or pending again); `false` if there
    /// is no such tag.
    pub fn set_tag_status(&mut self, id: i64, status: TagStatus) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute(
                "UPDATE tags SET status = ?2 WHERE id = ?1",
                params![id, status as i64],
            )
            .map_err(db_err)?;
        Ok(changed > 0)
    }

    /// Deletes a tag; `false` if it did not exist.
    pub fn delete_tag(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM tags WHERE id = ?1", [id])
            .map_err(db_err)?;
        Ok(changed > 0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS0: i64 = 1_790_000_000_000;

    fn tag(time_ms: i64, track_id: Option<i64>) -> NewTag {
        NewTag {
            time_ms,
            position: LatLon {
                lat: 55.7,
                lon: 13.2,
            },
            heading_deg: Some(45.0),
            speed_mps: Some(20.0),
            track_id,
        }
    }

    #[test]
    fn saves_lists_and_reviews_tags() {
        let mut s = Store::open_in_memory().unwrap();
        let track = s.start_track(MS0 / 1000).unwrap();
        let b = s.add_tag(&tag(MS0 + 2000, Some(track.id))).unwrap();
        let a = s.add_tag(&tag(MS0 + 1000, None)).unwrap();
        assert_eq!(a.status, TagStatus::Pending);
        assert_eq!(a.rider_id, LOCAL_RIDER);
        assert_eq!((b.track_id, b.heading_deg), (Some(track.id), Some(45.0)));
        // Oldest first.
        let ids: Vec<i64> = s.list_tags(None).unwrap().iter().map(|t| t.id).collect();
        assert_eq!(ids, [a.id, b.id]);

        assert!(s.set_tag_status(a.id, TagStatus::Used).unwrap());
        assert!(s.set_tag_status(b.id, TagStatus::Discarded).unwrap());
        assert!(s.list_tags(Some(TagStatus::Pending)).unwrap().is_empty());
        assert_eq!(s.list_tags(Some(TagStatus::Used)).unwrap()[0].id, a.id);
        assert!(!s.set_tag_status(999, TagStatus::Used).unwrap());

        assert!(s.delete_tag(a.id).unwrap());
        assert!(!s.delete_tag(a.id).unwrap());
        assert!(s.get_tag(a.id).unwrap().is_none());
    }

    #[test]
    fn deleting_the_ride_keeps_its_tags() {
        let mut s = Store::open_in_memory().unwrap();
        let track = s.start_track(MS0 / 1000).unwrap();
        let t = s.add_tag(&tag(MS0, Some(track.id))).unwrap();
        s.delete_track(track.id).unwrap();
        assert_eq!(s.get_tag(t.id).unwrap().unwrap().track_id, None);
    }

    #[test]
    fn invalid_tags_are_refused() {
        let mut s = Store::open_in_memory().unwrap();
        for bad in [
            NewTag {
                time_ms: -1,
                ..tag(MS0, None)
            },
            NewTag {
                heading_deg: Some(360.0),
                ..tag(MS0, None)
            },
            NewTag {
                speed_mps: Some(f64::NAN),
                ..tag(MS0, None)
            },
            NewTag {
                position: LatLon {
                    lat: 91.0,
                    lon: 0.0,
                },
                ..tag(MS0, None)
            },
            tag(MS0, Some(42)), // no such ride
        ] {
            assert!(
                matches!(s.add_tag(&bad), Err(CoreError::InvalidArgument(_))),
                "{bad:?}"
            );
        }
        assert!(s.list_tags(None).unwrap().is_empty());
    }

    #[test]
    fn damaged_rows_are_errors_not_panics() {
        for sql in [
            "UPDATE tags SET lat = 5000000000",
            "UPDATE tags SET lat = 950000000",
            "UPDATE tags SET heading_deg = 400.0",
            "UPDATE tags SET time_ms = -3",
            "UPDATE tags SET status = 7",
        ] {
            let mut s = Store::open_in_memory().unwrap();
            let t = s.add_tag(&tag(MS0, None)).unwrap();
            if s.conn.execute_batch(sql).is_err() {
                continue; // refused by a CHECK constraint
            }
            assert!(
                matches!(s.get_tag(t.id), Err(CoreError::Storage(_))),
                "{sql}"
            );
            assert!(
                matches!(s.list_tags(None), Err(CoreError::Storage(_))),
                "{sql}"
            );
        }
    }
}
