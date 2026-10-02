// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Section export and import across the FFI (PRD R10): GeoJSON, plain or
//! compressed.

use std::sync::Arc;

use moto_core::exchange as core;

use crate::sections::now;
use crate::{Engine, MotoError, Rating, SectionStore};

/// How an export is packed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ExportFormat {
    /// Plain `.geojson`.
    GeoJson,
    /// `.geojson.gz`.
    Gzip,
    /// `.zip` holding one `.geojson`.
    Zip,
}

/// A section named in an import's report: its name (may be empty),
/// rating and length.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ImportItem {
    pub name: String,
    pub rating: Rating,
    pub length_m: f64,
}

impl From<core::ImportItem> for ImportItem {
    fn from(i: core::ImportItem) -> Self {
        Self {
            name: i.name,
            rating: i.rating.into(),
            length_m: i.length_m,
        }
    }
}

/// What an import did: the counts, and the sections behind them, in file
/// order.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ImportReport {
    /// Sections added.
    pub added: u64,
    /// Imported sections already covered by a saved (or longer imported)
    /// section.
    pub skipped: u64,
    /// Saved sections replaced by a longer imported section covering them.
    pub replaced: u64,
    /// Added sections that don't fit the current map (hidden as unmatched).
    pub unmatched: u64,
    /// The sections added that fit the map.
    pub added_items: Vec<ImportItem>,
    /// The sections added that don't fit the map.
    pub unmatched_items: Vec<ImportItem>,
    /// The imported sections skipped as already saved.
    pub skipped_items: Vec<ImportItem>,
    /// The saved sections replaced by longer imported ones, as they were.
    pub replaced_items: Vec<ImportItem>,
}

impl From<ExportFormat> for core::ExportFormat {
    fn from(f: ExportFormat) -> Self {
        match f {
            ExportFormat::GeoJson => Self::GeoJson,
            ExportFormat::Gzip => Self::Gzip,
            ExportFormat::Zip => Self::Zip,
        }
    }
}

/// The usual file name extension for `format`, e.g. "geojson.gz".
#[uniffi::export]
pub fn export_extension(format: ExportFormat) -> String {
    core::ExportFormat::from(format).extension().into()
}

#[uniffi::export]
impl SectionStore {
    /// All saved sections as a GeoJSON file packed in `format`.
    pub fn export_sections(&self, format: ExportFormat) -> Result<Vec<u8>, MotoError> {
        Ok(core::export_sections(&self.store(), format.into())?)
    }

    /// Imports sections from a file in any export format (detected from
    /// its content), skipping ones already covered and replacing shorter
    /// ones, all in one transaction; then fits them to `engine`'s region if
    /// given. A malformed file changes nothing.
    pub fn import_sections(
        &self,
        bytes: Vec<u8>,
        engine: Option<Arc<Engine>>,
    ) -> Result<ImportReport, MotoError> {
        let r = core::import_sections(
            &mut self.store(),
            engine.as_deref().map(|e| &e.inner),
            &bytes,
            now(),
        )?;
        let items = |v: Vec<core::ImportItem>| v.into_iter().map(Into::into).collect();
        Ok(ImportReport {
            added: r.added,
            skipped: r.skipped,
            replaced: r.replaced,
            unmatched: r.unmatched,
            added_items: items(r.added_items),
            unmatched_items: items(r.unmatched_items),
            skipped_items: items(r.skipped_items),
            replaced_items: items(r.replaced_items),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Direction, LatLon, NewSection, Rating, SectionSource};

    #[test]
    fn exports_and_imports_across_the_ffi() {
        let dir = std::env::temp_dir();
        let pid = std::process::id();
        let region = dir.join(format!("moto-ffi-exchange-{pid}.region"));
        std::fs::write(&region, moto_core::fixture::region().to_bytes().unwrap()).unwrap();
        let engine = Engine::open(region.to_string_lossy().into_owned()).unwrap();
        std::fs::remove_file(&region).unwrap();
        let db = |name: &str| {
            let p = dir.join(format!("moto-ffi-exchange-{name}-{pid}.db"));
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{suffix}", p.display()));
            }
            p
        };
        let (a, b) = (db("a"), db("b"));
        let from = SectionStore::open(a.to_string_lossy().into_owned()).unwrap();
        let draft = engine
            .section_between(
                LatLon {
                    lat: 55.7001,
                    lon: 13.202,
                },
                LatLon {
                    lat: 55.7001,
                    lon: 13.219,
                },
            )
            .unwrap();
        from.add(NewSection {
            name: String::new(),
            rating: Rating::Epic,
            direction: Direction::Both,
            source: SectionSource::Map,
            ways: draft.ways,
            geometry: draft.geometry,
        })
        .unwrap()
        .section
        .unwrap();
        let to = SectionStore::open(b.to_string_lossy().into_owned()).unwrap();
        for format in [ExportFormat::GeoJson, ExportFormat::Gzip, ExportFormat::Zip] {
            let bytes = from.export_sections(format).unwrap();
            let r = to.import_sections(bytes, Some(engine.clone())).unwrap();
            // The first import adds it; the others find it already there.
            assert_eq!(r.added + r.skipped, 1, "{format:?}");
        }
        assert_eq!(to.list(None).unwrap().len(), 1);
        assert!(matches!(
            to.import_sections(b"nonsense".to_vec(), None),
            Err(MotoError::InvalidInput { .. })
        ));
        assert_eq!(export_extension(ExportFormat::Gzip), "geojson.gz");
        drop((from, to));
        for p in [a, b] {
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{suffix}", p.display()));
            }
        }
    }
}
