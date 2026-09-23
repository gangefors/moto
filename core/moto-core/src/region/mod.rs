// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Region files (ADR-0005): memory-mapped, zero-copy, validated on open.
//!
//! [`Region::open`] maps the file and checks its whole structure once
//! (header, section bounds, array lengths, monotonic offsets, index ranges),
//! so query code can index the typed slices without panicking. CRC32
//! checksums are verified separately by [`verify_file`], once, when a file
//! is installed.

pub mod format;
mod grid;
mod writer;

use std::fs::File;
use std::ops::Range;
use std::path::Path;

use bytemuck::Pod;
use memmap2::Mmap;

use crate::CoreError;
use format::*;
pub use writer::{RegionData, RegionInfo};

#[cfg(target_endian = "big")]
compile_error!("region files are little-endian and read zero-copy");

/// An open, validated region file.
#[derive(Debug)]
pub struct Region {
    bytes: Backing,
    info: RegionInfo,
    sections: Sections,
}

#[derive(Debug)]
enum Backing {
    Mmap(Mmap),
    /// `u64` storage keeps the copy 8-byte aligned for `bytemuck`.
    Owned(Vec<u64>, usize),
}

impl Backing {
    fn as_bytes(&self) -> &[u8] {
        match self {
            Backing::Mmap(m) => m,
            Backing::Owned(v, len) => &bytemuck::cast_slice(v)[..*len],
        }
    }
}

/// Validated byte ranges of the known sections.
#[derive(Debug, Clone)]
struct Sections {
    node_pos: Range<usize>,
    fwd_offsets: Range<usize>,
    bwd_offsets: Range<usize>,
    bwd_edges: Range<usize>,
    edges: Range<usize>,
    geom_offsets: Range<usize>,
    shape_points: Range<usize>,
    curvature: Range<usize>,
    grid_meta: GridMeta,
    grid_cells: Range<usize>,
    grid_edges: Range<usize>,
    way_refs: Range<usize>,
}

fn err(msg: impl std::fmt::Display) -> CoreError {
    CoreError::Region(msg.to_string())
}

fn map_file(path: &Path) -> Result<Mmap, CoreError> {
    let file =
        File::open(path).map_err(|e| err(format_args!("cannot open {}: {e}", path.display())))?;
    // SAFETY: the map is read-only and the app never modifies an installed
    // region file; updates are written to a new file and swapped in.
    #[allow(unsafe_code)]
    let map = unsafe { Mmap::map(&file) }
        .map_err(|e| err(format_args!("cannot map {}: {e}", path.display())))?;
    Ok(map)
}

