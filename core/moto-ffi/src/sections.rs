// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Favourite sections across the FFI (PRD R1–R2, ADR-0006): proposing a
//! section from two map points, and the rider's section store.

use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use moto_core::section as core;

use crate::{Engine, LatLon, MotoError};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Rating {
    Good,
    Great,
    Epic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Direction {
    /// Good both ways.
    Both,
    /// Only in the order of the section's geometry.
    Forward,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SectionSource {
    Map,
    Tag,
    Track,
    Import,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SectionStatus {
    Ok,
    NeedsRematch,
    Unmatched,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct WaySpan {
    pub way_id: i64,
    pub from_idx: u32,
    pub to_idx: u32,
}

/// A section proposed from the map, before it is saved.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SectionDraft {
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
}

/// What the app saves; the store adds the id, timestamps and status. The
/// rider is always the local one in v1.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NewSection {
    pub name: String,
    pub rating: Rating,
    pub direction: Direction,
    pub source: SectionSource,
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Section {
    pub id: i64,
    pub rider_id: String,
    pub name: String,
    pub rating: Rating,
    pub direction: Direction,
    pub source: SectionSource,
    pub status: SectionStatus,
    /// Seconds since the Unix epoch.
    pub created_at: i64,
    pub updated_at: i64,
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
}

/// Changes to a saved section; `None` leaves a field as it is.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct SectionUpdate {
    pub name: Option<String>,
    pub rating: Option<Rating>,
    pub direction: Option<Direction>,
}

/// A map area, e.g. what is on screen.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct Area {
    pub south_west: LatLon,
    pub north_east: LatLon,
}

#[uniffi::export]
impl Engine {
    /// Proposes a section along the road between two picked points.
    pub fn section_between(&self, from: LatLon, to: LatLon) -> Result<SectionDraft, MotoError> {
        Ok(self.inner.section_between(from.into(), to.into())?.into())
    }
}

/// The rider's saved sections, in one database file in app-private
/// storage. Thread-safe; share one instance.
#[derive(Debug, uniffi::Object)]
pub struct SectionStore {
    inner: Mutex<moto_core::store::Store>,
}

pub(crate) fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

impl SectionStore {
    /// The store, even if another thread panicked while holding it: the
    /// database itself stays consistent through its transactions.
    pub(crate) fn store(&self) -> MutexGuard<'_, moto_core::store::Store> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl SectionStore {
    /// Opens (creating if needed) the database at `path`, which the app
    /// places in its private files directory.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, MotoError> {
        let store = moto_core::store::Store::open(path)?;
        Ok(Arc::new(Self {
            inner: Mutex::new(store),
        }))
    }

    pub fn add(&self, section: NewSection) -> Result<Section, MotoError> {
        let new = core::NewSection {
            rider_id: core::LOCAL_RIDER.into(),
            name: section.name,
            rating: section.rating.into(),
            direction: section.direction.into(),
            source: section.source.into(),
            ways: section.ways.into_iter().map(Into::into).collect(),
            geometry: section.geometry.into_iter().map(Into::into).collect(),
        };
        Ok(self.store().add_section(&new, now())?.into())
    }

    pub fn get(&self, id: i64) -> Result<Option<Section>, MotoError> {
        Ok(self.store().get_section(id)?.map(Into::into))
    }

    /// All sections, or those overlapping `area`, oldest first.
    pub fn list(&self, area: Option<Area>) -> Result<Vec<Section>, MotoError> {
        let area = area.map(|a| (a.south_west.into(), a.north_east.into()));
        let sections = self.store().list_sections(area)?;
        Ok(sections.into_iter().map(Into::into).collect())
    }

    /// Changes name, rating or direction; `None` if there is no such section.
    pub fn update(&self, id: i64, update: SectionUpdate) -> Result<Option<Section>, MotoError> {
        let update = core::SectionUpdate {
            name: update.name,
            rating: update.rating.map(Into::into),
            direction: update.direction.map(Into::into),
        };
        Ok(self
            .store()
            .update_section(id, &update, now())?
            .map(Into::into))
    }

    /// Deletes a section; `false` if it did not exist.
    pub fn delete(&self, id: i64) -> Result<bool, MotoError> {
        Ok(self.store().delete_section(id)?)
    }
}

// --- conversions ---

