# ADR-0001: Routing engine — custom Rust, on-device

**Status:** Accepted · **Date:** 2026-09-22 · **Deciders:** Stefan · **Repo path:** `docs/adr/0001-routing-engine-custom-rust-on-device.md`

## Context

The app's whole point is routing that prefers **fun** roads: the rider's favourite road sections and curvy roads, within a detour budget. It must support one-way (A→B) and round-trip routes (PRD R5–R7, R12).

Forces at play:

- **Custom cost function is the product.** The routing cost combines distance/time, per-segment curvature, and per-rider favourites. The favourite weights change every time the rider saves a section. Later (P2) they will include community popularity.
- **Offline matters.** Rides go through areas with poor mobile coverage, and routes are often planned at a rest stop.
- **iOS later.** Platform-independent logic belongs in the shared Rust core so an iOS app can reuse it through UniFFI.
- **Single developer, hobby pace, no deadline.** There is no ops team; running a server is a cost in time as well as money.
- **Performance target:** < 10 s for a route up to 300 km on Stefan's Android phone.

## Decision

Build our own routing engine in **Rust**, in the shared core, running **on the phone**.

- A Rust **CLI** (run on desktop/CI) turns an OSM extract (Geofabrik: Skåne first, then Sweden) into a compact **region file**. The file holds a motorcycle-routable graph with per-edge length, speed estimate, road class, surface and curvature score.
- The app loads the region file (memory-mapped) and exposes, via UniFFI: `snap(lat, lon) -> RoadPoint`, `route(a, b, opts) -> Route`, `round_trip(start, target, opts) -> Vec<Route>`.
- Snapping taps and GPS points to roads (sections, quick-tags, track map-matching) uses the **same graph**, so sections, snapping and routing all share one road network.
- Personal favourite weights are applied at query time as an overlay on edge costs. The base graph is never rebuilt when favourites change.

## Options Considered

### Option A: Custom Rust engine, on-device (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | High: OSM import, graph build, search and round-trip algorithms are ours to write |
| Cost | Free to run; no server |
| Offline | Full, once the region file is installed |
| Cost-function control | Total; favourites and curvature are first-class |
| iOS reuse | Yes; the same Rust core |

**Pros:** full control of the scoring; offline; shared with iOS; no server to run; the region file and golden-route tests are fully reproducible.

**Cons:** the most up-front work. Phone memory and file size need tuning. Long-distance speed-ups must be engineered by us. Map-matching is also ours to write.

### Option B: GraphHopper, self-hosted server

| Dimension | Assessment |
| --- | --- |
| Complexity | Low–Med: mature Java server with custom models and a built-in round-trip algorithm |
| Cost | A VPS or home server, kept up and updated |
| Offline | None |
| Cost-function control | Good through custom models; per-user favourite weights need flexible mode or custom code |
| iOS reuse | Yes, via HTTP, but the logic is not in our core |

**Pros:** real routes working quickly; curvature and round trips come ready-made.

**Cons:** no offline use. It is a server to run. Per-rider weighting fights the engine's speed-up design.

### Option C: BRouter, on-device

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: embed or talk to the BRouter Android service |
| Offline | Yes (own segment data format) |
| Cost-function control | Scriptable profiles, but injecting per-user favourite sections is awkward |
| iOS reuse | No (Java/Android) |

**Pros:** proven offline routing on Android; profiles are expressive.

**Cons:** not reusable on iOS; its own data format; favourites would be a hack on top.

### Option D: Hybrid — GraphHopper now, Rust later

**Pros:** fastest path to riding with real routes; helps discover which cost function is fun before investing in the engine.

**Cons:** everything gets built twice; a server has to run in the meantime; the API contract must be frozen early.

### Also noted: Valhalla

C++, dynamic costing, has mobile builds. A credible alternative for dynamic costs. It was rejected because it adds a large C++ dependency and build chain alongside Rust, and injecting per-user favourites still requires modifying its costing.

## Trade-off Analysis

The deciding factor is that **the cost function is the product**. Engines built for speed (OSRM, GraphHopper's CH mode) bake weights into precomputed shortcuts. That conflicts with weights that change every time the rider saves a favourite, and later with community weights. Flexible modes exist, but then we pay the performance cost anyway and don't own the logic.

On-device plus offline plus iOS reuse only line up with a Rust core. The price is engineering effort, which is acceptable with no deadline, and the milestones control the risk: M0 proves the pipeline end-to-end with a plain shortest path before any clever routing.

Speed-up strategy (decide during M2):

- **Plain bidirectional A\*** on a Skåne-sized graph is likely fast enough. Measure first.
- **ALT (A\* + landmarks)** keeps working with changed weights, provided costs never drop below the base lower bound. Favourites should be **bonuses capped** so the adjusted cost stays ≥ a known fraction of base cost.
- **Customizable Contraction Hierarchies (CCH)** separate the expensive preprocessing from weight customisation (seconds). This is a good fit if Sweden-wide queries are too slow.

## Consequences

- **Easier:** tuning what "fun" means, and golden-route regression tests (deterministic, no network). Offline riding, and the iOS port.
- **Harder:** OSM parsing edge cases (turn restrictions, access tags, ferries). Keeping phone memory in check. Writing a round-trip generator.
- **We now own:** the region-file format and its versioning, the OSM refresh pipeline, and map-matching for recorded tracks.
- **Revisit if:** 300 km routes stay above 10 s after ALT/CCH, or the Sweden region file is too large for comfortable use on the phone. The fallback is Option D (server) for long routes only.

## Action Items

- [x] Rust CLI: parse Geofabrik PBF (e.g. the `osmpbf` crate) → filtered motorcycle graph → region file v1 (CSR arrays, memory-mapped, versioned header). Done in 52c2ff8 (format: ADR-0005).
- [x] Per-edge curvature score in the build step (R5), unit-tested on known roads. Changed by ADR-0005: the build stores curvature metrics and the score is computed at query time, so it can be tuned without a rebuild (07b4bbd).
- [x] `snap`, plain bidirectional A\* `route`, and UniFFI bindings; call them from Android (M0 slice). Done in bb42a50, d4bc3ee and d0159ea; plain one-directional A\* was fast enough (about 15 ms for 88 km routes on the build machine).
- [x] Favourite overlay: capped bonus per edge, applied at query time. Done in 47326ef.
- [ ] Golden-route regression set (Skåne) and timing on Stefan's phone. The set is in c04e043 and runs in CI; timing on the phone is still to do.
- [ ] Measure Sweden region: file size, load time, peak memory, 300 km query time. Decide on ALT vs CCH. File size measured (398 MiB, 6b5f282); the rest waits for the Sweden region.
- [x] Round-trip algorithm spike (R7). Decided in ADR-0007, done in dbc69a9.
