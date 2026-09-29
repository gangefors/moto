// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Region files (ADR-0005): memory-mapped, zero-copy, validated on open.
//!
//! [`Region::open`] maps the file and checks its whole structure once
//! (header, section bounds, array lengths, monotonic offsets, index ranges),
//! so query code can index the typed slices without panicking. CRC32
//! checksums are verified separately by [`verify_file`], once, when a file
//! is installed. A file that passed once can be proven unchanged by its
//! [`fingerprint`] instead ([`Region::open_fingerprinted`]).

mod coverage;
pub(crate) use coverage::{contains as coverage_contains, merge as merge_coverage};
pub mod format;
mod grid;
pub mod install;
mod writer;

use std::fs::File;
use std::ops::Range;
use std::path::Path;

use bytemuck::Pod;
use memmap2::Mmap;

use crate::CoreError;
use format::*;
pub use writer::{RegionData, RegionInfo, RoadNames};

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
    /// Both or neither (older files have no coverage).
    coverage: Option<(Range<usize>, Range<usize>)>,
    /// All four or none (files before format 1.2 have no names).
    names: Option<NameSections>,
    /// Border table (format 1.3); none in older files and island regions.
    border: Option<Range<usize>>,
    /// What the region is (format 1.3).
    meta: Option<RegionMeta>,
}

/// Validated byte ranges of the name sections (format 1.2).
#[derive(Debug, Clone)]
struct NameSections {
    offsets: Range<usize>,
    bytes: Range<usize>,
    geometry_names: Range<usize>,
    places: Range<usize>,
}

fn err(msg: impl std::fmt::Display) -> CoreError {
    CoreError::Region(msg.to_string())
}

fn open_file(path: &Path) -> Result<File, CoreError> {
    File::open(path).map_err(|e| err(format_args!("cannot open {}: {e}", path.display())))
}

fn map_file(path: &Path) -> Result<Mmap, CoreError> {
    map_open(&open_file(path)?, path)
}

fn map_open(file: &File, path: &Path) -> Result<Mmap, CoreError> {
    // SAFETY: the map is read-only and the app never modifies an installed
    // region file; updates are written to a new file and swapped in.
    #[allow(unsafe_code)]
    let map = unsafe { Mmap::map(file) }
        .map_err(|e| err(format_args!("cannot map {}: {e}", path.display())))?;
    Ok(map)
}

/// Domain tag of [`fingerprint`]. Bump its version whenever [`validate`]
/// gains a check, so a file proven only by an older build's validation is
/// validated in full again.
const FINGERPRINT_TAG: &[u8] = b"moto-region-fingerprint-v3";
/// The file is hashed in this many parts at once.
const FINGERPRINT_PARTS: u64 = 4;
const FINGERPRINT_CHUNK: usize = 1 << 20;

/// SHA-256 fingerprint of a region file (ADR-0005): the file length and
/// the SHA-256 of each of four equal parts, hashed in parallel with plain
/// reads. Recorded once a file has passed full validation, so
/// [`Region::open_fingerprinted`] can prove a file unchanged instead of
/// validating it again.
pub fn fingerprint(path: impl AsRef<Path>) -> Result<[u8; 32], CoreError> {
    let path = path.as_ref();
    Ok(fingerprint_open(&open_file(path)?, path)?.0)
}

/// The fingerprint of an open file, and the length it covers.
fn fingerprint_open(file: &File, path: &Path) -> Result<([u8; 32], u64), CoreError> {
    use sha2::{Digest, Sha256};
    let read_err = |e: std::io::Error| err(format_args!("cannot read {}: {e}", path.display()));
    let len = file.metadata().map_err(read_err)?.len();
    let part = len.div_ceil(FINGERPRINT_PARTS);
    let hash_part = |i: u64| -> Result<[u8; 32], CoreError> {
        let start = (i * part).min(len);
        let end = start.saturating_add(part).min(len);
        let mut hasher = Sha256::new();
        let mut buf = vec![0u8; FINGERPRINT_CHUNK];
        let mut at = start;
        while at < end {
            let n = usize::try_from(end - at)
                .unwrap_or(usize::MAX)
                .min(FINGERPRINT_CHUNK);
            read_exact_at(file, &mut buf[..n], at).map_err(read_err)?;
            hasher.update(&buf[..n]);
            at += n as u64;
        }
        Ok(hasher.finalize().into())
    };
    let parts: Vec<Result<[u8; 32], CoreError>> = std::thread::scope(|s| {
        let handles: Vec<_> = (0..FINGERPRINT_PARTS)
            .map(|i| s.spawn(move || hash_part(i)))
            .collect();
        handles
            .into_iter()
            .map(|h| {
                h.join()
                    .unwrap_or_else(|_| Err(err("fingerprint thread failed")))
            })
            .collect()
    });
    let mut hasher = Sha256::new();
    hasher.update(FINGERPRINT_TAG);
    hasher.update(len.to_le_bytes());
    for p in parts {
        hasher.update(p?);
    }
    Ok((hasher.finalize().into(), len))
}

