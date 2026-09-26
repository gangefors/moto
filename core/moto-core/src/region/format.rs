// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! On-disk layout of a region file (ADR-0005).
//!
//! All integers are little-endian. The first 4 KiB page holds the
//! [`Header`] followed by the section table ([`SectionEntry`]); every
//! section starts on a 4 KiB boundary and is an array of one of the
//! `#[repr(C)]` records below, so it can be viewed as a typed slice straight
//! from the memory map.
//!
//! Graph layout: nodes are routing nodes (intersections and dead ends) in
//! Hilbert order. Edges are directed and sorted by tail, so the forward CSR
//! offsets index the edge array directly (`edges[fwd[v]..fwd[v + 1]]` leave
//! node `v`); the backward CSR offsets index a separate list of edge ids
//! sorted by head. Both directions of a two-way road share one geometry;
//! the reverse edge carries [`edge_flags::REVERSED`].

use bytemuck::{Pod, Zeroable};

/// File magic, the first 8 bytes of every region file.
pub const MAGIC: [u8; 8] = *b"MOTOREG\0";
/// Major format version. Files with another major version are refused.
pub const VERSION_MAJOR: u16 = 1;
/// Minor format version. Minor bumps only add optional sections.
pub const VERSION_MINOR: u16 = 0;

/// Sections start on multiples of this.
pub const PAGE: u64 = 4096;
/// Byte offset of the section table (right after the header).
pub const SECTION_TABLE_OFFSET: usize = size_of::<Header>();
/// How many section entries fit in the first page.
pub const MAX_SECTIONS: usize = (PAGE as usize - SECTION_TABLE_OFFSET) / size_of::<SectionEntry>();

/// Fixed-point scale of coordinates: 1e-7 degrees, as OSM stores them.
pub const COORD_SCALE: f64 = 1e7;

/// Section ids. Readers ignore ids they don't know (minor versions).
pub mod section {
    /// `[PointE7]`, one per node.
    pub const NODE_POS: u32 = 1;
    /// `[u32]`, node count + 1: forward CSR offsets into `EDGES`.
    pub const FWD_OFFSETS: u32 = 2;
    /// `[u32]`, node count + 1: backward CSR offsets into `BWD_EDGES`.
    pub const BWD_OFFSETS: u32 = 3;
    /// `[u32]`, edge count: edge ids sorted by head.
    pub const BWD_EDGES: u32 = 4;
    /// `[Edge]`, sorted by tail.
    pub const EDGES: u32 = 5;
    /// `[u32]`, geometry count + 1: offsets into `SHAPE_POINTS`.
    pub const GEOM_OFFSETS: u32 = 6;
    /// `[PointE7]`: polylines, endpoints included.
    pub const SHAPE_POINTS: u32 = 7;
    /// `[CurvatureMetrics]`, one per edge.
    pub const CURVATURE: u32 = 8;
    /// `[GridMeta]`, exactly one.
    pub const GRID_META: u32 = 9;
    /// `[u32]`, cell count + 1: offsets into `GRID_EDGES`, row-major.
    pub const GRID_CELLS: u32 = 10;
    /// `[u32]`: edge ids per grid cell.
    pub const GRID_EDGES: u32 = 11;
    /// `[WayRef]`, one per edge.
    pub const WAY_REFS: u32 = 12;
    /// Reserved for ALT landmark distances (ADR-0005, decided in M2).
    pub const LANDMARKS: u32 = 100;
}

/// The file header, at offset 0. 256 bytes; the section table follows.
#[repr(C)]
#[derive(Debug, Clone, Copy, Pod, Zeroable)]
pub struct Header {
    pub magic: [u8; 8],
    pub version_major: u16,
    pub version_minor: u16,
    pub section_count: u32,
    /// Timestamp of the OSM data, seconds since the Unix epoch (0 if unknown).
    pub osm_timestamp: i64,
    pub bbox: BBoxE7,
    /// NUL-padded UTF-8, e.g. `moto-regionbuild 0.1.0`.
    pub builder_version: [u8; 32],
    /// NUL-padded UTF-8, e.g. `sweden-latest.osm.pbf (Skåne bbox)`.
    pub source_name: [u8; 64],
    pub reserved: [u8; 120],
}

/// One entry of the section table.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Pod, Zeroable)]
pub struct SectionEntry {
    pub id: u32,
    /// CRC32 (IEEE) of the section's `len` bytes.
    pub crc32: u32,
    /// Byte offset from the start of the file; a multiple of [`PAGE`].
    pub offset: u64,
    /// Length in bytes, without padding.
    pub len: u64,
}

/// A coordinate in 1e-7 degrees.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Pod, Zeroable)]
pub struct PointE7 {
    pub lat: i32,
    pub lon: i32,
}

/// A bounding box in 1e-7 degrees, inclusive.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Pod, Zeroable)]
pub struct BBoxE7 {
    pub min_lat: i32,
    pub min_lon: i32,
    pub max_lat: i32,
    pub max_lon: i32,
}

/// A directed edge between two routing nodes.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Pod, Zeroable)]
pub struct Edge {
    pub tail: u32,
    pub head: u32,
    /// Length along the geometry, in decimetres.
    pub length_dm: u32,
    /// Index into the geometry offsets.
    pub geometry: u32,
    /// Base speed in km/h, never 0.
    pub speed_kmh: u8,
    /// A [`RoadClass`] value.
    pub class: u8,
    /// A [`Surface`] value.
    pub surface: u8,
    /// Bits from [`edge_flags`].
    pub flags: u8,
}

