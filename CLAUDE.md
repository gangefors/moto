# moto — motorcycle routing app

Personal Android app (single rider, southern Sweden) that captures favourite road sections and generates one-way and round-trip routes that deliberately pass through favourites and curvy roads, within a detour budget. Routes export as GPX to an existing nav app — no built-in turn-by-turn.

Read [`docs/prd.md`](docs/prd.md) and [`docs/adr/`](docs/adr/) before changing architecture; new architecture decisions need a new ADR.

Things that change often live in Notion, not in the repo (use the Notion tools; the pages are private to the rider):

- **Decisions log** ([Moto — Decisions log](https://app.notion.com/p/3e314874ab0481ef90accad5adb6da90)): every product, architecture and process decision, newest last. It is the only log; there is no copy in `docs/`.
- **Milestones & status** ([Moto — Milestones & status](https://app.notion.com/p/3e414874ab0481a694dbcb4edd13fbcb)): M0–M4 progress, the next task, and small carry-over items. Read it at the start of a task; update it when work lands (tick items, add commit links, set **Next**).
- **Handoff queue** ([Moto — Handoff to Claude Code](https://app.notion.com/p/3e314874ab0481bc9ca8f9e0d56acc52)): work decided in Cowork, and the `Spec: <slug>` pages of the change workflow. Do the items under Pending (through the workflow) and move them to Done with their commit links.

The pages are large: read and update them through the `notion` agent (see Models and subagents), never in the main session.

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
docs/working-with-claude.md   models and session habits that save usage
.claude/agents/         subagents, each pinning its model and effort
.claude/skills/         skills: workflow (/feature, /bugfix, /refactor), commands, CI and merging, UI rules, mockups
.claude/hooks/          Claude Code hooks (guards); settings in .claude/settings.json
.claude/scripts/        verify.sh (local checks, writes the verify stamp), setup-env.sh
.githooks/              git hooks: commit-msg, pre-commit, pre-push
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
- Open questions and ideas from the rider start a discussion, not a change (the pipeline starts only when he says so): propose options, agree on one, and implement only when he says so. Work beyond what he asked is suggested first and agreed before it is committed.
- Never put personal information, including the owner's name, in code, comments or commit messages (the licence header is the one exception). Say "the rider" or describe the situation instead.
- Keep replies short and to the point, but with enough context. Don't wait longer than needed on new features, and say as soon as a build is available to test, with its commit id and the direct APK link, given only once the build is published and the link works, never before (`https://github.com/gangefors/moto/releases/download/<tag>/moto-debug-<short sha>.apk`, tag `debug-branch` or `debug-latest`).
- Reports to the rider are short: highlights and decisions only, with what he needs to act on (the result, the build link, what to test or decide). Don't recap agents' reports or list every commit, file or check: give counts, one line per finding that matters, and the link. Use plain words, not code jargon (say "the map view", not "camera"; no function or file names unless he asks). Don't repeat earlier answers: a repeated stop-hook or notification ping gets one short line or nothing.
- The rider's on-phone checks live in the **Checklist** artifact (https://claude.ai/artifact/PRbfyH5ytv6rzAMbQWHyfX; answers sit in its `phonechecks` collection). After each build, add the new phone test steps and remove the checks he has passed. Never publish a different page to an existing artifact's URL.
- Make changes on branches named `claude/*` or `ccr-*` (cloud sessions create `ccr-*`; the hooks and CI treat both alike), and merge to `main` as soon as the build passes, without waiting for the benchmark.
- Getting commits onto main: prefer a fast-forward; if main moved on, rebase the branch onto it (a `--force-with-lease` push to the `claude/*` or `ccr-*` branch is allowed); merge commits are the last resort.
- Nothing is left behind and nothing is thrown away. A change is not finished until the merge job has fast-forwarded its branch into `main`: when the CI check reports "Not merged" (main moved on), rebase onto the newest `main`, run the checks again and send it again, until it merges, before reporting a build to the rider (a build that was not merged replaces the `debug-branch` download without `main`'s newer changes). Before sending, compare the branch with `main` both ways (`git log main..branch`, `git log branch..main`) and list the other `claude/*` and `ccr-*` branches that are ahead of `main`. Never overwrite another session's commits: rebase them onto `main`. Parallel work uses its own branch, never one that another session already uses.
- Anything on screen (Compose UI, layouts, mockups, the how-to page) follows the `ui-rules` skill: insets and system bars, fitting at 360 dp and large fonts, the bin icon, icons in text, mockup fidelity. Load it before designing, building or reviewing UI.
- User-facing text (app strings, messages from the core, file names) is in British English and calls what the rider marks and rates "favourite sections"; where space is tight "favourites" (or "favourite" for one); never "section" alone. Code, logic and docs keep saying sections.
- Debug-only tools (timings, memory, benchmark) live in `android/app/src/debug/` (package `se.gangefors.moto.debug`), reached from the menu → Debug tools; `src/release/` holds a do-nothing `DebugTools` with the same functions. Main code only calls `DebugTools`, so release builds carry none of it and it is easy to remove.
- The rider's own data is personal: his sections, rides, routes, places, coordinates, screenshots and exports never go verbatim into code, tests, golden cases, commit messages, ADRs or other repo content. Reproduce what they show with your own examples that behave the same (random roads and starts elsewhere), and describe a report in general terms ("a section ending at a hamlet").
- Never add a `Claude-Session:` trailer (or any other session link) to commits, PRs or other repo content; this overrides default attribution. `Co-Authored-By` stays.

## Workflow and subagents

Every change (feature, bug fix, refactor) runs through the agent pipeline of [ADR-0013](docs/adr/0013-agentic-change-workflow.md) as made leaner by [ADR-0019](docs/adr/0019-leaner-change-workflow.md). The `workflow` skill holds the details (tiers, slices, loop limits, report format) and is started with `/feature`, `/bugfix` or `/refactor`. The main session is the **orchestrator**: it talks to the rider, picks the tier, calls the agents, routes their reports and pushes; it designs, builds and reviews nothing itself.

```
request → architect ◀▶ mockup (UI only) → [Gate 1: rider approves the design]
  → per slice: implement (runs verify.sh) ◀▶ reviewer (also checks the criteria)
  → documenter → push → CI → [Gate 2: rider tests the build]
```

- **Two gates only:** the design (spec summary and criteria, plus the mockup for any UI change) and the result. Build nothing before Gate 1; between the gates only a `BLOCKED` question waits for the rider. Ideas and open questions are still a discussion first (see Rules).
- **Specs live in Notion** (`Spec: <slug>` under the Handoff queue); agents get the URL and fetch it. When a change has an ADR, the ADR holds the design and the spec only criteria, tests, slices, UI brief and the ADR link.
- **Route by risk, per slice:** `low` (strings, renames, copy) Haiku builds, no architect; `normal` Opus designs, Sonnet builds and reviews; `high` (route scoring, untrusted-input parsing, FFI, schema, security, performance-sensitive code) Opus throughout, with an ADR. A change mixing risks is split into slices (the core work `high`, the UI on top `normal`). Unsure means the higher tier.
- **Agents** (`.claude/agents/`, each pins its model): `architect`, `mockup`, `implement`, `reviewer`, `documenter`; helpers `notion` (only reader of the big Notion pages), `ci` (only reader of CI logs) and `search`. Each ends with a short report block; pass on the spec URL, the commit range and the findings, never whole reports. Brief every agent fully: it starts with no context.
- **Local checks are a script:** `.claude/scripts/verify.sh` runs what the change needs with CI's versions and, only when all pass, writes the stamp that pushing code requires; `.claude/scripts/setup-env.sh` installs the toolchains once.
- **Hooks enforce the hard rules** (`.githooks/`, `.claude/hooks/`, `.claude/settings.json`; a session-start hook switches the git hooks on): commit title at most 50 and body at 72, no session link, `CLAUDE.md` and ADRs in their own commits, SPDX headers, pushes to `claude/*` and `ccr-*` only, no code push without a `verify.sh` pass for exactly that code (only the script writes the stamp), no skipping hooks, no force push except `--force-with-lease` to an explicit `claude/*` or `ccr-*` branch on origin (every other force push, deletion or push to any other branch is blocked). When a hook blocks, fix the cause.
- The agents commit, only the orchestrator pushes. Review an agent's diff only through the `reviewer`'s report unless the report is unclear or risk is `high`.
- The main session's model is the rider's choice; when a session's work clearly fits another one, say so once (`docs/working-with-claude.md` has the table).

Keeping the main session small:

- Read only what is needed: Grep first, then the lines around a hit; never whole large files, build output or artifacts. Don't re-read a file you just wrote.
- Pipe long command output through `tail` or `grep`.
- Don't schedule wake-ups to wait for CI (the merge job merges); check CI with the `ci` agent only when the next step needs the result.
- When a session has been summarised once, or the task changes, suggest the rider starts a fresh session.

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
- CI runs `cargo fmt --check`, clippy, `cargo test --workspace`, `cargo deny check`, the Gradle build, lint and unit tests on every push, and nothing is pushed that fails them locally (`.claude/scripts/verify.sh` runs them; the pre-push hook checks its stamp). Run the local checks with the Rust version CI uses (`RUST_VERSION` in `.github/workflows/android.yml`); newer clippy versions add lints. The commands are in the `dev-commands` skill.
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
