// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Reading an OSM `.osm.pbf` extract: node positions inside the region and
//! every motorcycle-routable way.

use std::path::Path;

use moto_core::region::format::{BBoxE7, PointE7};
use osmpbf::{BlobDecode, BlobReader, Element};
use rayon::prelude::*;

use crate::graph::RawWay;
use crate::tags;

/// What the builder needs from an extract.
#[derive(Default)]
pub struct Osm {
    /// Nodes inside the bounding box, in no particular order.
    pub nodes: Vec<(i64, PointE7)>,
    /// Routable ways from the whole file, sorted by id.
    pub ways: Vec<RawWay>,
    /// The extract's replication timestamp, if its header has one.
    pub timestamp: Option<i64>,
}

/// Reads the extract, decoding blobs in parallel. Errors (unreadable or
/// malformed files) come back as messages; nothing panics on bad input.
pub fn read(path: &Path, bbox: &BBoxE7) -> Result<Osm, String> {
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
                            out.nodes.push((n.id(), PointE7 { lat, lon }));
                        }
                    }
                    Element::Node(n) => {
                        let (lat, lon) = (n.decimicro_lat(), n.decimicro_lon());
                        if inside(lat, lon) {
                            out.nodes.push((n.id(), PointE7 { lat, lon }));
                        }
                    }
                    Element::Way(w) => {
                        let tags: Vec<(&str, &str)> = w.tags().collect();
                        if let Some(attrs) = tags::classify(&tags) {
                            out.ways.push(RawWay {
                                id: w.id(),
                                refs: w.refs().collect(),
                                attrs,
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
        all.timestamp = all.timestamp.or(p.timestamp);
    }
    // Blob order is lost in parallel; keep the output deterministic.
    all.ways.sort_unstable_by_key(|w| w.id);
    Ok(all)
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
        let osm = read(Path::new(FIXTURE), &bbox_e7(b[0], b[1], b[2], b[3])).unwrap();
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
        let osm = read(Path::new(FIXTURE), &small).unwrap();
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
        let res = read(&junk, &bbox);
        std::fs::remove_file(&junk).unwrap();
        assert!(res.is_err());
        assert!(read(Path::new("/definitely/not/here.osm.pbf"), &bbox).is_err());

        // A real file cut short.
        let bytes = std::fs::read(FIXTURE).unwrap();
        let cut = dir.join(format!("moto-cut-{}.osm.pbf", std::process::id()));
        std::fs::write(&cut, &bytes[..bytes.len() / 2]).unwrap();
        let res = read(&cut, &bbox);
        std::fs::remove_file(&cut).unwrap();
        assert!(res.is_err());
    }
}
