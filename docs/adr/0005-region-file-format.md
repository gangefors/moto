# ADR-0005: Region file format — memory-mapped binary sections

**Status:** Accepted · **Date:** 2026-09-23 · **Deciders:** Stefan · **Repo path:** `docs/adr/0005-region-file-format.md`

## Context

The region file is the PRD's blocking open question. [ADR-0001](0001-routing-engine-custom-rust-on-device.md) already fixes the broad shape: a Rust CLI (`moto-regionbuild`) turns an OSM extract into a region file; the phone memory-maps it; it holds a motorcycle-routable graph as CSR arrays with per-edge length, speed, road class, surface and curvature; it has a versioned header; favourites are a capped query-time bonus and never change the file.

Still open: the byte layout, how snapping finds nearby roads, how saved sections survive a rebuild from newer OSM data, where curvature scoring happens, and how a later speed-up (ALT landmarks) fits in without a format break.

Forces:

- Must open instantly on the phone and use little heap: no parse or deserialise step, the OS pages data in on demand.
- Must work for Skåne now and all of Sweden later.
- The Rust core is shared with a future iOS app, so the format must not depend on Android.
- Curvature scoring is the product and will be tuned often (golden-route set); tuning must not force a rebuild.
- Saved sections are personal data that must survive OSM refreshes (PRD open question on re-matching).
- Dependencies must be AGPL-compatible and kept few.

## Decision

A single **little-endian binary file of aligned, typed sections**, memory-mapped with `memmap2` and read zero-copy as slices with `bytemuck`. No general-purpose serialisation framework.

**Header and sections**

- Header: magic `MOTOREG`, format version (major.minor), OSM data timestamp, source extract name, bounding box, builder version, and a section table (id, offset, length, CRC32).
- Every section starts on a 4 KiB boundary so it can be viewed as a typed slice directly from the map.
- Major version change: the app refuses the file and asks for a new one. Minor version: new optional sections only; older apps ignore unknown section ids.
- Checksums are verified once, when a file is installed, not on every open. Opening checks the header, section bounds and array lengths only.

**Graph**

- Nodes are routing nodes only: intersections and dead ends. OSM nodes along a road between them become edge geometry.
- Nodes are ordered along a Hilbert curve so nearby nodes sit near each other in the file (fewer page faults on the phone).
- Node arrays: position as `i32` fixed point (1e-7°, as OSM stores it), forward CSR offsets, backward CSR offsets (for bidirectional search).
- Edge arrays (directed): head node, length, base speed, flags (road class, surface, one-way, ferry, access), geometry offset, curvature metrics.
- Geometry section: shape points per edge as `i32` fixed-point pairs.

**Curvature**

- The file stores **geometry-derived curvature metrics** per edge (for example total turning angle per km and a small histogram of turn radii), not a final score. The scoring function turns them into a cost at query time, so it can be tuned against the golden-route set without rebuilding the file.

**Snapping**

- A **uniform grid spatial index**: fixed-size cells over the bounding box, each listing the edges that pass through it, stored CSR-style (cell offsets + edge ids). Simple, mmap-friendly and fast enough for tap and GPS snapping.

**Stable references for saved sections**

- A side section maps each edge to its OSM way id and the node range it covers. Saved sections are stored as OSM way ids + positions (plus their own geometry), not as internal edge ids, so they can be re-matched after a rebuild. The re-matching algorithm itself is a separate decision.

**Reserved for later**

- An optional `LANDMARKS` section for ALT (precomputed distances to/from ~16 landmarks). Adding it is a minor version bump. Whether ALT is needed is decided by measurement in M2, as ADR-0001 says.

**Distribution**

- The download or bundled asset is zstd-compressed; the app decompresses it once on install into app storage, because a memory map needs the raw file.

## Options Considered

### Option A: Custom aligned sections + mmap + bytemuck (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: we write the builder, the reader and the validation |
| Open time | Near zero; no parsing, pages loaded on demand |
| Memory | Minimal heap; the OS pages the file in and out |
| Evolvability | Versioned sections; additive changes are minor versions |
| Dependencies | `memmap2`, `bytemuck`, `crc32fast` (all MIT/Apache); `osmpbf` in the builder only |
| iOS | Same Rust code; mmap works the same way |

**Pros:** fastest possible open and query; full control over layout for routing and cache locality; tiny dependency footprint; the file is easy to inspect and test.

**Cons:** we own the format code; `unsafe` is limited to creating the memory map, but array contents must be validated before use.

### Option B: rkyv (zero-copy archive of Rust structs)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low–Medium: derive macros generate the layout |
| Open time | Near zero after validation |
| Evolvability | Weak; schema changes need care, and the archived layout is tied to rkyv's version |
| Dependencies | `rkyv` plus its derive ecosystem (MIT) |

**Pros:** less hand-written code; zero-copy.

**Cons:** layout is not under our control (harder to tune for locality); upgrades of rkyv can break file compatibility; validation of large archives is slow without `unsafe`.

### Option C: FlatBuffers

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: schema file + code generation |
| Open time | Near zero |
| Evolvability | Good, built-in schema evolution |
| Dependencies | `flatbuffers` runtime + `flatc` compiler in the build (Apache-2.0) |

**Pros:** well-known, language-neutral, good schema evolution.

**Cons:** designed for tables of objects rather than large numeric arrays; extra build tool; no real benefit since only Rust reads the file.

### Option D: SQLite (with a spatial index)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low to build |
| Open time | Fast, but every graph access is a query |
| Query speed | Far too slow for A\* over millions of edges |

**Pros:** familiar, inspectable, easy updates.

**Cons:** unsuitable for the routing inner loop; would force loading the graph into heap memory anyway.

## Trade-off Analysis

The routing inner loop reads millions of small numeric records, so layout control and zero parsing matter more than convenience. Option A gives both with the fewest dependencies, at the cost of writing and testing our own reader. Option B saves some code but trades away layout control and long-term compatibility. Options C and D solve problems we don't have. The section table keeps A evolvable: new data (landmarks, extra metrics) arrives as new optional sections.

Storing curvature **metrics** rather than a score keeps the product's core (the cost function) tunable without rebuilding Sweden. Referencing sections by **OSM way id** rather than internal ids keeps personal data valid across OSM refreshes.

## Consequences

- **Easier:** instant open on the phone; tuning the scoring without rebuilds; adding ALT later; testing with small fixture files.
- **Harder:** we maintain the format, its validation and a compatibility policy; a builder bug can produce a file the app must reject cleanly.
- **Revisit if:** the Sweden file is too large for the phone (consider delta/varint-compressed geometry or dropping shape points at low detail), or snapping is slow in dense areas (move from a grid to a packed R-tree).

## Resolved questions

- **Skåne extract:** Geofabrik offers Sweden but no Skåne sub-extract. Decided: `moto-regionbuild` takes the Sweden PBF plus a Skåne boundary (bounding box for M0, polygon later) and cuts it itself.
- **Direction-dependent sections** (PRD open question) don't affect the file: edges are directed either way.

## Action Items

- [x] Copy this ADR to `docs/adr/0005-region-file-format.md` and add it to the index.
- [ ] `moto-core`: region reader (header, section table, typed slices, validation) with tests on a tiny hand-made fixture.
- [ ] `moto-regionbuild`: OSM PBF → filtered motorcycle graph → region file v1, with a Skåne bounding-box cut.
- [ ] `Engine::open` and `snap` on the real file; tap → snap → marker on the map.
- [ ] Measure the Skåne and Sweden files: size, open time, peak memory, snap time.
