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

- The download is compressed (gzip, not zstd as first planned: gzip is already in the core in pure Rust; see ADR-0008); the app decompresses it once on install into app storage, because a memory map needs the raw file.

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

## Implementation notes (format v1.0)

Details settled while implementing the decision (`core/moto-core/src/region/`):

- **Layout:** a 256-byte header (magic `MOTOREG\0`, version, OSM timestamp, bounding box in 1e-7°, builder version, source name) followed by the section table in the first 4 KiB page. Sections: node positions, forward and backward CSR offsets, backward edge list, edges, geometry offsets, shape points, curvature metrics, grid meta/cells/edges, OSM way refs. Records are `#[repr(C)]` `bytemuck` types; the file is little-endian and big-endian targets refuse to compile.
- **Edges** (20 bytes: tail, head, length in dm, geometry id, speed, class, surface, flags) are sorted by tail, so the forward CSR offsets index the edge array directly and there is no separate forward edge list. The backward CSR indexes a list of edge ids sorted by head.
- **Geometry** is shared by both directions of a two-way road; the reverse edge carries a `REVERSED` flag. Shape points include both endpoints. One-way-backward roads store their geometry in travel direction.
- **Curvature metrics** per edge: total absolute heading change (0.1°) and metres of road in six turn-radius bins (≤30, 60, 100, 175, 300, 500 m), measured on points at least 5 m apart. Bends within 20 m of a junction (a node where three or more roads meet) are left out: they are the corner where roads meet, a flare or a turning lane, not a bend of the road. Where one road simply continues into the next, its bends count (2026-09-26).
- **Built-up areas** (2026-09-26): the builder sets a `BUILT_UP` edge flag (bit 6) on roads that lie mostly in large built-up areas: 3 × 3 cells of about 560 m where 30 or more junctions per km² meet, in areas of such cells of at least 15 km² (cities such as Malmö, Helsingborg, Lund and Kristianstad; not towns or villages). Scoring treats flagged roads as dull and not curvy, and loop waypoints avoid them. Curvature and built-up areas are derived from the finished graph, so `moto-regionbuild --refresh` can derive them afresh for an existing file without the OSM extract. Older readers ignore the new bit.
- **Coverage outline (format 1.1, 2026-09-27):** two optional sections, ring offsets and closed rings of points, outlining where the region has roads, so the app can grey out the rest instead of showing the bounding box (a Sweden file's box also spans Norway, Denmark and Finland). The writer traces it from the roads themselves (ferries left out): cells of 0.02° latitude (about 2.2 km), widened by one cell, holes filled, outlines traced along the cell edges and simplified (Douglas–Peucker, half a cell), clamped to the box. It works for any cut (a country extract, a box, a future rider-chosen area). Sweden: 5 outlines, 2207 points, 24 KB. Readers of format 1.0 skip the sections; the core refuses a snap outside the outline as outside the region.
- **Snapping grid** lists only edges that run along their geometry (one per road). Cells are about 280 m; snapping scans cells in rings until nothing closer can remain, up to 500 m.
- **Validation on open is full**, not only header and lengths: CSR offsets monotonic and complete, every edge and grid entry in range, edge geometry joining its nodes, coordinates in range. Query code can then index without panicking. It costs one pass over the file (measurements below). CRC32 is checked separately by `verify_file` at install.
- **Builder:** keeps `highway=motorway…service` (not parking aisles or driveways), tracks and ferries only when open to motor vehicles, the most specific access tag wins; maxspeed with Swedish defaults. Ways are cut at the bounding box, routing nodes are way ends and shared nodes, self-loops are split in the middle, nodes are Hilbert-ordered.
- **Source:** Geofabrik's Sweden extract, fetched from the mirror `https://download.openstreetmap.fr/extracts/europe/sweden-latest.osm.pbf` (same data). Geofabrik refuses Claude's sessions, and since 2026-09-27 redirects GitHub's runners to plain HTTP, which CI refuses; so CI and Claude both use the mirror.
- **M0 region:** Skåne plus the southern half of Halland, southern Småland and western Blekinge: `55.28,12.20,56.72,15.05`.

## Measurements (2026-09-23)

Sweden extract from OSM data of 2026-09-23 (same data as Geofabrik's, via download.openstreetmap.fr), built on a 4-core cloud VM; open, verify and snap timed warm (file in page cache).

| | M0 region (Skåne +) | Sweden |
| --- | --- | --- |
| Routing nodes / edges | 182 k / 411 k | 1.53 M / 3.39 M |
| File size | 38.1 MiB | 398 MiB |
| Largest sections | shape points 9.6, edges 7.8, curvature 6.3, way refs 6.3 MiB | shape points 107, grid cells 65, edges 65, curvature 52, way refs 52 MiB |
| Build time / peak memory | 12 s / 0.7 GiB | 59 s / 5.2 GiB |
| Verify (CRC + structure) | 17 ms | 210 ms |
| Open (map + structure) | 12 ms | 150–200 ms |
| Snap, mean of 10 k random points | 4–7 µs (13 µs in Lund, all land) | 3–4 µs (mostly empty cells) |

Consequences for the "revisit if" list: the M0 file is comfortably small. For all of Sweden, full validation on open touches the whole 400 MiB, which will be slow on a phone with a cold cache, and the uniform grid is 65 MiB of mostly empty cells; both point at lazy (per-section or per-query) validation and a sparse grid or packed R-tree before Sweden ships.

## Measurements (2026-09-27): routing on all of Sweden

The same kind of 4-core cloud machine, OSM data of 2026-09-27, today's builder (built-up areas, junction-trimmed curvature, small networks dropped) and today's router, `--check` with route pairs at most 300 km apart (the PRD's longest trip). Desktop timings, warm; a phone is slower.

| | M0 region | Sweden |
| --- | --- | --- |
| Routing nodes / edges | 179 k / 408 k | 1.50 M / 3.35 M |
| File size | 37.8 MiB | 394 MiB |
| Build time / peak memory | 9 s | 53 s / 5.1 GiB |
| Verify / open | 9 / 7 ms | 150 / 114 ms |
| Route pairs, mean distance | 88 km | 246 km |
| Fastest route, mean / p95 | 13 / 36 ms | 49 / 124 ms |
| With favourites and curves, mean / p95 | 46 / 112 ms | 240 / 538 ms |
| Route choices (up to 3 + the fastest), mean / p95 | 162 / 358 ms | 871 / 2363 ms |
| Loops 50 / 100 km, mean / p95 | 169 / 347 ms | 105 / 204 ms |
| Loops 400 km (the longest offered), mean / p95 | 689 / 923 ms | 1547 / 4630 ms |
| Peak memory of `--check` (file mapped) | | 709 MiB |

Routing scales with trip length, not region size: the whole of Sweden adds no cost to a trip of the same length. Route choices for the longest trips and 400 km loops are the slowest queries, about 2.4 s and 4.6 s at p95 on the desktop; a phone 2–4× slower still stays within the PRD's 10 s for 300 km, but should be measured. The open questions from 2026-09-23 stand: full validation on open and the mostly empty uniform grid (5618 × 3048 cells) for a phone with a cold cache.

## Action Items

- [x] Copy this ADR to `docs/adr/0005-region-file-format.md` and add it to the index.
- [x] `moto-core`: region reader (header, section table, typed slices, validation) with tests on a tiny hand-made fixture.
- [x] `moto-regionbuild`: OSM PBF → filtered motorcycle graph → region file v1, with a Skåne bounding-box cut.
- [x] `Engine::open` and `snap` on the real file; tap → snap → marker on the map (verified on the phone, region bundled in the debug APK).
- [x] Measure the Skåne and Sweden files: size, open time, peak memory, snap time (peak memory on the phone still to measure).
