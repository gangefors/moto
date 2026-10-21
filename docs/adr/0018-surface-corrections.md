# ADR-0018: Surface corrections — the rider's own road surface for stretches, applied at query time

**Status:** Proposed · **Date:** 2026-10-21 · **Deciders:** the owner · **Repo path:** `docs/adr/0018-surface-corrections.md`

Extends [ADR-0006](0006-section-and-track-storage.md) (schema 10: two new tables) and [ADR-0010](0010-unridden-roads.md) (the rider's overlay also carries surface). Amends [ADR-0012](0012-backup-and-restore.md) and [ADR-0016](0016-backup-region-switches.md) (backup format 4 adds the corrections). The region file ([ADR-0005](0005-region-file-format.md)) does not change.

## Context

The region builder maps OSM's `surface` tag to `Surface`; a missing or unknown value becomes `Unknown`, and `Surface::is_paved` counts `Unknown` as paved. Many tracks and minor roads in OSM have no `surface` tag, and some have a wrong one. On those roads "avoid gravel" doesn't avoid the gravel, "prefer gravel" doesn't seek it out, and the route's gravel figure and dashes leave them out. The rider (2026-10-21) wants to add or correct the surface where the map is wrong or missing: tap a road, then move its ends where the gravel stretch is shorter or longer than the road.

Forces:

- **The cost function is the product** (ADR-0001): a correction must change routes exactly where it says, and change nothing elsewhere. Routes without corrections stay identical (golden routes, benchmark).
- **Never rebuild the graph** for rider data (CLAUDE.md): corrections are applied at query time, like favourites and ridden roads.
- **Rider data outlives the map:** corrections must survive weekly region rebuilds, where OSM ways are split, merged and renumbered, as sections do (ADR-0005, ADR-0006).
- **Hot path:** the router asks "is this edge unpaved?" for every edge it relaxes; the answer must stay one cheap test when no correction touches the edge.
- **Security:** stored rows and backup entries are hostile input (ADR-0006, ADR-0012).
- **One way to mark a stretch:** the app already marks favourites by picking two ends on the road; corrections should feel the same.

## Decision

**Unknown surface stays paved, on purpose** (the rider, 2026-10-21). Roads OSM leaves without a surface keep being routed and ridden as paved, as today; no default counts untagged tracks or minor roads as gravel, now or later. A surface correction is the way to fix a road the map has wrong or leaves out.

**A correction is a stretch, not a whole road.** It is stored like a section: the OSM way spans it covers (way id and node range), plus its own line. Surface is direction-free: a correction applies to both directions. New table `surface_corrections` (id, `rider_id`, surface, status `ok` / `needs_rematch`, created and changed times, bounding box, own line in the sections' fixed-point blob) and `surface_correction_ways` (ordered way spans, cascade delete), schema 10. A correction is Paved or Gravel only (2026-10-21, the rider: two choices that stand for all the map's surfaces). It is stored as the region-format `Surface` code for paved (3) or gravel (6); any other code is refused. Routing reads it with `Surface::is_paved`, as for the map's own values. At most 10 000 corrections.

**Overlap: newest wins.** Saving a correction deletes, in the same transaction, the one it edits and every older one whose way spans it fully covers. Where corrections overlap only in part, the newer one (changed time, then id) decides that stretch.

**Applied at query time, by share of edge.** The rider's overlay (`Favourites`, built by the store from sections and rides, ADR-0010) is also built from the corrections that are `ok`. For each edge a correction touches, it holds the unpaved stretches (fractions in travel order, for each direction): the map's surface outside the corrections, the newest correction's inside. Edges no correction touches cost one bit test, and nothing at all when there are no corrections (the overlay's surface set is empty, so the map's surface is used as today). Every place that asked `is_unpaved(edge)` asks the overlay instead:

- avoiding gravel costs the unpaved share of an edge's time (`1 + share × (avoid_penalty − 1)`), and preferred gravel is worth `share × gravel_weight`, so a short gravel stretch counts as long as it is;
- the yes-or-no rules count an edge as unpaved from half its length: the slow-road waiver for preferred gravel, favourites on gravel while gravel is avoided, gravel favourites' share (`SectionGravel`, hidden while avoiding gravel), and loop waypoint roads;
- a route's gravel metres and drawn gravel stretches are the unpaved stretches it rides;
- the search's lower bound uses the fastest unpaved speed of the map and of the corrected edges, so it stays admissible.

Way spans are node ranges, so the router sees a correction rounded out to the OSM nodes at its ends (draft rule, ADR-0006); its own line is kept for re-matching and for marking it again. A road corrected to gravel keeps the speed the map gives it (the builder's gravel cap applies to mapped gravel only).

**Survives rebuilds like sections.** When the region key changes, every correction is flagged and re-matched by its own line with the sections' matcher and tolerances; one that fits gets its new way spans and `ok`; one that doesn't is deleted (2026-10-21, the rider: if the map moved that much, the correction is probably wrong); one off the open map (its region switched off) waits as `needs_rematch` and is matched once its region is back. There is no `unmatched` state for corrections. After a run that deleted any, the app shows one short notice, once, with no action. The region key is stored only after sections and corrections are both done.

**Backup format 4.** A new entry `surface-corrections.geojson` (LineStrings; properties surface `paved` or `gravel`, created and changed times, way spans; `moto.content` = `"surface"`), listed when the manifest's new `surface_corrections` count is above 0. The manifest must have the count in format 4 and must not in formats 2–3; the entry is refused in formats 2–3. Caps: 10 000 features, 64 MiB, the sections' point and way limits; strict types (`deny_unknown_fields`), every value range-checked, SHA-256 as for every entry. A restore adds them in the same transaction as the rest, skips one with the same surface and the same line, keeps their times, and leaves them `needs_rematch` until the re-match after the restore. Formats 2 and 3 still restore (no corrections).

**UI** (spec `surface-corrections`, mockup at Gate 1): the road's card (tap a road) and a favourite's card get "Correct surface". It proposes the whole OSM way under the tap (OSM splits ways where the surface changes; at most 10 km each side) or the favourite's line, in the marking mode favourites use, with end pins that can be dragged (and tapped near, as today), a Paved | Gravel choice (nothing picked until the rider picks one), and Save. Corrections are never drawn on the map (2026-10-21, the rider: it is information for routing, not a map layer). The stretch and its pins show only while marking. The road's card shows the corrected surface and what the map says, with edit and the bin. While a route or loop is shown, its sheet steps aside during the correction, and the route is found again with it.

## Options Considered

### A. Stretches as way spans + own line, by share of edge, in the rider's overlay (chosen)

| Dimension | Assessment |
| --- | --- |
| Fidelity | What the rider marks is what routes see (to the OSM node) |
| Rebuilds | Re-matched by geometry, as sections; deleted when they no longer fit |
| Hot path | One bit test per edge where a correction might apply; none without corrections |
| Complexity | Medium: share-aware cost terms, a new store table, backup entry, rematch |

**Pros:** exact; reuses the section model, draft, matcher and overlay; no file change. **Cons:** several cost terms change from yes/no to a share; more tests.

### B. Whole OSM ways (way id + surface)

| Dimension | Assessment |
| --- | --- |
| Fidelity | Only whole ways; no "the gravel stops here" |
| Rebuilds | A split or merged way silently changes what is covered |

**Pros:** smallest storage and code. **Cons:** can't do what the rider asked; anchoring by way id alone is fragile.

### C. Per edge, yes or no (an edge counts from half covered, like ridden roads)

| Dimension | Assessment |
| --- | --- |
| Fidelity | A stretch under half an edge does nothing; a long junction-free edge is all or nothing |
| Complexity | Low: `is_unpaved` gains an id, nothing else changes |

**Pros:** simplest cost change. **Cons:** what the rider marked and what the route does disagree, which he would see in the route's gravel figure at once.

### D. Write the corrections into the region file

| Dimension | Assessment |
| --- | --- |
| Rules | Against "never rebuild the graph"; a download replaces the file |

**Pros:** no query-time cost. **Cons:** breaks the fingerprinted, signed file model (ADR-0005, ADR-0008).

### E. Fix OSM itself instead

| Dimension | Assessment |
| --- | --- |
| Reach | Fixes it for everyone, the right long-term fix |
| Delay | An OSM account and editor, then up to a week to reach the weekly region |

**Pros:** no app data. **Cons:** not now, not on the phone, not offline; complementary, not a substitute.

## Trade-off Analysis

A costs more than C in the cost function, but C's mismatch between what was marked and what is routed would undo the feature: the rider drags an end and nothing changes. B and D fail the request or the rules. Folding the surface into the existing overlay keeps one object to build, pass and free, and leaves every routing FFI call as it is. Using the share for continuous terms and "half or more" for yes-or-no rules keeps today's behaviour exactly when shares are 0 or 1, which is every edge without a correction.

## Consequences

- **Easier:** honest gravel routing where OSM is wrong or silent; the route's gravel figure and dashes match the road.
- **Harder:** every unpaved test in the core goes through the overlay; corrections must be re-matched after region updates and carried in backups.
- **Security:** new store rows are validated on read (surface code, line, spans), new FFI calls take whole requests, the backup gains a hostile-input entry with caps, strict types and corruption tests.
- **Performance:** the overlay build reads one more table; routing without corrections is unchanged, and with them pays a bit test per edge (benchmark guards it).
- **Revisit if:** the rider wants speeds on corrected roads, or wants to see his corrections on the map.

## Action Items

- [ ] Store: schema 10, save (with replace and covered-older removal), list, look-up near a point, delete; validation; migration and corruption tests.
- [ ] Core: the stretch under a tap (the OSM way, at most 10 km each side).
- [ ] Core: surface in the overlay; share-aware cost, route figures, `SectionGravel`, loop waypoints, the search bound; unit tests.
- [ ] Core: corrections re-matched with sections on a region change; those that no longer fit deleted.
- [ ] Core: backup format 4 (entry, manifest count, caps, dedupe on restore); format 2 and 3 tests; corruption tests.
- [ ] Golden: corrections in the runner; a case each way (made-up corrections); every other case unchanged.
- [ ] FFI: the stretch, save, list, look-up, delete; report fields.
- [ ] App: drag handles on the marking ends; Correct surface on the road's and favourite's cards; the marking mode with Paved | Gravel; the notice after an update that removed any; the plan sheet stepping aside; backup counts; the how-to line.
- [ ] Phone test: correct an untagged road to gravel and see a gravel-avoiding route leave it; drag an end; edit and remove; back up and restore.
