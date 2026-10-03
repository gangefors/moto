// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Riding a route (ADR-0011): the follower, the way back, and the route a
//! ride follows kept in the store.

use std::sync::{Arc, Mutex, MutexGuard};

use moto_core::follow as core;

use crate::sections::Rating;
use crate::tracks::TrackPoint;
use crate::{Engine, LatLon, MotoError, Route, RouteOptions, SectionStore};

/// One GPS fix.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct FollowFix {
    pub position: LatLon,
    /// Milliseconds since the Unix epoch.
    pub time_ms: i64,
    pub accuracy_m: Option<f64>,
    pub speed_mps: Option<f64>,
    pub bearing_deg: Option<f64>,
}

/// Where the rider stands with the route.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FollowPhase {
    /// Not yet on the route's first kilometre.
    Joining,
    OnRoute,
    OffRoute,
    /// At the end.
    Finished,
}

/// A favourite on the route ahead (within 2 km) or under the rider.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct NearFavourite {
    pub rating: Rating,
    /// True while the rider is on it.
    pub on: bool,
    /// To its start, or while on it, to its end, metres.
    pub distance_m: f64,
}

/// The state after a fix.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FollowState {
    pub phase: FollowPhase,
    pub along_m: f64,
    pub left_m: f64,
    pub left_s: f64,
    pub total_m: f64,
    /// The route's segment the rider was last matched to (point `segment`
    /// to `segment + 1` of its line) and how far along it, 0–1.
    pub segment: u32,
    pub segment_t: f64,
    /// How far from the route, while off it or joining; none when no road
    /// of the route is near.
    pub off_m: Option<f64>,
    /// When the rider left the route (fix time), while off it.
    pub off_since_ms: Option<i64>,
    /// At most two, nearest first.
    pub favourites: Vec<NearFavourite>,
    /// Joining because the rider is on the route the wrong way.
    #[uniffi(default = false)]
    pub wrong_way: bool,
    /// The rider has been at the route's start heading along it: the
    /// recording of a ride started with Ride starts then.
    #[uniffi(default = false)]
    pub started: bool,
}

/// The way back to a followed route.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Rejoin {
    pub route: Route,
    /// Where it meets the route, metres along it.
    pub to_along_m: f64,
    /// It starts behind the rider: "Turn round".
    pub turn_round: bool,
}

/// The route a ride follows, as kept in the store.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FollowedRoute {
    pub name: String,
    pub is_loop: bool,
    pub duration_s: f64,
    pub line: Vec<LatLon>,
    pub favourite_parts: Vec<Vec<LatLon>>,
    pub favourite_ratings: Vec<Rating>,
}

/// A line's stretches on favourite sections, each with its rating.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FavouriteParts {
    pub parts: Vec<Vec<LatLon>>,
    pub ratings: Vec<Rating>,
}

/// A favourite near the rider while recording without a route.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FavouriteNearby {
    pub section_id: i64,
    pub rating: Rating,
    /// To its nearest point, metres.
    pub distance_m: f64,
    /// The way to that point, degrees from north (0–360).
    pub bearing_deg: f64,
    /// The rider is on it.
    pub on: bool,
    /// On it: how much of it is left the way the rider is going.
    pub left_m: Option<f64>,
}

impl From<moto_core::near::NearFavourite> for FavouriteNearby {
    fn from(n: moto_core::near::NearFavourite) -> Self {
        Self {
            section_id: n.section_id,
            rating: n.rating.into(),
            distance_m: n.distance_m,
            bearing_deg: n.bearing_deg,
            on: n.on,
            left_m: n.left_m,
        }
    }
}

/// Follows one route through a ride: feed it every fix. Thread-safe.
#[derive(Debug, uniffi::Object)]
pub struct RouteFollower {
    inner: Mutex<core::RouteFollower>,
}

impl RouteFollower {
    fn get(&self) -> MutexGuard<'_, core::RouteFollower> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl RouteFollower {
    /// A follower for the route along `line` taking `duration_s`, with its
    /// favourite parts (in route order) and their ratings, as a `Route`
    /// gives them.
    #[uniffi::constructor]
    pub fn new(
        line: Vec<LatLon>,
        favourite_parts: Vec<Vec<LatLon>>,
        favourite_ratings: Vec<Rating>,
        duration_s: f64,
    ) -> Result<Arc<Self>, MotoError> {
        let parts: Vec<Vec<moto_core::LatLon>> = favourite_parts
            .into_iter()
            .map(|p| p.into_iter().map(Into::into).collect())
            .collect();
        let ratings: Vec<moto_core::section::Rating> =
            favourite_ratings.into_iter().map(Into::into).collect();
        let inner = core::RouteFollower::new(
            line.into_iter().map(Into::into).collect(),
            &parts,
            &ratings,
            duration_s,
        )?;
        Ok(Arc::new(Self {
            inner: Mutex::new(inner),
        }))
    }

