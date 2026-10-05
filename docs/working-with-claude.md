<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Working with Claude: saving usage

Claude Code plans have usage limits. Every turn re-sends the whole
conversation (CLAUDE.md, earlier diffs, build output), and Opus uses the
limits several times faster than Sonnet for the same work, Haiku least.
This page is for the rider; Claude follows the matching rules in CLAUDE.md
(Models and subagents). It is not loaded into sessions, so it costs
nothing.

## Session habits (the biggest savings)

- **One session per feature.** Start a fresh session (or `/clear`) for
  each piece of work: "backup and restore" in one, "button tweaks" in
  another. Late turns of a long session re-send far more than a fresh
  session's start.
- **No CI babysitting.** The CI `merge` job fast-forwards `main` once a
  work branch is green, so a session doesn't wake up to merge. GitHub
  emails you when a run fails; ask any session to look at it.
- **Batch feedback** ("1 ok, 2 ok, 3 …") in one message: each message
  re-sends the whole context.
- **Give decisions up front** when you can ("plain file, settings
  overwrite"). That saves a propose-then-confirm round trip.
- **Small, mechanical edits** (strings, renames) are cheaper in a short
  Haiku or Sonnet session than as an extra turn in a long Opus one.
- **Keep the Notion milestones page short**: the current milestone and
  **Next**, with finished items moved to an archive page.

## Which model for which task

Pick the session's model when you start it (`/model`, or in the app).
Within a session Claude hands work to subagents that pin a model
(`.claude/agents/`): Notion, CI logs and searches on Haiku; design on Opus;
implementation, mockups, checks and review on Sonnet; QA and docs on Haiku.
With the change workflow (`/feature`, `/bugfix`, `/refactor`, ADR-0013) you
talk to the main session and approve only the design and the result; it
picks the models per change from the spec's risk tag.

| Task (examples from this project) | Model | Effort |
|---|---|---|
| Architecture, ADRs, security-sensitive parsing (backup reader, region file format), route scoring and golden routes | Opus | medium–high |
| Hard bugs with unclear causes (zoom jumping, camera modes, GPS jitter) | Opus | medium |
| Everyday features from a clear spec (button order, dialogs, sorting, strings, Compose UI, tests) | Sonnet | medium |
| Mockups and their revisions | Sonnet | low–medium |
| Answering mockup comments (renames) | Sonnet or Haiku | low |
| CI check, Notion ticks, "what can I validate with build X" | Haiku | low |

**Plan with Opus, build with Sonnet.** The workflow does this for you: the
architect (Opus) writes the spec and ADR, Sonnet builds and reviews. For
the main session itself, Sonnet at medium effort is enough for the
orchestrating; it only routes short reports.

**Use `/feature`, `/bugfix` or `/refactor`** to start a change; say "just
discussing" for ideas, which stay a discussion until you say go.
