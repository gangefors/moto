# ADR-0007: Round trips — waypoint loops with a reuse penalty, scored by what they are worth

**Status:** Accepted · **Date:** 2026-09-24 · **Deciders:** the owner · **Repo path:** `docs/adr/0007-round-trip-generation.md`

## Context

M3 adds round trips (PRD R7): from a start and a target distance or duration, the app returns a loop within ±15 % of the target that does not ride the same road back for more than 10 % of its length, and offers at least two alternative loops. The PRD left the algorithm open ("heuristic loop generation vs scoring many candidate loops; how to guarantee variety").

Forces:

- The loop should be fun, not just a loop: the same worth as one-way routes (favourites and curvature, M2) should shape it.
- It must run on the phone in well under 10 s for loops up to 300 km (PRD), offline, in the Rust core, deterministically (the golden routes depend on it).
- Out-and-back is the natural failure of shortest-path loops: the way back is often the fastest way out, reversed.
- The same machinery is wanted later for "arrive by" routes with lots of spare time and for short favourites off the direct line (decisions log 2026-09-24): pick waypoints, route between them, score the whole.

## Decision

A round trip is a **loop through two waypoints** (start → W1 → W2 → start, a triangle), generated for a fixed set of candidates, each routed with the **one-way cost** plus a **reuse penalty**, and **scored by what it is worth**.

