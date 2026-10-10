// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Backup and restore (ADR-0012): everything the rider made, in one zip
//! of standard files, and merged back in after a reinstall.
//!
//! The zip holds `manifest.json` (what is in it, with every other file's
//! size and SHA-256), `favourites.geojson` (the sections, as the Sections
//! page exports them), `rides/NNNNN.gpx` (one per ride, a segment per
//! stretch recorded without a gap), `routes/NNNNN.gpx` (one per saved
//! route), `tags.gpx` (the tags waiting for review, as waypoints) and
//! `settings.json` (the app's own settings, carried as they are).
//!
//! A backup comes back from outside the app, so it is hostile input. Only
//! the fixed names above are accepted, each once; the zip's directory is
//! checked before the zip reader sees it (so a crafted one can't make it
//! allocate without bound); each file is read through a cap and must match
//! the size and hash the manifest gives; the GPX and GeoJSON readers are
//! the ones imports already use. Everything a restore adds goes into one
//! transaction. Nothing from the zip is ever used as a path.

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

use crate::exchange::{self, MAX_IMPORT_SECTIONS};
use crate::gpx::{self, MAX_GPX_BYTES};
use crate::overlap::Shape;
use crate::rematch::rematch_store;
use crate::section::{Section, validate_name};
use crate::store::{MAX_ROUTE_POINTS, NewRoute, Store};
use crate::tag::NewTag;
use crate::track::TrackPoint;
use crate::{CoreError, Engine, LatLon};

mod settings;
pub use settings::{MAX_SETTINGS, Setting, SettingValue};

/// The backup format this build writes and the newest it reads.
pub const FORMAT: u32 = 2;
/// Largest backup file read.
pub const MAX_BACKUP_BYTES: u64 = 1 << 30;
/// Most files in a backup.
pub const MAX_ENTRIES: usize = 20_000;
/// Most rides, and most saved routes, in a backup (so all the files fit
/// in [`MAX_ENTRIES`]).
pub const MAX_RIDES: usize = 9_990;
/// Most tags in a backup.
pub const MAX_TAGS: usize = 100_000;
/// Largest `manifest.json`.
const MAX_MANIFEST_BYTES: usize = 8 << 20;
/// Largest `settings.json`.
pub const MAX_SETTINGS_BYTES: usize = 64 << 10;
/// Largest GeoJSON of sections (as an import takes).
const MAX_SECTIONS_BYTES: usize = exchange::archive::MAX_JSON_BYTES;
/// Most bytes all the files of a backup may unpack to.
const MAX_TOTAL_BYTES: u64 = 4 << 30;
/// Most map regions a backup lists, and the longest key.
const MAX_REGIONS: usize = 64;
const MAX_REGION_KEY: usize = 64;
/// Longest app version string kept.
const MAX_APP_CHARS: usize = 64;

const MANIFEST: &str = "manifest.json";
const SECTIONS: &str = "favourites.geojson";
const SETTINGS: &str = "settings.json";
const TAGS: &str = "tags.gpx";

fn bad(why: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(format!("not a valid moto backup: {}", why.into()))
}

fn io_write(e: impl std::fmt::Display) -> CoreError {
    CoreError::Storage(format!("could not write the backup: {e}"))
}

fn io_read(e: impl std::fmt::Display) -> CoreError {
    bad(format!("unreadable file: {e}"))
}

/// What the app hands in for a backup besides the store.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupInfo {
    /// The app and its version, e.g. "moto 0.9 (42)".
    pub app: String,
    /// The keys of the map regions installed, to offer after a restore.
    pub regions: Vec<String>,
    /// Those of `regions` that are switched off (ADR-0016); keys not in
    /// `regions` are dropped.
    pub regions_disabled: Vec<String>,
    /// The app's settings; `None` writes no settings file.
    pub settings: Option<Vec<Setting>>,
    /// When the backup is made, milliseconds since the Unix epoch.
    pub created_at_ms: i64,
}

