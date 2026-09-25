// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Saved routes across the FFI: routes and loops the rider keeps to ride
//! again, with a name and the line as it was found.

use crate::sections::now;
use crate::{LatLon, MotoError, Route, SectionStore};
use moto_core::store as core;

/// A saved route, without its line (see `SectionStore::route_geometry`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SavedRoute {
    pub id: i64,
    pub rider_id: String,
    pub name: String,
    /// A round trip, rather than a route from A to B.
    pub is_loop: bool,
    /// Seconds since the Unix epoch.
    pub created_at: i64,
    pub distance_m: f64,
    pub duration_s: f64,
}

impl From<core::SavedRoute> for SavedRoute {
    fn from(r: core::SavedRoute) -> Self {
        Self {
            id: r.id,
            rider_id: r.rider_id,
            name: r.name,
            is_loop: r.is_loop,
            created_at: r.created_at,
            distance_m: r.distance_m,
            duration_s: r.duration_s,
        }
    }
}

#[uniffi::export]
impl SectionStore {
    /// Saves `route` (as found by `Engine.route` or `Engine.round_trip`)
    /// under `name`: its line, length and time.
    pub fn save_route(
        &self,
        name: String,
        is_loop: bool,
        route: Route,
    ) -> Result<SavedRoute, MotoError> {
        let new = core::NewRoute {
            name,
            is_loop,
            distance_m: route.distance_m,
            duration_s: route.duration_s,
            geometry: route.geometry.into_iter().map(Into::into).collect(),
        };
        Ok(self.store().save_route(&new, now())?.into())
    }

    /// All saved routes, newest first.
    pub fn list_routes(&self) -> Result<Vec<SavedRoute>, MotoError> {
        Ok(self
            .store()
            .list_routes()?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// A saved route's line; `None` if there is no such route.
    pub fn route_geometry(&self, id: i64) -> Result<Option<Vec<LatLon>>, MotoError> {
        Ok(self
            .store()
            .route_geometry(id)?
            .map(|g| g.into_iter().map(Into::into).collect()))
    }

    /// Renames a saved route; `false` if there is no such route.
    pub fn rename_route(&self, id: i64, name: String) -> Result<bool, MotoError> {
        Ok(self.store().rename_route(id, &name)?)
    }

    /// Deletes a saved route; `false` if it did not exist.
    pub fn delete_route(&self, id: i64) -> Result<bool, MotoError> {
        Ok(self.store().delete_route(id)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saves_and_reads_routes_across_the_ffi() {
        let path = std::env::temp_dir().join(format!("moto-ffi-routes-{}.db", std::process::id()));
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
        let store = SectionStore::open(path.to_string_lossy().into_owned()).unwrap();
        let line = vec![
            LatLon {
                lat: 55.7,
                lon: 13.2,
            },
            LatLon {
                lat: 55.71,
                lon: 13.21,
            },
        ];
        let route = Route {
            geometry: line.clone(),
            distance_m: 1300.0,
            duration_s: 90.0,
            favourite_share: 0.0,
            curvy_share: 0.0,
            fastest_duration_s: 90.0,
            favourite_parts: vec![],
            unpaved_m: 0.0,
            unpaved_parts: vec![],
        };
        let saved = store
            .save_route("Lund & back".into(), true, route.clone())
            .unwrap();
        assert!(saved.is_loop && saved.created_at > 1_700_000_000);
        assert_eq!(store.list_routes().unwrap(), std::slice::from_ref(&saved));
        assert_eq!(store.route_geometry(saved.id).unwrap().unwrap(), line);
        assert!(store.rename_route(saved.id, "Lund".into()).unwrap());
        assert!(matches!(
            store.save_route("a\u{0}".into(), false, route),
            Err(MotoError::InvalidInput { .. })
        ));
        assert!(store.delete_route(saved.id).unwrap());
        assert_eq!(store.route_geometry(saved.id).unwrap(), None);
        drop(store);
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
    }
}
