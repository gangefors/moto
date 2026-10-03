---
name: ci-and-merge
description: When CI runs, how long it takes, how work branches reach main (the CI merge job), the debug-branch and debug-latest test releases, and how to read benchmark and golden-route results. Use after a push, before a merge by hand, when CI is red, or when the rider asks when he can test a build.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# CI and merging

## When CI builds

- `android.yml` runs only for pushes that change code in `android/` or
  `core/`, or `.github/scripts/third_party.py` (the app build runs it),
  never for Markdown alone. A commit that only touches docs, ADRs,
  `CLAUDE.md`, `.claude/` or other files in `.github/` gets no CI run and
  no `debug-latest`, so there is nothing to wait for.
- The CI scripts' tests run in their own workflow (`scripts.yml`, seconds)
  whenever a script changes, without building anything.
- To test a change to `android.yml` before the next code change, start it
  by hand (workflow_dispatch) on the branch.
- A newer push cancels the run still going; the newer run covers both
  commits, so check CI on the newest one. Push each fix as soon as the
  local checks pass, never holding it for a CI run still going: the rider
  tests several fixes at once and wants fast turnaround.

## How long it takes

Medians from push (25 green runs, 2026-09-29): Core 2 min, Android app and
`debug-branch`/`debug-latest` 6 min (at most 9), Benchmark 14 min (at most
16). Golden routes run in the Core job; the Skåne (M0) and border regions
are rebuilt about once a week (cache miss: a few minutes more). The rider can
install `debug-branch` about 6 minutes after a push to a work branch; the
merge into main follows the benchmark, about 15 minutes after the push.

## Merging into main

The `merge` job in `android.yml` does it: once core, bench and build pass
on a Claude work branch (`ccr-*`, `claude/*`), it fast-forwards `main` to
the branch tip and starts main's CI (`debug-latest`, the benchmark
baseline) and CodeQL. `.github/scripts/fast_forward.py` decides. So after
a code push, never wait, poll or schedule a check-in just to merge.

Merge by hand (fast-forward, then push `main`) only where the job can't:

- **No CI run** (docs, ADRs, `CLAUDE.md`, `.claude/`): merge right after
  pushing, but only if `git diff --name-only origin/main..HEAD` changes no
  code; otherwise the code's own CI run merges it all.
- **Main moved on**: merge `origin/main` into the branch and push; CI
  merges when green. The run shows a warning "Not merged: main has moved
  on".
- **Workflow files** (`.github/workflows/`): the job's token can't push
  them. Wait for CI to be green on the branch's newest commit, then merge
  by hand.

## When CI is red

Use the `ci` agent to find the failing job and step and summarise the log;
never read whole job logs in the main session. A failure on the branch
blocks its merge; fix it next.

- **Benchmark**: compares this build's binary with the last `main` build's
  on the same machine; the job summary shows the table
  (`.github/scripts/bench_compare.py`). Thresholds are in CLAUDE.md,
  Testing and performance.
- **Golden routes**: a before/after table against the last `main` build;
  a route that breaks its expectations fails the Core job. See
  `core/moto-core/tests/golden/README.md` and the route-scoring skill.
