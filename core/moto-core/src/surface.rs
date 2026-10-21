// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Surface corrections (ADR-0018): the rider's own road surface for a
//! stretch where the map has it wrong or has none. A correction is stored
//! like a favourite section (the OSM way spans it covers plus its own
//! line, so it can be re-matched after a region rebuild), has no
//! direction, and is Paved or Gravel only. Routing applies it at query
//! time through the rider's overlay ([`crate::Favourites`]); the region
//! graph never changes.

use std::collections::HashSet;

use crate::draft::{SectionDraft, from_path};
use crate::region::format::Surface;
use crate::route::Partial;
use crate::section::{MAX_RIDER_ID_BYTES, MAX_SECTION_POINTS, MAX_SECTION_WAYS, Status, WaySpan};
use crate::{CoreError, Engine, LatLon};

/// How far the stretch under a tap ([`stretch_at`]) reaches on each side
/// of it, in metres.
pub const STRETCH_REACH_M: f64 = 10_000.0;
/// Most edges the stretch under a tap follows on each side.
const MAX_STRETCH_EDGES: usize = 20_000;

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

/// One side of [`stretch_at`]: from edge `start`, `walked` metres from
/// the tap already, edge by edge along the same OSM way (`ahead`: on
/// from each edge's head; else back from its tail), until the way ends,
/// an edge comes round again, or [`STRETCH_REACH_M`] is reached, where
/// the last piece is cut. Pieces in walking order.
fn walk_way(
    engine: &Engine,
    start: u32,
    mut walked: f64,
    ahead: bool,
    seen: &mut HashSet<u32>,
) -> Vec<Partial> {
    let region = engine.net();
    let length = |id: u32| (f64::from(region.edge(id).length_dm) / 10.0).max(0.0);
    let mut parts = Vec::new();
    let mut cur = start;
    let mut steps = 0;
    while walked < STRETCH_REACH_M && steps < MAX_STRETCH_EDGES {
        steps += 1;
        let (e, r) = (region.edge(cur), region.way_ref(cur));
        let follows = |c: &u32| {
            region.get_edge(*c).is_some_and(|ce| {
                let cr = region.way_ref(*c);
                ce.geometry != e.geometry
                    && cr.way_id == r.way_id
                    && if ahead {
                        cr.from_idx == r.to_idx
                    } else {
                        cr.to_idx == r.from_idx
                    }
            })
        };
        let next = if ahead {
            region.out_edges(e.head).find(follows)
        } else {
            region.in_edges(e.tail).find(follows)
        };
        let Some(c) = next.filter(|c| seen.insert(*c)) else {
            break;
        };
        let l = length(c);
        let left = STRETCH_REACH_M - walked;
        let cut = if l > left { left / l } else { 1.0 };
        parts.push(if ahead {
            Partial {
                edge: c,
                from: 0.0,
                to: cut,
            }
        } else {
            Partial {
                edge: c,
                from: 1.0 - cut,
                to: 1.0,
            }
        });
        if l > left {
            break;
        }
        walked += l;
        cur = c;
    }
    parts
}

