// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Riding a route (ADR-0011): the route a ride follows, kept beside the
//! ride until it ends so following can carry on after Android stopped the
//! app; and segment breaks, where recording of a ride started again after
//! such a gap.

use rusqlite::{OptionalExtension, params};

use super::{Store, db_err, decode_line, encode_geometry};
use crate::follow::MAX_FOLLOW_PARTS;
use crate::handoff::MAX_LINE_POINTS;
use crate::section::{Rating, validate_name};
use crate::track::TrackPoint;
use crate::{CoreError, LatLon};

/// The route a ride follows.
#[derive(Debug, Clone, PartialEq)]
pub struct FollowedRoute {
    pub name: String,
    pub is_loop: bool,
    pub duration_s: f64,
    pub line: Vec<LatLon>,
    /// Its favourite parts in route order, each with its rating.
    pub parts: Vec<Vec<LatLon>>,
    pub ratings: Vec<Rating>,
}

impl FollowedRoute {
    fn validate(&self) -> Result<(), CoreError> {
        validate_name(&self.name)?;
        if !(self.duration_s.is_finite() && self.duration_s >= 0.0) {
            return Err(CoreError::InvalidArgument("invalid route time".into()));
        }
        if self.line.len() < 2 || self.line.len() > MAX_LINE_POINTS {
            return Err(CoreError::InvalidArgument(format!(
                "a route line needs 2–{MAX_LINE_POINTS} points"
            )));
        }
        if self.parts.len() != self.ratings.len() || self.parts.len() > MAX_FOLLOW_PARTS {
            return Err(CoreError::InvalidArgument(
                "favourite parts and ratings don't match".into(),
            ));
        }
        for part in &self.parts {
            if part.len() < 2 || part.len() > MAX_LINE_POINTS {
                return Err(CoreError::InvalidArgument(
                    "a favourite part needs at least 2 points".into(),
                ));
            }
        }
        for p in self.line.iter().chain(self.parts.iter().flatten()) {
            p.validate()?;
        }
        Ok(())
    }
}

fn unknown(id: i64) -> CoreError {
    CoreError::InvalidArgument(format!("no track {id}"))
}

