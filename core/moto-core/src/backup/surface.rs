// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! `surface-corrections.geojson` in a backup (format 4, ADR-0018): the
//! rider's surface corrections as GeoJSON LineStrings.
//!
//! The file comes from outside the app, so it is read into strict types
//! (`deny_unknown_fields` everywhere) and every value is range-checked;
//! any problem refuses the whole backup. Its size is capped by the
//! manifest before it is read.

use serde::{Deserialize, Serialize};

use super::{bad, valid_time_s};
use crate::region::format::Surface;
use crate::section::{LOCAL_RIDER, WaySpan};
use crate::surface::{
    MAX_SURFACE_CORRECTIONS, NewSurfaceCorrection, SurfaceCorrection, validate_line,
};
use crate::{CoreError, LatLon};

/// Version of the file's `moto` properties.
const FORMAT_VERSION: u32 = 1;
/// What the file holds, in its `moto` properties.
const CONTENT: &str = "surface";

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Collection {
    #[serde(rename = "type")]
    kind: String,
    moto: Moto,
    features: Vec<Feature>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Moto {
    format: u32,
    content: String,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Feature {
    #[serde(rename = "type")]
    kind: String,
    geometry: Geometry,
    properties: Props,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Geometry {
    #[serde(rename = "type")]
    kind: String,
    /// [lon, lat] pairs.
    coordinates: Vec<[f64; 2]>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Props {
    /// `paved` or `gravel`.
    surface: String,
    created_at: i64,
    updated_at: i64,
    /// OSM way spans: [way id, from node index, to node index].
    ways: Vec<[i64; 3]>,
}

fn surface_name(s: Surface) -> Result<&'static str, CoreError> {
    match s {
        Surface::Paved => Ok("paved"),
        Surface::Gravel => Ok("gravel"),
        other => Err(CoreError::Storage(format!(
            "a surface correction of {other:?} can't be backed up"
        ))),
    }
}

/// The corrections as the backup's GeoJSON.
pub(super) fn to_geojson(corrections: &[SurfaceCorrection]) -> Result<Vec<u8>, CoreError> {
    let features = corrections
        .iter()
        .map(|c| {
            Ok(Feature {
                kind: "Feature".into(),
                geometry: Geometry {
                    kind: "LineString".into(),
                    coordinates: c.geometry.iter().map(|p| [p.lon, p.lat]).collect(),
                },
                properties: Props {
                    surface: surface_name(c.surface)?.into(),
                    created_at: c.created_at,
                    updated_at: c.updated_at,
                    ways: c
                        .ways
                        .iter()
                        .map(|w| [w.way_id, i64::from(w.from_idx), i64::from(w.to_idx)])
                        .collect(),
                },
            })
        })
        .collect::<Result<Vec<_>, CoreError>>()?;
    serde_json::to_vec_pretty(&Collection {
        kind: "FeatureCollection".into(),
        moto: Moto {
            format: FORMAT_VERSION,
            content: CONTENT.into(),
        },
        features,
    })
    .map_err(|e| CoreError::Storage(format!("could not write the backup: {e}")))
}

/// A correction read from a backup, with its times.
#[derive(Debug, Clone, PartialEq)]
pub(super) struct ReadCorrection {
    pub correction: NewSurfaceCorrection,
    pub created_at: i64,
    pub updated_at: i64,
}

/// The corrections in a backup's `surface-corrections.geojson`, validated;
/// any invalid value refuses the whole file.
pub(super) fn read(json: &[u8]) -> Result<Vec<ReadCorrection>, CoreError> {
    let doc: Collection = serde_json::from_slice(json)
        .map_err(|e| bad(format!("surface-corrections.geojson: {e}")))?;
    if doc.kind != "FeatureCollection"
        || doc.moto.format != FORMAT_VERSION
        || doc.moto.content != CONTENT
    {
        return Err(bad(
            "surface-corrections.geojson: not a file of corrections",
        ));
    }
    if doc.features.len() > MAX_SURFACE_CORRECTIONS {
        return Err(bad("too many surface corrections"));
    }
    doc.features
        .into_iter()
        .enumerate()
        .map(|(i, f)| feature(f).map_err(|e| bad(format!("surface correction {}: {e}", i + 1))))
        .collect()
}

fn feature(f: Feature) -> Result<ReadCorrection, String> {
    if f.kind != "Feature" || f.geometry.kind != "LineString" {
        return Err("not a LineString feature".into());
    }
    let surface = match f.properties.surface.as_str() {
        "paved" => Surface::Paved,
        "gravel" => Surface::Gravel,
        other => return Err(format!("unknown surface {other:?}")),
    };
    let (created_at, updated_at) = (f.properties.created_at, f.properties.updated_at);
    if !valid_time_s(created_at) || !valid_time_s(updated_at) {
        return Err("invalid time".into());
    }
    let geometry = f
        .geometry
        .coordinates
        .iter()
        .map(|&[lon, lat]| LatLon::new(lat, lon).map_err(|e| e.to_string()))
        .collect::<Result<Vec<_>, _>>()?;
    let ways = f
        .properties
        .ways
        .iter()
        .map(|&[way_id, from, to]| {
            let idx = |v: i64| u32::try_from(v).map_err(|_| "invalid way span".to_string());
            if way_id <= 0 {
                return Err("invalid way id".to_string());
            }
            Ok(WaySpan {
                way_id,
                from_idx: idx(from)?,
                to_idx: idx(to)?,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;
    validate_line(&ways, &geometry).map_err(|e| e.to_string())?;
    let correction = NewSurfaceCorrection {
        rider_id: LOCAL_RIDER.into(),
        surface,
        ways,
        geometry,
    };
    correction.validate().map_err(|e| e.to_string())?;
    Ok(ReadCorrection {
        correction,
        created_at,
        updated_at,
    })
}