impl Region {
    /// Memory-maps and validates a region file.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, CoreError> {
        let map = map_file(path.as_ref())?;
        Self::new(Backing::Mmap(map))
    }

    /// Validates a region file held in memory (copied to aligned storage).
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, CoreError> {
        let mut words = vec![0u64; bytes.len().div_ceil(8)];
        bytemuck::cast_slice_mut::<u64, u8>(&mut words)[..bytes.len()].copy_from_slice(bytes);
        Self::new(Backing::Owned(words, bytes.len()))
    }

    fn new(bytes: Backing) -> Result<Self, CoreError> {
        let (info, sections) = validate(bytes.as_bytes())?;
        Ok(Self {
            bytes,
            info,
            sections,
        })
    }

    /// Checks every section's CRC32 against the section table.
    pub fn verify_checksums(&self) -> Result<(), CoreError> {
        verify_crcs(self.bytes.as_bytes())
    }

    pub fn info(&self) -> &RegionInfo {
        &self.info
    }

    fn slice<T: Pod>(&self, r: &Range<usize>) -> &[T] {
        bytemuck::cast_slice(&self.bytes.as_bytes()[r.clone()])
    }

    /// Routing node positions, indexed by node id.
    pub fn nodes(&self) -> &[PointE7] {
        self.slice(&self.sections.node_pos)
    }
    /// Directed edges sorted by tail, indexed by edge id.
    pub fn edges(&self) -> &[Edge] {
        self.slice(&self.sections.edges)
    }
    /// Node count + 1 offsets into [`Self::edges`].
    pub fn fwd_offsets(&self) -> &[u32] {
        self.slice(&self.sections.fwd_offsets)
    }
    /// Node count + 1 offsets into [`Self::bwd_edges`].
    pub fn bwd_offsets(&self) -> &[u32] {
        self.slice(&self.sections.bwd_offsets)
    }
    /// Edge ids sorted by head.
    pub fn bwd_edges(&self) -> &[u32] {
        self.slice(&self.sections.bwd_edges)
    }
    pub fn geometry_offsets(&self) -> &[u32] {
        self.slice(&self.sections.geom_offsets)
    }
    pub fn shape_points(&self) -> &[PointE7] {
        self.slice(&self.sections.shape_points)
    }
    /// Curvature metrics, indexed by edge id.
    pub fn curvature(&self) -> &[CurvatureMetrics] {
        self.slice(&self.sections.curvature)
    }
    /// OSM way references, indexed by edge id.
    pub fn way_refs(&self) -> &[WayRef] {
        self.slice(&self.sections.way_refs)
    }
    pub fn grid_meta(&self) -> &GridMeta {
        &self.sections.grid_meta
    }
    /// Cell count + 1 offsets into [`Self::grid_edges`], row-major.
    pub fn grid_cells(&self) -> &[u32] {
        self.slice(&self.sections.grid_cells)
    }
    pub fn grid_edges(&self) -> &[u32] {
        self.slice(&self.sections.grid_edges)
    }

    pub fn node_count(&self) -> usize {
        self.nodes().len()
    }
    pub fn edge_count(&self) -> usize {
        self.edges().len()
    }

    /// Edge ids leaving `node` (empty for an unknown node).
    pub fn out_edges(&self, node: u32) -> Range<u32> {
        let o = self.fwd_offsets();
        match (o.get(node as usize), o.get(node as usize + 1)) {
            (Some(&a), Some(&b)) => a..b,
            _ => 0..0,
        }
    }

    /// Ids of the edges entering `node` (empty for an unknown node).
    pub fn in_edges(&self, node: u32) -> &[u32] {
        let o = self.bwd_offsets();
        match (o.get(node as usize), o.get(node as usize + 1)) {
            (Some(&a), Some(&b)) => &self.bwd_edges()[a as usize..b as usize],
            _ => &[],
        }
    }

    /// Shape points of a geometry, endpoints included.
    pub fn geometry(&self, geometry: u32) -> &[PointE7] {
        let o = self.geometry_offsets();
        match (o.get(geometry as usize), o.get(geometry as usize + 1)) {
            (Some(&a), Some(&b)) => &self.shape_points()[a as usize..b as usize],
            _ => &[],
        }
    }

    /// Edge ids listed in grid cell (`row`, `col`).
    pub fn grid_cell(&self, row: u32, col: u32) -> &[u32] {
        let meta = self.grid_meta();
        if row >= meta.rows || col >= meta.cols {
            return &[];
        }
        let cell = row as usize * meta.cols as usize + col as usize;
        let o = self.grid_cells();
        &self.grid_edges()[o[cell] as usize..o[cell + 1] as usize]
    }
}

/// Verifies a region file before it is installed: CRC32 of every section,
/// then the same structural checks as [`Region::open`].
pub fn verify_file(path: impl AsRef<Path>) -> Result<(), CoreError> {
    let map = map_file(path.as_ref())?;
    verify_crcs(&map)?;
    validate(&map).map(|_| ())
}

fn verify_crcs(bytes: &[u8]) -> Result<(), CoreError> {
    let (_, table) = parse_header(bytes)?;
    for s in &table {
        let r = section_range(bytes, s)?;
        let crc = crc32fast::hash(&bytes[r]);
        if crc != s.crc32 {
            return Err(err(format_args!(
                "checksum mismatch in section {} (expected {:08x}, got {crc:08x})",
                s.id, s.crc32
            )));
        }
    }
    Ok(())
}

