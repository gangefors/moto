// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Surface corrections across the FFI (ADR-0018): the stretch under a
//! tapped point, and saving, listing, finding and deleting the rider's
//! corrections. Whole requests in, whole results out; errors are the
//! typed `MotoError`.

use moto_core::region::format::Surface as CoreSurface;
use moto_core::surface as core;

use crate::road::Surface;
use crate::sections::{SectionDraft, SectionStatus, SectionStore, WaySpan, now};
use crate::{Engine, LatLon, MotoError};

/// What the edit sheet sets for a favourite's own surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FavouriteSurface {
    /// Go by the map: the favourite's own surface is cleared.
    Map,
    Paved,
    Gravel,
}

/// What the map alone has along a favourite, by length.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MapSurface {
    Paved,
    Gravel,
    /// No surface in OSM (routed as paved).
    Untagged,
}

/// A correction to save; the rider is always the local one.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NewSurfaceCorrection {
    /// `Paved` or `Gravel`; anything else is refused.
    pub surface: Surface,
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
}

/// A saved correction.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SurfaceCorrection {
    pub id: i64,
    pub surface: Surface,
    /// `Ok`, or `NeedsRematch` while its region is off the open map.
    pub status: SectionStatus,
    /// Seconds since the Unix epoch.
    pub created_at: i64,
    pub updated_at: i64,
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
}

/// A saved correction and the ids of those the save deleted (the one it
/// replaced and older ones it fully covers).
#[derive(Debug, Clone, uniffi::Record)]
pub struct SurfaceSaved {
    pub correction: SurfaceCorrection,
    pub replaced: Vec<i64>,
}

/// What the rider set for the road at a point: exactly one of
/// `correction` (a standalone one) and `favourite_id` (a favourite's own
/// surface) is set.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CorrectionAt {
    pub surface: Surface,
    pub correction: Option<SurfaceCorrection>,
    pub favourite_id: Option<i64>,
}

pub(crate) fn core_surface(s: Surface) -> CoreSurface {
    match s {
        Surface::Unknown => CoreSurface::Unknown,
        Surface::Asphalt => CoreSurface::Asphalt,
        Surface::Concrete => CoreSurface::Concrete,
        Surface::Paved => CoreSurface::Paved,
        Surface::Sett => CoreSurface::Sett,
        Surface::Compacted => CoreSurface::Compacted,
        Surface::Gravel => CoreSurface::Gravel,
        Surface::Dirt => CoreSurface::Dirt,
    }
}

impl From<FavouriteSurface> for moto_core::section::SurfaceSetting {
    fn from(s: FavouriteSurface) -> Self {
        match s {
            FavouriteSurface::Map => Self::Map,
            FavouriteSurface::Paved => Self::Paved,
            FavouriteSurface::Gravel => Self::Gravel,
        }
    }
}

impl From<moto_core::surface::MapSurface> for MapSurface {
    fn from(s: moto_core::surface::MapSurface) -> Self {
        use moto_core::surface::MapSurface as M;
        match s {
            M::Paved => Self::Paved,
            M::Gravel => Self::Gravel,
            M::Untagged => Self::Untagged,
        }
    }
}

