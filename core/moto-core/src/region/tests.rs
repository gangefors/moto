// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

use super::writer::assemble;
use super::*;
use crate::fixture::{self, A, B, C, D, E_AB, E_BA, E_BC, E_BD, E_CB};
use crate::{CoreError, LatLon};

fn bytes() -> Vec<u8> {
    fixture::region().to_bytes().unwrap()
}

fn entry(bytes: &[u8], id: u32) -> SectionEntry {
    let (_, table) = parse_header(bytes).unwrap();
    table.into_iter().find(|s| s.id == id).unwrap()
}

/// Overwrites the `index`th `T` of section `id`.
fn patch<T: Pod>(bytes: &mut [u8], id: u32, index: usize, value: T) {
    let s = entry(bytes, id);
    let at = s.offset as usize + index * size_of::<T>();
    bytes[at..at + size_of::<T>()].copy_from_slice(bytemuck::bytes_of(&value));
}

fn patch_entry(bytes: &mut [u8], id: u32, f: impl FnOnce(&mut SectionEntry)) {
    let (header, table) = parse_header(bytes).unwrap();
    let i = table.iter().position(|s| s.id == id).unwrap();
    let mut e = table[i];
    f(&mut e);
    let at = SECTION_TABLE_OFFSET + i * size_of::<SectionEntry>();
    bytes[at..at + size_of::<SectionEntry>()].copy_from_slice(bytemuck::bytes_of(&e));
    let _ = header;
}

fn assert_rejected(bytes: &[u8], why: &str) {
    match Region::from_bytes(bytes) {
        Err(CoreError::Region(msg)) => assert!(msg.contains(why), "expected '{why}', got '{msg}'"),
        other => panic!("expected rejection with '{why}', got {other:?}"),
    }
}

#[test]
fn round_trips_the_fixture() {
    let data = fixture::region();
    let b = data.to_bytes().unwrap();
    assert_eq!(b.len() % PAGE as usize, 0);
    let r = Region::from_bytes(&b).unwrap();

    assert_eq!(r.info(), &data.info);
    assert_eq!(r.nodes(), &data.nodes[..]);
    assert_eq!(r.edges(), &data.edges[..]);
    assert_eq!(r.geometry_offsets(), &data.geometry_offsets[..]);
    assert_eq!(r.shape_points(), &data.shape_points[..]);
    assert_eq!(r.curvature(), &data.curvature[..]);
    assert_eq!(r.way_refs(), &data.way_refs[..]);
    r.verify_checksums().unwrap();
}

#[test]
fn builds_forward_and_backward_adjacency() {
    let r = Region::from_bytes(&bytes()).unwrap();
    assert_eq!(r.fwd_offsets(), &[0, 1, 4, 5, 5]);
    assert_eq!(r.out_edges(A), E_AB..E_AB + 1);
    assert_eq!(r.out_edges(B), E_BA..E_CB);
    assert_eq!(r.out_edges(D), 5..5);
    assert_eq!(r.in_edges(A), &[E_BA]);
    let mut into_b = r.in_edges(B).to_vec();
    into_b.sort();
    assert_eq!(into_b, [E_AB, E_CB]);
    assert_eq!(r.in_edges(C), &[E_BC]);
    assert_eq!(r.in_edges(D), &[E_BD]);
    assert!(r.in_edges(99).is_empty());
    assert!(r.out_edges(99).is_empty());
}

#[test]
fn both_directions_share_one_geometry() {
    let r = Region::from_bytes(&bytes()).unwrap();
    let (ab, ba) = (r.edges()[E_AB as usize], r.edges()[E_BA as usize]);
    assert_eq!(ab.geometry, ba.geometry);
    assert_eq!(ab.length_dm, ba.length_dm);
    assert_eq!(ba.flags & edge_flags::REVERSED, edge_flags::REVERSED);
    assert_eq!(r.geometry(r.edges()[E_BC as usize].geometry).len(), 3);
    assert!(r.geometry(99).is_empty());
    // A–B is 0.01° of longitude at 55.7°N: about 627 m.
    assert!((ab.length_dm as i64 - 6_270).abs() < 20, "{ab:?}");
}

#[test]
fn curvature_is_stored_per_edge() {
    let r = Region::from_bytes(&bytes()).unwrap();
    assert_eq!(r.curvature()[E_AB as usize].turn_ddeg, 0);
    let bend = r.curvature()[E_BC as usize];
    assert!(bend.turn_ddeg > 0);
    assert_eq!(bend, r.curvature()[E_CB as usize]);
}

#[test]
fn grid_lists_every_forward_edge_where_it_runs() {
    let r = Region::from_bytes(&bytes()).unwrap();
    let meta = *r.grid_meta();
    assert_eq!((meta.cell_lat, meta.cell_lon), (50_000, 50_000));
    assert_eq!((meta.rows, meta.cols), (3, 5));
    let mut listed: Vec<u32> = Vec::new();
    for row in 0..meta.rows {
        for col in 0..meta.cols {
            listed.extend_from_slice(r.grid_cell(row, col));
        }
    }
    listed.sort();
    listed.dedup();
    assert_eq!(listed, [E_AB, E_BC, E_BD], "reverse edges are not indexed");
    // The cell holding the midpoint of A–B lists A–B.
    assert!(r.grid_cell(0, 1).contains(&E_AB));
    assert!(r.grid_cell(7, 0).is_empty());
}

