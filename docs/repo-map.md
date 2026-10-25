<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Repo map

Where things live, so a session doesn't have to search for them. Paths are
relative to the repo root; Kotlin files are in
`android/app/src/main/java/se/gangefors/moto/` (shortened to `app/` below).

## Android app

| What | Where |
|---|---|
| Map screen (state, setup, camera, gestures, overlays) | `app/MapScreen.kt`, `MapScreenState.kt`, `MapSetup.kt`, `MapCamera.kt`, `MapGestures.kt`, `RouteOverlay.kt`, `SectionOverlay.kt` |
| Map dialogs (about, route name, ...) | `app/MapDialogs.kt`, `AboutDialog.kt` |
| Cards and sheets on the map | `app/MapCards.kt`, `RouteCard.kt`, `RoadInfoCard.kt`, `ShownRideCard.kt`, `PlanSheet.kt`, `SectionSheet.kt`, `RidesSheet.kt` |
| Pages (full screen) | `app/SectionsPage.kt`, `RideSettings.kt`, `RideScreen.kt`, `HelpPage.kt`, `FullPage.kt` |
| Menu | `app/MenuDrawer.kt` |
| Pure logic with Kotlin tests | `app/*Logic.kt`, tests in `android/app/src/test/java/se/gangefors/moto/` |
| Strings (British English) | `android/app/src/main/res/values/strings.xml`; config (style URL) in `values/config.xml` |
| How-to page content and text | `app/HelpContent.kt` (topics, keys), `HelpText.kt`, `HelpPage.kt`; strings `help_*` in `strings.xml` |
| Debug-only tools | `android/app/src/debug/java/se/gangefors/moto/debug/`; no-op twin in `android/app/src/release/` |
| Android tests of debug code | `android/app/src/testDebug/` |

## Rust core

| What | Where |
|---|---|
| Pure logic (graph, scoring, favourites, GPX, region file, backup) | `core/moto-core/src/` (`engine.rs`, `scoring.rs`, `net.rs`, `favourites.rs`, `gpx.rs`, `region/`, `backup/`); tests beside the code in `<module>/tests.rs` |
| FFI entry points (UniFFI, `#[uniffi::export]`) | `core/moto-ffi/src/` (`routes.rs`, `sections.rs`, `tracks.rs`, `regions.rs`, `follow.rs`, `exchange.rs`, `backup.rs`, `road.rs`, `tags.rs`, `surface.rs`); bindgen in `src/bin/uniffi-bindgen.rs` |
| Region builder, benchmark, golden runner | `core/moto-regionbuild/src/` (`main.rs`, `bench.rs`, `golden.rs`) |
| Golden route cases | `core/moto-core/tests/golden/*.json` (+ `README.md`), border cases in `core/moto-core/tests/golden-border/` |
| Dependency policy | `core/deny.toml` |

## CI and checks

| What | Where |
|---|---|
| Workflows | `.github/workflows/` (`android.yml` pins `RUST_VERSION`; `regions.yml`, `merge-docs.yml`, ...) |
| CI helper scripts and their tests | `.github/scripts/` (`golden_compare.py`, `bench_compare.py`, `fast_forward.py`, `test_*.py`) |
| Local checks and stamp | `.claude/scripts/verify.sh`; toolchains: `setup-env.sh`; branch facts before a report: `ship-status.sh` |
| Git hooks | `.githooks/` |

## Agents, skills, hooks, docs

| What | Where |
|---|---|
| Agents | `.claude/agents/` (`architect`, `mockup`, `implement`, `reviewer`, `documenter`, helpers `notion`, `ci`, `search`) |
| Skills | `.claude/skills/<name>/SKILL.md`: `workflow`, `feature`, `bugfix`, `refactor`, `dev-commands`, `ci-and-merge`, `ui-rules`, `mockups` |
| Guards and their tests | `.claude/hooks/` (`guard-bash.sh`, `guard-push.py`, `guard-text.sh`, `test-*.sh`); settings in `.claude/settings.json` |
| Product, decisions | `docs/prd.md`, `docs/adr/` (index in `README.md`); mockup kit in `docs/mockups/` |

## Gotchas

- Cloud clones may be shallow: the session-start hook deepens them; if
  `origin/main` is missing, run `git fetch origin main` first (verify.sh
  needs it).
- Run `git push` as its own simple command: no `&&`, pipes, redirects or
  variables, branch `claude/*` or `ccr-*` only.
- Commit with `git commit -F - <<'EOF'` (optionally after `git add ... &&`)
  and nothing after the heredoc; the guard reads other shapes in full.
- The guards block `chmod`, `sed -i`, `tee` and the like on
  `.claude/hooks`, `.claude/scripts`, `.githooks` and `.claude/settings.json`
  from Bash; use the Edit/Write tools for those files.
- Only `verify.sh` writes the verify stamp; pushing code needs a pass for
  exactly that code.
- Rule changes (`CLAUDE.md`, ADRs) and application code (`android/`,
  `core/`, `.github/`) go in separate commits; docs and `.claude/` may
  share a commit with rule changes.
- Notion pages are large: read them through the `notion` agent.
- Geofabrik refuses the sessions; the Skåne region comes from the
  `skane-region` release (see the `dev-commands` skill).
