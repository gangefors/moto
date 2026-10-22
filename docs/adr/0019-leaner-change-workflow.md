# ADR-0019: Leaner change workflow — checks as a script, review takes QA, risk routed per slice

**Status:** Accepted · **Date:** 2026-10-22 · **Deciders:** the owner · **Repo path:** `docs/adr/0019-leaner-change-workflow.md`

Partly supersedes [ADR-0013](0013-agentic-change-workflow.md): its stage list, the `verify` and `qa` agents, the loop limits and the tiering per change. Its gates, specs in Notion, structured handover and hooks stay.

## Context

The first big change through the ADR-0013 pipeline (surface corrections, ADR-0018) used four 5-hour usage windows. Its git history shows where the time went:

- **Design** took about five and a half hours: seven ADR revisions by the architect on Opus at extra-high effort, and six mockup-kit commits that added classes and then removed them, while the rider's Gate 1 comments (mostly wording and layout) went round the architect each time.
- **The whole change was tier `high`** (schema, FFI, parsers, scoring), so Opus also built and reviewed the Kotlin UI on top.
- **Checks and review** took about four hours for four small fix commits. Every check was a separate `verify` agent starting cold (reading the commands skill, re-running the builds the implementer had just run), and every failure needed a new implementer to pick it up. A separate `qa` agent then re-read the spec and the diff, and ran the benchmark that CI runs on every build anyway. Re-reviews read the whole diff again, up to three rounds.
- **Repeated reading:** agents were told to read CLAUDE.md, which Claude Code already loads into every agent, and the workflow skill; the 15 KB ADR and the Notion spec held the same design twice.

