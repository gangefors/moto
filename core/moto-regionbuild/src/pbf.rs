// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Reading an OSM `.osm.pbf` extract: node positions inside the region and
//! every motorcycle-routable way.

use std::collections::HashSet;
use std::path::Path;

use moto_core::region::format::{BBoxE7, PlaceKind, PointE7, edge_flags};
use osmpbf::{BlobDecode, BlobReader, Element};
use rayon::prelude::*;

use crate::graph::RawWay;
use crate::speed::Country;
use crate::tags;

/// What the builder needs from an extract.
#[derive(Default)]
pub struct Osm {
    /// Nodes inside the bounding box, in no particular order.
    pub nodes: Vec<(i64, PointE7)>,
    /// Routable ways from the whole file, sorted by id.
    pub ways: Vec<RawWay>,
    /// Named places inside the bounding box (see [`tags::place`]).
    pub places: Vec<(PointE7, PlaceKind, String)>,
    /// The extract's replication timestamp, if its header has one.
    pub timestamp: Option<i64>,
    /// Toll booths inside the bounding box, and the ways a booth would
    /// make toll roads ([`tags::motorcycle_toll`]); used up by `read`.
    booths: Vec<i64>,
    booth_ways: Vec<i64>,
}

/// Reads the extract, decoding blobs in parallel; `country`'s speed
/// limits apply to its roads. Errors (unreadable or
/// malformed files) come back as messages; nothing panics on bad input.
pub fn read(path: &Path, bbox: &BBoxE7, country: Country) -> Result<Osm, String> {
    let reader = BlobReader::from_path(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let inside = |lat: i32, lon: i32| {
        (bbox.min_lat..=bbox.max_lat).contains(&lat) && (bbox.min_lon..=bbox.max_lon).contains(&lon)
    };
    let parts: Vec<Osm> = reader
        .par_bridge()
        .map(|blob| -> Result<Osm, String> {
            let blob = blob.map_err(|e| format!("{}: {e}", path.display()))?;
            let mut out = Osm::default();
            match blob
                .decode()
                .map_err(|e| format!("{}: {e}", path.display()))?
            {
                BlobDecode::OsmHeader(h) => out.timestamp = h.osmosis_replication_timestamp(),
                BlobDecode::OsmData(block) => block.for_each_element(|el| match el {
                    Element::DenseNode(n) => {
                        let (lat, lon) = (n.decimicro_lat(), n.decimicro_lon());
                        if inside(lat, lon) {
                            let pos = PointE7 { lat, lon };
                            out.nodes.push((n.id(), pos));
                            let tags: Vec<(&str, &str)> = n.tags().collect();
                            if let Some((kind, name)) = tags::place(&tags) {
                                out.places.push((pos, kind, name));
                            }
                            if tags::is_toll_booth(&tags) {
                                out.booths.push(n.id());
                            }
                        }
                    }
                    Element::Node(n) => {
                        let (lat, lon) = (n.decimicro_lat(), n.decimicro_lon());
                        if inside(lat, lon) {
                            let pos = PointE7 { lat, lon };
                            out.nodes.push((n.id(), pos));
                            let tags: Vec<(&str, &str)> = n.tags().collect();
                            if let Some((kind, name)) = tags::place(&tags) {
                                out.places.push((pos, kind, name));
                            }
                            if tags::is_toll_booth(&tags) {
                                out.booths.push(n.id());
                            }
                        }
                    }
                    Element::Way(w) => {
                        let tags: Vec<(&str, &str)> = w.tags().collect();
                        if let Some(attrs) = tags::classify(&tags, country) {
                            if tags::motorcycle_toll(&tags, attrs.class, country).is_none() {
                                out.booth_ways.push(w.id());
                            }
                            let (road_ref, name) = tags::road_names(&tags);
                            out.ways.push(RawWay {
                                id: w.id(),
                                refs: w.refs().collect(),
                                attrs,
                                road_ref,
                                name,
                            });
                        }
                    }
                    Element::Relation(_) => {}
                }),
                BlobDecode::Unknown(_) => {}
            }
            Ok(out)
        })
        .collect::<Result<_, _>>()?;

    let mut all = Osm::default();
    for mut p in parts {
        all.nodes.append(&mut p.nodes);
        all.ways.append(&mut p.ways);
        all.places.append(&mut p.places);
        all.timestamp = all.timestamp.or(p.timestamp);
        all.booths.append(&mut p.booths);
        all.booth_ways.append(&mut p.booth_ways);
    }
    let (booths, booth_ways) = (
        std::mem::take(&mut all.booths),
        std::mem::take(&mut all.booth_ways),
    );
    mark_booth_tolls(&mut all.ways, &booths, &booth_ways);
    // Blob order is lost in parallel; keep the output deterministic.
    all.ways.sort_unstable_by_key(|w| w.id);
    all.places
        .sort_unstable_by(|a, b| (a.0.lat, a.0.lon, a.1, &a.2).cmp(&(b.0.lat, b.0.lon, b.1, &b.2)));
    Ok(all)
}

/// Makes each of `booth_ways` a toll road when one of `booths` stands on
/// it (see [`tags::motorcycle_toll`]).
fn mark_booth_tolls(ways: &mut [RawWay], booths: &[i64], booth_ways: &[i64]) {
    let booths: HashSet<i64> = booths.iter().copied().collect();
    let booth_ways: HashSet<i64> = booth_ways.iter().copied().collect();
    for w in ways {
        if booth_ways.contains(&w.id) && w.refs.iter().any(|r| booths.contains(r)) {
            w.attrs.flags |= edge_flags::TOLL;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::graph::bbox_e7;
    use crate::test_support::{FIXTURE, FIXTURE_BBOX};
    use moto_core::region::format::RoadClass;

    #[test]
    fn reads_nodes_ways_and_timestamp() {
        let b = FIXTURE_BBOX;
        let osm = read(
            Path::new(FIXTURE),
            &bbox_e7(b[0], b[1], b[2], b[3]),
            Country::Sweden,
        )
        .unwrap();
        // 2026-09-22T20:22:59Z, set when the fixture was cut.
        assert_eq!(osm.timestamp, Some(1_790_108_579));
        assert_eq!(osm.nodes.len(), 1678);
        assert_eq!(osm.ways.len(), 10, "only routable ways");
        assert!(osm.ways.windows(2).all(|w| w[0].id < w[1].id));
        let residential = osm.ways.iter().find(|w| w.id == 79_664_841).unwrap();
        assert_eq!(residential.attrs.class, RoadClass::Residential);
    }

    #[test]
    fn keeps_only_nodes_inside_the_box() {
        // A box around the south-west quarter of the fixture.
        let small = bbox_e7(55.7040, 13.1900, 55.7050, 13.1920);
        let osm = read(Path::new(FIXTURE), &small, Country::Sweden).unwrap();
        assert!(!osm.nodes.is_empty() && osm.nodes.len() < 1678);
        for (_, p) in &osm.nodes {
            assert!((small.min_lat..=small.max_lat).contains(&p.lat));
            assert!((small.min_lon..=small.max_lon).contains(&p.lon));
        }
    }

    #[test]
    fn bad_files_are_errors_not_panics() {
        let dir = std::env::temp_dir();
        let junk = dir.join(format!("moto-junk-{}.osm.pbf", std::process::id()));
        std::fs::write(&junk, vec![0x42u8; 5000]).unwrap();
        let bbox = bbox_e7(55.0, 13.0, 56.0, 14.0);
        let res = read(&junk, &bbox, Country::Sweden);
        std::fs::remove_file(&junk).unwrap();
        assert!(res.is_err());
        assert!(
            read(
                Path::new("/definitely/not/here.osm.pbf"),
                &bbox,
                Country::Sweden
            )
            .is_err()
        );

        // A real file cut short.
        let bytes = std::fs::read(FIXTURE).unwrap();
        let cut = dir.join(format!("moto-cut-{}.osm.pbf", std::process::id()));
        std::fs::write(&cut, &bytes[..bytes.len() / 2]).unwrap();
        let res = read(&cut, &bbox, Country::Sweden);
        std::fs::remove_file(&cut).unwrap();
        assert!(res.is_err());
    }

    #[test]
    fn a_booth_makes_only_open_ways_toll_roads() {
        let attrs = tags::classify(&[("highway", "unclassified")], Country::Norway).unwrap();
        let way = |id: i64, refs: Vec<i64>| RawWay {
            id,
            refs,
            attrs,
            road_ref: None,
            name: None,
        };
        // 1: a booth on a way left open; 2: left open, no booth on it;
        // 3: a booth, but the way's tags settled it.
        let mut ways = vec![
            way(1, vec![10, 11]),
            way(2, vec![12, 13]),
            way(3, vec![11, 14]),
        ];
        mark_booth_tolls(&mut ways, &[11], &[1, 2]);
        let tolled: Vec<bool> = ways
            .iter()
            .map(|w| w.attrs.flags & edge_flags::TOLL != 0)
            .collect();
        assert_eq!(tolled, [true, false, false]);
    }
}
