// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Quick-tags across the FFI (PRD R3): storing tags made while riding and
//! suggesting a section for each after the ride.

use crate::{Engine, LatLon, MotoError, SectionDraft, SectionStore, TrackPoint};
use moto_core::tag as core;

/// A tag as the app creates it: the fix when the button was pressed, and
/// the ride being recorded, if any.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct NewTag {
    /// Milliseconds since the Unix epoch.
    pub time_ms: i64,
    pub position: LatLon,
    /// Direction of travel, degrees clockwise from north, if known.
    pub heading_deg: Option<f64>,
    pub speed_mps: Option<f64>,
    pub track_id: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Tag {
    pub id: i64,
    pub rider_id: String,
    pub time_ms: i64,
    pub position: LatLon,
    pub heading_deg: Option<f64>,
    pub speed_mps: Option<f64>,
    pub track_id: Option<i64>,
}

#[uniffi::export]
impl SectionStore {
    /// Saves a tag, waiting for review.
    pub fn add_tag(&self, tag: NewTag) -> Result<Tag, MotoError> {
        let t = core::NewTag {
            time_ms: tag.time_ms,
            position: tag.position.into(),
            heading_deg: tag.heading_deg,
            speed_mps: tag.speed_mps,
            track_id: tag.track_id,
        };
        Ok(self.store().add_tag(&t)?.into())
    }

    /// All tags (each waiting for review), oldest first.
    pub fn list_tags(&self) -> Result<Vec<Tag>, MotoError> {
        let tags = self.store().list_tags()?;
        Ok(tags.into_iter().map(Into::into).collect())
    }

    /// Deletes a tag once it is reviewed (saved as a favourite section or
    /// discarded); `false` if it did not exist.
    pub fn delete_tag(&self, id: i64) -> Result<bool, MotoError> {
        Ok(self.store().delete_tag(id)?)
    }
}

#[uniffi::export]
impl Engine {
    /// The section suggested for a tag: about 1 km of road on each side,
    /// from its ride's fixes (`track`) when they cover the tag's time,
    /// otherwise by following the road in the tag's heading.
    pub fn suggest_section(
        &self,
        tag: Tag,
        track: Option<Vec<TrackPoint>>,
    ) -> Result<SectionDraft, MotoError> {
        let track: Option<Vec<moto_core::track::TrackPoint>> =
            track.map(|t| t.into_iter().map(Into::into).collect());
        let tag = core::Tag {
            id: tag.id,
            rider_id: tag.rider_id,
            time_ms: tag.time_ms,
            position: tag.position.into(),
            heading_deg: tag.heading_deg,
            speed_mps: tag.speed_mps,
            track_id: tag.track_id,
        };
        Ok(self.inner.suggest_from_tag(&tag, track.as_deref())?.into())
    }
}

impl From<core::Tag> for Tag {
    fn from(t: core::Tag) -> Self {
        Self {
            id: t.id,
            rider_id: t.rider_id,
            time_ms: t.time_ms,
            position: t.position.into(),
            heading_deg: t.heading_deg,
            speed_mps: t.speed_mps,
            track_id: t.track_id,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tags_a_road_and_suggests_a_section() {
        let dir = std::env::temp_dir();
        let db = dir.join(format!("moto-ffi-tags-{}.db", std::process::id()));
        let region = dir.join(format!("moto-ffi-tags-{}.region", std::process::id()));
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", db.display()));
        }
        std::fs::write(&region, moto_core::fixture::region().to_bytes().unwrap()).unwrap();
        let engine = Engine::open(region.to_string_lossy().into_owned()).unwrap();
        std::fs::remove_file(&region).unwrap();
        let store = SectionStore::open(db.to_string_lossy().into_owned()).unwrap();

        let tag = store
            .add_tag(NewTag {
                time_ms: 1_790_000_000_000,
                position: LatLon {
                    lat: 55.7001,
                    lon: 13.205,
                },
                heading_deg: Some(90.0),
                speed_mps: None,
                track_id: None,
            })
            .unwrap();
        assert_eq!(store.list_tags().unwrap(), std::slice::from_ref(&tag));

        let draft = engine.suggest_section(tag.clone(), None).unwrap();
        assert_eq!(draft.ways.len(), 2);
        assert!(draft.distance_m > 900.0);

        assert!(store.delete_tag(tag.id).unwrap());
        assert!(store.list_tags().unwrap().is_empty());
        assert!(!store.delete_tag(tag.id).unwrap());
        assert!(matches!(
            store.add_tag(NewTag {
                heading_deg: Some(-5.0),
                ..NewTag {
                    time_ms: 0,
                    position: LatLon { lat: 0.0, lon: 0.0 },
                    heading_deg: None,
                    speed_mps: None,
                    track_id: None,
                }
            }),
            Err(MotoError::InvalidInput { .. })
        ));
        drop(store);
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", db.display()));
        }
    }
}
