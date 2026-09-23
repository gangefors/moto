// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Serialises region content into the ADR-0005 file layout.
//!
//! Used by `moto-regionbuild` and by tests that round-trip small hand-made
//! fixtures. The writer derives the CSR offsets, the backward edge list and
//! the snapping grid itself, so callers only supply the logical content.

use bytemuck::Pod;

use super::format::*;
use super::grid;
use crate::CoreError;

/// Descriptive fields of the header.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct RegionInfo {
    /// Seconds since the Unix epoch; 0 if unknown.
    pub osm_timestamp: i64,
    pub bbox: BBoxE7,
    /// Truncated to 32 bytes.
    pub builder_version: String,
    /// Truncated to 64 bytes.
    pub source_name: String,
}

/// Everything that goes into a region file, as owned arrays.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct RegionData {
    pub info: RegionInfo,
    /// Routing node positions; node ids are indices.
    pub nodes: Vec<PointE7>,
    /// Directed edges, sorted by `tail`; edge ids are indices.
    pub edges: Vec<Edge>,
    /// Geometry count + 1 offsets into `shape_points`, starting at 0.
    pub geometry_offsets: Vec<u32>,
    /// Polylines with both endpoints, in the geometry's own direction.
    pub shape_points: Vec<PointE7>,
    /// One per edge.
    pub curvature: Vec<CurvatureMetrics>,
    /// One per edge.
    pub way_refs: Vec<WayRef>,
    /// Snapping grid cell size in 1e-7 degrees (lat, lon).
    pub grid_cell: (i32, i32),
}

impl RegionData {
    /// Serialises to the file layout. The result is checked by opening it
    /// with the reader, so a file this returns is one the app accepts.
    pub fn to_bytes(&self) -> Result<Vec<u8>, CoreError> {
        let bad = |msg: String| CoreError::Region(format!("cannot write region: {msg}"));
        let n = self.nodes.len();
        let m = self.edges.len();
        let n32 = u32::try_from(n).map_err(|_| bad("too many nodes".into()))?;
        let m32 = u32::try_from(m).map_err(|_| bad("too many edges".into()))?;
        u32::try_from(self.shape_points.len()).map_err(|_| bad("too many points".into()))?;
        if self.curvature.len() != m || self.way_refs.len() != m {
            return Err(bad("curvature and way refs need one entry per edge".into()));
        }
        if let Some(e) = self.edges.iter().find(|e| e.tail >= n32 || e.head >= n32) {
            return Err(bad(format!("edge {e:?} refers to a missing node")));
        }
        if self.edges.windows(2).any(|w| w[0].tail > w[1].tail) {
            return Err(bad("edges must be sorted by tail".into()));
        }

        let mut fwd = vec![0u32; n + 1];
        let mut bwd = vec![0u32; n + 1];
        for e in &self.edges {
            fwd[e.tail as usize + 1] += 1;
            bwd[e.head as usize + 1] += 1;
        }
        for v in 0..n {
            fwd[v + 1] += fwd[v];
            bwd[v + 1] += bwd[v];
        }
        let mut next = bwd.clone();
        let mut bwd_edges = vec![0u32; m];
        for (id, e) in (0..m32).zip(&self.edges) {
            let slot = &mut next[e.head as usize];
            bwd_edges[*slot as usize] = id;
            *slot += 1;
        }

        let geometries = self.geometry_offsets.len().saturating_sub(1);
        let valid_geometry = |g: u32| (g as usize) < geometries;
        if self.edges.iter().any(|e| !valid_geometry(e.geometry)) {
            return Err(bad("edge refers to a missing geometry".into()));
        }
        let (meta, cells, cell_edges) = grid::build(
            &self.edges,
            &self.geometry_offsets,
            &self.shape_points,
            self.grid_cell,
        )
        .map_err(bad)?;

        let sections: Vec<(u32, &[u8])> = vec![
            (section::NODE_POS, bytes_of(&self.nodes)),
            (section::FWD_OFFSETS, bytes_of(&fwd)),
            (section::BWD_OFFSETS, bytes_of(&bwd)),
            (section::BWD_EDGES, bytes_of(&bwd_edges)),
            (section::EDGES, bytes_of(&self.edges)),
            (section::GEOM_OFFSETS, bytes_of(&self.geometry_offsets)),
            (section::SHAPE_POINTS, bytes_of(&self.shape_points)),
            (section::CURVATURE, bytes_of(&self.curvature)),
            (section::GRID_META, bytemuck::bytes_of(&meta)),
            (section::GRID_CELLS, bytes_of(&cells)),
            (section::GRID_EDGES, bytes_of(&cell_edges)),
            (section::WAY_REFS, bytes_of(&self.way_refs)),
        ];
        let out = assemble(&self.info, &sections);
        super::Region::from_bytes(&out)?;
        Ok(out)
    }
}

fn bytes_of<T: Pod>(v: &[T]) -> &[u8] {
    bytemuck::cast_slice(v)
}

/// Lays out header, section table and page-aligned sections.
pub(crate) fn assemble(info: &RegionInfo, sections: &[(u32, &[u8])]) -> Vec<u8> {
    assert!(sections.len() <= MAX_SECTIONS, "too many sections");
    let mut out = vec![0u8; PAGE as usize];
    let mut entries = Vec::with_capacity(sections.len());
    for &(id, data) in sections {
        let offset = out.len() as u64;
        out.extend_from_slice(data);
        entries.push(SectionEntry {
            id,
            crc32: crc32fast::hash(data),
            offset,
            len: data.len() as u64,
        });
        out.resize(out.len().next_multiple_of(PAGE as usize), 0);
    }

    let mut header = Header {
        magic: MAGIC,
        version_major: VERSION_MAJOR,
        version_minor: VERSION_MINOR,
        section_count: sections.len() as u32,
        osm_timestamp: info.osm_timestamp,
        bbox: info.bbox,
        ..bytemuck::Zeroable::zeroed()
    };
    copy_str(&mut header.builder_version, &info.builder_version);
    copy_str(&mut header.source_name, &info.source_name);
    out[..SECTION_TABLE_OFFSET].copy_from_slice(bytemuck::bytes_of(&header));
    let table: &[u8] = bytemuck::cast_slice(&entries);
    out[SECTION_TABLE_OFFSET..SECTION_TABLE_OFFSET + table.len()].copy_from_slice(table);
    out
}

/// Copies as much of `s` as fits, cutting only at a char boundary.
fn copy_str(dst: &mut [u8], s: &str) {
    let mut end = s.len().min(dst.len());
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    dst[..end].copy_from_slice(&s.as_bytes()[..end]);
}
