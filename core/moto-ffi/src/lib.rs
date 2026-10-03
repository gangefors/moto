// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! UniFFI bindings for `moto-core`.
//!
//! This is the only crate with FFI attributes. It mirrors the core types as
//! UniFFI records and keeps the surface coarse: one call per request, whole
//! results back, typed errors instead of panics.

use std::sync::Arc;

mod backup;
mod exchange;
mod follow;
mod regions;
mod road;
mod routes;
mod sections;
mod tags;
mod tracks;
pub use exchange::*;
pub use follow::*;
pub use regions::*;
pub use road::*;
pub use routes::*;
pub use sections::*;
pub use tags::*;
pub use tracks::*;

uniffi::setup_scaffolding!();

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LatLon {
    pub lat: f64,
    pub lon: f64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RoadPoint {
    pub position: LatLon,
    pub distance_m: f64,
    pub edge: u32,
    pub offset: f64,
}

/// Road types to stay off where possible; all avoided by default.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct Avoid {
    pub motorways: bool,
    pub ferries: bool,
    /// Roads a motorcycle pays toll on.
    #[uniffi(default = true)]
    pub tolls: bool,
}

/// What a route does with gravel and other unpaved roads.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Gravel {
    /// Keep off them where possible, except on the rider's favourites.
    Avoid,
    /// Treat them like any other road.
    Allow,
    /// Seek them out ("adv" riding), within the time budget.
    Prefer,
}

/// What a route does with the rider's favourite sections.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FavouritesMode {
    /// Seek them out, within the time budget.
    Prefer,
    /// Keep off them where possible, to find new roads.
    Avoid,
}

/// What a route does with roads the rider's rides have been on
/// (ADR-0010).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum UnriddenMode {
    /// Ridden or not, every road counts the same.
    Any,
    /// Seek out curvy roads no ride has been on.
    Prefer,
}

/// How much time a route may take; the time over the fastest route is
/// spent on favourites.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum TimeBudget {
    /// Up to this fraction more than the fastest route (0.4 = 40 %).
    Extra { ratio: f64 },
    /// At most this many seconds in all (e.g. to arrive by a set time).
    Total { seconds: f64 },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct RouteOptions {
    pub avoid: Avoid,
    pub budget: TimeBudget,
    /// Seconds of rating-weighted favourite riding each extra second must
    /// buy (0 = spend the whole budget if it adds any favourite road).
    pub min_gain: f64,
    /// Whether curvy roads pull the route too, besides favourites.
    pub curvy: bool,
    pub gravel: Gravel,
    pub favourites: FavouritesMode,
    pub unridden: UnriddenMode,
}

#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum RoundTripTarget {
    DistanceM { meters: f64 },
    DurationS { seconds: f64 },
}

/// How round trips are shaped beyond their length: `seed` 0 gives the
/// standard loops, any other value another set (the same for the same
/// seed); `bearing` (degrees clockwise from north) is the way they should
/// head, or any way when absent.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct LoopOptions {
    pub seed: u32,
    pub bearing: Option<f64>,
}

