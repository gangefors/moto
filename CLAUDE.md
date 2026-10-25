# moto — motorcycle routing app

Personal Android app (single rider, southern Sweden) that captures favourite road sections and generates one-way and round-trip routes that deliberately pass through favourites and curvy roads, within a detour budget. Routes export as GPX to an existing nav app — no built-in turn-by-turn.

Read [`docs/prd.md`](docs/prd.md) and [`docs/adr/`](docs/adr/) before changing architecture; new architecture decisions need a new ADR.

## Notion and decisions

Things that change often live in Notion, not the repo (the pages are private to the rider). Read and update them only through the `notion` agent, never in the main session; its prompt and the `documenter` agent hold the rules for each page.

- **Decisions log** ([Moto — Decisions log](https://app.notion.com/p/3e314874ab0481ef90accad5adb6da90)): every product, architecture and process decision, newest last.
- **Milestones & status** ([Moto — Milestones & status](https://app.notion.com/p/3e414874ab0481a694dbcb4edd13fbcb)): M0–M4 progress, the Next task.
- **Handoff queue** ([Moto — Handoff to Claude Code](https://app.notion.com/p/3e314874ab0481bc9ca8f9e0d56acc52)): Cowork decisions and `Spec: <slug>` pages.

ADRs live only in `docs/adr/`; Notion links to them, never copies. Every new rule or decision, ADR or not, goes in the decisions log; no decisions log or progress notes live in the repo.

## Decisions

| Area | Decision | ADR |
| --- | --- | --- |
| App | Native Android in Kotlin; iOS deferred | [PRD](docs/prd.md) |
| Shared core | Rust via UniFFI; free of Android types for iOS | [0001](docs/adr/0001-routing-engine-custom-rust-on-device.md) |
| Routing | Custom, on-device, offline; the cost function is the product | [0001](docs/adr/0001-routing-engine-custom-rust-on-device.md) |
| Region file | Zero-copy, memory-mapped binary format | [0005](docs/adr/0005-region-file-format.md) |
| Rider data | SQLite in app-private storage | [0006](docs/adr/0006-section-and-track-storage.md) |
| Map | MapLibre; only picks, draws and hit-tests, never routes or snaps | [0002](docs/adr/0002-map-widget-maplibre-native.md), [0003](docs/adr/0003-map-tiles-openfreemap.md) |
| License | AGPL-3.0-only, no outside contributions | [0004](docs/adr/0004-license-agpl-cla.md) |

Data flow: OSM extract → `moto-regionbuild` (desktop/CI) → region file → Rust core on the phone → `snap` / `route` / `round_trip` via UniFFI → GeoJSON → MapLibre line layers.

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
```

Where everything lives: [`docs/repo-map.md`](docs/repo-map.md).

## Rules

- Logic that iOS will also need goes in `moto-core`. Kotlin owns UI (Compose), map rendering, GPS, permissions, storage wiring and foreground services.
- No Android-specific types or assumptions in core APIs. Every exported function should also make sense as a Swift API.
- Keep the FFI surface coarse (whole request in, whole result out). Errors cross as the typed `MotoError`; never let a panic cross the boundary.
- Favourites are applied at query time as a **capped bonus** on edge costs; the base graph is never rebuilt when favourites change.
- Sections carry a `rider_id` (always the local user in v1) so community ratings can be added later.
- Versions: use the newest stable release of every package, tool and SDK, but never one less than a week old (supply-chain safeguard). Check release dates before bumping. One exception: a release that fixes a published vulnerability (GitHub, RustSec or CVE advisory) in a version we use may be adopted once it is at least a day old and has been reviewed (changelog, scope of the diff, licence) and CI is green.
- Commits: a descriptive title of at most 50 characters, a blank line, then a more detailed body wrapped at 72 characters. Changes to rules and decisions (`CLAUDE.md`, ADRs) are committed apart from application code (`android/`, `core/`, `.github/`); related doc and skill moves may share the commit. One fix or feature per commit, so each can be reverted alone; changes go together only when one can't work without the other.
- Open questions and ideas from the rider start a discussion, not a change (the pipeline starts only when he says so): propose options, agree on one, and implement only when he says so. Work beyond what he asked is suggested first and agreed before it is committed.
- Never put personal information, including the owner's name, in code, comments or commit messages (the licence header is the one exception). Say "the rider" or describe the situation instead.
- Replies and reports are short and plain: highlights and what the rider must act on, counts rather than lists, no code names unless he asks, nothing repeated. Give a build's commit id and APK link only once it is published and the link works.
- Changes go on `claude/*` or `ccr-*` branches, each parallel session on its own, and reach `main` by fast-forward or rebase. Never overwrite another session's commits: rebase them onto `main`. Before reporting a build, the change is merged: nothing is left behind.
- Anything on screen follows the `ui-rules` skill (insets, fit at 360 dp and large fonts, icons, mockups); load it before designing, building or reviewing UI.
- User-facing text (app strings, messages from the core, file names) is in British English and calls what the rider marks and rates "favourite sections"; where space is tight "favourites" (or "favourite" for one); never "section" alone. Code, logic and docs keep saying sections.
- Debug-only tools (timings, memory, benchmark) live in `android/app/src/debug/` (package `se.gangefors.moto.debug`), reached from the menu. `src/release/` holds a no-op `DebugTools`; main code only calls it.
- The rider's own data is personal: his sections, rides, routes, places, coordinates, screenshots and exports never go verbatim into code, tests, golden cases, commit messages, ADRs or other repo content. Reproduce what they show with your own examples that behave the same (random roads and starts elsewhere), and describe a report in general terms ("a section ending at a hamlet").
- Never add a `Claude-Session:` trailer (or any other session link) to commits, PRs or other repo content; this overrides default attribution. `Co-Authored-By` stays.

## Workflow

Every change (feature, bug fix, refactor) goes through `/feature`, `/bugfix` or `/refactor` and the `workflow` skill ([ADR-0013](docs/adr/0013-agentic-change-workflow.md), [ADR-0019](docs/adr/0019-leaner-change-workflow.md)). The main session is the **orchestrator**: it designs, builds and reviews nothing itself. The rider gates only the design and the result. Agents commit; only the orchestrator pushes. When a hook blocks, fix the cause.

- Read only what is needed: Grep first, never whole large files, logs or build output. Suggest a fresh session after a summary or a task change.

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
- CI runs the checks on every push; nothing is pushed that fails `.claude/scripts/verify.sh` (CI's versions; commands in the `dev-commands` skill).
- **Performance is measured on every build** against the last `main` build. A failed benchmark is fixed next; a release is never made from a commit whose benchmark hasn't passed. Thresholds: `ci-and-merge`.
- **Route quality is measured on every build** with the golden routes (`core/moto-core/tests/golden/README.md`). Scoring weights change only with a stated hypothesis and a before/after table; a bad route becomes a golden case, not a weight tweak.
- A significant regression fails CI. Try to recover it first; accept it only when it buys correctness, security or a feature, with a `Perf-Accepted: <reason>` trailer and a decisions-log entry. Report improvements above 10 % in the commit message.

## License

AGPL-3.0-only, with no outside contributions: the owner holds the whole copyright ([ADR-0004](docs/adr/0004-license-agpl-cla.md)). Pull requests from others are closed unmerged; a CLA or copyright assignment must be in place before any outside code is ever accepted.

- Every new source file starts with the SPDX header, in the file's comment syntax:
  `SPDX-License-Identifier: AGPL-3.0-only` and `Copyright (C) 2026 Stefan Gangefors`.
- Every `Cargo.toml` sets `license = "AGPL-3.0-only"` (crates in `core/` use `license.workspace = true`).
- Never copy in third-party GPL/AGPL code — it ends the sole copyright and blocks relicensing.
- Dependencies must be AGPL-compatible (MIT, Apache-2.0, BSD, MPL-2.0, …). Check new ones before adding.