- **Candidates:** 12 headings (every 30°). For heading θ the waypoints lie at θ ± 30° from the start, at a radius r from the target distance D. A triangle with legs r, r and the chord between the waypoints (2r·sin 30° = r) is 3r long in a straight line; roads add a detour factor, so r = D / (3 × 1.3). Favourite sections within reach (their middle within 0.6 r … 1.4 r of the start and within 30° of the waypoint's bearing) replace the geometric waypoint, best-rated first, so loops can go through the rider's favourites. A waypoint with no road near it (the sea, the region's edge) is pulled in towards the start (1.0, 0.8, then 0.6 r); the size correction makes up the length.
- **Legs:** each leg is an A* search with the M2 cost (favourites + curvature, a fixed pull) plus a **reuse penalty**: road geometries the loop already rides, either way, cost ×4, on their full time: a road ridden again earns nothing for being a favourite or curvy (2026-09-29; scaling the favoured cost left an epic road ridden twice cheaper than a plain one). So the way back takes another road wherever one exists. Rider's rule (decisions log 2026-09-24): avoid riding the same road twice, but **crossing** the loop's own line is free (a crossing rides no road twice), and **short reuse** is allowed when it chains good roads together: the penalty is a factor, not a ban, so a few hundred metres back along a road to reach a favourite is taken when it is worth it.
- **Home zone:** round trips usually start at home, often in a town, where the way out and the way home share streets (rider's rule, decisions log 2026-09-24). Roads wholly within the home zone, 5 % of the target from the start (at least 2 km, at most 5 km), are not penalised and do not count as reuse.
- **Size:** after the first pass, each candidate's radius is scaled by D / (its length) once and the loop routed again; a loop outside ±15 % of the target is dropped. A duration target converts to distance with the region's typical speed first and is checked on duration.
- **Reuse:** the share of the loop's length that rides a road also ridden in the other direction (or the same direction twice), outside the home zone, is measured; loops over 10 % are dropped.
- **Score and variety:** loops are ranked by worth per second (favourites + curvature, as in M2). The best is kept, then the next best whose length overlaps less than 50 % with every kept loop, up to three loops. At least two are returned when two valid loops exist; otherwise as many as there are, or a typed error.
- **Other sets and a direction** (added 2026-09-25, the rider): `LoopOptions { seed, bearing }`. Seed 0 is the standard set above, the same every time; any other seed turns the headings by up to one step and varies each candidate's spread (10–70°, from narrow and long to wide and round) and each waypoint's size on its own (0.6–1.3, so loops can be lopsided; before 2026-09-26: spread 15–45° and one size 0.8–1.2), deterministically, so the app's Shuffle gives a new set that is the same for the same seed. A bearing fans the 12 candidates within 60° either side of it; when fewer than two loops fit that way, the best loops of any direction top the set up to two. In the app every new loop starts at the rider's Direction default (Ride settings, any way at first); a direction picked on the loop sheet holds for that loop, through Shuffle and other changes, and is not saved (2026-09-30, the rider).
- **API:** `Engine::round_trip_with(start, target, opts, favourites, shape) -> Vec<Route>` (and the FFI `Engine.roundTrip(start, target, opts, favourites, shape)`), coarse like `route`. The route options' avoid and curvature settings apply; the time budget does not (the target is the budget).

## Options Considered

### Option A: Waypoint triangles, penalised reuse, scored (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: candidate generation, one extra cost term, scoring |
| Speed | 12 candidates × 2 passes × 3 legs = 72 A* searches over legs of D/3; measured on the benchmark |
| Quality | Worth-driven legs; favourites can be waypoints; variety by overlap filter |
| Reuse | Penalty keeps the way back off the way out |
| Reuse later | The same waypoint machinery serves "arrive by" and off-line favourites |

**Pros:** simple, deterministic, fast enough, reuses the M2 cost; alternatives come for free from the candidates.

**Cons:** loop shapes are limited to triangles; the size correction is one step, so some headings miss ±15 % and are dropped.

### Option B: Search for loops directly (e.g. bounded DFS / random walks scored by worth)

| Dimension | Assessment |
| --- | --- |
| Complexity | High: loop search in a large graph, pruning heuristics |
| Speed | Hard to bound on the phone |
| Quality | Can find odd shapes; hard to keep sensible |

**Pros:** in theory the best loops.

**Cons:** unbounded work, hard to test, non-obvious results.

### Option C: Isochrone + return (go out along the best road for D/2, come back by another)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium |
| Quality | Tends to out-and-back shapes; one loop, little variety |

**Pros:** easy to explain.

**Cons:** the return is exactly where out-and-back happens; poor variety.

## Trade-off Analysis

Option A trades the theoretical best loop for predictable, testable work: a fixed number of A* searches per request, each already tuned and benchmarked for one-way routes. The reuse penalty is the one new idea and is local to the cost. Waypoints give a natural hook for favourites and for the later "arrive by" search.

## Consequences

- The route cost gains a reuse term (an edge set per loop); one-way routes are unaffected.
- The golden routes get round-trip cases (target, expected length band, reuse limit, loops returned), and the benchmark a round-trip metric.
- Loop shapes are triangles; if rides show that too limiting, a quadrilateral pass can be added without API changes.
- **Loops through given points (2026-09-28):** the same legs can run through points the rider chose instead of generated waypoints: `round_trip_via(start, stops, both_ways)` rides from the start through the stops in order and back, each leg avoiding the roads the earlier ones took (the reuse penalty, home zone and side-loop cut as above, sized by the rough length out to the farthest stop and back). For a two-way stretch it also tries the stops the other way round and returns both, best first. The app uses it to ride a favourite section from where the rider is (Sections → Loop through it: the section's two ends as the stops), shown in the route sheet. There is no length target: the stops set the size. The stops are ridden through, never trimmed as guides are: an out-and-back at a guide is cut, never back past a stop, and the way between two stops is kept whole; side loops are cut on the way out and home, never through the stops (2026-09-29). Straight home from the far end can be back along the section, cheap as a favourite even with the reuse penalty (2026-09-29, found on a real ride), so each loop through the stops is made like an ordinary loop (2026-09-29): the start is one corner of the triangle and two waypoints the others, placed as above (at a favourite in that direction when there is one, else on a road fit for a loop, pulled in if need be). The first corner lies past the far end, 35 % of the way home from it (3–20 km), straight on or turned 45° or 90° either way; the second lies half way from the first to home, swung 40° off that line away from the way out (left out when the first is within 6 km of home). The loop is start → stops → corners → start, the stops ridden through, the corners ordinary waypoints; the loop straight home from the far end is a candidate too. The way out keeps off the roads between the stops as if already ridden, so a section that runs towards home (one way, ridden from its start) isn't reached by riding up it. Of all of them (both ways round), those riding at most 10 % twice are kept when any do, and up to three that differ are returned, best first, as for ordinary loops. The home zone reaches at most half way to the nearest stop, so a section near home counts as ridden once it is.

- **No spurs to a guide (2026-09-29, found on a real ride):** a waypoint 33 m before the end of a 2 km road led the next leg on to the junction, round and all the way back: a 2 km out-and-back, kept because it rode a favourite. The cut where legs meet is now a reduction piece by piece: a piece that carries on along the same edge joins the last one, and a piece riding back along the road just ridden (the twin edge) cancels it however far back it goes, favourite or not; stops the loop must visit are still never touched. It runs again after the side-loop cut, which can leave the two halves of an out-and-back facing each other (a turn through a junction's triangle, cut out). A side loop stays only when at least half of it rides favourites (before: any favourite on it), so a turn that just touches one is cut. Waypoints (geometric ones and favourites used as waypoints) never lie on a one-way road: on a dual carriageway the loop would ride on to the next turning and back up the other carriageway, an out-and-back no trim can see; a favourite there still pulls, the waypoint goes where it would without it. On 150 random starts with made-up favourites (2 h loops, M0 region), loops with an out-and-back of 500 m or more fell from 14 to 1 (inside its home zone, where riding out and back is allowed); golden case 42.- **Paid crossings only when worth it (2026-09-30, the rider):** heading west from the coast by the Öresund every waypoint lands in Denmark, so 150–300 km loops rode over the bridge and back for the little they ride there. A second on an avoided toll road or ferry now takes `paid_worth` (3) off a loop's worth, and a loop over one is offered only when it is worth, per second, at least as much as the best loop without one; when every loop in the chosen direction crosses one, the free loops of every direction are looked at too and come in its place. Allowing tolls or ferries brings such loops back. On 180 loop sets from six towns by the sound (100–400 km, west and any way), the first loop went to Denmark in 13 sets instead of 53, none at 150–300 km; curvy share 17.9 → 20.7 %. Border golden case 05.
- **Long loops with the sea around, and parallel search (2026-09-30, the rider):** candidates are worked out side by side on up to 4 threads, results in their order, so loops are unchanged (e4ea50d; 150 long-loop sets p50 2.1 → 0.9 s). Waypoints are pulled in to 45 % and 30 % too, and when a set's candidates give fewer loops that differ than it shows, those that gave none are tried again as a zig-zag: three waypoints, far, near (half as far) and far, spread 1.5 × the candidate's spread, sized to be as long as the triangle, so the length fits into less land; in a chosen direction only once it has two loops (e25240a). Sets with three loops 97 → 118 of 150, curvy share kept, time p90 4.0 s, max 8.6 s (the rider's limit for long loops: 5–10 s, never 20). Tried and left out: swinging waypoints along the coast, a second resize, five-waypoint zig-zags (slower for little more).
- **Avoiding favourites (2026-09-30, the rider):** the route options can avoid the rider's favourites instead of preferring them, to find new roads (4115867). Loops then place no waypoint at a favourite, and the legs treat favourites as roads to avoid: worth nothing, curvy or not, and three times their time in either direction, so a favourite that is the only sensible way is still ridden. The choice is a remembered setting like gravel (Prefer by default). Golden case 46.

## Action Items

- [x] Core: candidates, reuse penalty, size correction, scoring, variety filter; unit tests on fixtures; golden cases; benchmark metric. Done in dbc69a9: six golden loop cases; on the M0 region 157 ms mean, 340 ms p95 for 50 and 100 km loops with favourites.
- [x] FFI + app: "Loop from here", target choice, flip between alternatives, GPX share. Done in acdfe2f.
- [x] Loops through given points (`round_trip_via`), for riding a saved section from here.
- [ ] Ride-check loops on the phone; bad loops become golden cases.
