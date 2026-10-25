<!-- SPDX-License-Identifier: AGPL-3.0-only
     Copyright (C) 2026 Stefan Gangefors -->

# Brief templates

Fill-in-the-blanks prompts for the stages of the workflow skill. An agent
starts with no context, so every blank is filled or struck out. Pass
URLs, paths, commit ranges and findings, never whole reports or the spec
text. CLAUDE.md is already in every agent's context: don't repeat its
rules or tell the agent to read it. Each agent ends with its own report
block; say only what to put in it beyond the default.

## Architect

```
Design this change (<feature | bug | refactor>), risk <low | normal | high>.

Request, verbatim from the rider:
<paste>

Rider decisions so far: <list, or none>
Related: <ADR paths, earlier specs, commits, or none>
Bug only: <how it shows, what is known of the cause, or unknown>
Rider comments to apply (Gate 1 revision only, verbatim): <paste, or none>
Existing spec to change (revision only): <notion url, or none>

Return: the spec URL, the ADR path (if any), risk per slice, slices in
order, acceptance criteria, UI brief (or none), and open questions.
```

## Mockup

```
<New mockup | Revision | Move approved components into the kit>
Spec: <notion url> (UI brief section)
Screens / states to show: <list>
Existing mockup page: <url, or none>
Rider comments to apply (verbatim): <paste, or none>
Rider decisions: <list, or none>

Return: the published page URL, what changed, and any UI brief edits made.
```

## Implement

```
Slice: <name> (risk <normal | high>)
Spec: <notion url>
ADR: <path and the sections this slice needs, or none>
Mockup: <url, or none>
Branch / worktree: <claude/name; base commit or sha>
Earlier slices already committed: <range, or none>
Rider decisions that bind this slice: <list, or none>

Build this slice and nothing more. Return: the commit range, verify.sh's
last line, golden routes before/after if scoring or routing changed, and
anything in the spec left open or deviated from.

Fix round only: fix the blocker/major findings below (and cheap minors)
with new commits, then run verify.sh again. Review range: <sha..sha>.
Findings, verbatim:
<paste FINDINGS, or none>
```

## Reviewer

```
Review slice <name> (risk <normal | high>), round <1 | 2>.
Spec: <notion url>
ADR: <path, or none>
Commit range: <sha..sha>
Rider decisions: <list, or none>
Implementer reported: <verify.sh last line; open items, or none>

Round 2 only: check just these earlier findings against the fix range
<sha..sha>, nothing else:
<paste FINDINGS, or none>

Return: the verdict, findings with severity and path:line, every
acceptance criterion with evidence, and the phone test list.
```
