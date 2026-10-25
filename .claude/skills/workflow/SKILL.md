---
name: workflow
description: The moto change pipeline — how the main session turns a feature, bug fix or refactor request into a merged change with agents (architect, mockup, implement, reviewer, documenter) and the verify.sh script, which model each risk tier and slice uses, the loop limits, the handoff report format and the two rider gates. Load at the start of any change; /feature, /bugfix and /refactor load it for you.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Change workflow

The main session is the **orchestrator**: it talks to the rider, picks the
tier, calls the agents in order, routes their reports and keeps its own
context small. It designs, codes and reviews nothing itself. The rider
only sees two things: the **design** (Gate 1) and the **result** (Gate 2).
Everything in between runs without asking. ADR-0013 set this up;
ADR-0019 made it leaner.

## Pipeline

```
request ─▶ architect ◀─▶ mockup (UI only) ─▶ [Gate 1: rider approves design]
  ─▶ for each slice: implement (+ verify.sh) ◀─▶ reviewer
  ─▶ documenter ─▶ verify.sh ─▶ push ─▶ CI ─▶ [Gate 2: rider tests the build]
```

| # | Stage | Who (default model, effort) | Does |
|---|---|---|---|
| 1 | Design | `architect` (Opus, high) | Spec page in Notion with slices, ADR when architectural, root cause for a bug of unknown cause |
| 2 | Mockup | `mockup` (Sonnet, medium) | Only when the UI brief isn't `none`; also takes the rider's wording and layout revisions |
| 3 | Build | `implement` (Sonnet, medium) | Tests first, code, commits, runs `verify.sh` and fixes what fails (never pushes) |
| 4 | Review and QA | `reviewer` (Sonnet, high) | Diff against spec, CLAUDE.md, security; every acceptance criterion with evidence; phone test list |
| 5 | Docs | `documenter` (Haiku, medium) | ADR ticks, PRD, Notion Milestones, decisions log, handoff Done |

Helpers any stage may use: `search` (Haiku), `ci` (Haiku), `notion` (Haiku).

The orchestrator reads an agent's diff only through the reviewer's report,
unless the report is unclear or the risk is `high`. The main session's
model is the rider's choice: when a session's work clearly fits another
one, say so once (`docs/working-with-claude.md` has the table).

The local checks are not an agent: `.claude/scripts/verify.sh` runs the
checks the change needs (fmt, clippy, tests, deny, the Android build,
script and guard tests) with CI's versions and prints one line per check.
Only it writes the stamp the pre-push hook requires, and only when all
passed. `setup-env.sh` beside it installs the toolchains once. The
golden routes and the benchmark run in CI on every build; locally only
the implementer runs the golden routes, for a scoring change (the
before/after table CLAUDE.md asks for).

## Spec

The spec lives in Notion as a child page `Spec: <slug>` of the Handoff
page (written by the architect; sections are in its prompt). Agents get
the **URL**, never the spec text: they fetch it themselves, so the main
session never holds it. When the change has an ADR, the ADR holds the
design and the spec only links it: one source, read once.

## Tiers and slices: route by risk

The architect sets `RISK` per slice; the orchestrator picks the models
from it (the Agent tool's `model` argument; effort is the agent's own).

| Risk | Examples | Architect | Implement | Review |
|---|---|---|---|---|
| `low` | strings, renames, copy, small layout tweaks, doc fixes | skipped: the orchestrator writes a 5-line spec page itself | Haiku | Sonnet |
| `normal` | everyday features, Compose UI, bug fixes with a clear cause | Opus | Sonnet | Sonnet |
| `high` | route scoring, region file or any untrusted-input parsing (backup, GPX, GeoJSON, downloads), FFI surface, schema, security, performance-sensitive code | Opus + ADR | Opus | Opus |

A change that mixes risks comes as **slices** (typically `core`: high,
then `app`: normal). Each slice runs implement ◀▶ reviewer on its own
tier and commit range, in the order the spec gives; the next slice starts
when the previous one passed review. So Opus builds and reviews only the
risky part, and the UI on top of it is Sonnet's.

When unsure, take the higher tier. A `low` change that turns out to touch
a `high` area is re-tiered.

## Entry points

- `/feature`: full pipeline. A new idea the rider mentions in passing is a
  discussion first (CLAUDE.md): don't start the pipeline until he says so.
- `/bugfix`: triage first. Cause known: spec with the reproducing test.
  Unknown: the architect finds it. No mockup unless the fix changes the
  screen. The first implementer commit is the failing test.
