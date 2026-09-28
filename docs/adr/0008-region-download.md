# ADR-0008: Region download — built in CI, published as GitHub release files, installed by the core

**Status:** Accepted · **Date:** 2026-09-27 · **Deciders:** the owner · **Repo path:** `docs/adr/0008-region-download.md`

## Context

Until now the debug APK carried the M0 region (Skåne and its surroundings, 38 MiB) as an asset. That can't grow: all of Sweden is 394 MiB, and the release app should stay small (2026-09-27). ADR-0005 already says regions are downloaded, compressed, and decompressed once into app storage because a memory map needs the raw file; PRD R12 asks that the app can install and replace a region file and report its OSM data date. This ADR decides where the files come from, how they are checked, and how the app installs them.

Forces:

- **Security first** (CLAUDE.md): a region file is hostile input until verified. Downloads go over HTTPS only and are verified (checksum or signature, then structure) before they are installed or opened; no file path may come from the download.
- **No server of our own** (cost, upkeep, and knowing where riders ride).
- **Format changes:** the region format evolves with the core (ADR-0005 has a major version). An app must only ever be offered files it can read, and an older app must keep working after a newer format is published.
- **Size:** Sweden is 394 MiB raw; gzip -9 makes it 148 MiB (zstd -19 would save perhaps another 15–20 %, but adds a C dependency; gzip is already in the core through flate2 in pure Rust).
- **Portability:** the checking and installing is logic iOS needs too, so it belongs in the core; Kotlin only fetches bytes.

## Decision

- **Built in CI.** A `regions` workflow builds each published region with `moto-regionbuild` from the same commit, weekly and on demand, and publishes it to a GitHub release with the tag `regions` on `gangefors/moto`. The first region is **all of Sweden** (`--bbox 55.0,10.5,69.2,24.3`).
- **Files per format.** For region format major version *N* the release holds a manifest `regions-vN.json` and one `<id>-vN.region.gz` per region. The workflow replaces only the files of the current major version, so older apps keep their own files until they are removed by hand.
- **Manifest** (JSON, at most 64 KiB): `format_major`, and per region `id` (`[a-z0-9-]`, 1–32), `name` (display, at most 64 characters, no control characters), `gz_bytes`, `gz_sha256`, `region_bytes`, `osm_timestamp` and the bounding box. The core parses it strictly and refuses anything else, including a `format_major` other than its own. The file name is never read from the manifest: the app builds it from the checked id and the core's major version.
- **Source data.** CI builds from Geofabrik's Sweden extract, fetched from the openstreetmap.fr mirror (ADR-0005).
- **Fetched from a fixed place.** The app only fetches `https://github.com/gangefors/moto/releases/download/regions/<name>`; redirects are followed only to HTTPS (GitHub hands out the files from its own storage hosts). Cleartext stays disallowed app-wide.
- **Download** (Kotlin, no new libraries): one region at a time, while the app is open, into a partial file in app-private storage that is never backed up; an interrupted download resumes with an HTTP range request. The download is refused if free space is short for the compressed file plus the raw file.
- **Install** (core, `install_region`): check the downloaded file's size and SHA-256 against the manifest **before** decompressing anything; decompress to a temporary file next to the target, refusing more bytes than `region_bytes` (a gzip bomb stops there); run the full `verify_file` (section checksums and structure, ADR-0005); then rename it into place, so the old region stays usable until the new one is complete. The app passes the paths, inside its own storage, built from the checked id.
- **Choosing the region.** The installed download is used when there is one; otherwise a debug build falls back to its bundled M0 region, and a release build asks the rider to download one. Sections are re-matched to the new region automatically (ADR-0006).
- **Updates.** My data shows the installed region and its OSM data date, and offers an update when the manifest has newer data. No background downloads in v1.
- **Signed manifest (2026-09-28).** The manifest is published as `regions-vN.manifest`: a 64-byte Ed25519 signature followed by the manifest's JSON bytes, in one file so manifest and signature always change together (no window where a new manifest meets an old signature). The core checks the signature (`verify_strict`) against the public keys compiled into it **before** parsing the JSON, and `install_region` checks it again; a manifest without a valid signature is refused. The keys are a list, so a new key can ship in the app before the workflow switches to it. The signing key is created offline by the rider and held only as the secret `REGIONS_SIGNING_KEY` of the GitHub environment `regions` (main only), which only the regions workflow's publish job uses; it signs with `openssl pkeyutl` and checks the result with `moto-regionbuild` (the same core code and keys as the app) before uploading, so a key that doesn't match the app never publishes. As the manifest carries each file's SHA-256, the signature covers the region files too. Accepted: an old, validly signed manifest could be served again (stale map data, no other harm); the app only offers an update for newer data.
- **Release builds carry no region (2026-09-28).** Only debug builds bundle the M0 region; a release build starts with none and asks the rider to download one.
- **Licence.** Region files are derived from OpenStreetMap: the release notes state © OpenStreetMap contributors, ODbL 1.0, and link the builder's source.