impl From<LoopOptions> for moto_core::LoopOptions {
    fn from(o: LoopOptions) -> Self {
        Self {
            seed: o.seed,
            bearing: o.bearing,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Route {
    pub geometry: Vec<LatLon>,
    pub distance_m: f64,
    pub duration_s: f64,
    pub favourite_share: f64,
    pub curvy_share: f64,
    /// Share of the distance on roads none of the rider's rides has been
    /// on (1.0 with no rides).
    #[uniffi(default = 1.0)]
    pub unridden_share: f64,
    /// Time of the fastest route between the same points.
    pub fastest_duration_s: f64,
    /// The stretches of `geometry` on favourite sections, for highlighting.
    pub favourite_parts: Vec<Vec<LatLon>>,
    /// The rating of the section each favourite part runs on, one per
    /// part, for drawing each in its rating's colour.
    #[uniffi(default = [])]
    pub favourite_ratings: Vec<sections::Rating>,
    /// Metres on gravel and other unpaved roads, and those stretches of
    /// `geometry`, for marking them.
    pub unpaved_m: f64,
    pub unpaved_parts: Vec<Vec<LatLon>>,
    /// Metres on roads a motorcycle pays toll on (ferries not counted).
    #[uniffi(default = 0.0)]
    pub toll_m: f64,
    /// A route worth riding among route choices; the fastest has it too
    /// when the best choice was the same road (then offered once).
    #[uniffi(default = false)]
    pub suggested: bool,
}

/// Crosses the FFI as a flat error: each variant becomes an exception class
/// whose message is the `Display` text. (A field named `message` would clash
/// with `Throwable.message` in the generated Kotlin.)
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum MotoError {
    #[error("{message}")]
    InvalidInput { message: String },
    #[error("{message}")]
    Region { message: String },
    #[error("{message}")]
    Storage { message: String },
    #[error("{message}")]
    OutsideRegion { message: String },
    #[error("{message}")]
    NoRoadNearby { message: String },
    #[error("{message}")]
    NoRoute { message: String },
}

/// What a loaded region covers.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RegionInfo {
    /// South-west corner of the region's bounding box.
    pub south_west: LatLon,
    /// North-east corner of the region's bounding box.
    pub north_east: LatLon,
    /// Timestamp of the OSM data, seconds since the Unix epoch (0 if unknown).
    pub osm_timestamp: i64,
    /// Where the data came from, e.g. the extract name and bounding box.
    pub source_name: String,
    /// ISO country code of a single region (`SE`); none for the whole
    /// network or older files.
    #[uniffi(default = None)]
    pub country: Option<String>,
}

/// Default route options, so the app does not duplicate the core's defaults.
#[uniffi::export]
pub fn default_route_options() -> RouteOptions {
    moto_core::RouteOptions::default().into()
}

/// Checks a downloaded region file before it is installed: every section's
/// checksum, then the structure `Engine.open` relies on.
#[uniffi::export]
pub fn verify_region_file(path: String) -> Result<(), MotoError> {
    Ok(moto_core::region::verify_file(path)?)
}

/// One timed step of opening a region (see `profile_region_open`).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OpenStep {
    pub name: String,
    pub ms: f64,
}

/// Opens and validates a region file like `Engine.open`, timing each
/// step, to find what makes opening slow on a device.
#[uniffi::export]
pub fn profile_region_open(path: String) -> Result<Vec<OpenStep>, MotoError> {
    Ok(moto_core::region::profile_open(path)?
        .into_iter()
        .map(|(name, ms)| OpenStep { name, ms })
        .collect())
}

/// A region file to open with [`open_regions`], and the fingerprint
/// recorded when it last passed a full check, if any.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RegionFile {
    pub path: String,
    pub fingerprint: Option<String>,
}

/// Several regions opened as one network, and each file's fingerprint as
/// it stands now (to keep for the next open).
#[derive(Debug, Clone, uniffi::Record)]
pub struct OpenedRegions {
    pub engine: Arc<Engine>,
    pub fingerprints: Vec<String>,
}

/// Opens the enabled regions as one network, linked at their borders
/// (ADR-0009): routes, loops and matching run across them. A file whose
/// fingerprint matches is opened quickly (see `Engine.open_fingerprinted`);
/// one without, or that no longer matches, is checked in full and its new
/// fingerprint returned. Any file failing its check fails the whole open,
/// naming the file. At most 16 regions.
#[uniffi::export]
pub fn open_regions(files: Vec<RegionFile>) -> Result<OpenedRegions, MotoError> {
    let mut regions = Vec::with_capacity(files.len());
    let mut fingerprints = Vec::with_capacity(files.len());
    for f in &files {
        let known = f
            .fingerprint
            .as_deref()
            .map(regions::parse_fingerprint)
            .transpose()?;
        let quick = known.and_then(|fp| {
            moto_core::region::Region::open_fingerprinted(&f.path, &fp)
                .ok()
                .map(|r| (r, f.fingerprint.clone().unwrap_or_default()))
        });
        let (region, fp) = match quick {
            Some(opened) => opened,
            None => {
                let named = |e: moto_core::CoreError| {
                    MotoError::from(moto_core::CoreError::Region(format!("{}: {e}", f.path)))
                };
                let region = moto_core::region::Region::open(&f.path).map_err(named)?;
                (region, regions::region_fingerprint(f.path.clone())?)
            }
        };
        regions.push(region);
        fingerprints.push(fp);
    }
    let inner = moto_core::Engine::from_regions(regions)?;
    Ok(OpenedRegions {
        engine: Arc::new(Engine { inner }),
        fingerprints,
    })
}

/// A loaded routing region. Thread-safe; share one instance per region.
#[derive(Debug, uniffi::Object)]
pub struct Engine {
    inner: moto_core::Engine,
}

#[uniffi::export]
impl Engine {
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>, MotoError> {
        let inner = moto_core::Engine::open(path)?;
        Ok(Arc::new(Self { inner }))
    }

    /// Opens a region file that passed `Engine.open` or `install_region`
    /// before, proven unchanged by the `fingerprint` recorded then (see
    /// `region_fingerprint`): much faster than a full check. A file that
    /// does not match is refused; open it with `Engine.open` instead.
    #[uniffi::constructor]
    pub fn open_fingerprinted(path: String, fingerprint: String) -> Result<Arc<Self>, MotoError> {
        let fp = regions::parse_fingerprint(&fingerprint)?;
        let inner = moto_core::Engine::open_fingerprinted(path, &fp)?;
        Ok(Arc::new(Self { inner }))
    }