impl Store {
    /// Keeps `route` as the route ride `track_id` follows, replacing any
    /// kept before. The ride must still be recording.
    pub fn set_followed_route(
        &mut self,
        track_id: i64,
        route: &FollowedRoute,
    ) -> Result<(), CoreError> {
        route.validate()?;
        let track = self.get_track(track_id)?.ok_or_else(|| unknown(track_id))?;
        if track.ended_at.is_some() {
            return Err(CoreError::InvalidArgument(format!(
                "track {track_id} is finished"
            )));
        }
        let tx = self.conn.transaction().map_err(db_err)?;
        tx.execute(
            "DELETE FROM followed_routes WHERE track_id = ?1",
            [track_id],
        )
        .map_err(db_err)?;
        tx.execute(
            "INSERT INTO followed_routes (track_id, name, is_loop, duration_s, geometry)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                track_id,
                route.name,
                i64::from(route.is_loop),
                route.duration_s,
                encode_geometry(&route.line)
            ],
        )
        .map_err(db_err)?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT INTO followed_parts (track_id, seq, rating, geometry)
                     VALUES (?1, ?2, ?3, ?4)",
                )
                .map_err(db_err)?;
            for (seq, (part, rating)) in route.parts.iter().zip(&route.ratings).enumerate() {
                stmt.execute(params![
                    track_id,
                    seq as i64,
                    *rating as i64,
                    encode_geometry(part)
                ])
                .map_err(db_err)?;
            }
        }
        tx.commit().map_err(db_err)
    }

    /// The route ride `track_id` follows, if any. Everything read back is
    /// checked.
    pub fn followed_route(&self, track_id: i64) -> Result<Option<FollowedRoute>, CoreError> {
        let corrupt = |what: &str| {
            CoreError::Storage(format!(
                "followed route of track {track_id}: invalid {what}"
            ))
        };
        let Some((name, is_loop, duration_s, geometry)) = self
            .conn
            .query_row(
                "SELECT name, is_loop, duration_s, geometry FROM followed_routes
                 WHERE track_id = ?1",
                [track_id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, f64>(2)?,
                        r.get::<_, Vec<u8>>(3)?,
                    ))
                },
            )
            .optional()
            .map_err(db_err)?
        else {
            return Ok(None);
        };
        let line = decode_line(&geometry, MAX_LINE_POINTS).ok_or_else(|| corrupt("line"))?;
        let mut stmt = self
            .conn
            .prepare("SELECT rating, geometry FROM followed_parts WHERE track_id = ?1 ORDER BY seq")
            .map_err(db_err)?;
        let rows = stmt
            .query_map([track_id], |r| {
                Ok((r.get::<_, i64>(0)?, r.get::<_, Vec<u8>>(1)?))
            })
            .map_err(db_err)?;
        let (mut parts, mut ratings) = (Vec::new(), Vec::new());
        for row in rows {
            let (rating, geometry) = row.map_err(db_err)?;
            if parts.len() >= MAX_FOLLOW_PARTS {
                return Err(corrupt("parts"));
            }
            ratings.push(Rating::from_i64(rating).ok_or_else(|| corrupt("rating"))?);
            parts.push(decode_line(&geometry, MAX_LINE_POINTS).ok_or_else(|| corrupt("part"))?);
        }
        let route = FollowedRoute {
            name,
            is_loop: match is_loop {
                0 => false,
                1 => true,
                _ => return Err(corrupt("kind")),
            },
            duration_s,
            line,
            parts,
            ratings,
        };
        route.validate().map_err(|_| corrupt("route"))?;
        Ok(Some(route))
    }

    /// Stops keeping the route ride `track_id` follows; `false` if none
    /// was kept.
    pub fn clear_followed_route(&mut self, track_id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute(
                "DELETE FROM followed_routes WHERE track_id = ?1",
                [track_id],
            )
            .map_err(db_err)?;
        Ok(changed > 0)
    }

    /// The stretches of `line` on the rider's favourite sections as they
    /// are now, with their ratings (for a saved route, shown or ridden;
    /// ADR-0011). Only sections near the line are read.
    pub fn favourite_parts_along(
        &self,
        line: &[LatLon],
    ) -> Result<Vec<crate::favourite_parts::FavouritePart>, CoreError> {
        if line.len() < 2 || line.len() > MAX_LINE_POINTS {
            return Err(CoreError::InvalidArgument(format!(
                "a route line needs 2–{MAX_LINE_POINTS} points"
            )));
        }
        for p in line {
            p.validate()?;
        }
        let pad = 0.001;
        let (mut sw, mut ne) = (line[0], line[0]);
        for p in line {
            sw = LatLon {
                lat: sw.lat.min(p.lat),
                lon: sw.lon.min(p.lon),
            };
            ne = LatLon {
                lat: ne.lat.max(p.lat),
                lon: ne.lon.max(p.lon),
            };
        }
        let area = (
            LatLon {
                lat: (sw.lat - pad).max(-90.0),
                lon: (sw.lon - pad).max(-180.0),
            },
            LatLon {
                lat: (ne.lat + pad).min(90.0),
                lon: (ne.lon + pad).min(180.0),
            },
        );
        let sections = self.list_sections(Some(area))?;
        crate::favourite_parts::favourite_parts_along(line, &sections)
    }

    /// The rider's favourites near `at` (while recording without a route),
    /// with `heading` for the way they go along one they are on; see
    /// [`crate::near::near_favourites`]. Only sections near `at` are read.
    pub fn near_favourites(
        &self,
        at: LatLon,
        heading: Option<f64>,
    ) -> Result<Vec<crate::near::NearFavourite>, CoreError> {
        at.validate()?;
        let dlat = crate::near::NEAR_M / 111_195.0;
        let dlon = dlat / at.lat.to_radians().cos().max(0.01);
        let area = (
            LatLon {
                lat: (at.lat - dlat).max(-90.0),
                lon: (at.lon - dlon).max(-180.0),
            },
            LatLon {
                lat: (at.lat + dlat).min(90.0),
                lon: (at.lon + dlon).min(180.0),
            },
        );
        let sections = self.list_sections(Some(area))?;
        crate::near::near_favourites(&sections, at, heading)
    }

    /// Marks that recording of ride `track_id` starts again after a gap:
    /// the next fix appended begins a new segment. Nothing to mark before
    /// the first fix, or twice at the same place. The ride must still be
    /// recording.
    pub fn break_track(&mut self, track_id: i64) -> Result<(), CoreError> {
        let track = self.get_track(track_id)?.ok_or_else(|| unknown(track_id))?;
        if track.ended_at.is_some() {
            return Err(CoreError::InvalidArgument(format!(
                "track {track_id} is finished"
            )));
        }
        if track.point_count == 0 {
            return Ok(());
        }
        let seq = i64::try_from(track.point_count)
            .map_err(|_| CoreError::Storage(format!("track {track_id}: invalid point count")))?;
        self.conn
            .execute(
                "INSERT OR IGNORE INTO track_breaks (track_id, seq) VALUES (?1, ?2)",
                params![track_id, seq],
            )
            .map_err(db_err)?;
        Ok(())
    }

    /// Ride `track_id`'s fixes split where recording started again after a
    /// gap ([`Self::break_track`]); one segment for a ride without breaks,
    /// none for one without fixes. `None` if there is no such ride.
    pub fn track_segments(&self, track_id: i64) -> Result<Option<Vec<Vec<TrackPoint>>>, CoreError> {
        let Some(points) = self.track_points(track_id)? else {
            return Ok(None);
        };
        let mut stmt = self
            .conn
            .prepare("SELECT seq FROM track_breaks WHERE track_id = ?1 ORDER BY seq")
            .map_err(db_err)?;
        let breaks = stmt
            .query_map([track_id], |r| r.get::<_, i64>(0))
            .map_err(db_err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(db_err)?;
        Ok(Some(split_at(points, &breaks)))
    }
}

/// `points` split before each of `breaks` (indices, ascending); breaks
/// out of range or not increasing are ignored, and no segment is empty.
pub(crate) fn split_at(points: Vec<TrackPoint>, breaks: &[i64]) -> Vec<Vec<TrackPoint>> {
    let mut out = Vec::new();
    let mut rest = points;
    let mut taken = 0usize;
    for &b in breaks {
        let Ok(b) = usize::try_from(b) else {
            continue;
        };
        if b <= taken || b - taken >= rest.len() {
            continue;
        }
        let tail = rest.split_off(b - taken);
        out.push(rest);
        rest = tail;
        taken = b;
    }
    if !rest.is_empty() {
        out.push(rest);
    }
    out
}

#[cfg(test)]
mod tests;
