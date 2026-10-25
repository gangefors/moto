# ADR-0020: One favourite sheet — a new favourite and a saved one offer the same choices

**Status:** Accepted · **Date:** 2026-10-25 · **Deciders:** the owner · **Repo path:** `docs/adr/0020-one-favourite-sheet.md`

Amends [ADR-0018](0018-surface-corrections.md) ("Change start and end" and the Surface row were scoped to a saved favourite's edit sheet; they now belong to every favourite's sheet). Extends [ADR-0006](0006-section-and-track-storage.md) (`NewSection` can be saved turned round) and [ADR-0014](0014-map-screen-structure.md) (the sheet is driven by one state model).

## Context

Saving a newly marked favourite and editing a saved one use the same `SectionSheet` composable, but each option added after the first version arrived as an optional parameter that only the edit call passed: the turn-round toggle (`canReverse`), the Surface row and "On the map: …" (`mapSurface`, gated by `onReshape`), "Change start and end" (`onReshape`) and the bin (`onDelete`). Each addition was specified as "in the edit sheet" (ADR-0018 states it that way), so the save path never got them and nothing forced it to. The two call sites also map the rider's choices to the store separately (`NewSection` and `SectionUpdate`), and fit the map to the line differently (upright the save sheet reports nothing).

The rider (2026-10-25): a new favourite and a saved one are the same thing. The only difference is the bin (there is nothing saved to delete) and that editing starts from the saved values. Every other option shows identically in both, and any save/edit pair elsewhere follows the same rule.

Forces:

- **Drift by construction:** an option gated by a nullable callback is absent from every call that doesn't pass it. The design must make "both or neither" the default.
- **Logic iOS will need lives in the core** (CLAUDE.md): turning a line round is already core logic (`reversed_ways`, used by `update_section`).
- **Overlap rules depend on direction** (`overlap::add_section`: a one-way favourite only covers one-way in the same direction), so a new favourite must be turned round before it is added, not updated afterwards.
- **"On the map: …"** today comes from the favourites overlay by section id. A draft has no id, and a saved favourite whose ends were just moved shows its old line's value.

## Decision

**One sheet, driven by what it is about.** The app models the sheet's subject as either a new favourite (the marked draft, and the quick-tag it came from, if any) or a saved one (the section, and its moved line, if any). Pure functions derive everything that differs from that subject: the title and length, the first choices (defaults for a new one, the saved values for a saved one, or the choices kept across "Change start and end"), whether the bin shows, the line the map fits and previews, and the write (`NewSection` or `SectionUpdate`). The sheet itself takes no flag that hides an option; the bin (`canDelete`) is the only one. All of name, rating, one-way, turn round, Surface with "On the map: …", and "Change start and end" always show.

**What the options mean for a new favourite:**

- *Change start and end* closes the sheet with its choices kept and goes back to the marking with the draft's pins, to drag or tap; Save… on the task card opens the sheet again with those choices. X or Back on the sheet still leaves it without keeping them, as for a saved one.
- *Turn round* saves the line running from the end pin to the start pin. While the sheet is open the draft line shows arrows in the direction Save would give, as the saved favourite's line does.
- *Surface* is saved with it (`NewSection.surface` already exists).

**Core:** FFI `NewSection` gets `reverse` (default false). The FFI turns the line round with the core's existing reversal before `add_section`, so overlap rules see the direction that is stored. The core gets one measure of what the map has along a line's way spans (the longest share by metres; paved, then gravel, on a tie), exported as `Engine::map_surface_along`. The sheet asks it for the line it would save, for both kinds. The favourites overlay stops measuring this per favourite and `Favourites::map_surface` is removed, so there is one measure, not two.

**Other save/edit pairs** already follow the rule: a route's name dialog (save, rename a saved route, rename a ride) is one dialog with one field, and a surface correction is marked, edited and saved through one path (`replaces` set when editing).

## Options Considered

### A. One subject model, one sheet without option flags; reverse and the map's surface in the core (chosen)

| Dimension | Assessment |
| --- | --- |
| Drift | An option added to the sheet appears in both flows unless someone adds a flag; the rule is visible in the signature |
| Core work | One FFI field, one FFI function sharing an existing measure; one measure removed |
| iOS | Reversal and the surface measure stay in the core |
| Risk | FFI change (high); the app part is normal |

Pros: one code path for the choices and the write; fixes "On the map" for a moved line. Cons: needs a core slice before the app slice.

### B. Same sheet, pass the existing flags from the save call too

| Dimension | Assessment |
| --- | --- |
| Drift | Unchanged: the next option is again a flag one call may forget |
| Core work | Still needs reverse and a draft's map surface |

Cons: fixes today's gap, not the cause.

### C. Reverse the draft in Kotlin; leave out "On the map" for a draft

| Dimension | Assessment |
| --- | --- |
| Core work | None |
| iOS | Reversal duplicated in Swift later |
| Rider's rule | Broken: the hint shows in one flow only |

Cons: duplicates core logic and keeps one visible difference.

## Trade-off Analysis

B is cheapest but keeps the mechanism that caused the drift. C avoids the core slice at the price of duplicated logic and a remaining difference the rider ruled out. A costs one small FFI change and one new query, and removes a second measure, so the sheet has one path end to end.

## Consequences

- Marking a new favourite now also offers its surface, its direction turned round, and changing its ends from the sheet without losing the choices.
- Upright, the map fits a new favourite's line above its sheet as it does for a saved one (before, it stayed where it was).
- `map_surface_along` costs one pass over the region's edges, like the overlay build that drops the same work; it runs off the main thread when the sheet opens.
- Specs for future sheet options state them for "a favourite's sheet", not for one flow.

## Action Items

- [ ] Core and FFI: `NewSection.reverse` applied before `add_section`; `Engine::map_surface_along` (one shared measure); `Favourites::map_surface` and the overlay's per-favourite measure removed; tests including unknown ways and out-of-range spans.
- [ ] App: the sheet subject and its pure functions, one `SectionSheet` call without option flags, kept choices for a draft's "Change start and end", draft direction preview, one fit effect, the how-to line; Kotlin unit tests.
- [ ] Phone test: mark a new favourite, set it one-way, turn it round, set Gravel, change its ends from the sheet and save; check it on the map and in its edit sheet.
