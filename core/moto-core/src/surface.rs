// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Surface corrections (ADR-0018): the rider's own road surface for a
//! stretch where the map has it wrong or has none. A correction is stored
//! like a favourite section (the OSM way spans it covers plus its own
//! line, so it can be re-matched after a region rebuild), has no
//! direction, and is Paved or Gravel only. Routing applies it at query
//! time through the rider's overlay ([`crate::Favourites`]); the region
//! graph never changes.

use crate::region::format::Surface;
use crate::section::{MAX_RIDER_ID_BYTES, MAX_SECTION_POINTS, MAX_SECTION_WAYS, Status, WaySpan};
use crate::{CoreError, LatLon};

/// Most surface corrections a store keeps.
pub const MAX_SURFACE_CORRECTIONS: usize = 10_000;
/// How far from a road point a correction is looked up, in metres.
pub const CORRECTION_REACH_M: f64 = 25.0;

/// Whether `surface` is one a correction may set: Paved or Gravel, which
/// stand for all the map's surfaces.
pub fn is_correction_surface(surface: Surface) -> bool {
    matches!(surface, Surface::Paved | Surface::Gravel)
}

/// The correction surface stored as `code` (the region format's code),
/// or `None` for any other value.
pub(crate) fn correction_surface(code: i64) -> Option<Surface> {
    u8::try_from(code)
        .ok()
        .and_then(Surface::from_u8)
        .filter(|s| is_correction_surface(*s))
}

/// What the rider saves; the store adds id, times and status.
#[derive(Debug, Clone, PartialEq)]
pub struct NewSurfaceCorrection {
    pub rider_id: String,
    /// [`Surface::Paved`] or [`Surface::Gravel`].
    pub surface: Surface,
    /// The OSM way spans covered, in the order of `geometry`.
    pub ways: Vec<WaySpan>,
    /// The stretch's own line, at least two points.
    pub geometry: Vec<LatLon>,
}

/// A saved surface correction.
#[derive(Debug, Clone, PartialEq)]
pub struct SurfaceCorrection {
    pub id: i64,
    pub rider_id: String,
    pub surface: Surface,
    /// `Ok`, or `NeedsRematch` while its region is off the open map;
    /// never `Unmatched` (one that no longer fits is deleted).
    pub status: Status,
    /// Seconds since the Unix epoch.
    pub created_at: i64,
    pub updated_at: i64,
    pub ways: Vec<WaySpan>,
    pub geometry: Vec<LatLon>,
}

/// A saved correction and the ids of the corrections the save deleted
/// (the one it replaced and the older ones it fully covers).
#[derive(Debug, Clone, PartialEq)]
pub struct SurfaceSaved {
    pub correction: SurfaceCorrection,
    pub replaced: Vec<i64>,
}

/// What the rider has set for the road at a point.
#[derive(Debug, Clone, PartialEq)]
pub enum CorrectionAt {
    /// A favourite section's own surface.
    Favourite { section_id: i64, surface: Surface },
    /// A standalone correction.
    Standalone(SurfaceCorrection),
}

impl CorrectionAt {
    /// The surface the rider set.
    pub fn surface(&self) -> Surface {
        match self {
            Self::Favourite { surface, .. } => *surface,
            Self::Standalone(c) => c.surface,
        }
    }
}

fn invalid(msg: impl Into<String>) -> CoreError {
    CoreError::InvalidArgument(msg.into())
}

/// Checks a stretch's way spans and line as a section's are checked.
pub(crate) fn validate_line(ways: &[WaySpan], geometry: &[LatLon]) -> Result<(), CoreError> {
    if geometry.len() < 2 || geometry.len() > MAX_SECTION_POINTS {
        return Err(invalid(format!(
            "a stretch needs 2–{MAX_SECTION_POINTS} points, got {}",
            geometry.len()
        )));
    }
    for p in geometry {
        p.validate()
            .map_err(|_| invalid("a stretch has an invalid point"))?;
    }
    if ways.len() > MAX_SECTION_WAYS {
        return Err(invalid(format!(
            "a stretch references more than {MAX_SECTION_WAYS} ways"
        )));
    }
    if ways.iter().any(|w| w.way_id <= 0) {
        return Err(invalid("OSM way ids are positive"));
    }
    Ok(())
}

impl NewSurfaceCorrection {
    /// Checks everything the store and routing rely on.
    pub fn validate(&self) -> Result<(), CoreError> {
        if self.rider_id.is_empty() || self.rider_id.len() > MAX_RIDER_ID_BYTES {
            return Err(invalid("rider id must be 1–100 bytes"));
        }
        if !is_correction_surface(self.surface) {
            return Err(invalid("a surface correction is Paved or Gravel"));
        }
        validate_line(&self.ways, &self.geometry)
    }
}

