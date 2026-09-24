# ADR-0007: Round trips — waypoint loops with a reuse penalty, scored by what they are worth

**Status:** Accepted · **Date:** 2026-09-24 · **Deciders:** Stefan · **Repo path:** `docs/adr/0007-round-trip-generation.md`

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
- **Legs:** each leg is an A* search with the M2 cost (favourites + curvature, a fixed pull) plus a **reuse penalty**: road geometries the loop already rides, either way, cost ×4. So the way back takes another road wherever one exists. Rider's rule (decisions log 2026-09-24): avoid riding the same road twice, but **crossing** the loop's own line is free (a crossing rides no road twice), and **short reuse** is allowed when it chains good roads together: the penalty is a factor, not a ban, so a few hundred metres back along a road to reach a favourite is taken when it is worth it.
- **Home zone:** round trips usually start at home, often in a town, where the way out and the way home share streets (rider's rule, decisions log 2026-09-24). Roads wholly within the home zone, 5 % of the target from the start (at least 2 km, at most 5 km), are not penalised and do not count as reuse.
- **Size:** after the first pass, each candidate's radius is scaled by D / (its length) once and the loop routed again; a loop outside ±15 % of the target is dropped. A duration target converts to distance with the region's typical speed first and is checked on duration.
- **Reuse:** the share of the loop's length that rides a road also ridden in the other direction (or the same direction twice), outside the home zone, is measured; loops over 10 % are dropped.
- **Score and variety:** loops are ranked by worth per second (favourites + curvature, as in M2). The best is kept, then the next best whose length overlaps less than 50 % with every kept loop, up to three loops. At least two are returned when two valid loops exist; otherwise as many as there are, or a typed error.
- **API:** `Engine::round_trip_with(start, target, opts, favourites) -> Vec<Route>` (and the FFI `Engine.roundTrip(start, target, opts, favourites)`), coarse like `route`. The route options' avoid and curvature settings apply; the time budget does not (the target is the budget).

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

## Action Items

- [x] Core: candidates, reuse penalty, size correction, scoring, variety filter; unit tests on fixtures; golden cases; benchmark metric. Done in dbc69a9: six golden loop cases; on the M0 region 157 ms mean, 340 ms p95 for 50 and 100 km loops with favourites.
- [x] FFI + app: "Loop from here", target choice, flip between alternatives, GPX share. Done in acdfe2f.
- [ ] Ride-check loops on the phone; bad loops become golden cases.