    /// Takes one fix; the state after it.
    pub fn update(&self, fix: FollowFix) -> Result<FollowState, MotoError> {
        Ok(self.get().update(fix.into())?.into())
    }

    /// The state now.
    pub fn state(&self) -> FollowState {
        self.get().state().into()
    }
}

#[uniffi::export]
impl Engine {
    /// The quickest way from `position` back to the route `follower`
    /// follows, meeting it as early as it can without riding the route
    /// backwards (when joining, within its first kilometre); fastest roads
    /// under `opts.avoid`. `bearing_deg` (where the rider is heading)
    /// tells whether it starts behind them.
    pub fn rejoin(
        &self,
        position: LatLon,
        bearing_deg: Option<f64>,
        follower: Arc<RouteFollower>,
        opts: RouteOptions,
    ) -> Result<Rejoin, MotoError> {
        let f = follower.get();
        let r = self
            .inner
            .rejoin(position.into(), bearing_deg, &f, &opts.into())?;
        Ok(Rejoin {
            route: r.route.into(),
            to_along_m: r.to_along_m,
            turn_round: r.turn_round,
        })
    }
}

#[uniffi::export]
impl SectionStore {
    /// Keeps `route` as the route ride `track_id` follows until the ride
    /// ends (finishing it drops the route), so following can carry on if
    /// Android stops the app.
    pub fn set_followed_route(&self, track_id: i64, route: FollowedRoute) -> Result<(), MotoError> {
        Ok(self.store().set_followed_route(track_id, &route.into())?)
    }

    /// The route ride `track_id` follows, if any.
    pub fn followed_route(&self, track_id: i64) -> Result<Option<FollowedRoute>, MotoError> {
        Ok(self.store().followed_route(track_id)?.map(Into::into))
    }

    /// The stretches of `line` (a saved route's) on the rider's favourite
    /// sections as they are now, with their ratings: for its glow on the
    /// map and the favourites while riding it.
    pub fn favourite_parts_along(&self, line: Vec<LatLon>) -> Result<FavouriteParts, MotoError> {
        let line: Vec<moto_core::LatLon> = line.into_iter().map(Into::into).collect();
        let found = self.store().favourite_parts_along(&line)?;
        Ok(FavouriteParts {
            ratings: found.iter().map(|p| p.rating.into()).collect(),
            parts: found
                .into_iter()
                .map(|p| p.line.into_iter().map(Into::into).collect())
                .collect(),
        })
    }

