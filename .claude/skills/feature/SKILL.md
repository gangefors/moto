---
name: feature
description: Run the full moto change pipeline for a new feature the rider has agreed to build. Use when the rider says /feature or asks for a feature, not for an idea still being discussed.
---
<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

Load the `workflow` skill and run it for this feature: `$ARGUMENTS`.

1. Ask the `notion` agent for the Next item and any pending handoff that
   relates; skip if the request is self-contained.
2. Call `architect` with the request, type `feature` and the rider's
   decisions so far. On `BLOCKED`, put its question and recommendation to
   the rider.
3. If UI is `yes`, call `mockup` with the spec URL; loop with the
   architect up to the limit; then Gate 1 (spec summary, criteria and
   mockup link). If UI is `no`, Gate 1 is the summary and criteria alone.
4. After he approves: `implement` → `verify` → `reviewer` → `qa` within
   the loop limits and the tier's models, then `documenter`, then push.
5. Gate 2: report as the workflow says.