/// What a backup holds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupSummary {
    pub format: u32,
    pub app: String,
    /// The rider database's schema version when it was made.
    pub schema: i64,
    pub created_at_ms: i64,
    pub favourites: u64,
    pub rides: u64,
    pub routes: u64,
    pub tags: u64,
    pub has_settings: bool,
    pub regions: Vec<String>,
    /// Those of `regions` that were switched off; empty for a format-2
    /// backup, where every region comes back on.
    pub regions_disabled: Vec<String>,
}

/// What a restore did.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RestoreReport {
    pub favourites_added: u64,
    pub favourites_skipped: u64,
    /// Shorter favourites on the phone an added one covers, replaced (as
    /// an import does).
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
    /// The backup's settings, for the app to apply (it checks which keys
    /// it knows); `None` if the backup has none.
    pub settings: Option<Vec<Setting>>,
    /// The map regions the backup was made with.
    pub regions: Vec<String>,
    /// Those of `regions` that were switched off; empty for a format-2
    /// backup, where every region comes back on.
    pub regions_disabled: Vec<String>,
}

// --- The manifest ---

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(deny_unknown_fields)]
struct Manifest {
    format: u32,
    app: String,
    schema: i64,
    created_at_ms: i64,
    regions: Vec<String>,
    files: Vec<FileEntry>,
    favourites: u64,
    rides: Vec<RideEntry>,
    routes: Vec<RouteEntry>,
    tags: Vec<TagEntry>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(deny_unknown_fields)]
struct FileEntry {
    name: String,
    size: u64,
    sha256: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(deny_unknown_fields)]
struct RideEntry {
    file: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    /// Seconds since the Unix epoch (Record pressed; before the first fix).
    started_at: i64,
    ended_at: i64,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(deny_unknown_fields)]
struct RouteEntry {
    file: String,
    name: String,
    #[serde(rename = "loop")]
    is_loop: bool,
    distance_m: f64,
    duration_s: f64,
    created_at: i64,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(deny_unknown_fields)]
struct TagEntry {
    /// The ride it was made on, by its file in the backup.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    ride: Option<String>,
}

fn ride_file(i: usize) -> String {
    format!("rides/{:05}.gpx", i + 1)
}

fn route_file(i: usize) -> String {
    format!("routes/{:05}.gpx", i + 1)
}

/// Whether `name` is `<dir>/` and five digits and `.gpx`.
fn numbered(name: &str, dir: &str) -> bool {
    name.strip_prefix(dir)
        .and_then(|n| n.strip_prefix('/'))
        .and_then(|n| n.strip_suffix(".gpx"))
        .is_some_and(|n| n.len() == 5 && n.bytes().all(|b| b.is_ascii_digit()))
}

/// The most a file of this name may hold; `None` if the name isn't one a
/// backup has.
fn cap_for(name: &str) -> Option<usize> {
    match name {
        SECTIONS => Some(MAX_SECTIONS_BYTES),
        SETTINGS => Some(MAX_SETTINGS_BYTES),
        TAGS => Some(MAX_GPX_BYTES),
        _ if numbered(name, "rides") || numbered(name, "routes") => Some(MAX_GPX_BYTES),
        _ => None,
    }
}

/// Latest time a backup may give, in seconds since the Unix epoch (the
/// start of 2100).
const MAX_TIME_S: i64 = 4_102_444_800;