    /// The open regions' bounds and data source: the box around them all,
    /// the oldest data, and the sources joined.
    pub fn info(&self) -> RegionInfo {
        let (sw, ne) = self.inner.bounds();
        let regions = self.inner.net().regions();
        RegionInfo {
            south_west: sw.into(),
            north_east: ne.into(),
            osm_timestamp: regions
                .iter()
                .map(|r| r.info().osm_timestamp)
                .min()
                .unwrap_or(0),
            source_name: regions
                .iter()
                .map(|r| r.info().source_name.clone())
                .collect::<Vec<_>>()
                .join(" + "),
            country: None,
        }
    }

    /// Each open region's bounds, data and country, in the order opened.
    pub fn region_infos(&self) -> Vec<RegionInfo> {
        self.inner
            .net()
            .regions()
            .iter()
            .map(|r| {
                let (info, b) = (r.info(), r.info().bbox);
                let ll = |lat: i32, lon: i32| LatLon {
                    lat: f64::from(lat) / 1e7,
                    lon: f64::from(lon) / 1e7,
                };
                RegionInfo {
                    south_west: ll(b.min_lat, b.min_lon),
                    north_east: ll(b.max_lat, b.max_lon),
                    osm_timestamp: info.osm_timestamp,
                    source_name: info.source_name.clone(),
                    country: r.country().map(str::to_owned),
                }
            })
            .collect()
    }

    /// How many border crossings join the open regions.
    pub fn link_count(&self) -> u32 {
        self.inner.net().link_count() as u32
    }

    /// Where the region's roads are: closed rings (first point repeated
    /// last). Empty for older region files; then the bounding box is all
    /// there is to show.
    pub fn coverage(&self) -> Vec<Vec<LatLon>> {
        self.inner
            .coverage()
            .into_iter()
            .map(|ring| ring.into_iter().map(Into::into).collect())
            .collect()
    }

    pub fn snap(&self, point: LatLon) -> Result<RoadPoint, MotoError> {
        Ok(self.inner.snap(point.into())?.into())
    }

    /// Route from `from` to `to` through the `via` points in order (at
    /// most 8; each leg routed on its own): the fastest one, or with
    /// `favourites` (from `SectionStore.favourites` for this region) the
    /// one over as many of them as `opts.budget` buys.
    pub fn route(
        &self,
        from: LatLon,
        via: Vec<LatLon>,
        to: LatLon,
        opts: RouteOptions,
        favourites: Option<Arc<Favourites>>,
    ) -> Result<Route, MotoError> {
        let none = moto_core::Favourites::none();
        let fav = favourites.as_ref().map_or(&none, |f| &f.inner);
        let via: Vec<moto_core::LatLon> = via.into_iter().map(Into::into).collect();
        Ok(self
            .inner
            .route_via(from.into(), &via, to.into(), &opts.into(), fav)?
            .into())
    }

