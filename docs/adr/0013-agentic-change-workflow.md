# ADR-0013: Change workflow — a pipeline of agents with two rider gates, routed by risk

**Status:** Accepted · **Date:** 2026-10-05 · **Deciders:** the owner · **Repo path:** `docs/adr/0013-agentic-change-workflow.md`

## Context

Every change (feature, bug fix, refactor) used to run through the main session, which designed, delegated pieces to `implement`, `mockup` and `verify`, reviewed and pushed. That keeps the main session's context large (every turn re-sends it), puts design, build and review in one head, and relies on CLAUDE.md prose to keep the rules. The rider wants to talk to the main session and have the rest happen: design, mockup, build, review, QA and documentation, with his say on the design and on the result only.

Forces:

- **Usage limits.** The main session's context must stay small; models should match the difficulty of each stage (Opus uses limits several times faster than Sonnet, Haiku least).
- **Independent review.** The agent that wrote the code must not be the one that approves it.
- **Hard rules should be enforced, not remembered** (commit format, no session link, rule files in their own commits, nothing pushed that fails the checks).
- **The rider decides what gets built** (CLAUDE.md): a design he hasn't approved is not built.
- **Specs change often and are private**, so they belong in Notion; decisions stay in the repo.

## Decision

- **The main session is an orchestrator.** It classifies the request, calls the agents below in order, routes their short structured reports and pushes. It designs, builds and reviews nothing itself. `/feature`, `/bugfix` and `/refactor` (skills) start the pipeline; the `workflow` skill holds the details.
- **Stages and agents** (`.claude/agents/`): `architect` (Opus, extra high effort: spec, ADR, root cause of unknown bugs) ◀▶ `mockup` (Sonnet, medium: only for UI) → `implement` (Sonnet, medium; tests first) ◀▶ `verify` (Sonnet: mechanical checks) → `reviewer` (Sonnet, high effort) ◀▶ `implement` → `qa` (Haiku, medium: acceptance criteria, golden routes, benchmark, phone test list) → `documenter` (Haiku, medium: ADR ticks, PRD, Notion). `notion`, `ci` and `search` (Haiku) remain helpers.
- **Two rider gates.** Gate 1: the design (spec summary and acceptance criteria, with the mockup for UI changes). Gate 2: the result (commit id, APK link, what changed, QA's phone test list). Between them nothing waits for the rider except a `BLOCKED` question that needs his decision.
- **Route by risk.** The architect tags the spec `low`, `normal` or `high`. `low` (strings, renames, copy): no architect, Haiku implements. `normal`: Opus designs, Sonnet builds and reviews. `high` (route scoring, untrusted-input parsing, FFI, schema, security, performance-sensitive code): Opus designs with an ADR, builds and reviews. Unsure means the higher tier.
- **Specs live in Notion** as child pages `Spec: <slug>` of the Handoff page; ADRs stay in the repo. Agents receive the spec's URL and fetch it themselves, so the spec never passes through the main session.
- **Structured handover.** Each agent ends with one report block (`STAGE`, `VERDICT` `PASS|FAIL|BLOCKED`, `SPEC`, findings with `path:line`). The orchestrator passes only the URL, the commit range and the findings on.
- **Bounded loops.** architect◀▶mockup 2 rounds before the rider sees it; implement◀▶verify 2 retries; implement◀▶reviewer 3 rounds; implement◀▶qa 2 rounds. Then the orchestrator stops and brings the rider a summary and a recommendation. Rider revisions at Gate 1 are unlimited.
- **Rules are enforced by hooks**, committed in the repo and switched on by a session-start hook (git's hooks path set to `.githooks`): `commit-msg` (title ≤ 50, body ≤ 72, no session link), `pre-commit` (rule files in their own commit, SPDX headers), `pre-push` (only `claude/*` branches; code goes up only with a `verify` stamp for exactly that code, a fingerprint of `android/`, `core/` and `.github/scripts` so docs commits don't invalidate it), plus Claude Code hooks that block skipping git hooks, force pushes (except `--force-with-lease` to `claude/*`) and session links in PR text.

## Options Considered

### A. Orchestrator plus stage agents, two gates, hooks (chosen)

| Dimension | Assessment |
| --- | --- |
| Usage | Good: small main context, each stage on the cheapest model that does it well |
| Quality | Good: separate designer, builder and critic; risk-routed models |
| Control | Good: the rider approves design and result; hooks enforce hard rules |
| Complexity | Medium: seven agents, four skills, hooks to keep current |

**Pros:** the rider talks to one session and things happen; independent review; restartable stages (each reads the spec, not the chat).
**Cons:** more moving parts; each handoff costs a prompt; weak models can still miss things, so risk routing and the reviewer matter.

### B. Keep the main session doing everything, with helper agents

| Dimension | Assessment |
| --- | --- |
| Usage | Poor: long contexts re-sent every turn |
| Quality | Weaker: one head designs, builds and reviews |
| Control | Rules by prose only |
| Complexity | Low |

**Pros:** nothing to build.
**Cons:** what the rider wants to leave behind.

### C. Fully unattended, including design

| Dimension | Assessment |
| --- | --- |
| Usage | Good |
| Quality | Risky: a wrong design is the most expensive mistake and surfaces last |
| Control | None before the result |

**Pros:** least involvement.
**Cons:** against "the rider decides what gets built".

## Trade-off Analysis

A costs some setup and handoffs but fixes what B gets wrong (context size, no independent review, unenforced rules). C saves the rider's time only until the first wrong design. Haiku as implementer was considered and rejected for normal and high risk: it follows a spec well but is weak on Rust lifetimes, FFI and Compose edge cases, and extra effort doesn't change that; it stays for mechanical `low` changes.

## Consequences

- **Easier:** talk to one session; consistent process for every change; rules checked by machines.
- **Harder:** the agents, skills and hooks are code to keep current; a hook can block a legitimate action (the cause is fixed, hooks are not skipped; the owner can still bypass them by hand).
- **Cost:** specs in Notion need the Notion connector in every session that runs the pipeline.

## Action Items

- [ ] Agents: `architect`, `reviewer`, `qa`, `documenter` added; `implement`, `mockup`, `verify` and `notion` updated to the handoff format.
- [ ] Skills: `workflow`, `feature`, `bugfix`, `refactor`.
- [ ] Hooks: `.githooks/` (`commit-msg`, `pre-commit`, `pre-push`), `.claude/hooks/` and `.claude/settings.json`.
- [ ] CLAUDE.md and `docs/working-with-claude.md` describe the pipeline.
- [ ] Review after the first three changes through the pipeline: loop limits, tiers and model choices.