fn valid_time_s(t: i64) -> bool {
    (0..=MAX_TIME_S).contains(&t)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn valid_region(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= MAX_REGION_KEY
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

impl Manifest {
    /// Checks everything that doesn't need the other files: names, counts,
    /// metadata ranges, and that every file named is listed once.
    fn check(&self) -> Result<(), CoreError> {
        if self.format > FORMAT {
            return Err(CoreError::InvalidArgument(
                "this backup is from a newer version of moto".into(),
            ));
        }
        if self.format < FORMAT {
            return Err(bad("unknown format"));
        }
        if self.app.chars().count() > MAX_APP_CHARS || self.app.chars().any(char::is_control) {
            return Err(bad("app version"));
        }
        if self.regions.len() > MAX_REGIONS || !self.regions.iter().all(|r| valid_region(r)) {
            return Err(bad("regions"));
        }
        if self.files.len() > MAX_ENTRIES
            || self.rides.len() > MAX_RIDES
            || self.routes.len() > MAX_RIDES
            || self.tags.len() > MAX_TAGS
            || self.favourites > MAX_IMPORT_SECTIONS as u64
        {
            return Err(bad("too many items"));
        }
        let mut names = HashSet::new();
        let mut total = 0u64;
        for f in &self.files {
            let cap = cap_for(&f.name).ok_or_else(|| bad(format!("unknown file {:?}", f.name)))?;
            if !names.insert(f.name.as_str()) {
                return Err(bad(format!("{} listed twice", f.name)));
            }
            if f.size > cap as u64 {
                return Err(bad(format!("{} too large", f.name)));
            }
            if f.sha256.len() != 64
                || !f
                    .sha256
                    .bytes()
                    .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
            {
                return Err(bad(format!("{}: invalid hash", f.name)));
            }
            total += f.size;
        }
        if total > MAX_TOTAL_BYTES {
            return Err(bad("too large in all"));
        }
        let listed = |name: &str| names.contains(name);
        if (self.favourites > 0) != listed(SECTIONS) {
            return Err(bad("favourites don't match the files"));
        }
        if self.tags.is_empty() == listed(TAGS) {
            return Err(bad("tags don't match the files"));
        }
        let mut used = HashSet::new();
        for r in &self.rides {
            if !numbered(&r.file, "rides") || !listed(&r.file) || !used.insert(r.file.as_str()) {
                return Err(bad("rides don't match the files"));
            }
            if let Some(name) = &r.name {
                validate_name(name).map_err(|_| bad("a ride's name"))?;
            }
            if !valid_time_s(r.started_at) || !valid_time_s(r.ended_at) || r.ended_at < r.started_at
            {
                return Err(bad("a ride's times"));
            }
        }
        for r in &self.routes {
            if !numbered(&r.file, "routes") || !listed(&r.file) || !used.insert(r.file.as_str()) {
                return Err(bad("routes don't match the files"));
            }
            validate_name(&r.name).map_err(|_| bad("a route's name"))?;
            let figure = |v: f64| v.is_finite() && v >= 0.0;
            if !figure(r.distance_m) || !figure(r.duration_s) || !valid_time_s(r.created_at) {
                return Err(bad("a route's figures"));
            }
        }
        // Every GPX file is a ride or a route listed above.
        let gpx_files = self
            .files
            .iter()
            .filter(|f| numbered(&f.name, "rides") || numbered(&f.name, "routes"))
            .count();
        if gpx_files != used.len() {
            return Err(bad("a file is not a listed ride or route"));
        }
        let ride_files: HashSet<&str> = self.rides.iter().map(|r| r.file.as_str()).collect();
        if self
            .tags
            .iter()
            .any(|t| t.ride.as_deref().is_some_and(|r| !ride_files.contains(r)))
        {
            return Err(bad("a tag names a ride the backup doesn't have"));
        }
        Ok(())
    }

    fn summary(&self) -> BackupSummary {
        BackupSummary {
            format: self.format,
            app: self.app.clone(),
            schema: self.schema,
            created_at_ms: self.created_at_ms,
            favourites: self.favourites,
            rides: self.rides.len() as u64,
            routes: self.routes.len() as u64,
            tags: self.tags.len() as u64,
            has_settings: self.files.iter().any(|f| f.name == SETTINGS),
            regions: self.regions.clone(),
            regions_disabled: Vec::new(),
        }
    }
}

// --- Writing ---

fn too_many(what: &str) -> CoreError {
    CoreError::InvalidArgument(format!("too many {what} for one backup"))
}

struct Writer {
    zip: ZipWriter<File>,
    files: Vec<FileEntry>,
}

impl Writer {
    fn add(&mut self, name: &str, bytes: &[u8]) -> Result<(), CoreError> {
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .large_file(false);
        self.zip.start_file(name, options).map_err(io_write)?;
        self.zip.write_all(bytes).map_err(io_write)?;
        self.files.push(FileEntry {
            name: name.into(),
            size: bytes.len() as u64,
            sha256: hex(&Sha256::digest(bytes)),
        });
        Ok(())
    }
}

/// Writes a backup of everything in `store` to a new file at `path`
/// (replacing one there): finished rides with two fixes or more (a ride
/// still recording is left out), saved routes, tags, favourite sections
/// and `info`'s settings.
pub fn write_backup(
    store: &Store,
    path: &Path,
    info: &BackupInfo,
) -> Result<BackupSummary, CoreError> {
    let settings_json = info
        .settings
        .as_deref()
        .map(settings::to_json)
        .transpose()?;
    let regions: Vec<String> = info
        .regions
        .iter()
        .filter(|r| valid_region(r))
        .take(MAX_REGIONS)
        .cloned()
        .collect();
    let app: String = info
        .app
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_APP_CHARS)
        .collect();
    let file = File::create(path).map_err(io_write)?;
    let mut w = Writer {
        zip: ZipWriter::new(file),
        files: Vec::new(),
    };

    let sections: Vec<Section> = store.list_sections(None)?;
    if !sections.is_empty() {
        w.add(SECTIONS, &exchange::to_geojson(&sections)?)?;
    }

    let mut rides = Vec::new();
    let mut ride_of_track = HashMap::new();
    // Oldest first, so a restore adds them in the same order.
    let mut tracks = store.list_tracks()?;
    tracks.sort_by_key(|t| t.id);
    for t in tracks {
        let Some(ended_at) = t.ended_at else {
            continue;
        };
        let Some(segments) = store.track_segments(t.id)? else {
            continue;
        };
        let segments: Vec<Vec<TrackPoint>> =
            segments.into_iter().filter(|s| !s.is_empty()).collect();
        if segments.iter().map(Vec::len).sum::<usize>() < 2 {
            continue;
        }
        if rides.len() == MAX_RIDES {
            return Err(too_many("rides"));
        }
        let file = ride_file(rides.len());
        let name = t.name.clone().unwrap_or_default();
        w.add(&file, gpx::track_gpx_segments(&name, &segments).as_bytes())?;
        ride_of_track.insert(t.id, file.clone());
        rides.push(RideEntry {
            file,
            name: t.name,
            started_at: t.started_at,
            ended_at: ended_at.max(t.started_at),
        });
    }

    let mut routes = Vec::new();
    let mut saved_routes = store.list_routes()?;
    saved_routes.sort_by_key(|r| r.id);
    for r in saved_routes {
        if routes.len() == MAX_RIDES {
            return Err(too_many("saved routes"));
        }
        let Some(line) = store.route_geometry(r.id)? else {
            continue;
        };
        let file = route_file(routes.len());
        w.add(&file, gpx::line_gpx(&r.name, &line).as_bytes())?;
        routes.push(RouteEntry {
            file,
            name: r.name,
            is_loop: r.is_loop,
            distance_m: r.distance_m,
            duration_s: r.duration_s,
            created_at: r.created_at,
        });
    }

    let all_tags = store.list_tags()?;
    if all_tags.len() > MAX_TAGS {
        return Err(too_many("tags"));
    }
    let mut tags = Vec::new();
    if !all_tags.is_empty() {
        let fixes: Vec<TrackPoint> = all_tags
            .iter()
            .map(|t| TrackPoint {
                time_ms: t.time_ms,
                position: t.position,
                accuracy_m: None,
                speed_mps: t.speed_mps,
                bearing_deg: t.heading_deg,
            })
            .collect();
        w.add(TAGS, gpx::waypoints_gpx(&fixes).as_bytes())?;
        tags = all_tags
            .iter()
            .map(|t| TagEntry {
                ride: t.track_id.and_then(|id| ride_of_track.get(&id).cloned()),
            })
            .collect();
    }

    if let Some(json) = &settings_json {
        w.add(SETTINGS, json)?;
    }

    let manifest = Manifest {
        format: FORMAT,
        app,
        schema: store.schema_version()?,
        created_at_ms: info.created_at_ms,
        regions,
        files: std::mem::take(&mut w.files),
        favourites: sections.len() as u64,
        rides,
        routes,
        tags,
    };
    let json = serde_json::to_vec_pretty(&manifest).map_err(io_write)?;
    w.add(MANIFEST, &json)?;
    w.zip
        .finish()
        .map_err(io_write)?
        .sync_all()
        .map_err(io_write)?;
    Ok(manifest.summary())
}

// --- Reading ---

/// An opened backup: its directory checked, its manifest read and checked.
struct Opened {
    zip: ZipArchive<File>,
    manifest: Manifest,
    sizes: HashMap<String, (u64, String)>,
}

/// Checks the zip's end record before the zip reader sees it: it must sit
/// at the very end (a backup has no comment), name at most
/// [`MAX_ENTRIES`] entries on one disk with a directory of sane size, and
/// not be zip64. So a crafted directory can't make the reader allocate
/// without bound. Returns the number of entries.
fn check_end_record(file: &mut File, len: u64) -> Result<usize, CoreError> {
    const EOCD: u32 = 0x0605_4b50;
    const ZIP64_LOCATOR: u32 = 0x0706_4b50;
    if len < 22 {
        return Err(bad("too short"));
    }
    let back = len.min(42);
    file.seek(SeekFrom::Start(len - back)).map_err(io_read)?;
    let mut tail = vec![0u8; back as usize];
    file.read_exact(&mut tail).map_err(io_read)?;
    let rec = &tail[tail.len() - 22..];
    let u16_at = |i: usize| u16::from_le_bytes([rec[i], rec[i + 1]]);
    let u32_at = |b: &[u8], i: usize| u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]);
    if u32_at(rec, 0) != EOCD || u16_at(20) != 0 {
        return Err(bad("not a zip file written by moto"));
    }
    if tail.len() == 42 && u32_at(&tail, 0) == ZIP64_LOCATOR {
        return Err(bad("zip64 is not used by backups"));
    }
    let (disk, cd_disk) = (u16_at(4), u16_at(6));
    let (here, total) = (u16_at(8), u16_at(10));
    let cd_size = u64::from(u32_at(rec, 12));
    let cd_offset = u64::from(u32_at(rec, 16));
    if disk != 0 || cd_disk != 0 || here != total {
        return Err(bad("split zip"));
    }
    let entries = usize::from(total);
    // A directory entry is 46 bytes plus a name (ours are short).
    if entries > MAX_ENTRIES
        || cd_size > entries as u64 * (46 + 64)
        || cd_offset
            .checked_add(cd_size)
            .is_none_or(|end| end > len - 22)
    {
        return Err(bad("zip directory"));
    }
    Ok(entries)
}