impl From<Rating> for core::Rating {
    fn from(r: Rating) -> Self {
        match r {
            Rating::Good => Self::Good,
            Rating::Great => Self::Great,
            Rating::Epic => Self::Epic,
        }
    }
}

impl From<core::Rating> for Rating {
    fn from(r: core::Rating) -> Self {
        match r {
            core::Rating::Good => Self::Good,
            core::Rating::Great => Self::Great,
            core::Rating::Epic => Self::Epic,
        }
    }
}

impl From<Direction> for core::Direction {
    fn from(d: Direction) -> Self {
        match d {
            Direction::Both => Self::Both,
            Direction::Forward => Self::Forward,
        }
    }
}

impl From<core::Direction> for Direction {
    fn from(d: core::Direction) -> Self {
        match d {
            core::Direction::Both => Self::Both,
            core::Direction::Forward => Self::Forward,
        }
    }
}

impl From<SectionSource> for core::Source {
    fn from(s: SectionSource) -> Self {
        match s {
            SectionSource::Map => Self::Map,
            SectionSource::Tag => Self::Tag,
            SectionSource::Track => Self::Track,
            SectionSource::Import => Self::Import,
        }
    }
}

impl From<core::Source> for SectionSource {
    fn from(s: core::Source) -> Self {
        match s {
            core::Source::Map => Self::Map,
            core::Source::Tag => Self::Tag,
            core::Source::Track => Self::Track,
            core::Source::Import => Self::Import,
        }
    }
}

impl From<core::Status> for SectionStatus {
    fn from(s: core::Status) -> Self {
        match s {
            core::Status::Ok => Self::Ok,
            core::Status::NeedsRematch => Self::NeedsRematch,
            core::Status::Unmatched => Self::Unmatched,
        }
    }
}

impl From<WaySpan> for core::WaySpan {
    fn from(w: WaySpan) -> Self {
        Self {
            way_id: w.way_id,
            from_idx: w.from_idx,
            to_idx: w.to_idx,
        }
    }
}

impl From<core::WaySpan> for WaySpan {
    fn from(w: core::WaySpan) -> Self {
        Self {
            way_id: w.way_id,
            from_idx: w.from_idx,
            to_idx: w.to_idx,
        }
    }
}

impl From<moto_core::SectionDraft> for SectionDraft {
    fn from(d: moto_core::SectionDraft) -> Self {
        Self {
            ways: d.ways.into_iter().map(Into::into).collect(),
            geometry: d.geometry.into_iter().map(Into::into).collect(),
            distance_m: d.distance_m,
        }
    }
}

