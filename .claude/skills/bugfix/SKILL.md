---
name: bugfix
description: Run the moto change pipeline for a bug. Use when the rider says /bugfix or reports something broken, with or without a known cause.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

Load the `workflow` skill and run it for this bug: `$ARGUMENTS`.

1. **Triage** from what the rider said (and `search` if you need to find
   the code): is the cause known and the fix local? Describe a rider's
   report in general terms; never copy his coordinates or places into
   specs, tests or commits (CLAUDE.md).
2. Call `architect` with type `bugfix`. Cause unknown: it finds the root
   cause first. The spec must name the cause, the reproducing test that
   comes first, and the fix. A route that came out badly on a ride also
   gets a golden case (own example, same behaviour), not a weight tweak.
3. Gate 1 is short: the cause, the fix and the test in a few lines (and a
   mockup only if the screen changes). Skip the wait when the rider has
   already said "just fix it" and the tier is `low` or `normal`.
4. `implement` (first commit: the failing test) → `verify` → `reviewer`
   → `qa` → `documenter` → push, within the loop limits.
5. Gate 2: report as the workflow says.