/// Reads all of entry `name`, at most `cap` bytes, and checks it against
/// the manifest's size and hash.
fn read_entry(opened: &mut Opened, name: &str) -> Result<Vec<u8>, CoreError> {
    let (size, sha) = opened
        .sizes
        .get(name)
        .cloned()
        .ok_or_else(|| bad(format!("{name} is not listed")))?;
    read_checked(&mut opened.zip, name, size, Some(&sha))
}

fn read_checked(
    zip: &mut ZipArchive<File>,
    name: &str,
    size: u64,
    sha: Option<&str>,
) -> Result<Vec<u8>, CoreError> {
    let entry = zip.by_name(name).map_err(|e| bad(format!("{name}: {e}")))?;
    if !entry.is_file()
        || entry.encrypted()
        || !matches!(
            entry.compression(),
            CompressionMethod::Stored | CompressionMethod::Deflated
        )
    {
        return Err(bad(format!("{name}: not a plain file")));
    }
    let mut out = Vec::new();
    entry
        .take(size + 1)
        .read_to_end(&mut out)
        .map_err(|e| bad(format!("{name}: {e}")))?;
    if out.len() as u64 != size {
        return Err(bad(format!("{name}: wrong size")));
    }
    if let Some(sha) = sha
        && hex(&Sha256::digest(&out)) != sha
    {
        return Err(bad(format!("{name}: damaged (hash doesn't match)")));
    }
    Ok(out)
}