    /// Routes to choose from, like a nav app offers (PRD R5, R6): up to
    /// three worth riding over `favourites` and curvy roads within
    /// `opts.budget`, each sharing less than half its roads with the
    /// others, then the fastest, always last. Through `via` points each
    /// leg has its own choices, joined into whole routes (the first as
    /// `route` gives it).
    pub fn route_choices(
        &self,
        from: LatLon,
        via: Vec<LatLon>,
        to: LatLon,
        opts: RouteOptions,
        favourites: Option<Arc<Favourites>>,
    ) -> Result<Vec<Route>, MotoError> {
        let none = moto_core::Favourites::none();
        let fav = favourites.as_ref().map_or(&none, |f| &f.inner);
        let via: Vec<moto_core::LatLon> = via.into_iter().map(Into::into).collect();
        Ok(self
            .inner
            .route_choices(from.into(), &via, to.into(), &opts.into(), fav)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// A route as GPX for a nav app (PRD R9): route points chosen so
    /// that a nav app's own routing between them stays on `geometry` (the
    /// route's line, as `route` returned it), and the line itself as a
    /// track. `opts` are the options the route was made with.
    pub fn route_gpx(
        &self,
        geometry: Vec<LatLon>,
        name: String,
        opts: RouteOptions,
    ) -> Result<String, MotoError> {
        let line: Vec<moto_core::LatLon> = geometry.into_iter().map(Into::into).collect();
        let points = moto_core::handoff::route_points(&self.inner, &line, &opts.into())?;
        Ok(moto_core::gpx::route_gpx(&name, &points, &line))
    }

    /// Up to three round trips from `start` of about `target` (±15 %),
    /// best first, each riding at most 10 % of its length twice
    /// (ADR-0007). `opts.budget` does not apply; with `favourites` the
    /// loops go through and along them where they can; `shape` gives
    /// other sets of loops.
    pub fn round_trip(
        &self,
        start: LatLon,
        target: RoundTripTarget,
        opts: RouteOptions,
        favourites: Option<Arc<Favourites>>,
        shape: LoopOptions,
    ) -> Result<Vec<Route>, MotoError> {
        let none = moto_core::Favourites::none();
        let fav = favourites.as_ref().map_or(&none, |f| &f.inner);
        let routes = self.inner.round_trip_with(
            start.into(),
            target.into(),
            &opts.into(),
            fav,
            &shape.into(),
        )?;
        Ok(routes.into_iter().map(Into::into).collect())
    }

    /// Loops from `start` through `stops` in order and back (at most 8),
    /// e.g. a favourite section's two ends to ride it from here; with
    /// `both_ways` also through them the other way round. Made like
    /// ordinary loops, going on past the last stop; up to three, best
    /// first; the way back keeps off the roads out.
    pub fn round_trip_via(
        &self,
        start: LatLon,
        stops: Vec<LatLon>,
        both_ways: bool,
        opts: RouteOptions,
        favourites: Option<Arc<Favourites>>,
    ) -> Result<Vec<Route>, MotoError> {
        let none = moto_core::Favourites::none();
        let fav = favourites.as_ref().map_or(&none, |f| &f.inner);
        let stops: Vec<moto_core::LatLon> = stops.into_iter().map(Into::into).collect();
        let routes =
            self.inner
                .round_trip_via(start.into(), &stops, both_ways, &opts.into(), fav)?;
        Ok(routes.into_iter().map(Into::into).collect())
    }
}

// --- conversions between FFI records and core types ---

impl From<LatLon> for moto_core::LatLon {
    fn from(p: LatLon) -> Self {
        Self {
            lat: p.lat,
            lon: p.lon,
        }
    }
}

impl From<moto_core::LatLon> for LatLon {
    fn from(p: moto_core::LatLon) -> Self {
        Self {
            lat: p.lat,
            lon: p.lon,
        }
    }
}

impl From<moto_core::RoadPoint> for RoadPoint {
    fn from(p: moto_core::RoadPoint) -> Self {
        Self {
            position: p.position.into(),
            distance_m: p.distance_m,
            edge: p.edge,
            offset: p.offset,
        }
    }
}

impl From<Avoid> for moto_core::Avoid {
    fn from(a: Avoid) -> Self {
        Self {
            motorways: a.motorways,
            ferries: a.ferries,
            tolls: a.tolls,
        }
    }
}

impl From<moto_core::Avoid> for Avoid {
    fn from(a: moto_core::Avoid) -> Self {
        Self {
            motorways: a.motorways,
            ferries: a.ferries,
            tolls: a.tolls,
        }
    }
}

impl From<Gravel> for moto_core::Gravel {
    fn from(g: Gravel) -> Self {
        match g {
            Gravel::Avoid => Self::Avoid,
            Gravel::Allow => Self::Allow,
            Gravel::Prefer => Self::Prefer,
        }
    }
}

impl From<moto_core::Gravel> for Gravel {
    fn from(g: moto_core::Gravel) -> Self {
        match g {
            moto_core::Gravel::Avoid => Self::Avoid,
            moto_core::Gravel::Allow => Self::Allow,
            moto_core::Gravel::Prefer => Self::Prefer,
        }
    }
}

impl From<FavouritesMode> for moto_core::FavouritesMode {
    fn from(f: FavouritesMode) -> Self {
        match f {
            FavouritesMode::Prefer => Self::Prefer,
            FavouritesMode::Avoid => Self::Avoid,
        }
    }
}

impl From<moto_core::FavouritesMode> for FavouritesMode {
    fn from(f: moto_core::FavouritesMode) -> Self {
        match f {
            moto_core::FavouritesMode::Prefer => Self::Prefer,
            moto_core::FavouritesMode::Avoid => Self::Avoid,
        }
    }
}

impl From<UnriddenMode> for moto_core::UnriddenMode {
    fn from(u: UnriddenMode) -> Self {
        match u {
            UnriddenMode::Any => Self::Any,
            UnriddenMode::Prefer => Self::Prefer,
        }
    }
}

impl From<moto_core::UnriddenMode> for UnriddenMode {
    fn from(u: moto_core::UnriddenMode) -> Self {
        match u {
            moto_core::UnriddenMode::Any => Self::Any,
            moto_core::UnriddenMode::Prefer => Self::Prefer,
        }
    }
}

impl From<RouteOptions> for moto_core::RouteOptions {
    fn from(o: RouteOptions) -> Self {
        Self {
            avoid: o.avoid.into(),
            budget: match o.budget {
                TimeBudget::Extra { ratio } => moto_core::TimeBudget::Extra(ratio),
                TimeBudget::Total { seconds } => moto_core::TimeBudget::Total(seconds),
            },
            min_gain: o.min_gain,
            curvy: o.curvy,
            gravel: o.gravel.into(),
            favourites: o.favourites.into(),
            unridden: o.unridden.into(),
        }
    }
}

impl From<moto_core::RouteOptions> for RouteOptions {
    fn from(o: moto_core::RouteOptions) -> Self {
        Self {
            avoid: o.avoid.into(),
            budget: match o.budget {
                moto_core::TimeBudget::Extra(ratio) => TimeBudget::Extra { ratio },
                moto_core::TimeBudget::Total(seconds) => TimeBudget::Total { seconds },
            },
            min_gain: o.min_gain,
            curvy: o.curvy,
            gravel: o.gravel.into(),
            favourites: o.favourites.into(),
            unridden: o.unridden.into(),
        }
    }
}

impl From<RoundTripTarget> for moto_core::RoundTripTarget {
    fn from(t: RoundTripTarget) -> Self {
        match t {
            RoundTripTarget::DistanceM { meters } => Self::DistanceM(meters),
            RoundTripTarget::DurationS { seconds } => Self::DurationS(seconds),
        }
    }
}

impl From<moto_core::Route> for Route {
    fn from(r: moto_core::Route) -> Self {
        Self {
            geometry: r.geometry.into_iter().map(Into::into).collect(),
            distance_m: r.distance_m,
            duration_s: r.duration_s,
            favourite_share: r.favourite_share,
            curvy_share: r.curvy_share,
            unridden_share: r.unridden_share,
            fastest_duration_s: r.fastest_duration_s,
            favourite_parts: r
                .favourite_parts
                .into_iter()
                .map(|p| p.into_iter().map(Into::into).collect())
                .collect(),
            favourite_ratings: r.favourite_ratings.into_iter().map(Into::into).collect(),
            unpaved_m: r.unpaved_m,
            toll_m: r.toll_m,
            unpaved_parts: r
                .unpaved_parts
                .into_iter()
                .map(|p| p.into_iter().map(Into::into).collect())
                .collect(),
            suggested: r.suggested,
        }
    }
}

impl From<moto_core::CoreError> for MotoError {
    fn from(e: moto_core::CoreError) -> Self {
        use moto_core::CoreError as C;
        let message = e.to_string();
        match e {
            C::InvalidCoordinate { .. } | C::InvalidArgument(_) => Self::InvalidInput { message },
            C::Region(_) => Self::Region { message },
            C::Storage(_) => Self::Storage { message },
            C::OutsideRegion { .. } => Self::OutsideRegion { message },
            C::NoRoadNearby { .. } => Self::NoRoadNearby { message },
            C::NoRoute(_) => Self::NoRoute { message },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_missing_region_is_a_typed_error() {
        let err = Engine::open("/definitely/not/here.region".into()).unwrap_err();
        assert!(matches!(err, MotoError::Region { .. }), "got {err:?}");
    }

    #[test]
    fn verify_missing_region_is_a_typed_error() {
        let err = verify_region_file("/definitely/not/here.region".into()).unwrap_err();
        assert!(matches!(err, MotoError::Region { .. }), "got {err:?}");
    }

    #[test]
    fn default_options_round_trip_through_ffi_types() {
        let ffi = default_route_options();
        let core: moto_core::RouteOptions = ffi.into();
        assert_eq!(core, moto_core::RouteOptions::default());
    }

    /// A region file on disk, removed when dropped.
    struct TempRegion(std::path::PathBuf);

    impl TempRegion {
        /// A file of its own, even when two tests pick the same name: tests
        /// run in parallel, and one rewriting or removing a file another
        /// has mapped kills the process (SIGBUS).
        fn new(name: &str, bytes: &[u8]) -> Self {
            static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
            let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            let path = std::env::temp_dir()
                .join(format!("moto-ffi-{name}-{}-{n}.region", std::process::id()));
            std::fs::write(&path, bytes).unwrap();
            Self(path)
        }

        fn path(&self) -> String {
            self.0.to_string_lossy().into_owned()
        }
    }

    impl Drop for TempRegion {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    #[test]
    fn temp_regions_of_the_same_name_are_separate_files() {
        let (a, b) = (TempRegion::new("same", b"a"), TempRegion::new("same", b"b"));
        assert_ne!(a.path(), b.path());
        drop(a);
        assert_eq!(std::fs::read(b.path()).unwrap(), b"b");
    }

    fn fixture_file(name: &str) -> TempRegion {
        TempRegion::new(name, &moto_core::fixture::region().to_bytes().unwrap())
    }

    fn ll(lat: f64, lon: f64) -> LatLon {
        LatLon { lat, lon }
    }

    #[test]
    fn opens_several_regions_by_fingerprint_or_in_full() {
        let (a, b) = (fixture_file("net-a"), fixture_file("net-b"));
        let fp_a = regions::region_fingerprint(a.path()).unwrap();
        let file = |p: String, fp: Option<String>| RegionFile {
            path: p,
            fingerprint: fp,
        };
        // A by its fingerprint, B without one: B is checked in full and
        // its fingerprint comes back.
        let opened = open_regions(vec![
            file(a.path(), Some(fp_a.clone())),
            file(b.path(), None),
        ])
        .unwrap();
        assert_eq!(opened.fingerprints[0], fp_a);
        assert_eq!(
            opened.fingerprints[1],
            regions::region_fingerprint(b.path()).unwrap()
        );
        let infos = opened.engine.region_infos();
        assert_eq!(infos.len(), 2);
        assert_eq!(infos[0].country, None, "the fixture names no country");
        assert_eq!(opened.engine.link_count(), 0, "no border tables");
        assert!(opened.engine.info().source_name.contains(" + "));
        // A wrong fingerprint falls back to the full check.
        let wrong = format!(
            "{}{}",
            if fp_a.starts_with('0') { '1' } else { '0' },
            &fp_a[1..]
        );
        let again = open_regions(vec![file(a.path(), Some(wrong))]).unwrap();
        assert_eq!(again.fingerprints, [fp_a]);
        // A file that isn't a region fails the open, naming it.
        let bad = TempRegion::new("net-bad", &[0u8; 5000]);
        match open_regions(vec![file(a.path(), None), file(bad.path(), None)]) {
            Err(MotoError::Region { message }) => assert!(message.contains("net-bad"), "{message}"),
            other => panic!("{other:?}"),
        }
        assert!(open_regions(vec![]).is_err());
        assert!(open_regions(vec![file(a.path(), Some("not hex".into()))]).is_err());
    }

    #[test]
    fn opens_verifies_and_describes_a_region() {
        let file = fixture_file("info");
        verify_region_file(file.path()).unwrap();
        let engine = Engine::open(file.path()).unwrap();
        let info = engine.info();
        assert_eq!((info.south_west.lat, info.south_west.lon), (55.695, 13.195));
        assert_eq!((info.north_east.lat, info.north_east.lon), (55.715, 13.225));
        assert_eq!(info.osm_timestamp, 1_790_000_000);
        assert_eq!(info.source_name, "hand-made test fixture");
        let rings = engine.coverage();
        assert_eq!(rings.len(), 1);
        assert!(rings[0].len() >= 4 && rings[0].first() == rings[0].last());
    }

    #[test]
    fn opens_by_fingerprint_across_the_ffi() {
        let file = fixture_file("fingerprint");
        let fp = regions::region_fingerprint(file.path()).unwrap();
        assert_eq!(fp.len(), 64);
        let engine = Engine::open_fingerprinted(file.path(), fp.clone()).unwrap();
        assert_eq!(engine.info().osm_timestamp, 1_790_000_000);
        let other = format!(
            "{}{}",
            if fp.starts_with('0') { '1' } else { '0' },
            &fp[1..]
        );
        assert!(matches!(
            Engine::open_fingerprinted(file.path(), other),
            Err(MotoError::Region { .. })
        ));
        assert!(matches!(
            Engine::open_fingerprinted(file.path(), "not hex".into()),
            Err(MotoError::InvalidInput { .. })
        ));
    }

    #[test]
    fn verify_rejects_a_corrupted_file() {
        let mut bytes = moto_core::fixture::region().to_bytes().unwrap();
        let last = bytes.iter().rposition(|&b| b != 0).unwrap();
        bytes[last] ^= 0xff;
        let file = TempRegion::new("corrupt", &bytes);
        let err = verify_region_file(file.path()).unwrap_err();
        assert!(matches!(err, MotoError::Region { .. }), "got {err:?}");
        assert!(err.to_string().contains("checksum"), "{err}");
    }

    #[test]
    fn routes_through_via_points_across_the_ffi() {
        let file = fixture_file("via");
        let engine = Engine::open(file.path()).unwrap();
        let (from, to) = (ll(55.7001, 13.201), ll(55.7001, 13.219));
        let opts = default_route_options();
        let direct = engine.route(from, vec![], to, opts.clone(), None).unwrap();
        let via = engine
            .route(from, vec![ll(55.705, 13.2119)], to, opts.clone(), None)
            .unwrap();
        assert!(via.distance_m > direct.distance_m + 1000.0);
        assert!(matches!(
            engine.route(from, vec![from; 9], to, opts, None),
            Err(MotoError::InvalidInput { .. })
        ));
    }

    #[test]
    fn route_choices_cross_the_ffi() {
        let file = fixture_file("choices");
        let engine = Engine::open(file.path()).unwrap();
        let (from, to) = (ll(55.7001, 13.201), ll(55.7001, 13.219));
        let opts = default_route_options();
        let choices = engine
            .route_choices(from, vec![], to, opts.clone(), None)
            .unwrap();
        assert!(!choices.is_empty());
        let fastest = choices.last().unwrap();
        assert!((fastest.duration_s - fastest.fastest_duration_s).abs() < 1e-6);
        let via = engine
            .route_choices(from, vec![ll(55.705, 13.2119)], to, opts.clone(), None)
            .unwrap();
        assert!(!via.is_empty());
        let fastest = via.last().unwrap();
        assert!((fastest.duration_s - fastest.fastest_duration_s).abs() < 1e-6);
        assert!(matches!(
            engine.route_choices(from, vec![from; 9], to, opts, None),
            Err(MotoError::InvalidInput { .. })
        ));
    }

    #[test]
    fn describes_the_road_through_the_ffi() {
        let file = fixture_file("road");
        let engine = Engine::open(file.path()).unwrap();
        let r = engine.road_at(ll(55.7001, 13.215)).unwrap();
        assert_eq!(r.class, RoadClass::Primary);
        assert_eq!(r.surface, Surface::Asphalt);
        assert!(r.paved && r.one_way && !r.toll);
        assert_eq!((r.speed_kmh, r.way_id), (70, 300));
        assert!(matches!(
            engine.road_at(ll(10.0, 10.0)),
            Err(MotoError::OutsideRegion { .. })
        ));
    }

    #[test]
    fn unknown_classes_and_surfaces_map_to_catch_alls() {
        assert_eq!(RoadClass::from(None), RoadClass::Other);
        assert_eq!(Surface::from(None), Surface::Unknown);
        use moto_core::region::format as f;
        for c in f::RoadClass::ALL {
            assert_ne!(RoadClass::from(Some(c)), RoadClass::Other, "{c:?}");
        }
        assert_eq!(Surface::from(Some(f::Surface::Gravel)), Surface::Gravel);
    }

    #[test]
    fn snaps_and_routes_through_the_ffi() {
        let file = fixture_file("route");
        let engine = Engine::open(file.path()).unwrap();
        let p = engine.snap(ll(55.7002, 13.205)).unwrap();
        assert!((p.offset - 0.5).abs() < 1e-3, "{p:?}");
        assert!((p.position.lat - 55.7).abs() < 1e-7);

        let route = engine
            .route(
                ll(55.7001, 13.201),
                vec![],
                ll(55.7001, 13.219),
                default_route_options(),
                None,
            )
            .unwrap();
        assert!(
            route.distance_m > 1_000.0 && route.distance_m < 1_200.0,
            "{route:?}"
        );
        assert!(route.duration_s > 0.0);
        assert!(route.geometry.len() >= 3);
        assert_eq!(route.favourite_share, 0.0);
    }

    #[test]
    fn core_errors_cross_as_typed_errors() {
        let file = fixture_file("errors");
        let engine = Engine::open(file.path()).unwrap();
        let opts = default_route_options();
        let outside = engine.snap(ll(56.5, 14.0)).unwrap_err();
        assert!(
            matches!(outside, MotoError::OutsideRegion { .. }),
            "{outside:?}"
        );
        let far = engine.snap(ll(55.7145, 13.2245)).unwrap_err();
        assert!(matches!(far, MotoError::NoRoadNearby { .. }), "{far:?}");
        let none = engine
            .route(
                ll(55.7001, 13.219),
                vec![],
                ll(55.7001, 13.201),
                opts.clone(),
                None,
            )
            .unwrap_err();
        assert!(matches!(none, MotoError::NoRoute { .. }), "{none:?}");
        let bad = engine.snap(ll(91.0, 0.0)).unwrap_err();
        assert!(matches!(bad, MotoError::InvalidInput { .. }), "{bad:?}");
        let short = engine
            .round_trip(
                ll(55.7001, 13.201),
                RoundTripTarget::DistanceM { meters: 1_000.0 },
                opts,
                None,
                LoopOptions::default(),
            )
            .unwrap_err();
        assert!(matches!(short, MotoError::InvalidInput { .. }), "{short:?}");
        // Messages are the core's text, so the app can show them.
        assert!(
            outside.to_string().contains("outside the loaded region"),
            "{outside}"
        );
    }

    #[test]
    fn loops_through_points_cross_the_ffi() {
        let file = TempRegion::new(
            "via-loops",
            &moto_core::fixture::grid(13).to_bytes().unwrap(),
        );
        let engine = Engine::open(file.path()).unwrap();
        let stops = vec![ll(55.790, 13.448), ll(55.790, 13.496)];
        let loops = engine
            .round_trip_via(
                ll(55.754, 13.496),
                stops.clone(),
                true,
                default_route_options(),
                None,
            )
            .unwrap();
        assert!(!loops.is_empty() && loops.len() <= 3);
        assert_eq!(loops[0].geometry.first(), loops[0].geometry.last());
        let err = engine
            .round_trip_via(
                ll(55.754, 13.496),
                vec![],
                true,
                default_route_options(),
                None,
            )
            .unwrap_err();
        assert!(matches!(err, MotoError::InvalidInput { .. }), "{err:?}");
    }

    #[test]
    fn round_trips_cross_the_ffi() {
        let file = TempRegion::new("loops", &moto_core::fixture::grid(13).to_bytes().unwrap());
        let engine = Engine::open(file.path()).unwrap();
        let loops = engine
            .round_trip(
                ll(55.754, 13.496),
                RoundTripTarget::DistanceM { meters: 20_000.0 },
                default_route_options(),
                None,
                LoopOptions::default(),
            )
            .unwrap();
        assert!(loops.len() >= 2, "{}", loops.len());
        // A seed gives another set, the same every time.
        let seeded = |seed| {
            engine
                .round_trip(
                    ll(55.754, 13.496),
                    RoundTripTarget::DistanceM { meters: 20_000.0 },
                    default_route_options(),
                    None,
                    LoopOptions {
                        seed,
                        bearing: None,
                    },
                )
                .unwrap()
        };
        let lines = |ls: &[Route]| ls.iter().map(|l| l.geometry.len()).collect::<Vec<_>>();
        assert_eq!(lines(&seeded(7)), lines(&seeded(7)));
        assert!((1..=5).any(|seed| lines(&seeded(seed)) != lines(&loops)));
        for l in &loops {
            assert!(
                (l.distance_m - 20_000.0).abs() <= 3_000.0,
                "{}",
                l.distance_m
            );
            assert_eq!(l.geometry.first(), l.geometry.last());
        }
    }

    #[test]
    fn round_trip_targets_convert() {
        let d: moto_core::RoundTripTarget = RoundTripTarget::DistanceM { meters: 1.0 }.into();
        let t: moto_core::RoundTripTarget = RoundTripTarget::DurationS { seconds: 2.0 }.into();
        assert_eq!(d, moto_core::RoundTripTarget::DistanceM(1.0));
        assert_eq!(t, moto_core::RoundTripTarget::DurationS(2.0));
    }

    #[test]
    fn avoid_options_convert_both_ways() {
        let a = Avoid {
            motorways: false,
            ferries: true,
            tolls: false,
        };
        let core: moto_core::Avoid = a.into();
        let back: Avoid = core.into();
        assert_eq!(
            (back.motorways, back.ferries, back.tolls),
            (false, true, false)
        );
        let d = default_route_options().avoid;
        assert!(
            d.motorways && d.ferries && d.tolls,
            "all avoided by default"
        );
        for g in [Gravel::Avoid, Gravel::Allow, Gravel::Prefer] {
            let core: moto_core::Gravel = g.into();
            assert_eq!(Gravel::from(core), g);
        }
        assert_eq!(default_route_options().gravel, Gravel::Avoid);
        for f in [FavouritesMode::Prefer, FavouritesMode::Avoid] {
            let core: moto_core::FavouritesMode = f.into();
            assert_eq!(FavouritesMode::from(core), f);
        }
        assert_eq!(default_route_options().favourites, FavouritesMode::Prefer);
    }

    #[test]
    fn routes_export_as_gpx() {
        let file = fixture_file("gpx");
        let engine = Engine::open(file.path()).unwrap();
        let opts = default_route_options();
        let r = engine
            .route(
                ll(55.7001, 13.201),
                vec![],
                ll(55.7001, 13.219),
                opts.clone(),
                None,
            )
            .unwrap();
        let gpx = engine
            .route_gpx(r.geometry.clone(), "Lund & back".into(), opts.clone())
            .unwrap();
        assert!(gpx.contains("<rte>") && gpx.contains("<trk>"));
        assert!(gpx.contains("<name>Lund &amp; back</name>"));
        assert_eq!(gpx.matches("<trkpt ").count(), r.geometry.len());
        let err = engine
            .route_gpx(vec![ll(55.7, 13.2)], String::new(), opts)
            .unwrap_err();
        assert!(matches!(err, MotoError::InvalidInput { .. }), "{err:?}");
    }
}
