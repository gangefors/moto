// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Export and import of the rider's sections as GeoJSON (PRD R10, M1
//! step 8): for backups, a new phone, sharing with a friend, or looking at
//! them in other map tools. Plain `.geojson`, `.geojson.gz`, `.zip` or
//! `.tar.gz` ([`archive`]).
//!
//! An import file is untrusted: sizes are capped, the JSON is read into
//! strict types (never into arbitrary ones), and every value is validated;
//! any problem rejects the whole file with a typed error. Imported
//! sections that are already covered by a saved one are skipped; one that
//! covers a shorter saved section replaces it (the longer section wins);
//! partial overlaps are both kept. Imported sections are then fitted to the
//! current region like after a map update.

pub mod archive;

use serde::{Deserialize, Serialize};

use crate::geo::{densify, distance_to_line, haversine_m};
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
/// A section covers another when every point of the other lies within
/// this distance of it.
const COVER_TOLERANCE_M: f64 = 15.0;
/// Points checked along the covered section, this far apart.
const COVER_STEP_M: f64 = 10.0;

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
    let json = json.strip_prefix(b"\xef\xbb\xbf").unwrap_or(json);
    let doc: CollectionIn = serde_json::from_slice(json)
        .map_err(|e| bad(format!("not a GeoJSON file of sections: {e}")))?;
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
        .map(|(i, f)| feature(f).map_err(|e| bad(format!("section {}: {e}", i + 1))))
        .collect()
}

fn feature(f: FeatureIn) -> Result<NewSection, String> {
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
    Ok(s)
}

// --- overlap rules ---

/// A section's shape for overlap checks.
struct Shape {
    line: Vec<LatLon>,
    direction: Direction,
    length_m: f64,
    /// South-west and north-east corners, widened by the tolerance.
    bbox: (LatLon, LatLon),
}

impl Shape {
    fn new(line: &[LatLon], direction: Direction) -> Self {
        let pad_lat = COVER_TOLERANCE_M / 111_195.0;
        let mut sw = LatLon {
            lat: 90.0,
            lon: 180.0,
        };
        let mut ne = LatLon {
            lat: -90.0,
            lon: -180.0,
        };
        for p in line {
            sw = LatLon {
                lat: sw.lat.min(p.lat),
                lon: sw.lon.min(p.lon),
            };
            ne = LatLon {
                lat: ne.lat.max(p.lat),
                lon: ne.lon.max(p.lon),
            };
        }
        let pad_lon = pad_lat / sw.lat.to_radians().cos().max(0.01);
        Self {
            line: line.to_vec(),
            direction,
            length_m: line.windows(2).map(|w| haversine_m(w[0], w[1])).sum(),
            bbox: (
                LatLon {
                    lat: sw.lat - pad_lat,
                    lon: sw.lon - pad_lon,
                },
                LatLon {
                    lat: ne.lat + pad_lat,
                    lon: ne.lon + pad_lon,
                },
            ),
        }
    }

    fn bbox_overlaps(&self, other: &Shape) -> bool {
        self.bbox.0.lat <= other.bbox.1.lat
            && other.bbox.0.lat <= self.bbox.1.lat
            && self.bbox.0.lon <= other.bbox.1.lon
            && other.bbox.0.lon <= self.bbox.1.lon
    }

    /// Whether this section covers all of `other`: every point of `other`
    /// lies along it, and a one-way section only covers `other` if that is
    /// one-way the same way (a two-way section also counts the other way).
    fn covers(&self, other: &Shape) -> bool {
        if !self.bbox_overlaps(other) {
            return false;
        }
        let points = densify(&other.line, COVER_STEP_M);
        if !points
            .iter()
            .all(|&p| distance_to_line(p, &self.line) <= COVER_TOLERANCE_M)
        {
            return false;
        }
        match (self.direction, other.direction) {
            (Direction::Both, _) => true,
            (Direction::Forward, Direction::Both) => false,
            (Direction::Forward, Direction::Forward) => {
                let (Some(&a), Some(&b)) = (other.line.first(), other.line.last()) else {
                    return false;
                };
                position_along(&self.line, a) <= position_along(&self.line, b)
            }
        }
    }
}

