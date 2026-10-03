# moto — motorcycle routing app

Personal Android app (single rider, southern Sweden) that captures favourite road sections and generates one-way and round-trip routes that deliberately pass through favourites and curvy roads, within a detour budget. Routes export as GPX to an existing nav app — no built-in turn-by-turn.

Read [`docs/prd.md`](docs/prd.md) and [`docs/adr/`](docs/adr/) before changing architecture; new architecture decisions need a new ADR.

Things that change often live in Notion, not in the repo (use the Notion tools; the pages are private to the rider):

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
- **License:** AGPL-3.0-only, no outside contributions, so the owner holds the whole copyright ([ADR-0004](docs/adr/0004-license-agpl-cla.md)); see the License rules below.

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
docs/mockups/           shared mockup kit (CSS, map backgrounds, template)
.claude/skills/         skills: commands, CI and merging, mockups
```

## Rules

- Logic that iOS will also need goes in `moto-core`. Kotlin owns UI (Compose), map rendering, GPS, permissions, storage wiring and foreground services.
- No Android-specific types or assumptions in core APIs. Every exported function should also make sense as a Swift API.
- Keep the FFI surface coarse (whole request in, whole result out). Errors cross as the typed `MotoError`; never let a panic cross the boundary.
- Favourites are applied at query time as a **capped bonus** on edge costs; the base graph is never rebuilt when favourites change.
- Sections carry a `rider_id` (always the local user in v1) so community ratings can be added later.
- Record significant new architecture decisions as an ADR in `docs/adr/`, and keep each ADR's action items up to date as work lands. ADRs live only in the repo: Notion pages link to the files on GitHub and never hold copies. Add every new rule or decision (ADR or not) to the Notion decisions log; don't keep a decisions log or progress notes in the repo.
- Versions: use the newest stable release of every package, tool and SDK, but never one less than a week old (supply-chain safeguard). Check release dates before bumping. One exception: a release that fixes a published vulnerability (GitHub, RustSec or CVE advisory) in a version we use may be adopted once it is at least a day old and has been reviewed (changelog, scope of the diff, licence) and CI is green.
- Commits: a descriptive title of at most 50 characters, a blank line, then a more detailed body wrapped at 72 characters. Changes to rules and decisions (`CLAUDE.md`, ADRs) go in their own commits, separate from code changes. One fix or feature per commit, so each can be reverted alone; changes go together only when one can't work without the other.
- Mockups look as close to the real app as possible, never ASCII sketches: an HTML page published as an artifact, with phones at real size (360 dp wide), the app's own Material 3 colours (light and dark), Roboto, components at their real dp sizes, strings from `strings.xml` and icons from `res/drawable`, built from the kit in `docs/mockups/`. Mark what changes, and show the current screen beside the proposal when something moves. The `mockups` skill has the details.
- Open questions and ideas from the rider start a discussion, not a change: propose options, agree on one, and implement only when he says so. Work beyond what he asked is suggested first and agreed before it is committed.
- Work that should be published is pushed to the workspace's own branch, never straight to `main` (no PR unless asked). CI merges it: once core, benchmark and app build pass on a Claude work branch, the `merge` job fast-forwards `main` to it and starts main's CI, so never wait, poll or schedule check-ins to merge. Merge by hand only where the job can't: a push with no CI run (merge right away, if `origin/main..HEAD` changes no code), a change to `.github/workflows/` (once CI is green on the newest commit), or when `main` has moved on (bring it into the branch and push; CI merges when green). Every `main` build is a test release (`debug-latest`); every build of a Claude work branch (`ccr-*`, `claude/*`, the patterns the `debug-signing` environment allows) is published as `debug-branch` once the app builds and its tests pass, before the benchmark, so the rider can test it about 6 minutes after the push; both are signed with the debug key and install over each other. Tags get proper releases. The `ci-and-merge` skill has the details.
- UI draws edge to edge, but interactive or informational elements (buttons, map controls, attribution, text) must never sit under the status bar, navigation bar or a display cutout; offset them by `WindowInsets.safeDrawing`. System bar icons must stay readable: keep the status bar fully transparent and switch its icons between light and dark to contrast with whatever is behind them, whatever the map style or overlay (like Google Maps); give the navigation bar a translucent scrim in the app theme's colour (light or dark: it follows the phone until the rider picks one in the menu), with icons that follow that theme; where an app panel (like the planning sheet) reaches down behind the bar, its icons contrast with that panel instead.
- User-facing text (app strings, messages from the core, file names) is in British English and calls what the rider marks and rates "favourite sections"; where space is tight "favourites" (or "favourite" for one); never "section" alone. Code, logic and docs keep saying sections.
- The how-to page shows a button's own icon inline wherever its text refers to a button or other on-screen control, so the rider can find it. Elsewhere, add an icon to text only where it helps in that spot (e.g. pointing at a button on the same card).
- Delete and remove actions use the bin icon (`ic_delete`, in `DELETE_COLOR`; `DeleteButton` where a second tap confirms), never a text-only button. Clearing a single value (like an arrival time) is an X right beside it.
- UI elements must fit or scale, never squeeze: rows of buttons wrap onto another line (`FlowRow`) when there is no room, button labels stay on one line, and every screen, sheet and banner must work at 360 dp width and with large system font sizes.
- Debug-only tools (timings, memory, benchmark) live in `android/app/src/debug/` (package `se.gangefors.moto.debug`), reached from the menu → Debug tools; `src/release/` holds a do-nothing `DebugTools` with the same functions. Main code only calls `DebugTools`, so release builds carry none of it and it is easy to remove.
- The rider's own data is personal: his sections, rides, routes, places, coordinates, screenshots and exports never go verbatim into code, tests, golden cases, commit messages, ADRs or other repo content. Reproduce what they show with your own examples that behave the same (random roads and starts elsewhere), and describe a report in general terms ("a section ending at a hamlet").
- Never add a `Claude-Session:` trailer (or any other session link) to commits, PRs or other repo content; this overrides default attribution. `Co-Authored-By` stays.

## Security

Security comes first: before performance, features and convenience. Never choose an insecure implementation because it is faster, simpler or quicker to ship; find a secure one or stop and raise it with the rider.

- Treat every input from outside the code as hostile: region files and other downloads, OSM data, GPX and other imports, network responses, intents and other apps, and user input. Validate it before use and reject it with a typed error. Malformed input must never crash, panic, read out of bounds or cause undefined behaviour.
- Nothing may let an attacker run code or act as the app: no dynamic code loading, no eval or scripting of external data, no deserialising into arbitrary types, no shell commands built from input, no WebView JavaScript bridges, no file paths taken from input without normalising them against app storage (no path traversal).
- Rust: no `unsafe` beyond what is unavoidable (today only creating the memory map). Every `unsafe` block carries a `SAFETY:` comment that says why it is sound. Data read zero-copy is validated before any code indexes it (or proven by its fingerprint to be a file that was, ADR-0005); use checked arithmetic and `get` on untrusted numbers.
- Android: least privilege. Request only the permissions a feature needs, export no components except the launcher activity, allow no cleartext traffic, keep personal data (rides, locations, favourites) in app-private storage and on the device unless the rider opts in. No secrets in the repository or the APK.
- Downloads go over HTTPS only and are verified (checksum or signature, then structure) before they are installed or opened.
- Supply chain: keep dependencies few, AGPL-compatible and at least a week old (security fixes: a day, see Versions); check a new dependency's maintenance and `unsafe` use before adding it; pin GitHub Actions by commit SHA; CI tokens get the least permissions they need.
- Vulnerability reports follow [`SECURITY.md`](SECURITY.md).

## Testing and performance

- **Tests come with every change.** Everything that can sensibly be tested is: all core logic in Rust unit tests, including error paths and malformed input; pure app logic in Kotlin unit tests (move logic out of Android classes so it can be tested); a bug fix starts with a test that reproduces it. Code that parses untrusted input also gets corruption tests that prove it never panics.
- CI runs `cargo fmt --check`, clippy, `cargo test --workspace`, `cargo deny check`, the Gradle build, lint and unit tests on every push, and nothing is pushed that fails them locally. Run the local checks with the Rust version CI uses (`RUST_VERSION` in `.github/workflows/android.yml`); newer clippy versions add lints. The commands are in the `dev-commands` skill.
- **When CI builds.** Only for pushes that change code in `android/` or `core/` (or `.github/scripts/third_party.py`), never for Markdown alone. Push each fix as soon as the local checks pass; a newer push cancels the run still going. Timings and details: the `ci-and-merge` skill.
- **Performance is measured on every build.** CI benchmarks each build (region open and verify, snapping, routing on the Skåne region) against the last `main` build's binary on the same machine, beside the app build so `debug-latest` doesn't wait. A failed benchmark turns CI red and is fixed next; a release is never made from a commit whose benchmark hasn't passed.
- **Route quality is measured on every build.** CI runs the golden routes (`core/moto-core/tests/golden/`) against the last `main` build; a route that breaks its expectations fails CI. Change scoring weights only with a stated hypothesis and that before/after table, and add a golden case for every bad route found on a real ride instead of tuning weights to one route.
- A significant regression fails CI: more than 25 % slower for snapping and routing, or more than 50 % and 5 ms slower for the short, memory- and disk-bound region verify and open timings (each binary runs twice; each metric keeps its faster result). Re-evaluate the implementation and try to recover the loss first. Accept a regression only when it buys something worth it (correctness, security, a feature), with a `Perf-Accepted: <reason>` trailer in the commit message and an entry in the decisions log. Improvements of more than 10 % are reported too; note them in the commit message.
- Security beats performance: never accept an insecure change to win back speed.

## License

AGPL-3.0-only, with no outside contributions: the owner holds the whole copyright ([ADR-0004](docs/adr/0004-license-agpl-cla.md)). Pull requests from others are closed unmerged; a CLA or copyright assignment must be in place before any outside code is ever accepted.

- Every new source file starts with the SPDX header, in the file's comment syntax:
  `SPDX-License-Identifier: AGPL-3.0-only` and `Copyright (C) 2026 Stefan Gangefors`.
- Every `Cargo.toml` sets `license = "AGPL-3.0-only"` (crates in `core/` use `license.workspace = true`).
- Never copy in third-party GPL/AGPL code — it ends the sole copyright and blocks relicensing.
- Dependencies must be AGPL-compatible (MIT, Apache-2.0, BSD, MPL-2.0, …). Check new ones before adding.
