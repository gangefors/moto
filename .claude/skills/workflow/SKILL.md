---
name: workflow
description: The moto change pipeline — how the main session turns a feature, bug fix or refactor request into a merged change with agents (architect, mockup, implement, verify, reviewer, qa, documenter), which model each risk tier uses, the loop limits, the handoff report format and the two rider gates. Load at the start of any change; /feature, /bugfix and /refactor load it for you.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Change workflow

The main session is the **orchestrator**: it talks to the rider, picks the
tier, calls the agents in order, routes their reports and keeps its own
context small. It designs, codes and reviews nothing itself. The rider
only sees two things: the **design** (Gate 1) and the **result** (Gate 2).
Everything in between runs without asking.

## Pipeline

```
request ─▶ architect ◀─▶ mockup (UI only) ─▶ [Gate 1: rider approves design]
  ─▶ implement ◀─▶ verify ─▶ reviewer ◀─▶ implement ─▶ qa
  ─▶ documenter ─▶ push ─▶ CI ─▶ [Gate 2: rider tests the build]
```

| # | Stage | Agent (default model, effort) | Does |
|---|---|---|---|
| 1 | Design | `architect` (Opus, xhigh) | Spec page in Notion, ADR if architectural, root cause for a bug of unknown cause |
| 2 | Mockup | `mockup` (Sonnet, medium) | Only when the spec's UI brief isn't `none`; revised with the architect |
| 3 | Build | `implement` (Sonnet, medium) | Tests first, code, local checks, commits (never pushes) |
| 4 | Checks | `verify` (Sonnet) | fmt, clippy, tests, deny, Gradle; stamps the code fingerprint on pass |
| 5 | Review | `reviewer` (Sonnet, high) | Diff against spec, CLAUDE.md, security |
| 6 | QA | `qa` (Haiku, medium) | Acceptance criteria, golden routes and benchmark, phone test list |
| 7 | Docs | `documenter` (Haiku, medium) | ADR ticks, PRD, Notion Milestones, decisions log, handoff Done |

Helpers any stage may use: `search` (Haiku), `ci` (Haiku), `notion` (Haiku).

## Spec

The spec lives in Notion as a child page `Spec: <slug>` of the Handoff
page (written by the architect; sections are in its prompt). Agents get
the **URL**, never the spec text: they fetch it themselves, so the main
session never holds it. ADRs stay in the repo.

## Tiers: route by risk

The architect sets `RISK` in the spec; the orchestrator picks the models
from it (the Agent tool's `model` argument; effort is the agent's own).

| Risk | Examples | Architect | Implement | Review |
|---|---|---|---|---|
| `low` | strings, renames, copy, small layout tweaks, doc fixes | skipped: the orchestrator writes a 5-line spec page itself | Haiku | Sonnet |
| `normal` | everyday features and bug fixes with a clear cause | Opus | Sonnet | Sonnet |
| `high` | route scoring, region file or any untrusted-input parsing (backup, GPX, GeoJSON, downloads), FFI surface, schema, security, performance-sensitive code | Opus + ADR | Opus | Opus |

When unsure, take the higher tier. QA, verify and documenter don't change.
A `low` change that turns out to touch a `high` area is re-tiered.

## Entry points

- `/feature`: full pipeline. A new idea the rider mentions in passing is a
  discussion first (CLAUDE.md): don't start the pipeline until he says so.
- `/bugfix`: triage first. Cause known: spec with the reproducing test.
  Unknown: the architect finds it. No mockup unless the fix changes the
  screen. The first implementer commit is the failing test.
- `/refactor`: the spec fixes what must not change. QA must show golden
  routes and benchmark unchanged. No mockup.
- A chore with no design (a version bump, a doc edit) is `low`: spec page
  in two lines, then implement, verify, review, push.

## Gates

**Gate 1, design.** After the architect (and mockup, when there is UI) the
orchestrator shows the rider: the approach in a few lines, the acceptance
criteria, the spec link, the ADR path and the mockup link. The mockup is
mandatory for UI changes and follows the mockup rules in CLAUDE.md. Build
only after he says go. Comments revise the spec or mockup; repeat.

**Gate 2, result.** After documenter and push, wait for the build with the
`ci` agent only when needed, then tell the rider: the commit id, the
direct APK link once published and working (see CLAUDE.md), what changed
in a few lines, QA's phone test list and anything left open. He tests; his
feedback starts a new change.

Between the gates nothing waits for the rider, except a `BLOCKED` report
that needs his decision.

## Loop limits

After the limit, stop the loop and bring the rider a short summary of
what blocks (last findings, what was tried) with a recommendation.

| Loop | Limit |
|---|---|
| architect ◀▶ mockup (before showing the rider) | 2 rounds |
| implement ◀▶ verify | 2 retries |
| implement ◀▶ reviewer (`blocker`/`major` findings) | 3 rounds |
| implement ◀▶ qa (FAIL) | 2 rounds |
| rider revisions at Gate 1 | unlimited (his call) |

After every fix round that changes code, verify runs again (the stamp is
for one code fingerprint) before the reviewer sees it again.

## Handoff format

Every agent ends with one report block in this shape (fields vary by
stage, see each agent); the orchestrator reads only these:

```
STAGE: <name>
VERDICT: PASS | FAIL | BLOCKED
SPEC: <notion url>
SUMMARY / FINDINGS / ...: short, structured, with path:line
```

- `FAIL` goes back to the implementer with only the `FINDINGS` lines, the
  spec URL and the commit range. Not the whole report history.
- `BLOCKED` carries a question; the orchestrator answers it from the spec
  and the rider's earlier decisions, or asks the rider.
- Never paste a stage's whole output into the next prompt; pass the URL,
  the commit range and the findings.
- Brief each agent fully: it starts with no context (request, tier, spec
  URL, commit range, decisions the rider made, what to return).

## Pushing

The orchestrator pushes (`git push -u origin claude/<name>`) after
review and QA pass and the documenter's commits exist. Hooks enforce the
rules in `.githooks/` and `.claude/hooks/`: pushes only to `claude/*`; no
code push without a passing `verify` stamp for exactly that code; commit
title at most 50 characters and body wrapped at 72; no session link;
rule files (`CLAUDE.md`, ADRs) in their own commits; SPDX headers; no
skipping hooks; no force push except `--force-with-lease` to `claude/*`. If a hook blocks, fix the cause.
CI's merge job merges to `main`; don't wait for it unless the rider needs
the build.
