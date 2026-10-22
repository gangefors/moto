---
name: refactor
description: Run the moto change pipeline for a behaviour-preserving refactor. Use when the rider says /refactor or asks to clean up, split, rename or restructure code without changing what the app does.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

Load the `workflow` skill and run it for this refactor: `$ARGUMENTS`.

1. Call `architect` with type `refactor`. The spec must state what must
   not change (behaviour, public and FFI API, golden routes, benchmark),
   how that is shown, and the steps as separate commits that each build
   and pass tests. Refactors that touch scoring, parsers or FFI are tier
   `high`.
2. Gate 1: the approach and what is proven unchanged. No mockup.
3. `implement` (runs verify.sh; when routing or scoring code moved, it
   also runs the golden routes before and after, and any difference is
   `BLOCKED`) → `reviewer` (checks that behaviour didn't change and
   nothing was added) → `documenter` → push, within the loop limits.
   CI's benchmark must show no regression.
4. Gate 2: report as the workflow says.
