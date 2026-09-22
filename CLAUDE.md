# moto — motorcycle routing app

Personal Android app (single rider, southern Sweden) that captures favourite road sections and generates one-way and round-trip routes that deliberately pass through favourites and curvy roads, within a detour budget. Routes export as GPX to an existing nav app — no built-in turn-by-turn.

Source of truth for scope: the PRD "Moto routing app — v1 (personal MVP)" in Notion. Decisions: `docs/adr/`. Read both before proposing architecture.

## Architecture (accepted ADRs)

- **Routing engine:** custom Rust, on-device, offline ([ADR-0001](docs/adr/0001-routing-engine-custom-rust-on-device.md)). The cost function (curvature + favourites) is the product.
- **Map widget:** MapLibre Native Android ([ADR-0002](docs/adr/0002-map-widget-maplibre-native.md)). The map only picks, draws and hit-tests; it never routes or snaps.
- **Map tiles:** OpenFreeMap, Liberty style, URL in config ([ADR-0003](docs/adr/0003-map-tiles-openfreemap.md)). Attribution must be visible.

Data flow: OSM extract (Geofabrik, Skåne first) → `moto-regionbuild` (desktop/CI) → region file → loaded by the Rust core on the phone → `snap` / `route` / `round_trip` via UniFFI → GeoJSON → MapLibre line layers.

## Layout

```
core/                   cargo workspace
  moto-core/            pure logic + tests; no FFI attributes, no platform APIs
  moto-ffi/             UniFFI wrapper — the only crate with FFI attributes
  moto-regionbuild/     CLI: OSM extract → region file (never runs on the phone)
android/                Gradle project (not yet created — next M0 step)
docs/adr/               architecture decision records
```

## Rules

- Logic that iOS will also need goes in `moto-core`. Kotlin owns UI (Compose), map rendering, GPS, permissions, storage wiring and foreground services.
- No Android-specific types or assumptions in core APIs. Every exported function should also make sense as a Swift API.
- Keep the FFI surface coarse (whole request in, whole result out). Errors cross as the typed `MotoError`; never let a panic cross the boundary.
- Favourites are applied at query time as a **capped bonus** on edge costs; the base graph is never rebuilt when favourites change.
- Sections carry a `rider_id` (always the local user in v1) so community ratings can be added later.
- Record significant new decisions as an ADR in `docs/adr/`.

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

# Android libs (needs cargo-ndk + ANDROID_NDK_HOME)
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release -p moto-ffi
```

## Status

M0 (Foundations) in progress. Done: ADRs committed, Rust workspace skeleton with the ADR-0001 API (`Engine::open/snap/route/round_trip` return `NotImplemented` after input validation). Next: Android shell with MapLibre + OpenFreeMap, then the region file format (the PRD's blocking open question), then tap → snap → shortest path end-to-end.