- `/refactor`: the spec fixes what must not change. CI's golden routes and
  benchmark must show it unchanged. No mockup.
- A chore with no design (a version bump, a doc edit) is `low`: spec page
  in two lines, then implement, review, push.

## Gates

**Gate 1, design.** After the architect (and mockup, when there is UI) the
orchestrator shows the rider: the approach in a few lines, the slices,
the acceptance criteria, the spec link, the ADR path and the mockup link.
The mockup is mandatory for UI changes and follows the `ui-rules` and
`mockups` skills. Build only after he says go.

His comments are routed by what they change:

- **Wording, strings, layout, colours, an icon:** to the `mockup` agent
  (page edit and republish, plus the matching UI brief edit). No architect.
- **The approach** (what the feature does, data, a new screen, a
  different trade-off): to the `architect`, with the comments verbatim;
  it changes only what they touch.

After he approves a design with UI, ask the `mockup` agent to move the
page's new components into the kit (one commit).

**Gate 2, result.** Before reporting a build, run
`bash .claude/scripts/ship-status.sh` (unpushed commits, ahead/behind
main, other branches, CI, APK link). After documenter and push, wait for
the build with the `ci` agent only when needed, then tell the rider: the
commit id, the direct APK link once published and working (the script
prints the pattern), what changed in a few lines, the reviewer's phone
test list (added to the Checklist) and anything left open. Say as soon as
a build is testable; don't wait longer than needed on new features. The
report is short: no recap of agents' reports, no list of every commit,
file or check, one line per finding that matters, and a repeated ping
gets one short line or nothing. He tests; his feedback starts a new
change.

**The Checklist** is the rider's on-phone checks: the artifact at
https://claude.ai/artifact/PRbfyH5ytv6rzAMbQWHyfX, his answers in its
`phonechecks` collection. After each build, add the new phone test steps
and remove the checks he has passed. Never publish a different page to
that artifact's URL.

Between the gates nothing waits for the rider, except a `BLOCKED` report
that needs his decision.

## Loop limits

After the limit, stop the loop and bring the rider a short summary of
what blocks (last findings, what was tried) with a recommendation.

| Loop | Limit |
|---|---|
| architect ◀▶ mockup (before showing the rider) | 2 rounds |
| implement's own verify.sh fix attempts | 3 runs, then `BLOCKED` |
| implement ◀▶ reviewer (`blocker`/`major` findings or a criterion not met), per slice | 2 rounds |
| rider revisions at Gate 1 | unlimited (his call), routed as above |

Round 2 of a review gets only the round-1 FINDINGS and the fix commits'
range: the reviewer checks those fixes and nothing else. The implementer
runs verify.sh again after its fixes, before the reviewer sees them.

## Handoff format

Every agent ends with one report block in this shape (fields vary by
stage, see each agent); the orchestrator reads only these:

```
STAGE: <name>
VERDICT: PASS | FAIL | BLOCKED
SPEC: <notion url>
SUMMARY / FINDINGS / ...: short, structured, with path:line
```

- `FAIL` goes back to the implementer with only the `FINDINGS` and the
  criteria not met, the spec URL, the slice and the commit range. Not the
  whole report history.
- `BLOCKED` carries a question; the orchestrator answers it from the spec
  and the rider's earlier decisions, or asks the rider.
- Never paste a stage's whole output into the next prompt; pass the URL,
  the slice, the commit range and the findings.
- Brief each agent fully: it starts with no context (request, tier,
  slice, spec URL, ADR path, commit range, decisions the rider made,
  what to return). CLAUDE.md is loaded into every agent already: don't
  tell agents to read it, and don't paste rules from it. Fill-in
  templates for each stage: `brief-template.md` beside this file.

## Pushing

The orchestrator runs `.claude/scripts/verify.sh 2>&1 | tail -40` (it
returns at once when the implementer's run already stamped this code),
then pushes (`git push -u origin claude/<name>`, or the session's `ccr-*`
branch) after every slice's review passed and the documenter's commits
exist. Hooks enforce the rules in `.githooks/` and `.claude/hooks/`:
pushes only to `claude/*` and `ccr-*`; no code push without a verify.sh
stamp for exactly that code (only the script writes it); commit title at
most 50 characters and body wrapped at 72; no session link; rule changes
(`CLAUDE.md`, ADRs) never in a commit with `android/`, `core/` or
`.github/` files; SPDX headers; no skipping hooks; no force push except
`--force-with-lease` to `claude/*` or `ccr-*`. If a hook blocks, fix the
cause.
CI's merge job merges to `main`; don't wait for it unless the rider needs
the build.