/// The OSM way under `point` (snapped as [`Engine::snap`] does), as a
/// draft to correct: from the snapped edge on along the same way, edge by
/// edge, and back the same way, each side stopping at the way's end, back
/// at the first edge (a closed way) or [`STRETCH_REACH_M`] from the tap,
/// where the last piece is cut. OSM splits ways where the surface
/// changes, so this is usually the stretch to correct as it stands.
pub(crate) fn stretch_at(engine: &Engine, point: LatLon) -> Result<SectionDraft, CoreError> {
    point.validate()?;
    let region = engine.net();
    let p = engine.snap(point)?;
    let len0 = (f64::from(region.edge(p.edge).length_dm) / 10.0).max(0.0);
    let mut seen = HashSet::from([p.edge]);
    let ahead = walk_way(engine, p.edge, (1.0 - p.offset) * len0, true, &mut seen);
    let behind = walk_way(engine, p.edge, p.offset * len0, false, &mut seen);
    // The tapped edge itself, cut where it alone runs past the reach.
    let (from, to) = if len0 > 0.0 {
        let k = STRETCH_REACH_M / len0;
        ((p.offset - k).max(0.0), (p.offset + k).min(1.0))
    } else {
        (0.0, 1.0)
    };
    let mut parts: Vec<Partial> = behind.into_iter().rev().collect();
    parts.push(Partial {
        edge: p.edge,
        from,
        to,
    });
    parts.extend(ahead);
    from_path(region, &parts)
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

    pub(crate) fn engine(data: crate::region::RegionData) -> Engine {
        Engine::from_region(crate::region::Region::from_bytes(&data.to_bytes().unwrap()).unwrap())
    }

    /// One straight east–west OSM way (500) along lat 55.70 from lon
    /// 13.00 to 13.60, split into twelve roads of about 3.1 km, with a
    /// side road (way 501) north from its middle node.
    pub(crate) fn long_way() -> crate::region::RegionData {
        use crate::fixture::{Road, build};
        use crate::region::format::RoadClass;
        let mut nodes: Vec<(f64, f64)> = (0..13)
            .map(|i| (55.70, 13.0 + 0.05 * f64::from(i)))
            .collect();
        nodes.push((55.72, 13.30));
        let mut roads: Vec<Road> = (0..12u32)
            .map(|i| Road {
                way_start: i,
                ..Road::new(i, i + 1, RoadClass::Tertiary, 70, 500)
            })
            .collect();
        roads.push(Road::new(6, 13, RoadClass::Tertiary, 70, 501));
        build(&nodes, &roads, 50_000)
    }

    #[test]
    fn the_stretch_is_the_whole_way_under_the_tap() {
        use crate::fixture::{self, ladder};
        // The ladder's north road is way 1 in two roads; its ends meet
        // way 3 and way 4.
        let e = engine(ladder(Surface::Asphalt));
        let d = e
            .surface_stretch_at(LatLon {
                lat: 55.7201,
                lon: 13.405,
            })
            .unwrap();
        assert_eq!(d.ways.len(), 1, "{d:?}");
        let w = d.ways[0];
        assert_eq!(
            (w.way_id, w.from_idx.min(w.to_idx), w.from_idx.max(w.to_idx)),
            (1, 0, 2)
        );
        let road_m = crate::geo::haversine_m(
            LatLon {
                lat: 55.72,
                lon: 13.40,
            },
            LatLon {
                lat: 55.72,
                lon: 13.44,
            },
        );
        assert!((d.distance_m - road_m).abs() < 1.0, "{d:?}");

        // Not on into other ways at junctions: the fork's straight road
        // alone, though the north loop and the stubs meet it.
        let e = engine(fixture::fork());
        let d = e
            .surface_stretch_at(LatLon {
                lat: 55.7001,
                lon: 13.42,
            })
            .unwrap();
        assert_eq!(d.ways.len(), 1, "{d:?}");
        assert_eq!(d.ways[0].way_id, 10);
        // The north loop: three roads of one way.
        let d = e
            .surface_stretch_at(LatLon {
                lat: 55.7101,
                lon: 13.42,
            })
            .unwrap();
        assert_eq!(d.ways.len(), 1, "{d:?}");
        let w = d.ways[0];
        assert_eq!(
            (w.way_id, w.from_idx.min(w.to_idx), w.from_idx.max(w.to_idx)),
            (fixture::FORK_NORTH, 0, 3)
        );
    }

    #[test]
    fn the_stretch_stops_10_km_each_side() {
        let e = engine(long_way());
        let d = e
            .surface_stretch_at(LatLon {
                lat: 55.7001,
                lon: 13.31,
            })
            .unwrap();
        assert!(
            (d.distance_m - 2.0 * STRETCH_REACH_M).abs() < 5.0,
            "{}",
            d.distance_m
        );
        assert_eq!(d.ways.len(), 1);
        assert_eq!(d.ways[0].way_id, 500, "not up the side road");
        // Near an end: the way's end on that side, 10 km on the other.
        let d = e
            .surface_stretch_at(LatLon {
                lat: 55.7001,
                lon: 13.01,
            })
            .unwrap();
        let to_start = 0.01 * 111_320.0 * 55.70f64.to_radians().cos();
        assert!(
            (d.distance_m - (STRETCH_REACH_M + to_start)).abs() < 30.0,
            "{}",
            d.distance_m
        );
    }

    #[test]
    fn a_closed_way_is_walked_round_once() {
        use crate::fixture::{Road, build};
        use crate::region::format::RoadClass;
        let nodes = [
            (55.70, 13.40),
            (55.70, 13.41),
            (55.71, 13.41),
            (55.71, 13.40),
            (55.69, 13.40),
        ];
        let ring = |from, to, start| Road {
            way_start: start,
            ..Road::new(from, to, RoadClass::Unclassified, 50, 600)
        };
        let e = engine(build(
            &nodes,
            &[
                ring(0, 1, 0),
                ring(1, 2, 1),
                ring(2, 3, 2),
                ring(3, 0, 3),
                Road::new(0, 4, RoadClass::Unclassified, 50, 601),
            ],
            50_000,
        ));
        let d = e
            .surface_stretch_at(LatLon {
                lat: 55.705,
                lon: 13.4101,
            })
            .unwrap();
        let ring_m: f64 = (0..4)
            .map(|i| {
                let a = nodes[i];
                let b = nodes[(i + 1) % 4];
                crate::geo::haversine_m(
                    LatLon { lat: a.0, lon: a.1 },
                    LatLon { lat: b.0, lon: b.1 },
                )
            })
            .sum();
        assert!((d.distance_m - ring_m).abs() < 1.0, "{}", d.distance_m);
        assert!(d.ways.iter().all(|w| w.way_id == 600), "{d:?}");
    }

    #[test]
    fn a_tap_off_the_region_is_a_typed_error() {
        let e = engine(crate::fixture::ladder(Surface::Asphalt));
        for p in [
            LatLon { lat: 0.0, lon: 0.0 },
            LatLon {
                lat: f64::NAN,
                lon: 13.4,
            },
        ] {
            assert!(e.surface_stretch_at(p).is_err(), "{p:?}");
        }
    }
}
