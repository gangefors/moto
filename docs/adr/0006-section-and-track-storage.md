# ADR-0006: Sections and tracks — stored by the Rust core in SQLite

**Status:** Accepted · **Date:** 2026-09-23 · **Deciders:** Stefan · **Repo path:** `docs/adr/0006-section-and-track-storage.md`

## Context

M1 (Capture) adds the rider's own data: favourite road sections (PRD R1–R3), recorded ride tracks and quick-tags (R3, R4), and export/import for backup (R10). Until now the app only read a region file; now it must keep personal data safely on the phone.

Forces:

- The section model and the logic around it (matching tracks to roads, re-matching sections after an OSM update, turning tags into suggested sections) are product logic that the later iOS app needs too, so they belong in the Rust core (ADR-0001, CLAUDE.md).
- Writes happen on a phone that can die at any moment (battery, crash, app killed). A half-written save must never lose or corrupt existing sections.
- Sections must survive region rebuilds: they are stored as OSM way references plus their own geometry (ADR-0005), and flagged rather than dropped when they no longer match.
- Sections carry a `rider_id` so community ratings can be added later without a migration of meaning.
- Location history is personal data: it stays in app-private storage on the device unless the rider exports it (Security rules in CLAUDE.md).
- The FFI stays coarse: whole requests in, whole results out.

Product decisions taken with this ADR (decisions log, 2026-09-23): a section is good in both directions by default and can optionally be marked one-way; a section is rated good / great / epic; quick-tag works whenever the map is open.

## Decision

The Rust core owns the section, tag and track model **and** its persistence, in one **SQLite** database in app-private storage, accessed through `rusqlite` with SQLite bundled (compiled into the core, the same version on Android and iOS).

- **One database file** per rider data set (`moto.db` in the app's files directory; the app passes the path, the core never chooses paths itself).
- **Tables:** `sections` (id, rider_id, name, rating, direction, source, created/updated, status such as `ok` / `needs_rematch` / `unmatched`), `section_ways` (ordered OSM way id + node range per section), the section's own polyline as a binary column of 1e-7° fixed-point pairs on `sections` (independent of the region file, with the bounding box as indexed columns for area queries), `tags` (time, position, heading, speed, optional track), `tracks` and `track_points` (a recorded ride).
- **Schema versioning** with SQLite's `user_version` and forward-only migrations in the core; a database from a newer app is refused, not modified.
- **Crash safety:** every save is one transaction; WAL journal mode. During a ride the Kotlin side appends GPS points to a small crash-safe buffer and hands the core batches (not one call per point), and the whole track at the end.
- **Security:** parameterised statements only (no SQL built from strings); no SQLite extensions loaded; the database is never shared with other apps; imports (GeoJSON, R10) are parsed by the core with size and count limits and validated before anything is written.
- **API shape:** coarse calls such as `save_section`, `list_sections(bbox)`, `delete_section`, `rate_section`, `record_track(points)`, `suggest_sections_from_tags(track)`, `export_sections()`, `import_sections(bytes)`; results cross as UniFFI records.

## Options Considered

### Option A: Rust core + SQLite via rusqlite (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: schema, migrations and queries in Rust |
| Crash safety | Transactions and WAL; well proven on phones |
| iOS | Same code; SQLite bundled, so the same version everywhere |
| Queries | Indexed lookups by area and id; easy to add views later |
| Dependencies | `rusqlite` (MIT) with bundled SQLite (public domain); C code compiled by cargo-ndk |

**Pros:** robust against crashes, queryable, shared with iOS, one well-known file format for backup tooling.

**Cons:** a C library inside the core (large, heavily fuzzed and audited, but not Rust); we write migrations ourselves.

### Option B: Rust core + plain files (GeoJSON or our own binary)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low to start, grows with every query and crash-safety need |
| Crash safety | Ours to build (write-rename, journaling) |
| iOS | Same code |
| Dependencies | `serde_json` or none |

**Pros:** no C code; the export format could be the storage format.

**Cons:** queries (sections in view, tags of a ride) and safe concurrent writes become our own mini-database.

### Option C: Kotlin with Room (Android SQLite)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low on Android |
| iOS | Re-implement everything in Swift |
| Core logic | Map matching and re-matching in the core would need the data passed back and forth |

**Pros:** standard Android tooling.

**Cons:** splits the product's data model from its logic and breaks the rule that iOS-relevant logic lives in the core.

## Trade-off Analysis

The section data is small (hundreds to thousands of sections, a few hundred rides) but precious, so crash safety and correctness matter more than raw speed. SQLite gives transactional safety and indexed queries for free and runs the same on both platforms; the cost is a well-audited C dependency in the core. Plain files would keep the core pure Rust but make us re-invent a database badly. Room would be easiest today and costliest for iOS.

## Consequences

- **Easier:** reliable saving on the bike, queries by map area, adding community fields later, one backup file.
- **Harder:** the core now does file I/O on a path the app provides; migrations must be tested (old database → new schema) on every schema change; the bundled C code adds build time and APK size (to be measured).
- **Security:** SQLite and every import path are covered by the Security rules; database and import parsing get corruption tests like the region file.
- **Revisit if:** the bundled SQLite adds unacceptable APK size or build complexity, or sync (community) needs a different store.

## Action Items

- [x] Add `rusqlite` (newest release at least a week old, bundled SQLite) to `moto-core`; measure APK size and build time impact. rusqlite 0.40.2; with the store linked, `libmoto_ffi.so` grows from 0.5 to 2.45 MB per ABI (native libraries are stored uncompressed), so the debug APK with both ABIs grows by about 6 MB; the phone uses the arm64 library only. Build time impact is small (SQLite compiles once per ABI, then comes from the cache).
- [x] Section/tag/track schema with `user_version` migrations; tests for every migration and for crash-in-the-middle (transaction rollback). Sections (schema 1), tracks (schema 2), tags (schema 3).
- [x] Coarse FFI calls for sections, tags and tracks; Kotlin passes the database path in app-private storage. Sections done (`SectionStore`, `Engine.sectionBetween`); tracks done (track calls on `SectionStore`, `Engine.matchTrack`); the Kotlin side's crash-safe fix buffer is a text file per track in app storage, handed to the core every 30 fixes or 30 s; tags done (tag calls on `SectionStore`, `Engine.suggestSection`).
- [x] GeoJSON export/import in the core with limits and corruption tests (R10): plain, .gz or .zip (tar.gz dropped 2026-09-24: an export is one file), read in memory with size caps (32 MiB in, 64 MiB unpacked), strict types, overlap rules (a section is dropped only when another covers it in every direction it applies to and is rated at least as high, the longer winning a tie; since 2026-09-24 also for new sections), one transaction per import.