/// Bits of [`Edge::flags`].
pub mod edge_flags {
    /// The edge runs against its geometry (the reverse of a two-way road).
    pub const REVERSED: u8 = 1 << 0;
    pub const FERRY: u8 = 1 << 1;
    pub const TOLL: u8 = 1 << 2;
    /// Access only to reach a destination (`access=destination`).
    pub const DESTINATION: u8 = 1 << 3;
    pub const ROUNDABOUT: u8 = 1 << 4;
    /// Slip road (`*_link`).
    pub const LINK: u8 = 1 << 5;
    /// Mostly within a built-up area (a town or suburb, told by how
    /// densely roads meet around it); set by the region builder.
    pub const BUILT_UP: u8 = 1 << 6;
}

/// Road class, from OSM `highway=*` (and `route=ferry`).
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RoadClass {
    Motorway = 0,
    Trunk = 1,
    Primary = 2,
    Secondary = 3,
    Tertiary = 4,
    Unclassified = 5,
    Residential = 6,
    LivingStreet = 7,
    Service = 8,
    Track = 9,
    Ferry = 10,
}

impl RoadClass {
    pub const ALL: [RoadClass; 11] = [
        Self::Motorway,
        Self::Trunk,
        Self::Primary,
        Self::Secondary,
        Self::Tertiary,
        Self::Unclassified,
        Self::Residential,
        Self::LivingStreet,
        Self::Service,
        Self::Track,
        Self::Ferry,
    ];

    /// `None` for values added by a newer minor version.
    pub fn from_u8(v: u8) -> Option<Self> {
        Self::ALL.get(usize::from(v)).copied()
    }
}

/// Road surface, from OSM `surface=*`.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Surface {
    Unknown = 0,
    Asphalt = 1,
    Concrete = 2,
    /// Other paved surfaces.
    Paved = 3,
    /// Sett, cobblestone, paving stones.
    Sett = 4,
    Compacted = 5,
    Gravel = 6,
    /// Dirt, ground, grass, sand and other loose surfaces.
    Dirt = 7,
}

impl Surface {
    pub const ALL: [Surface; 8] = [
        Self::Unknown,
        Self::Asphalt,
        Self::Concrete,
        Self::Paved,
        Self::Sett,
        Self::Compacted,
        Self::Gravel,
        Self::Dirt,
    ];

    pub fn from_u8(v: u8) -> Option<Self> {
        Self::ALL.get(usize::from(v)).copied()
    }

    pub fn is_paved(self) -> bool {
        matches!(
            self,
            Self::Unknown | Self::Asphalt | Self::Concrete | Self::Paved | Self::Sett
        )
    }
}

/// Upper bounds (metres) of the turn-radius bins in [`CurvatureMetrics`].
pub const RADIUS_BINS_M: [f64; 6] = [30.0, 60.0, 100.0, 175.0, 300.0, 500.0];

/// Geometry-derived curvature of one edge; turned into a cost at query time
/// so scoring can be tuned without rebuilding the file (ADR-0005).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Pod, Zeroable)]
pub struct CurvatureMetrics {
    /// Sum of absolute heading changes along the edge, in 0.1°.
    pub turn_ddeg: u32,
    /// Metres of road whose turn radius falls in each [`RADIUS_BINS_M`] bin
    /// (bin `i` is `(RADIUS_BINS_M[i - 1], RADIUS_BINS_M[i]]`). Road with a
    /// larger radius counts as straight and is in no bin. Saturates.
    pub radius_len_m: [u16; 6],
}

/// Where an edge comes from in OSM, so saved sections survive rebuilds.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Pod, Zeroable)]
pub struct WayRef {
    pub way_id: i64,
    /// Index into the way's node list where the edge starts.
    pub from_idx: u32,
    /// Index where the edge ends; smaller than `from_idx` when the edge runs
    /// against the way's direction.
    pub to_idx: u32,
}

/// Uniform snapping grid over the region.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Pod, Zeroable)]
pub struct GridMeta {
    /// South-west corner of cell (0, 0).
    pub min_lat: i32,
    pub min_lon: i32,
    /// Cell size in 1e-7 degrees, both > 0.
    pub cell_lat: i32,
    pub cell_lon: i32,
    pub rows: u32,
    pub cols: u32,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn record_sizes_are_stable() {
        assert_eq!(size_of::<Header>(), 256);
        assert_eq!(size_of::<SectionEntry>(), 24);
        assert_eq!(size_of::<PointE7>(), 8);
        assert_eq!(size_of::<Edge>(), 20);
        assert_eq!(size_of::<CurvatureMetrics>(), 16);
        assert_eq!(size_of::<WayRef>(), 16);
        assert_eq!(size_of::<GridMeta>(), 24);
        assert_eq!(MAX_SECTIONS, 160);
    }

    #[test]
    fn enums_round_trip_through_u8() {
        for c in RoadClass::ALL {
            assert_eq!(RoadClass::from_u8(c as u8), Some(c));
        }
        for s in Surface::ALL {
            assert_eq!(Surface::from_u8(s as u8), Some(s));
        }
        assert_eq!(RoadClass::from_u8(200), None);
    }
}