Forces: quality and security must not drop (ADR-0013's reasons for independent review and enforced rules still hold); the rider keeps both gates; usage should go to judgement, not to mechanics or re-reading.

## Decision

- **Local checks are a script, not an agent.** `.claude/scripts/verify.sh` picks the checks from what the branch changed (Rust fmt, clippy, tests, cargo-deny; the Android build when `android/` or the FFI crate changed; CI-script and guard tests), runs them with CI's pinned versions and prints one line per check, with only the first errors of a failure. When every check that applies passed and no code is uncommitted, it writes the fingerprint stamp the pre-push hook requires; an already-stamped HEAD returns at once. The implementer runs it and fixes failures in its own context (3 runs, then `BLOCKED`). `setup-env.sh` installs the toolchains once per machine at CI's versions, checking each download against its publisher's checksum. The `verify` agent and `stamp-verified.sh` are retired.
- **The stamp is written only by the script.** The Bash guard blocks any command that writes or names the stamp file, writes the fingerprint to a file, or changes `.claude/scripts/`. This is stricter than before: an LLM no longer decides when to stamp.
- **The reviewer is also QA.** It checks every acceptance criterion with evidence (test, `path:line`, or "phone") and writes the rider's phone test list, beside the correctness, security, test and rule review it already did. The `qa` agent is retired. Golden routes and the benchmark gate in CI, where `main` only moves when they pass; locally the implementer runs the golden routes for a scoring change (the before/after table CLAUDE.md already asks for) and for a refactor that moves routing code.
- **Re-reviews are deltas.** Round 2 gets the round-1 findings and the fix commits only. implement ◀▶ reviewer is limited to 2 rounds per slice.
- **Risk is routed per slice.** The architect splits a change that mixes risks into slices built and reviewed in turn (typically `core`: high, Opus; then `app`: normal, Sonnet). Each slice must build and pass its tests alone.
- **Gate 1 revisions are routed by what they change.** Wording, strings, layout, colours and icons go to the `mockup` agent (Sonnet), which also edits the spec's UI brief; only a change of approach goes back to the architect, who then edits in place. Mockup components stay in the page until the design is approved, then move into the kit in one commit.
- **One design document.** When a change has an ADR, the ADR holds the design and the Notion spec only the criteria, slices, tests, UI brief and a link. ADRs stay on the decision: no restated PRD, no code listings.
- **Less re-reading.** Agents don't re-read CLAUDE.md or the workflow skill; each agent file carries its own report block. The UI and mockup rules move out of CLAUDE.md into a `ui-rules` skill that the agents read only when a change touches the screen. The architect runs at high effort instead of extra high. The implementer gets an explicit tool list (no unused tool definitions in its context).

Pipeline:

```
request → architect ◀▶ mockup (UI) → [Gate 1; wording and layout: mockup only]
  → per slice: implement (+ verify.sh) ◀▶ reviewer (criteria, phone tests)
  → documenter → verify.sh → push → CI → [Gate 2]
```

## Options Considered

### A. Script for checks, review takes QA, slices, cheap Gate 1 revisions (chosen)

| Dimension | Assessment |
| --- | --- |
| Usage | Good: no agent spent on mechanics; Opus only where the risk is; design revisions mostly on Sonnet |
| Quality | Same: an independent reviewer still checks spec, criteria, correctness, security and tests; CI still runs golden routes and benchmark before `main` moves |
| Security | Same or better: the stamp is written by a script only after real passes |
| Complexity | Lower: five stage agents instead of seven |

**Pros:** removes the costliest loops seen in practice; fewer cold starts; the hard rules stay enforced by hooks.
**Cons:** the reviewer's job is bigger; slices add a planning step for the architect.

### B. Keep ADR-0013, lower the models (Haiku verify, Sonnet everywhere)

| Dimension | Assessment |
| --- | --- |
| Usage | Better than now, but the cold starts and duplicate builds stay |
| Quality | Worse: Sonnet reviewing schema, FFI and parser work is what ADR-0013 rightly avoided |
| Security | Weaker for high-risk code |
| Complexity | Unchanged |

**Pros:** small edit.
**Cons:** saves usage by giving up quality where it matters.

### C. Drop the reviewer for normal changes

| Dimension | Assessment |
| --- | --- |
| Usage | Best |
| Quality | Worse: the builder approves its own work |
| Security | Worse |
| Complexity | Lower |

**Pros:** fastest.
**Cons:** against ADR-0013's independent review.

## Trade-off Analysis

A cuts the work that bought nothing (re-running builds in a fresh agent, re-reading what is already loaded, rewriting the ADR for a wording change, Opus on plain UI) and keeps every check that protects quality: the reviewer is still independent, the risky slice is still Opus throughout, and CI still runs the golden routes and benchmark before `main` moves. B and C save usage by weakening review, which the rider ruled out.

## Consequences

- **Easier:** fewer agents and handoffs; a failed check is fixed by the agent that has the context; the rider's wording comments come back quickly.
- **Harder:** the reviewer must be thorough on criteria as well as code; the architect must plan slices that build alone.
- **Guard limits** (as in ADR-0013): the Bash guard is a seat belt, not a sandbox; file edits by an agent are not guarded, so the pre-push stamp check and server-side branch protection remain the real controls.

## Action Items

- [x] `.claude/scripts/verify.sh` and `setup-env.sh`; the guard blocks other stamp writes and changes to the scripts; guard tests extended; `stamp-verified.sh` retired.
- [x] Agents: `verify` and `qa` removed; `implement` runs verify.sh, has a tool list; `reviewer` takes the criteria and phone tests, delta round 2; `architect` at high effort, with slices, ADR-as-spec and in-place revisions; `mockup` takes wording revisions, page-local CSS until Gate 1.
- [x] Skills: `workflow`, `feature`, `bugfix`, `refactor`, `dev-commands`, `ci-and-merge`, `mockups`; new `ui-rules`.
- [x] CLAUDE.md and `docs/working-with-claude.md` describe the leaner pipeline; UI rules moved to `ui-rules`.
- [ ] Add the decision to the Notion decisions log.
- [ ] Delete the retired `.claude/hooks/stamp-verified.sh` by hand (the guard keeps sessions from deleting hook files).
- [ ] Review after the next three changes: usage per change, review findings that CI or the rider caught later.