/// Metres along `line` to the point nearest to `p`.
fn position_along(line: &[LatLon], p: LatLon) -> f64 {
    let mut best = (f64::INFINITY, 0.0);
    let mut walked = 0.0;
    for w in line.windows(2) {
        let seg = haversine_m(w[0], w[1]);
        let d = distance_to_line(p, w);
        if d < best.0 {
            // Along this segment: the part of the segment before p.
            let a = haversine_m(w[0], p);
            let along = (a * a - d * d).max(0.0).sqrt().min(seg);
            best = (d, walked + along);
        }
        walked += seg;
    }
    best.1
}

/// What an import does, before touching the store.
#[derive(Debug, Default, PartialEq)]
struct Plan {
    /// Imported sections to add (indices into the import).
    add: Vec<usize>,
    /// Saved sections the import replaces.
    remove: Vec<i64>,
    /// Imported sections skipped as already covered.
    skipped: u64,
}

/// Applies the overlap rules to `imported` against `saved`, one imported
/// section at a time, so the file's own duplicates are handled too.
fn plan(saved: &[Section], imported: &[NewSection]) -> Plan {
    let mut plan = Plan::default();
    let mut saved: Vec<(i64, Shape)> = saved
        .iter()
        .map(|s| (s.id, Shape::new(&s.geometry, s.direction)))
        .collect();
    // Imported sections accepted so far: (index into `imported`, shape).
    let mut accepted: Vec<(usize, Shape)> = Vec::new();
    for (i, s) in imported.iter().enumerate() {
        let shape = Shape::new(&s.geometry, s.direction);
        let covered = saved.iter().any(|(_, o)| o.covers(&shape))
            || accepted.iter().any(|(_, o)| o.covers(&shape));
        if covered {
            plan.skipped += 1;
            continue;
        }
        // It wins over shorter sections it covers.
        saved.retain(|(id, o)| {
            let replaced = o.length_m <= shape.length_m && shape.covers(o);
            if replaced {
                plan.remove.push(*id);
            }
            !replaced
        });
        let before = accepted.len();
        accepted.retain(|(_, o)| !(o.length_m <= shape.length_m && shape.covers(o)));
        // A shorter one earlier in the same file counts as skipped.
        plan.skipped += (before - accepted.len()) as u64;
        accepted.push((i, shape));
    }
    plan.add = accepted.into_iter().map(|(i, _)| i).collect();
    plan.add.sort_unstable();
    plan
}

/// What an import did.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ImportReport {
    /// Sections added.
    pub added: u64,
    /// Imported sections skipped: already covered by a saved section (or
    /// by a longer one in the same file).
    pub skipped: u64,
    /// Saved sections removed because an imported section covers them and
    /// is longer.
    pub replaced: u64,
    /// Added sections that don't fit the current map (kept, hidden as
    /// unmatched until a map they fit).
    pub unmatched: u64,
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
    let plan = plan(&store.list_sections(None)?, &imported);
    let add: Vec<NewSection> = plan.add.iter().map(|&i| imported[i].clone()).collect();
    let ids = store.apply_import(&add, &plan.remove, now)?;
    let mut report = ImportReport {
        added: ids.len() as u64,
        skipped: plan.skipped,
        replaced: plan.remove.len() as u64,
        unmatched: 0,
    };
    if let Some(engine) = engine {
        rematch_store(store, engine)?;
        for id in ids {
            if store
                .get_section(id)?
                .is_some_and(|s| s.status != Status::Ok)
            {
                report.unmatched += 1;
            }
        }
    }
    Ok(report)
}

#[cfg(test)]
mod tests;
