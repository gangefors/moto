---
name: dev-commands
description: Build, test, lint, benchmark and golden-route commands for the moto repo (Rust core, region file, UniFFI bindings, Android app). Use before running local checks, building the app or a region file, running the benchmark or golden routes, generating Kotlin bindings, or measuring coverage.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Commands

Run the local checks with the Rust version CI uses (`RUST_VERSION` in
`.github/workflows/android.yml`); newer clippy versions add lints. Nothing
is pushed that fails them. Pipe long output through `tail`/`grep` so only
the result lands in the conversation.

```sh
cd core
cargo test --workspace
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo fmt --all
cargo deny --locked check   # advisories, licences, sources (deny.toml; cargo-deny 0.20.2)

# Region file and benchmark (CI compares --json output between builds).
# The Skåne region (the M0 region) for golden routes and the benchmark:
# download main's build from the skane-region release (CI publishes it
# when it is built again, about weekly), check it, then use it.
R=https://github.com/gangefors/moto/releases/download/skane-region
curl -fsSL --proto '=https' -O "$R/skane.region.gz" -O "$R/skane.region.gz.sha256"
sha256sum -c skane.region.gz.sha256 && gunzip skane.region.gz
cargo run --release -p moto-regionbuild -- --check skane.region
# Build it yourself only when your change touches the builder or the
# region format. The extract: download.geofabrik.de refuses Claude's
# sessions (never try it); use the same data from the openstreetmap.fr
# mirror:
# https://download.openstreetmap.fr/extracts/europe/sweden-latest.osm.pbf
cargo run --release -p moto-regionbuild -- sweden-latest.osm.pbf skane.region
# Derive curvature and built-up areas afresh for an existing region file,
# without the extract
cargo run --release -p moto-regionbuild -- --refresh skane.region skane-new.region
cargo run --release -p moto-regionbuild -- --check skane.region --json bench.json
python3 ../.github/scripts/bench_compare.py old.json bench.json
# Golden routes: route-quality regression set (run before and after every
# scoring change; see moto-core/tests/golden/README.md)
cargo run --release -p moto-regionbuild -- --golden skane.region moto-core/tests/golden --json golden.json
# Map matching on a real ride exported from the app (Rides → Export)
cargo run --release -p moto-regionbuild -- --match skane.region ride.gpx --geojson ride.geojson
python3 -m unittest discover -s ../.github/scripts -p 'test_*.py'
cargo llvm-cov --workspace --summary-only   # coverage (needs cargo-llvm-cov)

# Kotlin bindings (package se.gangefors.moto.core, see moto-ffi/uniffi.toml)
cargo build -p moto-ffi
cargo run -p moto-ffi --features cli --bin uniffi-bindgen -- \
  generate --library target/debug/libmoto_ffi.so --language kotlin --out-dir <dir>

# Android libs (needs cargo-ndk + ANDROID_NDK_HOME); Gradle runs this for you
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release -p moto-ffi

# Android app (needs ANDROID_HOME or android/local.properties, cargo-ndk,
# python3 and the aarch64/x86_64-linux-android Rust targets). preBuild runs
# cargo-ndk and generates the UniFFI bindings into app/build/generated/; the
# licence notices (.github/scripts/third_party.py) go into the assets.
cd ../android
./gradlew assembleDebug lintDebug testDebugUnitTest compileReleaseKotlin
```