fn parse_header(bytes: &[u8]) -> Result<(Header, Vec<SectionEntry>), CoreError> {
    if bytes.len() < PAGE as usize {
        return Err(err("file too short for a region header"));
    }
    let header: Header = bytemuck::pod_read_unaligned(&bytes[..SECTION_TABLE_OFFSET]);
    if header.magic != MAGIC {
        return Err(err("not a region file (bad magic)"));
    }
    if header.version_major != VERSION_MAJOR {
        return Err(err(format_args!(
            "unsupported region format {}.{} (this app reads {VERSION_MAJOR}.x); \
             download a new region file",
            header.version_major, header.version_minor
        )));
    }
    let count = header.section_count as usize;
    if count > MAX_SECTIONS {
        return Err(err(format_args!("section table too large ({count})")));
    }
    let table_end = SECTION_TABLE_OFFSET + count * size_of::<SectionEntry>();
    let table = bytes[SECTION_TABLE_OFFSET..table_end]
        .chunks_exact(size_of::<SectionEntry>())
        .map(bytemuck::pod_read_unaligned)
        .collect();
    Ok((header, table))
}

fn section_range(bytes: &[u8], s: &SectionEntry) -> Result<Range<usize>, CoreError> {
    let end = s.offset.checked_add(s.len);
    match end {
        Some(end)
            if s.offset >= PAGE && s.offset.is_multiple_of(PAGE) && end <= bytes.len() as u64 =>
        {
            Ok(s.offset as usize..end as usize)
        }
        _ => Err(err(format_args!(
            "section {} out of bounds or misaligned (offset {}, len {}, file {})",
            s.id,
            s.offset,
            s.len,
            bytes.len()
        ))),
    }
}

fn str_field(b: &[u8]) -> String {
    let end = b.iter().position(|&c| c == 0).unwrap_or(b.len());
    String::from_utf8_lossy(&b[..end]).into_owned()
}