## Options Considered

### A. Bundle regions in the APK (today's M0 approach)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low: already built |
| Size | Every APK carries every region: 150+ MiB compressed for Sweden |
| Updates | New map data needs a new app release |
| Security | Signed with the APK |

**Pros:** nothing to download or check. **Cons:** a huge APK; map updates tied to app releases; can't offer several regions.

### B. GitHub release files, checked by the core (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: a workflow, a manifest, an installer, a download screen |
| Cost | None; GitHub hosts release files for public repositories |
| Security | HTTPS from a fixed source, signed manifest, SHA-256 before decompressing, bounded decompression, full structure check |
| Portability | Checking and installing in the core; only the fetch is Kotlin |

**Pros:** small APK; weekly map updates without app releases; several regions later with the same machinery. **Cons:** GitHub is a single source; a compromised release could serve a well-formed but wrong file until manifests are signed.

### C. Our own server that builds files on demand

| Dimension | Assessment |
| --- | --- |
| Complexity | High: a service to run and secure |
| Cost | Hosting and compute |
| Privacy | The server learns where riders ride |

**Pros:** any area on request. **Cons:** cost, upkeep, and privacy; not worth it for one rider. Rider-chosen areas are better served later by CI-built tiles the phone stitches together (a future ADR).

## Trade-off Analysis

B keeps the APK small and the map fresh at no cost, and every check that protects the phone from a bad file runs in the core, where the region reader's corruption tests already live. What B does not give yet is authenticity if GitHub itself, or the release, is compromised: the manifest's checksum comes from the same place as the file. The structure check still guarantees such a file can't crash the app or read out of bounds; the worst it could do is route badly. Signing the manifest closes that gap, but needs a signing key held as a CI secret, which the rider must create; it is an action item before the app is offered to other riders.

## Consequences

- **Easier:** Sweden (and later other regions) on the phone without a new APK; the release APK carries no region.
- **Harder:** the app now does network downloads of hundreds of MiB; free space must be checked; a region format change needs the workflow to publish the new major version before an app that reads it is released.
- **Revisit if:** more regions or riders are added (sign manifests first), downloads need to run in the background (WorkManager), or zstd's smaller files become worth a dependency.

## Action Items

- [x] `regions` workflow: weekly and on demand, build Sweden, gzip, manifest (written by `moto-regionbuild --manifest`, which first installs each region back with the core), publish to the `regions` release.
- [x] Core: manifest parsing (`region::install::parse_manifest`) and `install_region` with tests, including corrupt and oversized input; FFI (`parse_region_manifest`, `install_region`).
- [x] App: My data → Map region: installed region and date, Download / Update with progress and stop, resume, free-space check, a bin to remove it; use the installed region, else the bundled one (debug).
- [x] Measure on the phone: done 2026-09-28 (Pixel 7): removing Sweden and downloading it again took about 18 s to a ready region (download, full check, install), open by fingerprint 0.24–0.95 s (ADR-0005), routes and loops in ADR-0001.
- [x] Before other riders: sign the manifest (above): core verification and `regionbuild --check-manifest`, the app fetching `regions-vN.manifest`, the workflow signing; the rider created the key and the `regions` environment (2026-09-28, c793d6a); a signed download checked on the phone; the unsigned `regions-vN.json` is no longer published.
- [x] Release builds bundle no region (dc3abe8).