#[test]
fn opens_from_disk_and_verifies_checksums() {
    let dir = std::env::temp_dir();
    let good = dir.join(format!("moto-good-{}.region", std::process::id()));
    let bad = dir.join(format!("moto-bad-{}.region", std::process::id()));
    let b = bytes();
    let mut corrupt = b.clone();
    let shape = entry(&b, section::SHAPE_POINTS);
    corrupt[shape.offset as usize + 3 * 8] ^= 1; // the bend of B–C
    std::fs::write(&good, &b).unwrap();
    std::fs::write(&bad, &corrupt).unwrap();

    let opened = Region::open(&good).map(|r| r.nodes().len());
    let verified = verify_file(&good);
    let bad_verified = verify_file(&bad);
    // A flipped low bit in a shape point is structurally fine, so opening
    // succeeds and only the checksum catches it.
    let bad_opened = Region::open(&bad).is_ok();
    std::fs::remove_file(&good).unwrap();
    std::fs::remove_file(&bad).unwrap();

    assert_eq!(opened.unwrap(), 4);
    verified.unwrap();
    match bad_verified {
        Err(CoreError::Region(msg)) => assert!(msg.contains("checksum"), "{msg}"),
        other => panic!("expected a checksum error, got {other:?}"),
    }
    assert!(bad_opened);
}

#[test]
fn ignores_unknown_sections() {
    let data = fixture::region();
    let b = data.to_bytes().unwrap();
    let (_, table) = parse_header(&b).unwrap();
    let mut sections: Vec<(u32, Vec<u8>)> = table
        .iter()
        .map(|s| {
            (
                s.id,
                b[s.offset as usize..(s.offset + s.len) as usize].to_vec(),
            )
        })
        .collect();
    sections.push((section::LANDMARKS, vec![1, 2, 3]));
    sections.push((4242, vec![9; 5000]));
    let refs: Vec<(u32, &[u8])> = sections.iter().map(|(i, d)| (*i, &d[..])).collect();
    let mut out = assemble(&data.info, &refs);
    // A newer minor version is fine too.
    out[10..12].copy_from_slice(&7u16.to_le_bytes());
    let r = Region::from_bytes(&out).unwrap();
    assert_eq!(r.edges(), &data.edges[..]);
    r.verify_checksums().unwrap();
}

#[test]
fn rejects_bad_headers() {
    assert_rejected(&[], "too short");
    let mut b = bytes();
    b[0] = b'X';
    assert_rejected(&b, "magic");

    let mut b = bytes();
    b[8..10].copy_from_slice(&2u16.to_le_bytes());
    assert_rejected(&b, "unsupported region format 2.0");

    let mut b = bytes();
    b[12..16].copy_from_slice(&1000u32.to_le_bytes());
    assert_rejected(&b, "section table too large");
}

#[test]
fn rejects_bad_section_table() {
    let mut b = bytes();
    patch_entry(&mut b, section::EDGES, |e| e.offset += 8);
    assert_rejected(&b, "misaligned");

    let mut b = bytes();
    patch_entry(&mut b, section::EDGES, |e| e.len = u64::MAX);
    assert_rejected(&b, "out of bounds");

    let mut b = bytes();
    let len = b.len();
    b.truncate(len - PAGE as usize);
    assert_rejected(&b, "out of bounds");

    let mut b = bytes();
    let nodes = entry(&b, section::NODE_POS);
    patch_entry(&mut b, section::EDGES, |e| e.offset = nodes.offset);
    assert_rejected(&b, "overlap");

    let mut b = bytes();
    patch_entry(&mut b, section::EDGES, |e| e.id = section::NODE_POS);
    assert_rejected(&b, "duplicate section");

    let mut b = bytes();
    patch_entry(&mut b, section::CURVATURE, |e| e.id = 999);
    assert_rejected(&b, "missing section 8");

    let mut b = bytes();
    patch_entry(&mut b, section::EDGES, |e| e.len -= 1);
    assert_rejected(&b, "not a whole array");
}

