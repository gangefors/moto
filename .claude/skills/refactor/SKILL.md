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
3. `implement` → `verify` → `reviewer` (checks that behaviour didn't
   change and nothing was added) → `qa` (golden routes and benchmark must
   be unchanged when core code moved; any difference is a FAIL) →
   `documenter` → push, within the loop limits.
4. Gate 2: report as the workflow says.