impl From<core::SurfaceCorrection> for SurfaceCorrection {
    fn from(c: core::SurfaceCorrection) -> Self {
        Self {
            id: c.id,
            surface: Some(c.surface).into(),
            status: c.status.into(),
            created_at: c.created_at,
            updated_at: c.updated_at,
            ways: c.ways.into_iter().map(Into::into).collect(),
            geometry: c.geometry.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<core::CorrectionAt> for CorrectionAt {
    fn from(c: core::CorrectionAt) -> Self {
        match c {
            core::CorrectionAt::Favourite {
                section_id,
                surface,
            } => Self {
                surface: Some(surface).into(),
                correction: None,
                favourite_id: Some(section_id),
            },
            core::CorrectionAt::Standalone(c) => Self {
                surface: Some(c.surface).into(),
                correction: Some(c.into()),
                favourite_id: None,
            },
        }
    }
}

#[uniffi::export]
impl Engine {
    /// The OSM way under `point` (at most 10 km each side of it), as a
    /// stretch to correct.
    pub fn surface_stretch_at(&self, point: LatLon) -> Result<SectionDraft, MotoError> {
        Ok(self.inner.surface_stretch_at(point.into())?.into())
    }

    /// What the map alone has along a line's way spans, by length: for a
    /// favourite's sheet, new or saved. None when no span is on this map;
    /// no spans is an error. Costs one pass over the map's roads: call it
    /// off the main thread.
    pub fn map_surface_along(&self, ways: Vec<WaySpan>) -> Result<Option<MapSurface>, MotoError> {
        let ways: Vec<moto_core::section::WaySpan> = ways.into_iter().map(Into::into).collect();
        Ok(self.inner.map_surface_along(&ways)?.map(Into::into))
    }
}

#[uniffi::export]
impl SectionStore {
    /// Saves a correction, deleting `replaces` (the one being edited) and
    /// every older one it fully covers, in one transaction. Only `Paved`
    /// and `Gravel` are corrections.
    pub fn add_surface_correction(
        &self,
        correction: NewSurfaceCorrection,
        replaces: Option<i64>,
    ) -> Result<SurfaceSaved, MotoError> {
        let new = core::NewSurfaceCorrection {
            rider_id: moto_core::section::LOCAL_RIDER.into(),
            surface: core_surface(correction.surface),
            ways: correction.ways.into_iter().map(Into::into).collect(),
            geometry: correction.geometry.into_iter().map(Into::into).collect(),
        };
        let saved = self.store().add_surface_correction(&new, replaces, now())?;
        Ok(SurfaceSaved {
            correction: saved.correction.into(),
            replaced: saved.replaced,
        })
    }

    /// Every correction, oldest first.
    pub fn surface_corrections(&self) -> Result<Vec<SurfaceCorrection>, MotoError> {
        Ok(self
            .store()
            .surface_corrections()?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// What the rider set for the road at `point` (within 25 m): a
    /// favourite's own surface first, else the newest standalone
    /// correction there.
    pub fn surface_correction_at(&self, point: LatLon) -> Result<Option<CorrectionAt>, MotoError> {
        Ok(self
            .store()
            .surface_correction_at(point.into(), core::CORRECTION_REACH_M)?
            .map(Into::into))
    }

    /// Deletes a correction; `false` if there was none.
    pub fn delete_surface_correction(&self, id: i64) -> Result<bool, MotoError> {
        Ok(self.store().delete_surface_correction(id)?)
    }
}

/// The FFI surface of `s`, for tests elsewhere in the crate.
#[cfg(test)]
pub(crate) fn surface_of(s: CoreSurface) -> Surface {
    Some(s).into()
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;

    fn store() -> Arc<SectionStore> {
        SectionStore::open(":memory:".into()).unwrap()
    }

    fn sample(surface: Surface) -> NewSurfaceCorrection {
        NewSurfaceCorrection {
            surface,
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
                    lon: 12.45,
                },
            ],
        }
    }

    #[test]
    fn corrections_go_both_ways() {
        let s = store();
        let saved = s
            .add_surface_correction(sample(Surface::Gravel), None)
            .unwrap();
        assert!(saved.replaced.is_empty());
        let c = &saved.correction;
        assert_eq!(
            (c.surface, c.status, c.ways.len()),
            (Surface::Gravel, SectionStatus::Ok, 1)
        );
        let all = s.surface_corrections().unwrap();
        assert_eq!(all.len(), 1);
        let at = s
            .surface_correction_at(LatLon {
                lat: 56.305,
                lon: 12.4501,
            })
            .unwrap()
            .unwrap();
        assert_eq!(at.surface, Surface::Gravel);
        assert_eq!(at.correction.unwrap().id, c.id);
        assert_eq!(at.favourite_id, None);
        // Edited: replaced, one left.
        let again = s
            .add_surface_correction(sample(Surface::Paved), Some(c.id))
            .unwrap();
        assert_eq!(again.replaced, [c.id]);
        assert_eq!(s.surface_corrections().unwrap().len(), 1);
        assert!(s.delete_surface_correction(again.correction.id).unwrap());
        assert!(!s.delete_surface_correction(again.correction.id).unwrap());
    }

    #[test]
    fn only_paved_and_gravel_are_saved() {
        let s = store();
        for bad in [
            Surface::Unknown,
            Surface::Asphalt,
            Surface::Concrete,
            Surface::Sett,
            Surface::Compacted,
            Surface::Dirt,
        ] {
            assert!(
                matches!(
                    s.add_surface_correction(sample(bad), None),
                    Err(MotoError::InvalidInput { .. })
                ),
                "{bad:?}"
            );
        }
        let mut nan = sample(Surface::Paved);
        nan.geometry[0].lat = f64::NAN;
        assert!(s.add_surface_correction(nan, None).is_err());
        assert!(s.surface_corrections().unwrap().is_empty());
    }

    #[test]
    fn conversions_both_ways() {
        for s in CoreSurface::ALL {
            assert_eq!(core_surface(surface_of(s)), s);
        }
        use moto_core::section::SurfaceSetting;
        assert_eq!(
            SurfaceSetting::from(FavouriteSurface::Map),
            SurfaceSetting::Map
        );
        assert_eq!(
            SurfaceSetting::from(FavouriteSurface::Gravel),
            SurfaceSetting::Gravel
        );
        assert_eq!(
            MapSurface::from(moto_core::surface::MapSurface::Untagged),
            MapSurface::Untagged
        );
        let fav: CorrectionAt = core::CorrectionAt::Favourite {
            section_id: 7,
            surface: CoreSurface::Paved,
        }
        .into();
        assert_eq!((fav.surface, fav.favourite_id), (Surface::Paved, Some(7)));
        assert!(fav.correction.is_none());
    }
}