impl From<core::Section> for Section {
    fn from(s: core::Section) -> Self {
        Self {
            id: s.id,
            rider_id: s.rider_id,
            name: s.name,
            rating: s.rating.into(),
            direction: s.direction.into(),
            source: s.source.into(),
            status: s.status.into(),
            created_at: s.created_at,
            updated_at: s.updated_at,
            ways: s.ways.into_iter().map(Into::into).collect(),
            geometry: s.geometry.into_iter().map(Into::into).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A database file removed (with its WAL files) when dropped.
    struct TempDb(std::path::PathBuf);

    impl TempDb {
        fn new(name: &str) -> Self {
            let db = Self(
                std::env::temp_dir().join(format!("moto-ffi-{name}-{}.db", std::process::id())),
            );
            db.remove();
            db
        }

        fn path(&self) -> String {
            self.0.to_string_lossy().into_owned()
        }

        fn remove(&self) {
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{suffix}", self.0.display()));
            }
        }
    }

    impl Drop for TempDb {
        fn drop(&mut self) {
            self.remove();
        }
    }

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    /// An engine on the fixture region, from a file unique to `name` (tests
    /// run in parallel).
    fn engine(name: &str) -> (Arc<Engine>, std::path::PathBuf) {
        let path =
            std::env::temp_dir().join(format!("moto-ffi-{name}-{}.region", std::process::id()));
        std::fs::write(&path, moto_core::fixture::region().to_bytes().unwrap()).unwrap();
        let engine = Engine::open(path.to_string_lossy().into_owned()).unwrap();
        (engine, path)
    }

    #[test]
    fn saves_a_section_picked_on_the_map() {
        let (engine, region) = engine("pick");
        let draft = engine
            .section_between(ll(55.7001, 13.202), ll(55.7001, 13.219))
            .unwrap();
        std::fs::remove_file(region).unwrap();
        assert_eq!(draft.ways.len(), 2);

        let db = TempDb::new("pick");
        let store = SectionStore::open(db.path()).unwrap();
        let saved = store
            .add(NewSection {
                name: "Lund test".into(),
                rating: Rating::Great,
                direction: Direction::Forward,
                source: SectionSource::Map,
                ways: draft.ways.clone(),
                geometry: draft.geometry.clone(),
            })
            .unwrap();
        assert_eq!(saved.rider_id, "local");
        assert_eq!(saved.status, SectionStatus::Ok);
        assert_eq!(saved.ways, draft.ways);
        assert!(saved.created_at > 1_700_000_000, "a real clock");
        assert_eq!(store.get(saved.id).unwrap().unwrap().name, "Lund test");

        // Reopening the file sees the same section.
        drop(store);
        let store = SectionStore::open(db.path()).unwrap();
        assert_eq!(store.list(None).unwrap().len(), 1);
    }

    #[test]
    fn lists_updates_and_deletes() {
        let db = TempDb::new("crud");
        let store = SectionStore::open(db.path()).unwrap();
        let new = |lat: f64| NewSection {
            name: String::new(),
            rating: Rating::Good,
            direction: Direction::Both,
            source: SectionSource::Map,
            ways: vec![],
            geometry: vec![ll(lat, 13.0), ll(lat + 0.01, 13.01)],
        };
        let south = store.add(new(55.4)).unwrap();
        let north = store.add(new(56.4)).unwrap();
        let area = Area {
            south_west: ll(56.0, 12.5),
            north_east: ll(57.0, 13.5),
        };
        let found: Vec<i64> = store
            .list(Some(area))
            .unwrap()
            .iter()
            .map(|s| s.id)
            .collect();
        assert_eq!(found, [north.id]);

        let changed = store
            .update(
                south.id,
                SectionUpdate {
                    rating: Some(Rating::Epic),
                    ..Default::default()
                },
            )
            .unwrap()
            .unwrap();
        assert_eq!(
            (changed.rating, changed.direction),
            (Rating::Epic, Direction::Both)
        );
        assert!(
            store
                .update(999, SectionUpdate::default())
                .unwrap()
                .is_none()
        );

        assert!(store.delete(south.id).unwrap());
        assert!(!store.delete(south.id).unwrap());
        assert!(store.get(south.id).unwrap().is_none());
    }

    #[test]
    fn bad_input_and_bad_files_are_typed_errors() {
        let db = TempDb::new("errors");
        let store = SectionStore::open(db.path()).unwrap();
        let bad = NewSection {
            name: "x".into(),
            rating: Rating::Good,
            direction: Direction::Both,
            source: SectionSource::Import,
            ways: vec![],
            geometry: vec![ll(55.0, 13.0)],
        };
        assert!(matches!(
            store.add(bad),
            Err(MotoError::InvalidInput { .. })
        ));

        let junk = TempDb::new("junk");
        std::fs::write(&junk.0, vec![0x42u8; 8192]).unwrap();
        assert!(matches!(
            SectionStore::open(junk.path()),
            Err(MotoError::Storage { .. })
        ));

        let (engine, region) = engine("errors");
        let p = ll(55.7001, 13.205);
        let same = engine.section_between(p, p).unwrap_err();
        std::fs::remove_file(region).unwrap();
        assert!(matches!(same, MotoError::InvalidInput { .. }), "{same:?}");
    }

    #[test]
    fn enums_convert_both_ways() {
        for r in [Rating::Good, Rating::Great, Rating::Epic] {
            assert_eq!(Rating::from(core::Rating::from(r)), r);
        }
        for d in [Direction::Both, Direction::Forward] {
            assert_eq!(Direction::from(core::Direction::from(d)), d);
        }
        for s in [
            SectionSource::Map,
            SectionSource::Tag,
            SectionSource::Track,
            SectionSource::Import,
        ] {
            assert_eq!(SectionSource::from(core::Source::from(s)), s);
        }
        assert_eq!(
            SectionStatus::from(core::Status::Unmatched),
            SectionStatus::Unmatched
        );
        assert_eq!(
            SectionStatus::from(core::Status::NeedsRematch),
            SectionStatus::NeedsRematch
        );
    }
}
