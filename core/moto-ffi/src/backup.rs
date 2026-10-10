// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Backup and restore across the FFI (ADR-0012). The app hands in a path
//! in its own storage (it copies the file the rider picked there, and
//! copies a written backup out to where the rider chose).

use std::path::Path;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use moto_core::backup as core;

use crate::sections::now;
use crate::{Engine, MotoError, SectionStore};

/// One of the app's settings in a backup: a simple key (lower-case
/// letters, digits and `_`) and its value.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupSetting {
    pub key: String,
    pub value: BackupSettingValue,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum BackupSettingValue {
    Bool { value: bool },
    Int { value: i64 },
    Text { value: String },
}

impl From<core::Setting> for BackupSetting {
    fn from(s: core::Setting) -> Self {
        Self {
            key: s.key,
            value: match s.value {
                core::SettingValue::Bool(value) => BackupSettingValue::Bool { value },
                core::SettingValue::Int(value) => BackupSettingValue::Int { value },
                core::SettingValue::Text(value) => BackupSettingValue::Text { value },
            },
        }
    }
}

impl From<BackupSetting> for core::Setting {
    fn from(s: BackupSetting) -> Self {
        Self {
            key: s.key,
            value: match s.value {
                BackupSettingValue::Bool { value } => core::SettingValue::Bool(value),
                BackupSettingValue::Int { value } => core::SettingValue::Int(value),
                BackupSettingValue::Text { value } => core::SettingValue::Text(value),
            },
        }
    }
}

/// What a backup holds.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupSummary {
    /// The backup format version.
    pub format: u32,
    /// The app and version that made it.
    pub app: String,
    /// When it was made, milliseconds since the Unix epoch.
    pub created_at_ms: i64,
    pub favourites: u64,
    pub rides: u64,
    pub routes: u64,
    /// Tags waiting for review.
    pub tags: u64,
    pub has_settings: bool,
    /// The map regions installed when it was made.
    pub regions: Vec<String>,
}

impl From<core::BackupSummary> for BackupSummary {
    fn from(s: core::BackupSummary) -> Self {
        Self {
            format: s.format,
            app: s.app,
            created_at_ms: s.created_at_ms,
            favourites: s.favourites,
            rides: s.rides,
            routes: s.routes,
            tags: s.tags,
            has_settings: s.has_settings,
            regions: s.regions,
        }
    }
}

/// What a restore did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RestoreReport {
    pub favourites_added: u64,
    pub favourites_skipped: u64,
    /// Shorter favourites on the phone an added one covers, replaced.
    pub favourites_replaced: u64,
    pub rides_added: u64,
    pub rides_skipped: u64,
    pub routes_added: u64,
    pub routes_skipped: u64,
    pub tags_added: u64,
    pub tags_skipped: u64,
    /// Tags left out because they lie on a favourite the phone already had
    /// (taken as reviewed since the backup was made).
    pub tags_on_favourites: u64,
    /// The backup's settings, for the app to check (which keys it knows)
    /// and apply; `None` if it has none.
    pub settings: Option<Vec<BackupSetting>>,
    /// The map regions the backup was made with.
    pub regions: Vec<String>,
}

impl From<core::RestoreReport> for RestoreReport {
    fn from(r: core::RestoreReport) -> Self {
        Self {
            favourites_added: r.favourites_added,
            favourites_skipped: r.favourites_skipped,
            favourites_replaced: r.favourites_replaced,
            rides_added: r.rides_added,
            rides_skipped: r.rides_skipped,
            routes_added: r.routes_added,
            routes_skipped: r.routes_skipped,
            tags_added: r.tags_added,
            tags_skipped: r.tags_skipped,
            tags_on_favourites: r.tags_on_favourites,
            settings: r.settings.map(|v| v.into_iter().map(Into::into).collect()),
            regions: r.regions,
        }
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

/// What the backup at `path` holds, after checking its structure.
#[uniffi::export]
pub fn backup_summary(path: String) -> Result<BackupSummary, MotoError> {
    Ok(core::read_summary(Path::new(&path))?.into())
}

#[uniffi::export]
impl SectionStore {
    /// Writes a backup of everything to a new file at `path`: `app` names
    /// the app and its version, `regions` the map regions installed, and
    /// `settings` the app's settings (`None`: no settings file).
    pub fn write_backup(
        &self,
        path: String,
        app: String,
        regions: Vec<String>,
        settings: Option<Vec<BackupSetting>>,
    ) -> Result<BackupSummary, MotoError> {
        let info = core::BackupInfo {
            app,
            regions,
            regions_disabled: Vec::new(),
            settings: settings.map(|v| v.into_iter().map(Into::into).collect()),
            created_at_ms: now_ms(),
        };
        Ok(core::write_backup(&self.store(), Path::new(&path), &info)?.into())
    }

    /// Restores the backup at `path`, merging it in (nothing is deleted,
    /// anything already there is skipped), all in one transaction; then
    /// fits the favourites to `engine`'s map if given. A damaged backup
    /// changes nothing.
    pub fn restore_backup(
        &self,
        path: String,
        engine: Option<Arc<Engine>>,
    ) -> Result<RestoreReport, MotoError> {
        let report = core::restore_backup(
            &mut self.store(),
            engine.as_deref().map(|e| &e.inner),
            Path::new(&path),
            now(),
        )?;
        Ok(report.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> String {
        std::env::temp_dir()
            .join(format!("moto-ffi-backup-{name}-{}", std::process::id()))
            .to_string_lossy()
            .into_owned()
    }

    /// A fresh database file named `name`, and its path (removed after).
    fn open_db(name: &str) -> (Arc<SectionStore>, String) {
        let p = temp(&format!("{name}.db"));
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{p}{suffix}"));
        }
        (SectionStore::open(p.clone()).unwrap(), p)
    }

    fn remove(p: &str) {
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{p}{suffix}"));
        }
    }

    #[test]
    fn a_backup_round_trips_through_the_ffi() {
        let (store, a) = open_db("a");
        let path = temp("round.zip");
        let written = store
            .write_backup(
                path.clone(),
                "moto test".into(),
                vec!["sweden".into()],
                Some(vec![BackupSetting {
                    key: "ride_zoom_step".into(),
                    value: BackupSettingValue::Int { value: 3 },
                }]),
            )
            .unwrap();
        assert_eq!(written, backup_summary(path.clone()).unwrap());
        assert!(written.has_settings);
        let (other, b) = open_db("b");
        let report = other.restore_backup(path.clone(), None).unwrap();
        assert_eq!(
            report.settings,
            Some(vec![BackupSetting {
                key: "ride_zoom_step".into(),
                value: BackupSettingValue::Int { value: 3 },
            }])
        );
        assert_eq!(report.regions, ["sweden"]);
        drop((store, other));
        for p in [&a, &b, &path] {
            remove(p);
        }
    }

    #[test]
    fn a_file_that_is_not_a_backup_is_a_typed_error() {
        let path = temp("junk.zip");
        std::fs::write(&path, b"not a zip").unwrap();
        assert!(matches!(
            backup_summary(path.clone()),
            Err(MotoError::InvalidInput { .. })
        ));
        let (store, a) = open_db("c");
        assert!(store.restore_backup(path.clone(), None).is_err());
        drop(store);
        remove(&a);
        remove(&path);
    }
}
