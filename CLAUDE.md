# moto — motorcycle routing app

Personal Android app (single rider, southern Sweden) that captures favourite road sections and generates one-way and round-trip routes that deliberately pass through favourites and curvy roads, within a detour budget. Routes export as GPX to an existing nav app — no built-in turn-by-turn.

Read [`docs/prd.md`](docs/prd.md) and [`docs/adr/`](docs/adr/) before changing architecture; new architecture decisions need a new ADR. The running log of decisions is [`docs/decisions.md`](docs/decisions.md).

## Decisions

- **App:** native Android in Kotlin; iOS deferred ([decisions log](docs/decisions.md), [PRD](docs/prd.md)).
- **Shared core:** Rust, bound to Kotlin via UniFFI and built with cargo-ndk; kept free of Android types for a later iOS port ([decisions log](docs/decisions.md), [ADR-0001](docs/adr/0001-routing-engine-custom-rust-on-device.md)).
- **Routing and snapping:** custom Rust engine, on-device and offline; the cost function (curvature + favourites) is the product ([ADR-0001](docs/adr/0001-routing-engine-custom-rust-on-device.md)).
- **Map:** MapLibre Native Android ([ADR-0002](docs/adr/0002-map-widget-maplibre-native.md)) with OpenFreeMap tiles, style URL in config, attribution visible ([ADR-0003](docs/adr/0003-map-tiles-openfreemap.md)). The map only picks, draws and hit-tests; it never routes or snaps.
- **License:** AGPL-3.0-only with a CLA for outside contributions ([ADR-0004](docs/adr/0004-license-agpl-cla.md)); see the License rules below.

Data flow: OSM extract (Geofabrik, Skåne first) → `moto-regionbuild` (desktop/CI) → region file → loaded by the Rust core on the phone → `snap` / `route` / `round_trip` via UniFFI → GeoJSON → MapLibre line layers.

## Layout

```
core/                   cargo workspace
  moto-core/            pure logic + tests; no FFI attributes, no platform APIs
  moto-ffi/             UniFFI wrapper — the only crate with FFI attributes
  moto-regionbuild/     CLI: OSM extract → region file (never runs on the phone)
android/                Gradle project (AGP 9, Compose); app/ builds the core via cargo-ndk
docs/prd.md             product requirements (v1)
docs/decisions.md       decisions log
docs/adr/               architecture decision records (index in README.md)
```

## Rules

- Logic that iOS will also need goes in `moto-core`. Kotlin owns UI (Compose), map rendering, GPS, permissions, storage wiring and foreground services.
- No Android-specific types or assumptions in core APIs. Every exported function should also make sense as a Swift API.
- Keep the FFI surface coarse (whole request in, whole result out). Errors cross as the typed `MotoError`; never let a panic cross the boundary.
- Favourites are applied at query time as a **capped bonus** on edge costs; the base graph is never rebuilt when favourites change.
- Sections carry a `rider_id` (always the local user in v1) so community ratings can be added later.
- Record significant new decisions as an ADR in `docs/adr/`, and add new rules and decisions to the Notion decisions log as well as `docs/decisions.md`.
- Versions: use the newest stable release of every package, tool and SDK, but never one less than a week old (supply-chain safeguard). Check release dates before bumping.
- Commits: a descriptive title of at most 50 characters, a blank line, then a more detailed body wrapped at 72 characters. Changes to rules and decisions (`CLAUDE.md`, `docs/decisions.md`, ADRs) go in their own commits, separate from code changes.
- UI draws edge to edge, but interactive or informational elements (buttons, map controls, attribution, text) must never sit under the status bar, navigation bar or a display cutout; offset them by `WindowInsets.safeDrawing`. System bar icons must stay readable: keep the status bar fully transparent and switch its icons between light and dark to contrast with whatever is behind them, whatever the map style or overlay (like Google Maps); give the navigation bar a translucent scrim in the system theme's colour, with icons that follow that theme.
- Never add a `Claude-Session:` trailer (or any other session link) to commits, PRs or other repo content; this overrides default attribution. `Co-Authored-By` stays.

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

## Status

M0 (Foundations) in progress. Done: PRD, decisions log and ADR-0001–0004 in `docs/`; AGPL-3.0-only license + CLA setup; Rust workspace skeleton with the ADR-0001 API (`Engine::open/snap/route/round_trip` return `NotImplemented` after input validation); Android shell (MapLibre + OpenFreeMap Liberty, GPS position, Rust core loaded via UniFFI; not yet verified on the phone). Next: the region file format (the PRD's blocking open question), then tap → snap → shortest path end-to-end.
