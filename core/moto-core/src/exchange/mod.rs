// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Export and import of the rider's sections as GeoJSON (PRD R10, M1
//! step 8): for backups, a new phone, sharing with a friend, or looking at
//! them in other map tools. Plain `.geojson`, `.geojson.gz` or `.zip`
//! ([`archive`]).
//!
//! An import file is untrusted: sizes are capped, the JSON is read into
//! strict types (never into arbitrary ones), and every value is validated;
//! any problem rejects the whole file with a typed error. Imported
//! sections that a saved one makes redundant are skipped, and saved ones
//! an imported section makes redundant are replaced (see [`crate::overlap`]:
//! same road, every direction, rating at least as high; the longer wins a
//! tie); everything else, partial overlaps included, is kept. Imported sections are then fitted to the
//! current region like after a map update.

pub mod archive;

use serde::{Deserialize, Serialize};

use crate::overlap::Shape;
use crate::rematch::rematch_store;
use crate::section::{
    Direction, LOCAL_RIDER, MAX_NAME_CHARS, NewSection, Rating, Section, Source, Status, WaySpan,
};
use crate::store::Store;
use crate::{CoreError, Engine, LatLon};

pub use archive::ExportFormat;

/// Most sections one import may hold.
pub const MAX_IMPORT_SECTIONS: usize = 10_000;
/// Version of the `moto` properties in our exports.
const FORMAT_VERSION: u32 = 1;

// --- GeoJSON as written ---

#[derive(Serialize)]
struct CollectionOut<'a> {
    #[serde(rename = "type")]
    kind: &'static str,
    moto: MotoOut,
    features: Vec<FeatureOut<'a>>,
}

#[derive(Serialize)]
struct MotoOut {
    format: u32,
    content: &'static str,
}

#[derive(Serialize)]
struct FeatureOut<'a> {
    #[serde(rename = "type")]
    kind: &'static str,
    geometry: GeometryOut,
    properties: PropsOut<'a>,
}

#[derive(Serialize)]
struct GeometryOut {
    #[serde(rename = "type")]
    kind: &'static str,
    coordinates: Vec<[f64; 2]>,
}

#[derive(Serialize)]
struct PropsOut<'a> {
    name: &'a str,
    rating: &'static str,
    direction: &'static str,
    source: &'static str,
    created_at: i64,
    updated_at: i64,
    /// OSM way spans: [way id, from node index, to node index].
    ways: Vec<[i64; 3]>,
}

// --- GeoJSON as read: strict types, unknown fields ignored ---

#[derive(Deserialize)]
struct CollectionIn {
    #[serde(rename = "type")]
    kind: String,
    features: Vec<FeatureIn>,
}

#[derive(Deserialize)]
struct FeatureIn {
    #[serde(rename = "type")]
    kind: String,
    geometry: GeometryIn,
    #[serde(default)]
    properties: Option<PropsIn>,
}

#[derive(Deserialize)]
struct GeometryIn {
    #[serde(rename = "type")]
    kind: String,
    coordinates: Vec<Vec<f64>>,
}

#[derive(Deserialize, Default)]
struct PropsIn {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    rating: Option<String>,
    #[serde(default)]
    direction: Option<String>,
    #[serde(default)]
    ways: Option<Vec<[i64; 3]>>,
    // Kept by a backup restore (ADR-0012); an import ignores them.
    #[serde(default)]
    source: Option<String>,
    #[serde(default)]
    created_at: Option<i64>,
    #[serde(default)]
    updated_at: Option<i64>,
}

fn rating_name(r: Rating) -> &'static str {
    match r {
        Rating::Good => "good",
        Rating::Great => "great",
        Rating::Epic => "epic",
    }
}

fn direction_name(d: Direction) -> &'static str {
    match d {
        Direction::Both => "both",
        Direction::Forward => "forward",
    }
}

fn source_name(s: Source) -> &'static str {
    match s {
        Source::Map => "map",
        Source::Tag => "tag",
        Source::Track => "track",
        Source::Import => "import",
    }
}

/// The sections as a GeoJSON FeatureCollection of LineStrings.
pub fn to_geojson(sections: &[Section]) -> Result<Vec<u8>, CoreError> {
    let features = sections
        .iter()
        .map(|s| FeatureOut {
            kind: "Feature",
            geometry: GeometryOut {
                kind: "LineString",
                coordinates: s.geometry.iter().map(|p| [p.lon, p.lat]).collect(),
            },
            properties: PropsOut {
                name: &s.name,
                rating: rating_name(s.rating),
                direction: direction_name(s.direction),
                source: source_name(s.source),
                created_at: s.created_at,
                updated_at: s.updated_at,
                ways: s
                    .ways
                    .iter()
                    .map(|w| [w.way_id, i64::from(w.from_idx), i64::from(w.to_idx)])
                    .collect(),
            },
        })
        .collect();
    serde_json::to_vec_pretty(&CollectionOut {
        kind: "FeatureCollection",
        moto: MotoOut {
            format: FORMAT_VERSION,
            content: "sections",
        },
        features,
    })
    .map_err(|e| CoreError::Storage(format!("could not write the export: {e}")))
}