/// Whether span `inner` lies within span `outer`: the same way, its node
/// range inside, either direction.
pub(crate) fn span_within(inner: &WaySpan, outer: &WaySpan) -> bool {
    let (ilo, ihi) = (
        inner.from_idx.min(inner.to_idx),
        inner.from_idx.max(inner.to_idx),
    );
    let (olo, ohi) = (
        outer.from_idx.min(outer.to_idx),
        outer.from_idx.max(outer.to_idx),
    );
    inner.way_id == outer.way_id && olo <= ilo && ihi <= ohi
}

/// Whether every one of `old`'s spans lies within one of `new`'s (and it
/// has any): saving `new` makes `old` pointless.
pub(crate) fn covers(new: &[WaySpan], old: &[WaySpan]) -> bool {
    !old.is_empty() && old.iter().all(|o| new.iter().any(|n| span_within(o, n)))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::section::LOCAL_RIDER;

    pub fn sample(surface: Surface) -> NewSurfaceCorrection {
        NewSurfaceCorrection {
            rider_id: LOCAL_RIDER.into(),
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
                    lon: 12.46,
                },
            ],
        }
    }

    #[test]
    fn only_paved_and_gravel_are_corrections() {
        for s in Surface::ALL {
            let ok = matches!(s, Surface::Paved | Surface::Gravel);
            assert_eq!(is_correction_surface(s), ok, "{s:?}");
            assert_eq!(sample(s).validate().is_ok(), ok, "{s:?}");
            assert_eq!(correction_surface(s as i64), ok.then_some(s));
        }
        for bad in [-1, 0, 1, 7, 8, 255, 256, 259, i64::MAX] {
            assert_eq!(correction_surface(bad), None, "{bad}");
        }
    }

    #[test]
    fn rejects_bad_corrections() {
        type Break = Box<dyn Fn(&mut NewSurfaceCorrection)>;
        let cases: Vec<(&str, Break)> = vec![
            ("empty rider", Box::new(|c| c.rider_id.clear())),
            ("long rider", Box::new(|c| c.rider_id = "x".repeat(101))),
            ("unknown", Box::new(|c| c.surface = Surface::Unknown)),
            ("asphalt", Box::new(|c| c.surface = Surface::Asphalt)),
            ("dirt", Box::new(|c| c.surface = Surface::Dirt)),
            ("one point", Box::new(|c| c.geometry.truncate(1))),
            ("no points", Box::new(|c| c.geometry.clear())),
            (
                "too many points",
                Box::new(|c| c.geometry = vec![c.geometry[0]; MAX_SECTION_POINTS + 1]),
            ),
            ("NaN", Box::new(|c| c.geometry[1].lat = f64::NAN)),
            ("north of the pole", Box::new(|c| c.geometry[0].lat = 91.0)),
            ("lon", Box::new(|c| c.geometry[0].lon = -180.5)),
            (
                "too many ways",
                Box::new(|c| c.ways = vec![c.ways[0]; MAX_SECTION_WAYS + 1]),
            ),
            ("way 0", Box::new(|c| c.ways[0].way_id = 0)),
            ("negative way", Box::new(|c| c.ways[0].way_id = -5)),
        ];
        for (why, break_it) in cases {
            let mut c = sample(Surface::Gravel);
            break_it(&mut c);
            assert!(
                matches!(c.validate(), Err(CoreError::InvalidArgument(_))),
                "{why} should be rejected"
            );
        }
    }

    #[test]
    fn coverage_of_spans() {
        let s = |way_id, from_idx, to_idx| WaySpan {
            way_id,
            from_idx,
            to_idx,
        };
        assert!(span_within(&s(1, 2, 3), &s(1, 0, 5)));
        assert!(span_within(&s(1, 3, 2), &s(1, 5, 0)), "either direction");
        assert!(span_within(&s(1, 0, 5), &s(1, 5, 0)));
        assert!(!span_within(&s(1, 0, 6), &s(1, 0, 5)));
        assert!(!span_within(&s(2, 2, 3), &s(1, 0, 5)));
        assert!(covers(&[s(1, 0, 5), s(2, 0, 9)], &[s(1, 1, 2), s(2, 9, 3)]));
        assert!(!covers(&[s(1, 0, 5)], &[s(1, 1, 2), s(2, 0, 1)]));
        assert!(!covers(&[s(1, 0, 5)], &[]), "nothing to cover");
    }
}
