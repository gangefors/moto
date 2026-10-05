# ADR-0014: Map screen structure — one state holder, a per-composition scope, pieces by concern

**Status:** Proposed · **Date:** 2026-10-05 · **Deciders:** the owner · **Repo path:** `docs/adr/0014-map-screen-structure.md`

## Context

`MapScreen.kt` is 3,800 lines, and almost all of it is one composable, `MapScreen()` (about 3,170 lines). It holds 103 `var … by remember { mutableStateOf(…) }` values and one saveable one, 58 effects, 49 local functions that capture that state, an 820-line UI tree and 400 lines of dialogs and sheets. Finding the code for one feature means reading through the others, agents read large slices of the file for small changes, and a change in one place (closing the info cards, say) can depend on state declared a thousand lines away. Pure layout and visibility rules already live in `CardLayout.kt` and `MapLogic.kt` with tests; what remains is the screen's state, its operations and effects, and its UI.

Forces:

- **No behaviour change** while restructuring. The app has no UI tests, so the new structure must let a reviewer show that code moved rather than changed.
- **Compose semantics.** A local function closes over two kinds of names: state delegated to a `MutableState`, read live whenever it runs, and plain values of the composition that created it (the overlays, the region, the layout), fixed for that composition. Callbacks and coroutines that outlive a composition depend on that difference.
- **Order matters.** Effects launch, and `BackHandler`s register, in composition order; the map's Back handler must stay below the menu's and the route sheet's.
- **No new architecture or dependency for its own sake.** The activity handles its own configuration changes, so a ViewModel would buy nothing.
- **Logic iOS will need stays in the Rust core** (ADR-0001); this is Kotlin UI code only.

## Decision

- **`MapScreenState`**, one object remembered for the screen's life, holds what the screen keeps: every former `var … by remember { mutableStateOf(…) }` (same names, types, initial values and comments), the unkeyed remembered helpers (busy tasks, notices, route picker, section marker, loop length, loops found ahead, the favourites build lock) and the two coroutine scopes. It holds nothing that changes per composition and nothing keyed: the map overlays, remembered per style, stay outside. The route and loop sheet's expanded flag stays saveable; its `rememberSaveable` state is passed in.
- **`MapScreenScope`**, a plain class made anew in each composition, holds that state plus the values of the composition: context and resources, the map view, overlays, region, theme, insets and layout, the permission launchers, and the flags derived from state (planning, ride mode, and so on). Values that were read through a `State` delegate stay delegates (`val recording by …`), so they are still read live.
- **Operations, effects and UI pieces are extension functions on `MapScreenScope`** (UI pieces on `BoxScope`, taking the scope), in files by concern: setup, camera and location, store and favourite layers, marking, planning, what is shown, ride, gestures, buttons, cards, plan sheet, dialogs. Their bodies open `with(state)` and are otherwise the code that was in `MapScreen()`, so a callback or coroutine sees the values of the composition that made it, exactly as the local functions did.
- **`MapScreen()` is the composition root.** It creates the state and the scope, reads the composition's values, and calls the effect groups and the pieces in the old order. Effects and `BackHandler`s keep their composition order; one-line effects stay inline.
- **Pure rules are pure functions** in the logic files, with unit tests: Back's precedence, which cards and map buttons show, and ride mode.
- **For new code:** new screen state goes into `MapScreenState`, new behaviour into its concern's file as a `MapScreenScope` extension, and a decision that can be pure into a logic file with tests.

## Options Considered

### A. One state holder, a per-composition scope, extension files by concern (chosen)

| Dimension | Assessment |
| --- | --- |
| Readability | Good: `MapScreen.kt` about 450 lines, each concern in a file under 500 |
| Risk | Medium: bodies move verbatim and the compiler resolves every name; order and capture rules are reviewable |
| Coupling | As today: any operation can still touch any state |
| Effort | Medium: about 20 mechanical commits |

**Pros:** provable as a move; the same semantics by construction; no new dependency.
**Cons:** two receivers (`with(state)` inside a scope extension) are an unusual idiom; coupling between concerns is made visible, not reduced.

### B. Move only: helpers out, stateless UI pieces with hoisted parameters

| Dimension | Assessment |
| --- | --- |
| Readability | Fair: `MapScreen.kt` stays about 2,900 lines (state, effects and operations stay) |
| Risk | Low |
| Effort | Low |

**Pros:** safest.
**Cons:** misses the goal; the plan sheet alone would pass some 70 parameters through.

### C. A holder per feature (route plan, loop, marking, info cards, ride) with explicit dependencies

| Dimension | Assessment |
| --- | --- |
| Readability | Best |
| Risk | High: every reference and every cross-feature operation rewritten; what each callback captures re-derived by hand |
| Effort | High |

**Pros:** real decoupling.
**Cons:** a rewrite, not a refactor; behaviour changes would be likely and hard to see without UI tests.

### D. A ViewModel with StateFlows

| Dimension | Assessment |
| --- | --- |
| Fit | Poor: the activity handles configuration changes itself; a ViewModel adds a lifecycle and a dependency for nothing |
| Risk | High: a rewrite |

**Pros:** a familiar Android pattern.
**Cons:** solves a problem the app doesn't have.

## Trade-off Analysis

A gets most of C's readability at close to B's risk, because the code moves verbatim and Kotlin resolves every name through the two receivers or fails to compile. It leaves the coupling between concerns as it is; C can still follow later, one concern at a time, on top of A. The cost is one idiom (scope extensions that open `with(state)`), explained here and in the state file's KDoc.

## Consequences

- **Easier:** finding and changing one concern, reviewing a change to it, and reading one file instead of a slice of 3,800 lines.
- **Recomposition:** pieces recompose on their own state reads as well as with `MapScreen()`, so `MapScreen()` itself recomposes less often. Nothing visible depends on that except two values read during composition without snapshot state (the position given to Routes & rides, and a section's cached description in the edit sheet); both are still read when their sheet opens, as before.
- **Callbacks** that capture the scope are new objects in each composition, so a few cards and buttons recompose a little more often; not measurable at the screen's update rates.
- **Harder:** two receivers per function, and the scope class must list each per-composition value the moved code uses.

## Action Items

- [ ] File-level helpers out of `MapScreen.kt` (system bars, buttons, map setup, camera and location, planning types).
- [ ] Pure rules with tests: Back's precedence, cards shown, map buttons shown, ride mode.
- [ ] `MapScreenState` holds the screen's state.
- [ ] `MapScreenScope` gathers the composition's values; composable value calls and derived values come first.
- [ ] Operations out as scope extensions, by concern.
- [ ] Effect groups out, in composition order.
- [ ] UI pieces out: buttons, cards, plan sheet.
- [ ] Dialogs and sheets out.
- [ ] Phone smoke test of the main flows in both orientations and both themes.
