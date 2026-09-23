// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Favourite road sections (PRD R1, ADR-0006).
//!
//! A section is a stretch of road the rider likes: the OSM ways it covers
//! (so it can be re-matched after the region is rebuilt from newer OSM data)
//! plus its own geometry (so it can be drawn, and re-matched, even when the
//! ways have changed).

use crate::{CoreError, LatLon};

/// The rider every section belongs to in v1; community ratings later add
/// other riders without changing the model.
pub const LOCAL_RIDER: &str = "local";

/// Most points a section's geometry may have (about 500 km of densely
/// mapped road); bounds memory and work on imported data.
pub const MAX_SECTION_POINTS: usize = 50_000;
/// Most OSM way spans a section may reference.
pub const MAX_SECTION_WAYS: usize = 10_000;
/// Longest section name, in characters.
pub const MAX_NAME_CHARS: usize = 200;
/// Longest rider id, in bytes.
pub const MAX_RIDER_ID_BYTES: usize = 100;

/// How good a section is. Stored as 1–3.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Rating {
    Good = 1,
    Great = 2,
    Epic = 3,
}

impl Rating {
    pub fn from_i64(v: i64) -> Option<Self> {
        match v {
            1 => Some(Self::Good),
            2 => Some(Self::Great),
            3 => Some(Self::Epic),
            _ => None,
        }
    }
}

/// Which way a section is good to ride.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Direction {
    /// Good both ways (the default).
    Both = 0,
    /// Only in the order of its geometry.
    Forward = 1,
}

impl Direction {
    pub fn from_i64(v: i64) -> Option<Self> {
        match v {
            0 => Some(Self::Both),
            1 => Some(Self::Forward),
            _ => None,
        }
    }
}

/// How a section was captured (PRD R2–R4, R10).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Source {
    Map = 0,
    Tag = 1,
    Track = 2,
    Import = 3,
}

impl Source {
    pub fn from_i64(v: i64) -> Option<Self> {
        match v {
            0 => Some(Self::Map),
            1 => Some(Self::Tag),
            2 => Some(Self::Track),
            3 => Some(Self::Import),
            _ => None,
        }
    }
}

/// Whether a section still matches the loaded road network (PRD R1:
/// flagged, never silently dropped).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Status {
    Ok = 0,
    /// The region changed; re-match before trusting the way references.
    NeedsRematch = 1,
    /// Re-matching failed; the rider should look at it.
    Unmatched = 2,
}

impl Status {
    pub fn from_i64(v: i64) -> Option<Self> {
        match v {
            0 => Some(Self::Ok),
            1 => Some(Self::NeedsRematch),
            2 => Some(Self::Unmatched),
            _ => None,
        }
    }
}

/// The part of one OSM way a section covers: node indices `from_idx` to
/// `to_idx` in the way's node list (`from_idx > to_idx` runs against the
/// way). Same convention as the region file's way refs.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct WaySpan {
    pub way_id: i64,
    pub from_idx: u32,
    pub to_idx: u32,
}

/// What the rider saves; the store adds id, timestamps and status.
#[derive(Debug, Clone, PartialEq)]
pub struct NewSection {
    pub rider_id: String,
    pub name: String,
    pub rating: Rating,
    pub direction: Direction,
    pub source: Source,
    /// The OSM ways covered, in riding order.
    pub ways: Vec<WaySpan>,
    /// The section's own polyline, at least two points.
    pub geometry: Vec<LatLon>,
}

/// A saved section.
#[derive(Debug, Clone, PartialEq)]
pub struct Section {
    pub id: i64,
    pub rider_id: String,
    pub name: String,
    pub rating: Rating,
    pub direction: Direction,
    pub source: Source,
    pub status: Status,
    /// Seconds since the Unix epoch.
    pub created_at: i64,
    pub updated_at: i64,
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
}

/// Changes to a saved section; `None` leaves a field as it is.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SectionUpdate {
    pub name: Option<String>,
    pub rating: Option<Rating>,
    pub direction: Option<Direction>,
}

fn invalid(msg: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(msg.into())
}