fn bad(msg: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(msg.into())
}

/// Sections from GeoJSON text, validated; rated `good` and good both ways
/// unless the file says otherwise. Any invalid feature rejects the file.
pub fn from_geojson(json: &[u8]) -> Result<Vec<NewSection>, CoreError> {
    Ok(read_features(json)?
        .into_iter()
        .map(|f| f.section)
        .collect())
}

/// A section read from a file, with what a backup restore keeps besides.
pub(crate) struct ReadSection {
    pub section: NewSection,
    /// The source the file gives, if it names a known one.
    pub source: Option<Source>,
    pub created_at: Option<i64>,
    pub updated_at: Option<i64>,
}

/// As [`from_geojson`], with each section's source and times as the file
/// gives them (an export writes them), for a backup restore.
pub(crate) fn read_features(json: &[u8]) -> Result<Vec<ReadSection>, CoreError> {
    let json = json.strip_prefix(b"\xef\xbb\xbf").unwrap_or(json);
    let doc: CollectionIn = serde_json::from_slice(json)
        .map_err(|e| bad(format!("not a GeoJSON file of favourite sections: {e}")))?;
    if doc.kind != "FeatureCollection" {
        return Err(bad("expected a GeoJSON FeatureCollection"));
    }
    if doc.features.len() > MAX_IMPORT_SECTIONS {
        return Err(bad(format!(
            "at most {MAX_IMPORT_SECTIONS} sections per import, got {}",
            doc.features.len()
        )));
    }
    doc.features
        .into_iter()
        .enumerate()
        .map(|(i, f)| feature(f).map_err(|e| bad(format!("favourite section {}: {e}", i + 1))))
        .collect()
}

