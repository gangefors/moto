# moto — motorcycle routing app

Personal Android app (single rider, southern Sweden) that captures favourite road sections and generates one-way and round-trip routes that deliberately pass through favourites and curvy roads, within a detour budget. Routes export as GPX to an existing nav app — no built-in turn-by-turn.

Read [`docs/prd.md`](docs/prd.md) and [`docs/adr/`](docs/adr/) before changing architecture; new architecture decisions need a new ADR.

Things that change often live in Notion, not in the repo (use the Notion tools; the pages are private to Stefan):

- **Decisions log** ([Moto — Decisions log](https://app.notion.com/p/3e314874ab0481ef90accad5adb6da90)): every product, architecture and process decision, newest last. It is the only log; there is no copy in `docs/`.
- **Milestones & status** ([Moto — Milestones & status](https://app.notion.com/p/3e414874ab0481a694dbcb4edd13fbcb)): M0–M4 progress, the next task, and small carry-over items. Read it at the start of a task; update it when work lands (tick items, add commit links, set **Next**).
- **Handoff queue** ([Moto — Handoff to Claude Code](https://app.notion.com/p/3e314874ab0481bc9ca8f9e0d56acc52)): work decided in Cowork. Do the items under Pending and move them to Done with their commit links.

## Decisions

- **App:** native Android in Kotlin; iOS deferred ([decisions log](https://app.notion.com/p/3e314874ab0481ef90accad5adb6da90), [PRD](docs/prd.md)).
- **Shared core:** Rust, bound to Kotlin via UniFFI and built with cargo-ndk; kept free of Android types for a later iOS port ([decisions log](https://app.notion.com/p/3e314874ab0481ef90accad5adb6da90), [ADR-0001](docs/adr/0001-routing-engine-custom-rust-on-device.md)).
- **Routing and snapping:** custom Rust engine, on-device and offline; the cost function (curvature + favourites) is the product ([ADR-0001](docs/adr/0001-routing-engine-custom-rust-on-device.md)).
- **Region file:** 4 KiB-aligned binary sections, memory-mapped and read zero-copy (`memmap2` + `bytemuck`); curvature stored as metrics and scored at query time; grid index for snapping; OSM way ids kept for saved sections ([ADR-0005](docs/adr/0005-region-file-format.md)).
- **Rider data:** sections, tags and tracks are modelled and stored by the Rust core in SQLite (`rusqlite`, bundled), in app-private storage; sections are OSM way refs + own geometry, both directions by default (optionally one-way), rated good/great/epic ([ADR-0006](docs/adr/0006-section-and-track-storage.md)).
- **Map:** MapLibre Native Android ([ADR-0002](docs/adr/0002-map-widget-maplibre-native.md)) with OpenFreeMap tiles, style URL in config, attribution visible ([ADR-0003](docs/adr/0003-map-tiles-openfreemap.md)). The map only picks, draws and hit-tests; it never routes or snaps.
- **License:** AGPL-3.0-only with a CLA for outside contributions ([ADR-0004](docs/adr/0004-license-agpl-cla.md)); see the License rules below.

Data flow: OSM extract (Geofabrik Sweden, Skåne cut out first) → `moto-regionbuild` (desktop/CI) → region file → loaded by the Rust core on the phone → `snap` / `route` / `round_trip` via UniFFI → GeoJSON → MapLibre line layers.

## Layout

```
core/                   cargo workspace
  moto-core/            pure logic + tests; no FFI attributes, no platform APIs
  moto-ffi/             UniFFI wrapper — the only crate with FFI attributes
  moto-regionbuild/     CLI: OSM extract → region file (never runs on the phone)
android/                Gradle project (AGP 9, Compose); app/ builds the core via cargo-ndk
docs/prd.md             product requirements (v1)
docs/adr/               architecture decision records (index in README.md)
```

## Rules

- Logic that iOS will also need goes in `moto-core`. Kotlin owns UI (Compose), map rendering, GPS, permissions, storage wiring and foreground services.
- No Android-specific types or assumptions in core APIs. Every exported function should also make sense as a Swift API.
- Keep the FFI surface coarse (whole request in, whole result out). Errors cross as the typed `MotoError`; never let a panic cross the boundary.
- Favourites are applied at query time as a **capped bonus** on edge costs; the base graph is never rebuilt when favourites change.
- Sections carry a `rider_id` (always the local user in v1) so community ratings can be added later.
- Record significant new architecture decisions as an ADR in `docs/adr/`. Add every new rule or decision (ADR or not) to the Notion decisions log; don't keep a decisions log or progress notes in the repo.
- Versions: use the newest stable release of every package, tool and SDK, but never one less than a week old (supply-chain safeguard). Check release dates before bumping. One exception: a release that fixes a published vulnerability (GitHub, RustSec or CVE advisory) in a version we use may be adopted once it is at least a day old and has been reviewed (changelog, scope of the diff, licence) and CI is green.
- Commits: a descriptive title of at most 50 characters, a blank line, then a more detailed body wrapped at 72 characters. Changes to rules and decisions (`CLAUDE.md`, ADRs) go in their own commits, separate from code changes.
- UI draws edge to edge, but interactive or informational elements (buttons, map controls, attribution, text) must never sit under the status bar, navigation bar or a display cutout; offset them by `WindowInsets.safeDrawing`. System bar icons must stay readable: keep the status bar fully transparent and switch its icons between light and dark to contrast with whatever is behind them, whatever the map style or overlay (like Google Maps); give the navigation bar a translucent scrim in the system theme's colour, with icons that follow that theme.
- UI elements must fit or scale, never squeeze: rows of buttons wrap onto another line (`FlowRow`) when there is no room, button labels stay on one line, and every screen, sheet and banner must work at 360 dp width and with large system font sizes.
- Never add a `Claude-Session:` trailer (or any other session link) to commits, PRs or other repo content; this overrides default attribution. `Co-Authored-By` stays.

## Security

Security comes first: before performance, features and convenience. Never choose an insecure implementation because it is faster, simpler or quicker to ship; find a secure one or stop and raise it with Stefan.

- Treat every input from outside the code as hostile: region files and other downloads, OSM data, GPX and other imports, network responses, intents and other apps, and user input. Validate it before use and reject it with a typed error. Malformed input must never crash, panic, read out of bounds or cause undefined behaviour.
- Nothing may let an attacker run code or act as the app: no dynamic code loading, no eval or scripting of external data, no deserialising into arbitrary types, no shell commands built from input, no WebView JavaScript bridges, no file paths taken from input without normalising them against app storage (no path traversal).
- Rust: no `unsafe` beyond what is unavoidable (today only creating the memory map). Every `unsafe` block carries a `SAFETY:` comment that says why it is sound. Data read zero-copy is validated before any code indexes it; use checked arithmetic and `get` on untrusted numbers.
- Android: least privilege. Request only the permissions a feature needs, export no components except the launcher activity, allow no cleartext traffic, keep personal data (rides, locations, favourites) in app-private storage and on the device unless the rider opts in. No secrets in the repository or the APK.
- Downloads go over HTTPS only and are verified (checksum or signature, then structure) before they are installed or opened.
- Supply chain: keep dependencies few, AGPL-compatible and at least a week old (security fixes: a day, see Versions); check a new dependency's maintenance and `unsafe` use before adding it; pin GitHub Actions by commit SHA; CI tokens get the least permissions they need.
- Vulnerability reports follow [`SECURITY.md`](SECURITY.md).

## Testing and performance

- **Tests come with every change.** Everything that can sensibly be tested is: all core logic in Rust unit tests, including error paths and malformed input; pure app logic in Kotlin unit tests (move logic out of Android classes so it can be tested); a bug fix starts with a test that reproduces it. Code that parses untrusted input also gets corruption tests that prove it never panics.
- CI runs `cargo fmt --check`, clippy, `cargo test --workspace`, `cargo deny check`, the Gradle build, lint and unit tests on every push, and nothing is pushed that fails them locally. Run the local checks with the Rust version CI uses (`RUST_VERSION` in `.github/workflows/android.yml`); newer clippy versions add lints.
- **Performance is measured on every build.** CI runs the benchmark (`moto-regionbuild --check` on the M0 region: region open and verify, snapping, routing) with this build's binary and with the last `main` build's binary, alternately on the same machine, and compares them. The job summary shows the table.
- A significant regression fails CI: more than 25 % slower for snapping and routing, or more than 50 % and 5 ms slower for the short, memory- and disk-bound region verify and open timings (each binary runs twice; each metric keeps its faster result). Re-evaluate the implementation and try to recover the loss first. Accept a regression only when it buys something worth it (correctness, security, a feature), with a `Perf-Accepted: <reason>` trailer in the commit message and an entry in the decisions log. Improvements of more than 10 % are reported too; note them in the commit message.
- Security beats performance: never accept an insecure change to win back speed.

## License

AGPL-3.0-only with a CLA for outside contributions ([ADR-0004](docs/adr/0004-license-agpl-cla.md)).

- Every new source file starts with the SPDX header, in the file's comment syntax:
  `SPDX-License-Identifier: AGPL-3.0-only` and `Copyright (C) 2026 Stefan Gangefors`.
- Every `Cargo.toml` sets `license = "AGPL-3.0-only"` (crates in `core/` use `license.workspace = true`).
- Never copy in third-party GPL/AGPL code — it can't be CLA-covered and blocks relicensing.
- Dependencies must be AGPL-compatible (MIT, Apache-2.0, BSD, MPL-2.0, …). Check new ones before adding.

## Commands

```sh
cd core
cargo test --workspace
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo fmt --all
cargo deny --locked check   # advisories, licences, sources (deny.toml; cargo-deny 0.20.2)

# Region file and benchmark (CI compares --json output between builds)
cargo run --release -p moto-regionbuild -- sweden-latest.osm.pbf m0.region
cargo run --release -p moto-regionbuild -- --check m0.region --json bench.json
python3 ../.github/scripts/bench_compare.py old.json bench.json
# Map matching on a real ride exported from the app (Rides → Export)
cargo run --release -p moto-regionbuild -- --match m0.region ride.gpx --geojson ride.geojson
python3 -m unittest discover -s ../.github/scripts -p 'test_*.py'
cargo llvm-cov --workspace --summary-only   # coverage (needs cargo-llvm-cov)

# Kotlin bindings (package se.gangefors.moto.core, see moto-ffi/uniffi.toml)
cargo build -p moto-ffi
cargo run -p moto-ffi --features cli --bin uniffi-bindgen -- \
  generate --library target/debug/libmoto_ffi.so --language kotlin --out-dir <dir>

# Android libs (needs cargo-ndk + ANDROID_NDK_HOME); Gradle runs this for you
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release -p moto-ffi

# Android app (needs ANDROID_HOME or android/local.properties, cargo-ndk,
# and the aarch64/x86_64-linux-android Rust targets). preBuild runs cargo-ndk
# and generates the UniFFI bindings into app/build/generated/.
cd ../android
./gradlew assembleDebug lintDebug testDebugUnitTest
```