pub(crate) fn validate_name(name: &str) -> Result<(), CoreError> {
    if name.chars().count() > MAX_NAME_CHARS {
        return Err(invalid(format!(
            "section name longer than {MAX_NAME_CHARS} characters"
        )));
    }
    if name.chars().any(char::is_control) {
        return Err(invalid("section name contains control characters"));
    }
    Ok(())
}

impl NewSection {
    /// Checks everything the store and later code rely on.
    pub fn validate(&self) -> Result<(), CoreError> {
        if self.rider_id.is_empty() || self.rider_id.len() > MAX_RIDER_ID_BYTES {
            return Err(invalid("rider id must be 1–100 bytes"));
        }
        validate_name(&self.name)?;
        if self.geometry.len() < 2 || self.geometry.len() > MAX_SECTION_POINTS {
            return Err(invalid(format!(
                "section geometry needs 2–{MAX_SECTION_POINTS} points, got {}",
                self.geometry.len()
            )));
        }
        for p in &self.geometry {
            p.validate()?;
        }
        if self.ways.len() > MAX_SECTION_WAYS {
            return Err(invalid(format!(
                "section references more than {MAX_SECTION_WAYS} ways"
            )));
        }
        if self.ways.iter().any(|w| w.way_id <= 0) {
            return Err(invalid("OSM way ids are positive"));
        }
        Ok(())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub fn sample() -> NewSection {
        NewSection {
            rider_id: LOCAL_RIDER.into(),
            name: "Kullaberg loop".into(),
            rating: Rating::Epic,
            direction: Direction::Both,
            source: Source::Map,
            ways: vec![WaySpan {
                way_id: 42,
                from_idx: 0,
                to_idx: 3,
            }],
            geometry: vec![
                LatLon {
                    lat: 56.30,
                    lon: 12.45,
                },
                LatLon {
                    lat: 56.31,
                    lon: 12.46,
                },
            ],
        }
    }

    #[test]
    fn enums_round_trip_through_integers() {
        for r in [Rating::Good, Rating::Great, Rating::Epic] {
            assert_eq!(Rating::from_i64(r as i64), Some(r));
        }
        for d in [Direction::Both, Direction::Forward] {
            assert_eq!(Direction::from_i64(d as i64), Some(d));
        }
        for s in [Source::Map, Source::Tag, Source::Track, Source::Import] {
            assert_eq!(Source::from_i64(s as i64), Some(s));
        }
        for s in [Status::Ok, Status::NeedsRematch, Status::Unmatched] {
            assert_eq!(Status::from_i64(s as i64), Some(s));
        }
        for bad in [-1, 4, 99] {
            assert_eq!(Rating::from_i64(bad), None);
            assert_eq!(Source::from_i64(bad), None);
        }
        assert_eq!(Rating::from_i64(0), None);
        assert_eq!(Direction::from_i64(2), None);
        assert_eq!(Status::from_i64(3), None);
        assert!(Rating::Epic > Rating::Good);
    }

    #[test]
    fn accepts_a_normal_section() {
        sample().validate().unwrap();
    }

    #[test]
    fn rejects_bad_sections() {
        type Break = Box<dyn Fn(&mut NewSection)>;
        let cases: Vec<(&str, Break)> = vec![
            ("empty rider", Box::new(|s| s.rider_id.clear())),
            ("long rider", Box::new(|s| s.rider_id = "x".repeat(101))),
            ("long name", Box::new(|s| s.name = "é".repeat(201))),
            ("control char", Box::new(|s| s.name = "a\u{0}b".into())),
            ("one point", Box::new(|s| s.geometry.truncate(1))),
            (
                "too many points",
                Box::new(|s| s.geometry = vec![s.geometry[0]; MAX_SECTION_POINTS + 1]),
            ),
            ("bad point", Box::new(|s| s.geometry[1].lat = f64::NAN)),
            (
                "too many ways",
                Box::new(|s| s.ways = vec![s.ways[0]; MAX_SECTION_WAYS + 1]),
            ),
            ("bad way id", Box::new(|s| s.ways[0].way_id = 0)),
        ];
        for (why, break_it) in cases {
            let mut s = sample();
            break_it(&mut s);
            assert!(s.validate().is_err(), "{why} should be rejected");
        }
        let mut ok = sample();
        ok.name = "é".repeat(200);
        ok.ways.clear();
        ok.validate().unwrap();
    }
}