fn feature(f: FeatureIn) -> Result<ReadSection, String> {
    if f.kind != "Feature" {
        return Err("not a Feature".into());
    }
    if f.geometry.kind != "LineString" {
        return Err(format!(
            "{} geometry; sections are LineStrings",
            f.geometry.kind
        ));
    }
    let geometry = f
        .geometry
        .coordinates
        .iter()
        .map(|c| match c.as_slice() {
            // [lon, lat] or [lon, lat, altitude].
            [lon, lat] | [lon, lat, _] => LatLon::new(*lat, *lon).map_err(|e| e.to_string()),
            _ => Err("a position needs longitude and latitude".into()),
        })
        .collect::<Result<Vec<_>, _>>()?;
    let props = f.properties.unwrap_or_default();
    let rating = match props.rating.as_deref() {
        None | Some("good") => Rating::Good,
        Some("great") => Rating::Great,
        Some("epic") => Rating::Epic,
        Some(other) => return Err(format!("unknown rating {other:?}")),
    };
    let direction = match props.direction.as_deref() {
        None | Some("both") => Direction::Both,
        Some("forward") => Direction::Forward,
        Some(other) => return Err(format!("unknown direction {other:?}")),
    };
    let name: String = props
        .name
        .unwrap_or_default()
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_NAME_CHARS)
        .collect();
    let ways = props
        .ways
        .unwrap_or_default()
        .into_iter()
        .map(|[way_id, from, to]| {
            Ok(WaySpan {
                way_id,
                from_idx: u32::try_from(from).map_err(|_| "invalid way span".to_string())?,
                to_idx: u32::try_from(to).map_err(|_| "invalid way span".to_string())?,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;
    let s = NewSection {
        rider_id: LOCAL_RIDER.into(),
        name,
        rating,
        direction,
        source: Source::Import,
        ways,
        geometry,
    };
    s.validate().map_err(|e| e.to_string())?;
    Ok(ReadSection {
        section: s,
        source: match props.source.as_deref() {
            Some("map") => Some(Source::Map),
            Some("tag") => Some(Source::Tag),
            Some("track") => Some(Source::Track),
            Some("import") => Some(Source::Import),
            _ => None,
        },
        created_at: props.created_at,
        updated_at: props.updated_at,
    })
}

/// What an import does, before touching the store.
#[derive(Debug, Default, PartialEq)]
pub(crate) struct Plan {
    /// Imported sections to add (indices into the import).
    pub add: Vec<usize>,
    /// Saved sections the import replaces.
    pub remove: Vec<i64>,
    /// Imported sections skipped as already covered (indices into the
    /// import, in file order).
    pub skipped: Vec<usize>,
}

/// Applies the overlap rules to `imported` against `saved`, one imported
/// section at a time, so the file's own duplicates are handled too.
pub(crate) fn plan(saved: &[Section], imported: &[NewSection]) -> Plan {
    let mut plan = Plan::default();
    let mut saved: Vec<(i64, Shape)> = saved.iter().map(|s| (s.id, Shape::of_section(s))).collect();
    // Imported sections accepted so far: (index into `imported`, shape).
    let mut accepted: Vec<(usize, Shape)> = Vec::new();
    for (i, s) in imported.iter().enumerate() {
        let shape = Shape::of_new(s);
        let covered = saved.iter().any(|(_, o)| o.beats(&shape))
            || accepted.iter().any(|(_, o)| o.beats(&shape));
        if covered {
            plan.skipped.push(i);
            continue;
        }
        // It replaces the sections it makes redundant.
        saved.retain(|(id, o)| {
            let replaced = shape.beats(o);
            if replaced {
                plan.remove.push(*id);
            }
            !replaced
        });
        // One earlier in the same file that this makes redundant counts as
        // skipped.
        accepted.retain(|(j, o)| {
            let redundant = shape.beats(o);
            if redundant {
                plan.skipped.push(*j);
            }
            !redundant
        });
        accepted.push((i, shape));
    }
    plan.add = accepted.into_iter().map(|(i, _)| i).collect();
    plan.add.sort_unstable();
    plan.skipped.sort_unstable();
    plan
}

/// A section named in an import's report: its name (may be empty), rating
/// and length.
#[derive(Debug, Clone, PartialEq)]
pub struct ImportItem {
    pub name: String,
    pub rating: Rating,
    pub length_m: f64,
}

impl ImportItem {
    fn of(name: &str, rating: Rating, geometry: &[LatLon]) -> Self {
        Self {
            name: name.to_owned(),
            rating,
            length_m: crate::geo::polyline_length_m(geometry),
        }
    }
}

/// What an import did: the counts, and the sections behind them, each
/// list in file order (replaced ones in the order they were saved).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ImportReport {
    /// Sections added.
    pub added: u64,
    /// Imported sections skipped: made redundant by a saved section (or
    /// by another one in the same file).
    pub skipped: u64,
    /// Saved sections removed because an imported section makes them
    /// redundant.
    pub replaced: u64,
    /// Added sections that don't fit the current map (kept: waiting when
    /// no open region covers them, else flagged unmatched).
    pub unmatched: u64,
    /// The sections added that fit the map.
    pub added_items: Vec<ImportItem>,
    /// The sections added that don't fit the map.
    pub unmatched_items: Vec<ImportItem>,
    /// The imported sections skipped.
    pub skipped_items: Vec<ImportItem>,
    /// The saved sections replaced, as they were.
    pub replaced_items: Vec<ImportItem>,
}

/// Exports every saved section in `format`.
pub fn export_sections(store: &Store, format: ExportFormat) -> Result<Vec<u8>, CoreError> {
    let sections = store.list_sections(None)?;
    archive::pack(&to_geojson(&sections)?, format)
}

/// Imports sections from a file in any export format (see the module
/// docs), then fits them to `engine`'s region if one is loaded; without
/// one they wait, flagged `needs_rematch`. `now` is seconds since the
/// Unix epoch.
pub fn import_sections(
    store: &mut Store,
    engine: Option<&Engine>,
    bytes: &[u8],
    now: i64,
) -> Result<ImportReport, CoreError> {
    let imported = from_geojson(&archive::unpack(bytes)?)?;
    let saved = store.list_sections(None)?;
    let plan = plan(&saved, &imported);
    let add: Vec<NewSection> = plan.add.iter().map(|&i| imported[i].clone()).collect();
    let new_item = |s: &NewSection| ImportItem::of(&s.name, s.rating, &s.geometry);
    let replaced_items: Vec<ImportItem> = saved
        .iter()
        .filter(|s| plan.remove.contains(&s.id))
        .map(|s| ImportItem::of(&s.name, s.rating, &s.geometry))
        .collect();
    let ids = store.apply_import(&add, &plan.remove, now)?;
    let mut report = ImportReport {
        added: ids.len() as u64,
        skipped: plan.skipped.len() as u64,
        replaced: plan.remove.len() as u64,
        skipped_items: plan
            .skipped
            .iter()
            .filter_map(|&i| imported.get(i))
            .map(new_item)
            .collect(),
        replaced_items,
        ..ImportReport::default()
    };
    if let Some(engine) = engine {
        rematch_store(store, engine)?;
    }
    for (id, s) in ids.iter().zip(&add) {
        // Without a map they wait to be fitted; they count as added.
        let fits = engine.is_none()
            || store
                .get_section(*id)?
                .is_some_and(|s| s.status == Status::Ok);
        if fits {
            report.added_items.push(new_item(s));
        } else {
            report.unmatched += 1;
            report.unmatched_items.push(new_item(s));
        }
    }
    Ok(report)
}

#[cfg(test)]
mod tests;