/// Reads exactly `buf.len()` bytes at `offset`; safe to call from several
/// threads on one file.
#[cfg(unix)]
fn read_exact_at(file: &File, buf: &mut [u8], offset: u64) -> std::io::Result<()> {
    std::os::unix::fs::FileExt::read_exact_at(file, buf, offset)
}

#[cfg(windows)]
fn read_exact_at(file: &File, mut buf: &mut [u8], mut offset: u64) -> std::io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        match file.seek_read(buf, offset) {
            Ok(0) => return Err(std::io::ErrorKind::UnexpectedEof.into()),
            Ok(n) => {
                buf = &mut buf[n..];
                offset += n as u64;
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

impl Region {
    /// Memory-maps and validates a region file.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, CoreError> {
        let map = map_file(path.as_ref())?;
        Self::new(Backing::Mmap(map))
    }

    /// Opens a region file that passed full validation before, when its
    /// [`fingerprint`] was recorded: the file is read once with plain
    /// reads (far faster on a phone than faulting in every page of the
    /// map) and must match `expected`; then only the header and section
    /// table are checked, since the bytes are the ones that were
    /// validated. A mismatch is an error, and the caller falls back to
    /// [`Region::open`].
    pub fn open_fingerprinted(
        path: impl AsRef<Path>,
        expected: &[u8; 32],
    ) -> Result<Self, CoreError> {
        let path = path.as_ref();
        let file = open_file(path)?;
        let (actual, len) = fingerprint_open(&file, path)?;
        let map = map_open(&file, path)?;
        if actual != *expected || map.len() as u64 != len {
            return Err(err("region file does not match its fingerprint"));
        }
        let bytes = Backing::Mmap(map);
        let (info, sections) = validate_marked(bytes.as_bytes(), Checks::Located, &mut |_| {})?;
        Ok(Self {
            bytes,
            info,
            sections,
        })
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

    /// Where the region has roads: closed rings, counter-clockwise; empty
    /// for files older than format 1.1.
    pub fn coverage(&self) -> Vec<&[PointE7]> {
        let Some((o, p)) = &self.sections.coverage else {
            return Vec::new();
        };
        let (offsets, points): (&[u32], &[PointE7]) = (self.slice(o), self.slice(p));
        offsets
            .windows(2)
            .map(|w| &points[w[0] as usize..w[1] as usize])
            .collect()
    }

    /// Whether `p` lies in the region's coverage; `None` for files without
    /// one (format 1.0).
    pub fn covers(&self, p: PointE7) -> Option<bool> {
        let (o, pts) = self.sections.coverage.as_ref()?;
        Some(coverage::contains(self.slice(o), self.slice(pts), p))
    }

    /// String `id` of the names; `None` for [`NO_NAME`], an index out of
    /// range or a file without names. Never panics, even on a file only
    /// located (fingerprinted), not validated in full.
    pub fn string(&self, id: u32) -> Option<&str> {
        let n = self.sections.names.as_ref()?;
        let offsets: &[u32] = self.slice(&n.offsets);
        let (a, b) = (
            *offsets.get(id as usize)? as usize,
            *offsets.get(id as usize + 1)? as usize,
        );
        let bytes = self.bytes.as_bytes().get(n.bytes.clone())?;
        std::str::from_utf8(bytes.get(a..b)?).ok()
    }

    /// The number and name of the road geometry `geometry` belongs to;
    /// [`GeometryName::NONE`] when it has none or the file has no names.
    pub fn geometry_name(&self, geometry: u32) -> GeometryName {
        self.sections
            .names
            .as_ref()
            .and_then(|n| {
                self.slice::<GeometryName>(&n.geometry_names)
                    .get(geometry as usize)
                    .copied()
            })
            .unwrap_or(GeometryName::NONE)
    }

    /// Named places, sorted by latitude; empty for files without names.
    pub fn places(&self) -> &[Place] {
        match &self.sections.names {
            Some(n) => self.slice(&n.places),
            None => &[],
        }
    }

    /// The routing nodes at the region's border, sorted by OSM id
    /// (ADR-0009); empty for older files and regions without neighbours.
    pub fn border_nodes(&self) -> &[BorderNode] {
        match &self.sections.border {
            Some(r) => self.slice(r),
            None => &[],
        }
    }

    /// What the file says the region is (format 1.3).
    pub fn meta(&self) -> Option<RegionMeta> {
        self.sections.meta
    }

    /// The region's country, ISO 3166-1 alpha-2 (e.g. `SE`), if the file
    /// says (format 1.3).
    pub fn country(&self) -> Option<&str> {
        let meta = self.sections.meta.as_ref()?;
        std::str::from_utf8(&meta.country).ok()
    }

    /// Whether the file has road names and places (format 1.2).
    pub fn has_names(&self) -> bool {
        self.sections.names.is_some()
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

/// Opens and validates a region file like [`Region::open`], timing each
/// step (in milliseconds): mapping the file, then each part of the
/// validation. To find what makes opening slow on a device.
pub fn profile_open(path: impl AsRef<Path>) -> Result<Vec<(String, f64)>, CoreError> {
    let mut steps = Vec::new();
    let mut last = std::time::Instant::now();
    let map = map_file(path.as_ref())?;
    let mut mark = |name: &'static str| {
        let now = std::time::Instant::now();
        steps.push((
            name.to_owned(),
            now.duration_since(last).as_secs_f64() * 1000.0,
        ));
        last = now;
    };
    mark("map the file");
    validate_marked(&map, Checks::Full, &mut mark)?;
    Ok(steps)
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
    let (entries, _) =
        bytes[SECTION_TABLE_OFFSET..table_end].as_chunks::<{ size_of::<SectionEntry>() }>();
    let table = entries
        .iter()
        .map(|e| bytemuck::pod_read_unaligned(e))
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

/// How much of a file [`validate_marked`] checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Checks {
    /// Everything the accessors rely on.
    Full,
    /// Only what locates the sections (header, section table, whole
    /// arrays, grid record, coverage both or neither), for a file proven
    /// by its fingerprint to be one that passed [`Checks::Full`].
    Located,
}

/// Full structural validation; everything the accessors rely on.
fn validate(bytes: &[u8]) -> Result<(RegionInfo, Sections), CoreError> {
    validate_marked(bytes, Checks::Full, &mut |_| {})
}

/// [`validate`] to the extent `checks` asks, calling `mark` after each
/// part (for [`profile_open`]).
fn validate_marked(
    bytes: &[u8],
    checks: Checks,
    mark: &mut dyn FnMut(&'static str),
) -> Result<(RegionInfo, Sections), CoreError> {
    let full = checks == Checks::Full;
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
        coverage: None,
        names: None,
        border: None,
        meta: None,
    };
    mark("header and section table");
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

    mark("typed sections");
    let n = nodes.len();
    let m = edges.len();
    if n >= u32::MAX as usize || m >= u32::MAX as usize || shape.len() >= u32::MAX as usize {
        return Err(err("arrays too large for 32-bit ids"));
    }
    if full {
        check_contents(
            nodes, fwd, bwd, bwd_edges, edges, geom, shape, curvature, way_refs, mark,
        )?;
    }

    let [meta] = meta else {
        return Err(err("grid meta must be exactly one record"));
    };
    if full {
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
        mark("snapping grid");
    }

    let coverage = match (
        find(section::COVERAGE_OFFSETS).ok(),
        find(section::COVERAGE_POINTS).ok(),
    ) {
        (None, None) => None,
        (Some(o), Some(p)) => {
            let offsets: &[u32] = typed(bytes, &o, section::COVERAGE_OFFSETS)?;
            let points: &[PointE7] = typed(bytes, &p, section::COVERAGE_POINTS)?;
            if full {
                let rings = offsets
                    .len()
                    .checked_sub(1)
                    .ok_or_else(|| err("empty coverage offsets"))?;
                check_offsets("coverage", offsets, rings, points.len())?;
                if !points.iter().all(in_range) {
                    return Err(err("coverage coordinate out of range"));
                }
                let closed = |w: &[u32]| {
                    let ring = &points[w[0] as usize..w[1] as usize];
                    ring.len() >= 4 && ring.first() == ring.last()
                };
                if !offsets.windows(2).all(closed) {
                    return Err(err("coverage ring not closed or too short"));
                }
            }
            Some((o, p))
        }
        _ => return Err(err("coverage needs both its sections")),
    };

    mark("coverage");
    let names = match [
        section::NAME_OFFSETS,
        section::NAME_BYTES,
        section::GEOMETRY_NAMES,
        section::PLACES,
    ]
    .map(|id| find(id).ok())
    {
        [None, None, None, None] => None,
        [Some(o), Some(b), Some(g), Some(p)] => {
            let offsets: &[u32] = typed(bytes, &o, section::NAME_OFFSETS)?;
            let geometry_names: &[GeometryName] = typed(bytes, &g, section::GEOMETRY_NAMES)?;
            let places: &[Place] = typed(bytes, &p, section::PLACES)?;
            if full {
                check_names(
                    offsets,
                    &bytes[b.clone()],
                    geometry_names,
                    geom.len(),
                    places,
                )?;
                mark("names");
            }
            Some(NameSections {
                offsets: o,
                bytes: b,
                geometry_names: g,
                places: p,
            })
        }
        _ => return Err(err("names need all four of their sections")),
    };
    let border = match find(section::BORDER_NODES).ok() {
        None => None,
        Some(r) => {
            let list: &[BorderNode] = typed(bytes, &r, section::BORDER_NODES)?;
            if full {
                writer::check_border(list, n).map_err(err)?;
            } else if list.len() > MAX_BORDER_NODES {
                return Err(err("too many border nodes"));
            }
            Some(r)
        }
    };
    let region_meta = match find(section::REGION_META).ok() {
        None => None,
        Some(r) => {
            let [m] = typed::<RegionMeta>(bytes, &r, section::REGION_META)? else {
                return Err(err("region meta must be exactly one record"));
            };
            writer::check_meta(m).map_err(err)?;
            Some(*m)
        }
    };
    mark("border");
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
            coverage,
            names,
            border,
            meta: region_meta,
            ..s
        },
    ))
}