fn open(path: &Path) -> Result<Opened, CoreError> {
    let mut file = File::open(path).map_err(io_read)?;
    let len = file.metadata().map_err(io_read)?.len();
    if len > MAX_BACKUP_BYTES {
        return Err(bad("larger than 1 GiB"));
    }
    let entries = check_end_record(&mut file, len)?;
    file.seek(SeekFrom::Start(0)).map_err(io_read)?;
    let mut zip = ZipArchive::new(file).map_err(|e| bad(format!("not a readable zip: {e}")))?;
    // Fewer than the end record counts: names given twice.
    if zip.len() != entries {
        return Err(bad("a file is in the zip twice"));
    }
    let manifest_bytes = {
        let entry = zip.by_name(MANIFEST).map_err(|_| bad("no manifest.json"))?;
        let size = entry.size();
        drop(entry);
        if size > MAX_MANIFEST_BYTES as u64 {
            return Err(bad("manifest.json too large"));
        }
        read_checked(&mut zip, MANIFEST, size, None)?
    };
    let manifest: Manifest =
        serde_json::from_slice(&manifest_bytes).map_err(|e| bad(format!("manifest.json: {e}")))?;
    manifest.check()?;
    // Exactly the files the manifest lists, and the manifest.
    let names: HashSet<&str> = zip.file_names().collect();
    if names.len() != manifest.files.len() + 1
        || !names.contains(MANIFEST)
        || !manifest
            .files
            .iter()
            .all(|f| names.contains(f.name.as_str()))
    {
        return Err(bad("the files don't match the manifest"));
    }
    let sizes = manifest
        .files
        .iter()
        .map(|f| (f.name.clone(), (f.size, f.sha256.clone())))
        .collect();
    Ok(Opened {
        zip,
        manifest,
        sizes,
    })
}