    /// The rider's favourites near `position` (at most two, within 5 km,
    /// nearest first), with `heading_deg` for the way they go along one
    /// they are on: for the recording card.
    pub fn near_favourites(
        &self,
        position: LatLon,
        heading_deg: Option<f64>,
    ) -> Result<Vec<FavouriteNearby>, MotoError> {
        Ok(self
            .store()
            .near_favourites(position.into(), heading_deg)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Stops keeping the route ride `track_id` follows.
    pub fn clear_followed_route(&self, track_id: i64) -> Result<bool, MotoError> {
        Ok(self.store().clear_followed_route(track_id)?)
    }

    /// Recording of ride `track_id` starts again after a gap: the next fix
    /// begins a new segment.
    pub fn break_track(&self, track_id: i64) -> Result<(), MotoError> {
        Ok(self.store().break_track(track_id)?)
    }

    /// Ride `track_id`'s fixes split where recording started again after a
    /// gap, for drawing; `None` if there is no such ride.
    pub fn track_segments(&self, track_id: i64) -> Result<Option<Vec<Vec<TrackPoint>>>, MotoError> {
        Ok(self.store().track_segments(track_id)?.map(|segments| {
            segments
                .into_iter()
                .map(|s| s.into_iter().map(Into::into).collect())
                .collect()
        }))
    }
}

impl From<FollowFix> for core::FollowFix {
    fn from(f: FollowFix) -> Self {
        Self {
            position: f.position.into(),
            time_ms: f.time_ms,
            accuracy_m: f.accuracy_m,
            speed_mps: f.speed_mps,
            bearing_deg: f.bearing_deg,
        }
    }
}

impl From<core::FollowPhase> for FollowPhase {
    fn from(p: core::FollowPhase) -> Self {
        match p {
            core::FollowPhase::Joining => Self::Joining,
            core::FollowPhase::OnRoute => Self::OnRoute,
            core::FollowPhase::OffRoute => Self::OffRoute,
            core::FollowPhase::Finished => Self::Finished,
        }
    }
}

impl From<core::FollowState> for FollowState {
    fn from(s: core::FollowState) -> Self {
        Self {
            phase: s.phase.into(),
            along_m: s.along_m,
            left_m: s.left_m,
            left_s: s.left_s,
            total_m: s.total_m,
            segment: s.segment,
            segment_t: s.segment_t,
            off_m: s.off_m,
            off_since_ms: s.off_since_ms,
            favourites: s
                .favourites
                .into_iter()
                .map(|f| NearFavourite {
                    rating: f.rating.into(),
                    on: f.on,
                    distance_m: f.distance_m,
                })
                .collect(),
            wrong_way: s.wrong_way,
            started: s.started,
        }
    }
}

impl From<FollowedRoute> for moto_core::store::FollowedRoute {
    fn from(r: FollowedRoute) -> Self {
        Self {
            name: r.name,
            is_loop: r.is_loop,
            duration_s: r.duration_s,
            line: r.line.into_iter().map(Into::into).collect(),
            parts: r
                .favourite_parts
                .into_iter()
                .map(|p| p.into_iter().map(Into::into).collect())
                .collect(),
            ratings: r.favourite_ratings.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<moto_core::store::FollowedRoute> for FollowedRoute {
    fn from(r: moto_core::store::FollowedRoute) -> Self {
        Self {
            name: r.name,
            is_loop: r.is_loop,
            duration_s: r.duration_s,
            line: r.line.into_iter().map(Into::into).collect(),
            favourite_parts: r
                .parts
                .into_iter()
                .map(|p| p.into_iter().map(Into::into).collect())
                .collect(),
            favourite_ratings: r.ratings.into_iter().map(Into::into).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    fn line() -> Vec<LatLon> {
        (0..100)
            .map(|i| ll(57.0 + f64::from(i) * 0.0002, 14.0))
            .collect()
    }

    #[test]
    fn follows_through_the_ffi() {
        let f = RouteFollower::new(
            line(),
            vec![line()[50..60].to_vec()],
            vec![Rating::Epic],
            60.0,
        )
        .unwrap();
        let s = f
            .update(FollowFix {
                position: line()[10],
                time_ms: 1_000,
                accuracy_m: Some(5.0),
                speed_mps: Some(15.0),
                bearing_deg: Some(0.0),
            })
            .unwrap();
        assert_eq!(s.phase, FollowPhase::OnRoute);
        assert_eq!(s.favourites.len(), 1);
        assert_eq!(s.favourites[0].rating, Rating::Epic);
        assert_eq!(f.state(), s);
    }

    #[test]
    fn bad_input_is_an_error_not_a_panic() {
        assert!(RouteFollower::new(vec![ll(57.0, 14.0)], vec![], vec![], 60.0).is_err());
        assert!(RouteFollower::new(line(), vec![line()], vec![], 60.0).is_err());
        let f = RouteFollower::new(line(), vec![], vec![], 60.0).unwrap();
        assert!(
            f.update(FollowFix {
                position: ll(f64::NAN, 14.0),
                time_ms: 0,
                accuracy_m: None,
                speed_mps: None,
                bearing_deg: None,
            })
            .is_err()
        );
    }

    #[test]
    fn keeps_the_followed_route() {
        let path = std::env::temp_dir().join(format!("moto-ffi-follow-{}.db", std::process::id()));
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
        let store = SectionStore::open(path.to_string_lossy().into_owned()).unwrap();
        let t = store.start_track().unwrap();
        let route = FollowedRoute {
            name: "Loop".into(),
            is_loop: true,
            duration_s: 60.0,
            line: vec![ll(57.0, 14.0), ll(57.5, 14.5)],
            favourite_parts: vec![],
            favourite_ratings: vec![],
        };
        store.set_followed_route(t.id, route.clone()).unwrap();
        assert_eq!(store.followed_route(t.id).unwrap(), Some(route));
        store.break_track(t.id).unwrap();
        assert_eq!(store.track_segments(t.id).unwrap().unwrap().len(), 0);
        assert!(store.clear_followed_route(t.id).unwrap());
        drop(store);
        for suffix in ["", "-wal", "-shm"] {
            let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
        }
    }
}