fn in_range(p: &PointE7) -> bool {
    (-900_000_000..=900_000_000).contains(&p.lat)
        && (-1_800_000_000..=1_800_000_000).contains(&p.lon)
}

/// The expensive part of [`Checks::Full`]: coordinates, the graph and
/// geometry indexes, and that every edge joins its nodes.
#[allow(clippy::too_many_arguments)]
fn check_contents(
    nodes: &[PointE7],
    fwd: &[u32],
    bwd: &[u32],
    bwd_edges: &[u32],
    edges: &[Edge],
    geom: &[u32],
    shape: &[PointE7],
    curvature: &[CurvatureMetrics],
    way_refs: &[WayRef],
    mark: &mut dyn FnMut(&'static str),
) -> Result<(), CoreError> {
    let n = nodes.len();
    let m = edges.len();
    if !nodes.iter().all(in_range) || !shape.iter().all(in_range) {
        return Err(err("coordinate out of range"));
    }

    mark("coordinates in range");
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

    mark("graph and geometry offsets");
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

    mark("edges join their nodes");
    Ok(())
}

/// The names (format 1.2): every string valid UTF-8, one name per
/// geometry (`geometry_offsets` has one more entry than there are),
/// every index a string or [`NO_NAME`], places in range, of a known kind,
/// sorted by latitude.
fn check_names(
    offsets: &[u32],
    bytes: &[u8],
    geometry_names: &[GeometryName],
    geometry_offsets: usize,
    places: &[Place],
) -> Result<(), CoreError> {
    let count = offsets
        .len()
        .checked_sub(1)
        .ok_or_else(|| err("empty name offsets"))?;
    check_offsets("name", offsets, count, bytes.len())?;
    if offsets
        .windows(2)
        .any(|w| std::str::from_utf8(&bytes[w[0] as usize..w[1] as usize]).is_err())
    {
        return Err(err("name is not valid UTF-8"));
    }
    if geometry_names.len() + 1 != geometry_offsets {
        return Err(err("road names need one entry per geometry"));
    }
    let known = |i: u32| i == NO_NAME || (i as usize) < count;
    if !geometry_names
        .iter()
        .all(|g| known(g.road_ref) && known(g.name))
    {
        return Err(err("road name refers to a missing string"));
    }
    let place_ok = |p: &Place| {
        in_range(&p.pos) && (p.name as usize) < count && PlaceKind::from_u8(p.kind).is_some()
    };
    if !places.iter().all(place_ok) {
        return Err(err("invalid place"));
    }
    if places.windows(2).any(|w| w[0].pos.lat > w[1].pos.lat) {
        return Err(err("places not sorted by latitude"));
    }
    Ok(())
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
