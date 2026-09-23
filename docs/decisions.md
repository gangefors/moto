# Moto — Decisions log

Every product and architecture decision, newest last. Full records for architecture decisions are in [`adr/`](adr/); scope is in [`prd.md`](prd.md).

- **2026-09-22 — Platform:** native Android first (Kotlin, Android SDK). Shared, platform-independent logic in Rust so an iOS port can reuse it. iOS deferred.
- **2026-09-22 — Testing:** real-world tests on Stefan's Android phone. No iOS device yet.
- **2026-09-22 — Repo & tools:** code at [github.com/gangefors/moto](https://github.com/gangefors/moto). Repo work happens in Claude Code. Planning, specs and decisions happen in Cowork and are written to Notion; Notion is the hand-off point to Claude Code.
- **2026-09-22 — v1 scope:** personal MVP (single user, no accounts/community). Favourites captured via map selection, quick-tag while riding, and recorded rides. One-way and round-trip routes weighted by favourites + curvature. No built-in navigation: GPX export / hand-off to a nav app. No deadline; milestones M0–M4. See the [PRD](prd.md).
- **2026-09-22 — Routing engine:** custom Rust, on-device, in the shared core. Snapping to roads also in the Rust core. Region file (graph + curvature) built by a Rust CLI from an OSM extract; Skåne first, then Sweden. Favourites are capped query-time bonuses. → [ADR-0001](adr/0001-routing-engine-custom-rust-on-device.md)
- **2026-09-22 — Map widget:** MapLibre Native Android. One map view for picking, favourites and routes; no routing or snapping in the map. → [ADR-0002](adr/0002-map-widget-maplibre-native.md)
- **2026-09-22 — Map tiles:** OpenFreeMap; style URL in config; self-hosted PMTiles is the likely offline end state. → [ADR-0003](adr/0003-map-tiles-openfreemap.md)
- **2026-09-22 — License:** AGPL-3.0-only. Outside contributions require a CLA (right to relicense) so Stefan can monetise: paid apps, App Store publishing under his own terms, commercial licenses, paid sync service. App name/logo treated as trademarks. No app-store exception for now. Don't copy in third-party GPL/AGPL code. → [ADR-0004](adr/0004-license-agpl-cla.md)
- **2026-09-22 — Versions policy:** use the most recent stable version of every package, tool and SDK (Gradle, AGP, Kotlin, AndroidX, MapLibre, Android SDK/NDK, Rust toolchain, crates). Never adopt a release less than one week old, as a supply-chain safeguard; pick the newest release that is at least 7 days old.
- **2026-09-22 — Android build:** Gradle project in `android/` (AGP 9, Compose, minSdk 26, ABIs arm64-v8a + x86_64). Every Gradle build rebuilds the Rust core with cargo-ndk and regenerates the UniFFI Kotlin bindings, so the app always matches the core. `MotoError` crosses the FFI as a flat error: one exception class per variant, message only.
- **2026-09-22 — Commit messages:** a short, descriptive title of at most 50 characters, then a blank line and a body that describes the changes in more detail, wrapped at 72 characters. Changes to rules and decisions go in their own commits, separate from code changes. Commits carry no `Claude-Session:` trailer (session links stay out of the public history).
- **2026-09-23 — CI builds:** GitHub Actions builds and lints the Android app on every push and pull request touching `android/` or `core/`. Each push to `main` replaces the rolling `debug-latest` pre-release with the new debug APK, which is how builds reach the phone. A stable debug key comes from the `DEBUG_KEYSTORE_BASE64` secret. Actions are pinned by commit SHA.

**Open:** region file format / graph representation; long-route speed-up (plain A\* vs ALT vs CCH) decided after measuring in M2.