/// What the backup at `path` holds, from its manifest, after checking its
/// structure (the files themselves are checked on restore).
pub fn read_summary(path: &Path) -> Result<BackupSummary, CoreError> {
    Ok(open(path)?.manifest.summary())
}

fn utf8(bytes: Vec<u8>, name: &str) -> Result<String, CoreError> {
    String::from_utf8(bytes).map_err(|_| bad(format!("{name}: not UTF-8")))
}

/// Restores the backup at `path` into `store`, merging: nothing is
/// deleted (but shorter favourites an added one covers, as an import),
/// and anything already there is skipped. All in one transaction: a
/// damaged backup changes nothing. Then fits the favourites to `engine`'s
/// map if given. `now` is seconds since the Unix epoch.
pub fn restore_backup(
    store: &mut Store,
    engine: Option<&Engine>,
    path: &Path,
    now: i64,
) -> Result<RestoreReport, CoreError> {
    let mut opened = open(path)?;
    let manifest = opened.manifest.clone();
    let mut report = RestoreReport {
        regions: manifest.regions.clone(),
        ..RestoreReport::default()
    };

    // Read and check the small files first.
    if opened.sizes.contains_key(SETTINGS) {
        report.settings = Some(settings::from_json(&read_entry(&mut opened, SETTINGS)?)?);
    }
    let sections = if opened.sizes.contains_key(SECTIONS) {
        let read = exchange::read_features(&read_entry(&mut opened, SECTIONS)?)?;
        if read.len() as u64 != manifest.favourites {
            return Err(bad("favourites don't match the manifest"));
        }
        read
    } else {
        Vec::new()
    };
    let tag_fixes = if opened.sizes.contains_key(TAGS) {
        let fixes = gpx::read_waypoints(&utf8(read_entry(&mut opened, TAGS)?, TAGS)?)?;
        if fixes.len() != manifest.tags.len() {
            return Err(bad("tags don't match the manifest"));
        }
        fixes
    } else {
        Vec::new()
    };

    let saved = store.list_sections(None)?;
    let restore = store.begin_restore()?;

    // Favourite sections, by the import's overlap rules.
    let news: Vec<_> = sections.iter().map(|r| r.section.clone()).collect();
    let plan = exchange::plan(&saved, &news);
    let add: Vec<_> = plan
        .add
        .iter()
        .map(|&i| {
            let r = &sections[i];
            let mut s = r.section.clone();
            if let Some(source) = r.source {
                s.source = source;
            }
            let created = r.created_at.unwrap_or(now);
            (s, created, r.updated_at.unwrap_or(created).max(created))
        })
        .collect();
    restore.add_sections(&add, &plan.remove)?;
    report.favourites_added = add.len() as u64;
    report.favourites_skipped = plan.skipped.len() as u64;
    report.favourites_replaced = plan.remove.len() as u64;

    // Rides: the ride already there when it has the same fixes.
    let mut track_of_ride = HashMap::new();
    for r in &manifest.rides {
        let text = utf8(read_entry(&mut opened, &r.file)?, &r.file)?;
        let segments = gpx::read_tracks(&text, 0)?;
        let all: Vec<TrackPoint> = segments.iter().flatten().copied().collect();
        if let Some(id) = store.same_track(&all)? {
            track_of_ride.insert(r.file.as_str(), id);
            report.rides_skipped += 1;
            continue;
        }
        let id = restore.add_ride(&segments, r.name.as_deref(), r.started_at, r.ended_at)?;
        track_of_ride.insert(r.file.as_str(), id);
        report.rides_added += 1;
    }

    // Saved routes: the route already there when it has the same name and line.
    for r in &manifest.routes {
        let text = utf8(read_entry(&mut opened, &r.file)?, &r.file)?;
        let line: Vec<LatLon> = gpx::read_track(&text, 0)?
            .into_iter()
            .map(|p| p.position)
            .collect();
        if line.len() > MAX_ROUTE_POINTS {
            return Err(bad(format!("{}: too many points", r.file)));
        }
        if store.same_route(&r.name, &line)? {
            report.routes_skipped += 1;
            continue;
        }
        let route = NewRoute {
            name: r.name.clone(),
            is_loop: r.is_loop,
            distance_m: r.distance_m,
            duration_s: r.duration_s,
            geometry: line,
        };
        restore.add_route(&route, r.created_at)?;
        report.routes_added += 1;
    }

    // Tags: the tag already there when it has the same time and place.
    // Otherwise one on a favourite the phone had before this restore was
    // most likely saved as it since the backup was made, so it is already
    // reviewed (ADR-0015). Favourites this restore adds don't count: every
    // tag in a backup was waiting when it was made.
    let on_phone: Vec<Shape> = saved.iter().map(Shape::of_section).collect();
    for (fix, t) in tag_fixes.iter().zip(&manifest.tags) {
        if store.same_tag(fix.time_ms, fix.position)? {
            report.tags_skipped += 1;
            continue;
        }
        if on_phone.iter().any(|s| s.covers_point(fix.position)) {
            report.tags_on_favourites += 1;
            continue;
        }
        let tag = NewTag {
            time_ms: fix.time_ms,
            position: fix.position,
            heading_deg: fix.bearing_deg,
            speed_mps: fix.speed_mps,
            track_id: t
                .ride
                .as_deref()
                .and_then(|f| track_of_ride.get(f).copied()),
        };
        restore.add_tag(&tag)?;
        report.tags_added += 1;
    }

    restore.commit()?;
    if let Some(engine) = engine
        && report.favourites_added > 0
    {
        rematch_store(store, engine)?;
    }
    Ok(report)
}

#[cfg(test)]
mod tests;
