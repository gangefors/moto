// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Re-matching saved sections to a new region file (M1 step 7, ADR-0005,
//! ADR-0006). OSM ways get split, merged, renumbered and realigned between
//! extracts, so after a region update each section's own geometry is
//! map-matched to the new road network. A section that no longer fits is
//! flagged `unmatched`, never dropped: the rider's data outlives any map.

use crate::geo::haversine_m;
use crate::matching::MatchedPiece;
use crate::section::{MAX_SECTION_POINTS, MAX_SECTION_WAYS, Section, Status, WaySpan};
use crate::store::Store;
use crate::{CoreError, Engine, LatLon};

/// Points fed to the matcher along a section's geometry, this far apart.
const DENSIFY_STEP_M: f64 = 15.0;
/// Farthest a point of the old geometry may lie from the re-matched road.
const MAX_DEVIATION_M: f64 = 25.0;
/// The re-matched road may differ in length by this share of the old one...
const MAX_LENGTH_CHANGE: f64 = 0.05;
/// ...or by this many metres, whichever is larger (the matcher may stop a
/// little short of the last point).
const MAX_LENGTH_CHANGE_M: f64 = 30.0;

/// The key a store remembers for the region its sections were matched to.
/// It changes with the OSM data, the builder, or the graph built.
pub fn region_key(engine: &Engine) -> String {
    let region = engine.region();
    let info = region.info();
    format!(
        "{}|{}|{}|{}|{}",
        info.source_name,
        info.osm_timestamp,
        info.builder_version,
        region.node_count(),
        region.edge_count()
    )
}

/// A section's fit to the current region: its new way spans and geometry,
/// or `None` if it no longer fits well enough.
pub fn rematch(engine: &Engine, section: &Section) -> Option<(Vec<WaySpan>, Vec<LatLon>)> {
    let old = &section.geometry;
    let old_len: f64 = old.windows(2).map(|w| haversine_m(w[0], w[1])).sum();
    if old.len() < 2 || old_len <= 0.0 {
        return None;
    }
    let m = engine.match_track(&densify(old, DENSIFY_STEP_M)).ok()?;
    let [piece] = m.pieces.as_slice() else {
        return None; // gone, or broken in two
    };
    fits(old, old_len, piece).then(|| (piece.ways.clone(), piece.geometry.clone()))
}

/// Whether a matched piece still is the old section: about as long, and
/// never far from it.
fn fits(old: &[LatLon], old_len: f64, piece: &MatchedPiece) -> bool {
    let tolerance = (old_len * MAX_LENGTH_CHANGE).max(MAX_LENGTH_CHANGE_M);
    if (piece.distance_m - old_len).abs() > tolerance {
        return false;
    }
    if piece.geometry.len() > MAX_SECTION_POINTS || piece.ways.len() > MAX_SECTION_WAYS {
        return false;
    }
    old.iter()
        .all(|&p| distance_to_line(p, &piece.geometry) <= MAX_DEVIATION_M)
}

/// Points along `line` no more than `step_m` apart, its vertices included.
fn densify(line: &[LatLon], step_m: f64) -> Vec<LatLon> {
    let mut out = Vec::with_capacity(line.len() * 2);
    for w in line.windows(2) {
        let d = haversine_m(w[0], w[1]);
        let n = (d / step_m).ceil().max(1.0) as usize;
        for k in 0..n {
            let f = k as f64 / n as f64;
            out.push(LatLon {
                lat: w[0].lat + f * (w[1].lat - w[0].lat),
                lon: w[0].lon + f * (w[1].lon - w[0].lon),
            });
        }
    }
    out.extend(line.last().copied());
    out
}

/// Distance in metres from `p` to the nearest point of `line` (local flat
/// approximation, fine over a section).
fn distance_to_line(p: LatLon, line: &[LatLon]) -> f64 {
    let k = p.lat.to_radians().cos();
    let m = 111_195.0;
    let xy = |q: LatLon| ((q.lon - p.lon) * k * m, (q.lat - p.lat) * m);
    let mut best = f64::INFINITY;
    for w in line.windows(2) {
        let (a, b) = (xy(w[0]), xy(w[1]));
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len2 = dx * dx + dy * dy;
        let t = if len2 > 0.0 {
            (-(a.0 * dx + a.1 * dy) / len2).clamp(0.0, 1.0)
        } else {
            0.0
        };
        best = best.min((a.0 + t * dx).hypot(a.1 + t * dy));
    }
    if line.len() == 1 {
        best = xy(line[0]).0.hypot(xy(line[0]).1);
    }
    best
}

/// What [`rematch_store`] did.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct RematchReport {
    /// Sections looked at (0 when the region hadn't changed).
    pub checked: u64,
    /// Sections that fit the region (way spans updated where they changed).
    pub matched: u64,
    /// Sections that no longer fit: kept, flagged `unmatched`.
    pub unmatched: u64,
}

/// Brings the store's sections up to date with `engine`'s region, if the
/// region changed since they were last matched (or a previous run was cut
/// short). First every section is flagged `needs_rematch` in one
/// transaction, then each is re-matched and saved on its own, and only
/// then is the region remembered: a crash part-way leaves the work to be
/// redone on the next start, never a section wrongly marked as fitting.
pub fn rematch_store(store: &mut Store, engine: &Engine) -> Result<RematchReport, CoreError> {
    let key = region_key(engine);
    let pending = store.sections_with_status(Status::NeedsRematch)?;
    if store.region_key()?.as_deref() == Some(key.as_str()) && pending.is_empty() {
        return Ok(RematchReport::default());
    }
    store.flag_all_for_rematch()?;
    let mut report = RematchReport::default();
    for section in store.sections_with_status(Status::NeedsRematch)? {
        report.checked += 1;
        match rematch(engine, &section) {
            Some((ways, geometry)) => {
                store.save_match(section.id, &ways, &geometry)?;
                report.matched += 1;
            }
            None => {
                store.set_status(section.id, Status::Unmatched)?;
                report.unmatched += 1;
            }
        }
    }
    store.set_region_key(&key)?;
    Ok(report)
}

#[cfg(test)]
mod tests;
