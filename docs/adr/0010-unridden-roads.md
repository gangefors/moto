# ADR-0010: Unridden roads — the rider's rides as a query-time overlay that makes ridden curvy roads pull less

**Status:** Accepted · **Date:** 2026-10-01 · **Deciders:** the owner · **Repo path:** `docs/adr/0010-unridden-roads.md`

## Context

Routes and loops are drawn to the rider's favourites and to curvy roads (ADR-0001, ADR-0007). A rider who has ridden the curvy roads around home gets those same roads offered again and again. "Avoid favourites" (2026-09-30) keeps off the roads the rider marked, but not off the curvy roads they have already ridden without marking them. The rider wants routes that seek out curvy roads none of his rides have been on (backlog "Roads never or seldom ridden", designed 2026-10-01).

The rides are already on the phone: recorded rides and GPX imports are tracks in the store (ADR-0006), and the core can map-match a track to the region's roads (`match_track`, used for ridden counts and for marking sections along a ride).

Forces:

- **The cost function is the product** (ADR-0001): this must make routes better, not just different, and be measured with golden routes before and after.
- **Never rebuild the graph** when rider data changes (CLAUDE.md): favourites are a query-time overlay; ridden roads must be too.
- **Fast:** routing must stay as fast as today (the benchmark guards it), and starting the app must not match every ride every time.
- **Privacy:** rides are personal data; they stay in app-private storage and on the phone.
- **Less clutter** (the rider): one setting and one figure, no new screens; the app is opinionated.

## Decision

- **Rides matched once.** Each finished ride (recorded or imported) is map-matched to the open regions once, and its matched OSM way spans (way id and node range, as for sections) are stored in a new table `track_ways` beside it (schema 7). The region key it was matched against is stored on the ride (`tracks.ways_key`). When the open regions change (an update, a region enabled, disabled or removed), every ride is matched again, as sections are (ADR-0006, `rematch`). A ride off every open map gets no spans until its region is back. Deleting a ride deletes its spans (foreign key cascade). A ride still being recorded is not matched.
- **A ridden overlay at query time.** The rider's overlay (`Favourites`, built by the store from the sections) also gets one bit per edge of the open regions: set when a ride's spans cover at least half of the edge, either way. It is rebuilt whenever the sections or the rides change, off the main thread, like today; the region graph is never touched. About 1 bit per edge: a few MB for all four countries.
- **Every ride counts, forever.** No time window and no fading back (2026-10-01: less clutter; not needed).
- **Scoring.** A new route option `unridden`: **Any** (the default, today's routes) or **Prefer**. With Prefer, a ridden edge keeps only `ridden_worth` of its **curvature** worth (`ScoringParams`, starting at 0.3; tuned with the golden before/after table). Favourite and gravel worth are untouched, and ridden roads cost no more time: they are still the way to get places, they just pull less. Curviness still spares a ridden road its dullness penalty (a curvy road is never dull). The search's lower bound stays valid, as worth only goes down. The detour guard (`min_gain`, `choice_gain`) and loop scoring use the same worth, so they follow.
- **Works with both favourites modes.** Prefer favourites + Prefer unridden: favourites still pull fully, and the curvy roads in between are new ones. Avoid favourites + Prefer unridden: away from both.
- **The unridden share.** Every route and loop reports `unridden_share`: the share of its length on edges no ride has covered. With no rides, it is 1. The app shows it whatever the setting (UI below).
- **UI** (mockup [Unridden roads setting](https://claude.ai/artifact/6DzaSTETioJRsDHyCSST6W), v3): "Unridden roads" (Any / Prefer) under Favourites, in Ride settings and on the route and loop sheets, one saved choice for all of them. On the resting sheet the share is a figure after curvy and before gravel: shown from 1 % (with its value, 100 % included: changed from the icon alone at 100 %, the rider, 2026-10-01), hidden under 1 %, on a blank sheet and while finding. A summary chip shows only with Prefer. Icon: a short curvy road with a big sparkle (`ic_unridden`).
- **Later, after the routing works:** a faint map layer of ridden roads with an on/off switch (2026-10-01).

## Options Considered

### A. Discount the curvy worth of ridden roads (chosen)

| Dimension | Assessment |
| --- | --- |
| Effect | Unridden curvy roads win when the time budget allows; ridden roads still link |
| Risk | Low: worth only goes down, the search's estimate stays valid |
| Tuning | One number (`ridden_worth`), set from golden runs |

**Pros:** small, measured, no new failure mode. **Cons:** a ridden road that is the only curvy road nearby still gets some pull (by design).

### B. Make ridden roads cost more time (a penalty, as for avoided favourites)

| Dimension | Assessment |
| --- | --- |
| Effect | Routes go out of their way to avoid any road ridden before, curvy or not |
| Risk | Around home every road is ridden: detours everywhere, and loops that can't leave town |

**Pros:** strong. **Cons:** punishes the roads that get the rider anywhere; a second detour budget to balance.

### C. A bonus for unridden roads instead

| Dimension | Assessment |
| --- | --- |
| Effect | Same direction as A |
| Risk | Raises worth above today's caps: the estimate's bounds and the guard need retuning |

**Pros:** none over A. **Cons:** touches every bound A leaves alone.

### D. Match rides on every build instead of storing their ways

| Dimension | Assessment |
| --- | --- |
| Storage | Nothing new |
| Speed | Every start and every section change matches every ride again |

**Pros:** no schema change. **Cons:** start-up time grows with every ride.

## Trade-off Analysis

A changes only what pulls, so routes stay sensible around home where every road is ridden; B would fight the rider there. Storing matched ways (against D) costs one small table and a re-match after region updates, which sections already do. Folding the ridden bits into the existing overlay keeps one object to build, pass and free, and one FFI parameter per routing call.

## Consequences

- **Easier:** finding new roads near home; seeing how new a route is.
- **Harder:** the store matches rides (on finish, import and region change); the overlay is rebuilt when rides change, not only sections.
- **Security:** spans come from our own matcher, but rows read back are validated like `section_ways` (way ids and node ranges checked before use); the overlay is built with `get` and checked arithmetic.
- **Privacy:** spans are derived from rides and live beside them in app-private storage; they are not exported (rides export as GPX, as today).
- **Revisit if:** golden runs show no setting of `ridden_worth` that finds new roads without silly detours, or start-up after a region update gets too slow with many rides.

## Action Items

- [x] Store: schema 7 (`track_ways`, `tracks.ways_key`), matching rides once and again after region changes; migration and corruption tests (ab574c5).
- [x] Core: ridden bits in the overlay; `unridden` route option and `ridden_worth`; `unridden_share` on routes and loops; unit tests (e5657b8).
- [x] Golden: rides in the runner, cases 48–50 (made-up rides), and a sweep of `ridden_worth` (d24d995): at 0.2 or less some routes fall back to dull roads, at 0.5 a ridden road is kept where another curvy way was as quick; 0.3 kept.
- [x] FFI and app: the option, the setting in Ride settings and on the sheets, the figure, the chip, the help key, `ic_unridden`; rebuild the overlay when rides change (274fbf6).
- [ ] Phone: plan in an area with several rides, Any against Prefer.