#[test]
fn rejects_inconsistent_arrays() {
    type Corrupt = Box<dyn Fn(&mut Vec<u8>)>;
    let cases: Vec<(&str, Corrupt)> = vec![
        (
            "forward CSR",
            Box::new(|b| patch(b, section::FWD_OFFSETS, 2, 0u32)),
        ),
        (
            "backward CSR",
            Box::new(|b| patch(b, section::BWD_OFFSETS, 4, 9u32)),
        ),
        (
            "geometry offsets",
            Box::new(|b| patch(b, section::GEOM_OFFSETS, 0, 1u32)),
        ),
        (
            "fewer than two points",
            Box::new(|b| {
                patch(b, section::GEOM_OFFSETS, 1, 1u32);
            }),
        ),
        (
            "inconsistent with node",
            Box::new(|b| {
                let mut e = fixture::region().edges[E_AB as usize];
                e.head = 77;
                patch(b, section::EDGES, E_AB as usize, e);
            }),
        ),
        (
            "inconsistent with node",
            Box::new(|b| {
                let mut e = fixture::region().edges[E_AB as usize];
                e.geometry = 3;
                patch(b, section::EDGES, E_AB as usize, e);
            }),
        ),
        (
            "zero speed",
            Box::new(|b| {
                let mut e = fixture::region().edges[E_BD as usize];
                e.speed_kmh = 0;
                patch(b, section::EDGES, E_BD as usize, e);
            }),
        ),
        (
            "does not join its nodes",
            Box::new(|b| {
                let mut e = fixture::region().edges[E_BA as usize];
                e.flags = 0;
                patch(b, section::EDGES, E_BA as usize, e);
            }),
        ),
        (
            "does not enter node",
            Box::new(|b| patch(b, section::BWD_EDGES, 0, E_BD)),
        ),
        (
            "does not enter node",
            Box::new(|b| patch(b, section::BWD_EDGES, 0, 1000u32)),
        ),
        (
            "coordinate out of range",
            Box::new(|b| {
                patch(
                    b,
                    section::NODE_POS,
                    0,
                    PointE7 {
                        lat: i32::MAX,
                        lon: 0,
                    },
                )
            }),
        ),
        (
            "grid refers to a missing edge",
            Box::new(|b| patch(b, section::GRID_EDGES, 0, 5u32)),
        ),
        (
            "grid offsets",
            Box::new(|b| patch(b, section::GRID_CELLS, 1, u32::MAX)),
        ),
        (
            "invalid grid dimensions",
            Box::new(|b| {
                let mut meta: GridMeta = bytemuck::pod_read_unaligned(
                    &b[entry(b, section::GRID_META).offset as usize..][..24],
                );
                meta.cell_lon = 0;
                patch(b, section::GRID_META, 0, meta);
            }),
        ),
        (
            "grid offsets",
            Box::new(|b| {
                let mut meta: GridMeta = bytemuck::pod_read_unaligned(
                    &b[entry(b, section::GRID_META).offset as usize..][..24],
                );
                meta.rows = u32::MAX;
                meta.cols = u32::MAX;
                patch(b, section::GRID_META, 0, meta);
            }),
        ),
        (
            "per-edge sections",
            Box::new(|b| patch_entry(b, section::WAY_REFS, |e| e.len -= 16)),
        ),
    ];
    for (why, corrupt) in cases {
        let mut b = bytes();
        corrupt(&mut b);
        assert_rejected(&b, why);
    }
}

#[test]
fn writer_rejects_bad_input() {
    let mut d = fixture::region();
    d.edges.swap(0, 2);
    assert!(d.to_bytes().is_err());

    let mut d = fixture::region();
    d.curvature.pop();
    assert!(d.to_bytes().is_err());

    let mut d = fixture::region();
    d.grid_cell = (0, 10);
    assert!(d.to_bytes().is_err());

    let mut d = fixture::region();
    d.edges[0].geometry = 9;
    assert!(d.to_bytes().is_err());
}

#[test]
fn writes_an_empty_region() {
    let d = RegionData {
        geometry_offsets: vec![0],
        grid_cell: (10_000, 10_000),
        ..RegionData::default()
    };
    let r = Region::from_bytes(&d.to_bytes().unwrap()).unwrap();
    assert_eq!(r.node_count(), 0);
    let engine = crate::Engine::from_region(r);
    assert!(matches!(
        engine.snap(LatLon {
            lat: 55.7,
            lon: 13.2
        }),
        Err(CoreError::NoRoadNearby { .. })
    ));
}

#[test]
fn truncates_long_header_strings_on_char_boundaries() {
    let mut d = fixture::region();
    d.info.source_name = "Skåne ".repeat(20);
    let r = Region::from_bytes(&d.to_bytes().unwrap()).unwrap();
    assert!(r.info().source_name.len() <= 64);
    assert!(d.info.source_name.starts_with(&r.info().source_name));
}

/// Random single-byte corruptions anywhere in the file must either be
/// rejected or leave a region whose queries still don't panic.
#[test]
fn corruption_never_panics() {
    let good = bytes();
    let mut state = 0x2545_f491_4f6c_dd1du64;
    let mut next = move || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    let interesting: Vec<usize> = {
        let (_, table) = parse_header(&good).unwrap();
        let mut v: Vec<usize> = (0..SECTION_TABLE_OFFSET + table.len() * 24).collect();
        for s in table {
            v.extend(s.offset as usize..(s.offset + s.len) as usize);
        }
        v
    };
    for _ in 0..3000 {
        let mut b = good.clone();
        for _ in 0..1 + next() % 3 {
            let at = interesting[(next() % interesting.len() as u64) as usize];
            b[at] = next() as u8;
        }
        if let Ok(r) = Region::from_bytes(&b) {
            let engine = crate::Engine::from_region(r);
            for (lat, lon) in [(55.7, 13.205), (55.705, 13.212), (55.0, 13.0)] {
                let _ = engine.snap(LatLon { lat, lon });
            }
        }
    }
}