/// Full structural validation; everything the accessors rely on.
fn validate(bytes: &[u8]) -> Result<(RegionInfo, Sections), CoreError> {
    let (header, table) = parse_header(bytes)?;

    let mut ranges: Vec<(u32, Range<usize>)> = Vec::with_capacity(table.len());
    for s in &table {
        let r = section_range(bytes, s)?;
        if ranges.iter().any(|(id, _)| *id == s.id) {
            return Err(err(format_args!("duplicate section {}", s.id)));
        }
        ranges.push((s.id, r));
    }
    let mut sorted: Vec<&Range<usize>> = ranges.iter().map(|(_, r)| r).collect();
    sorted.sort_by_key(|r| r.start);
    if sorted.windows(2).any(|w| w[0].end > w[1].start) {
        return Err(err("sections overlap"));
    }

    let find = |id: u32| -> Result<Range<usize>, CoreError> {
        ranges
            .iter()
            .find(|(i, _)| *i == id)
            .map(|(_, r)| r.clone())
            .ok_or_else(|| err(format_args!("missing section {id}")))
    };
    fn typed<'a, T: Pod>(bytes: &'a [u8], r: &Range<usize>, id: u32) -> Result<&'a [T], CoreError> {
        bytemuck::try_cast_slice(&bytes[r.clone()])
            .map_err(|e| err(format_args!("section {id} is not a whole array: {e:?}")))
    }

    let s = Sections {
        node_pos: find(section::NODE_POS)?,
        fwd_offsets: find(section::FWD_OFFSETS)?,
        bwd_offsets: find(section::BWD_OFFSETS)?,
        bwd_edges: find(section::BWD_EDGES)?,
        edges: find(section::EDGES)?,
        geom_offsets: find(section::GEOM_OFFSETS)?,
        shape_points: find(section::SHAPE_POINTS)?,
        curvature: find(section::CURVATURE)?,
        grid_meta: GridMeta::default(),
        grid_cells: find(section::GRID_CELLS)?,
        grid_edges: find(section::GRID_EDGES)?,
        way_refs: find(section::WAY_REFS)?,
    };
    let nodes: &[PointE7] = typed(bytes, &s.node_pos, section::NODE_POS)?;
    let fwd: &[u32] = typed(bytes, &s.fwd_offsets, section::FWD_OFFSETS)?;
    let bwd: &[u32] = typed(bytes, &s.bwd_offsets, section::BWD_OFFSETS)?;
    let bwd_edges: &[u32] = typed(bytes, &s.bwd_edges, section::BWD_EDGES)?;
    let edges: &[Edge] = typed(bytes, &s.edges, section::EDGES)?;
    let geom: &[u32] = typed(bytes, &s.geom_offsets, section::GEOM_OFFSETS)?;
    let shape: &[PointE7] = typed(bytes, &s.shape_points, section::SHAPE_POINTS)?;
    let curvature: &[CurvatureMetrics] = typed(bytes, &s.curvature, section::CURVATURE)?;
    let meta: &[GridMeta] = typed(bytes, &find(section::GRID_META)?, section::GRID_META)?;
    let cells: &[u32] = typed(bytes, &s.grid_cells, section::GRID_CELLS)?;
    let cell_edges: &[u32] = typed(bytes, &s.grid_edges, section::GRID_EDGES)?;
    let way_refs: &[WayRef] = typed(bytes, &s.way_refs, section::WAY_REFS)?;

    let n = nodes.len();
    let m = edges.len();
    if n >= u32::MAX as usize || m >= u32::MAX as usize || shape.len() >= u32::MAX as usize {
        return Err(err("arrays too large for 32-bit ids"));
    }
    let in_range = |p: &PointE7| {
        (-900_000_000..=900_000_000).contains(&p.lat)
            && (-1_800_000_000..=1_800_000_000).contains(&p.lon)
    };
    if !nodes.iter().all(in_range) || !shape.iter().all(in_range) {
        return Err(err("coordinate out of range"));
    }

    check_offsets("forward CSR", fwd, n, m)?;
    check_offsets("backward CSR", bwd, n, bwd_edges.len())?;
    if bwd_edges.len() != m || curvature.len() != m || way_refs.len() != m {
        return Err(err("per-edge sections disagree on the edge count"));
    }
    let g = geom
        .len()
        .checked_sub(1)
        .ok_or_else(|| err("empty geometry offsets"))?;
    check_offsets("geometry", geom, g, shape.len())?;
    if geom.windows(2).any(|w| w[1] - w[0] < 2) {
        return Err(err("geometry with fewer than two points"));
    }

    for v in 0..n {
        for e in &edges[fwd[v] as usize..fwd[v + 1] as usize] {
            if e.tail as usize != v || e.head as usize >= n || e.geometry as usize >= g {
                return Err(err(format_args!("edge {e:?} inconsistent with node {v}")));
            }
            if e.speed_kmh == 0 {
                return Err(err("edge with zero speed"));
            }
            let line =
                &shape[geom[e.geometry as usize] as usize..geom[e.geometry as usize + 1] as usize];
            let (first, last) = (line[0], line[line.len() - 1]);
            let (from, to) = if e.flags & edge_flags::REVERSED == 0 {
                (first, last)
            } else {
                (last, first)
            };
            if from != nodes[v] || to != nodes[e.head as usize] {
                return Err(err("edge geometry does not join its nodes"));
            }
        }
        for &id in &bwd_edges[bwd[v] as usize..bwd[v + 1] as usize] {
            if edges.get(id as usize).is_none_or(|e| e.head as usize != v) {
                return Err(err(format_args!(
                    "backward edge {id} does not enter node {v}"
                )));
            }
        }
    }

    let [meta] = meta else {
        return Err(err("grid meta must be exactly one record"));
    };
    if meta.cell_lat <= 0 || meta.cell_lon <= 0 || meta.rows == 0 || meta.cols == 0 {
        return Err(err("invalid grid dimensions"));
    }
    let cell_count = (meta.rows as usize)
        .checked_mul(meta.cols as usize)
        .ok_or_else(|| err("grid too large"))?;
    check_offsets("grid", cells, cell_count, cell_edges.len())?;
    if cell_edges.iter().any(|&e| e as usize >= m) {
        return Err(err("grid refers to a missing edge"));
    }

    let info = RegionInfo {
        osm_timestamp: header.osm_timestamp,
        bbox: header.bbox,
        builder_version: str_field(&header.builder_version),
        source_name: str_field(&header.source_name),
    };
    Ok((
        info,
        Sections {
            grid_meta: *meta,
            ..s
        },
    ))
}

/// CSR offsets: `count + 1` entries from 0 to `total`, never decreasing.
fn check_offsets(what: &str, o: &[u32], count: usize, total: usize) -> Result<(), CoreError> {
    let ok = o.len() == count + 1
        && o.first() == Some(&0)
        && o.last().map(|&l| l as usize) == Some(total)
        && o.windows(2).all(|w| w[0] <= w[1]);
    if ok {
        Ok(())
    } else {
        Err(err(format_args!("{what} offsets are not a valid index")))
    }
}

#[cfg(test)]
mod tests;
