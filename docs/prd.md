# PRD: Moto routing app — v1 (personal MVP)

**Status:** Draft · **Owner:** Stefan · **Created:** 2026-09-22 · **Platform:** Android (Kotlin) + Rust core · **Repo:** [gangefors/moto](https://github.com/gangefors/moto)

---

## Problem Statement

General-purpose navigation apps optimise for speed, so they route motorcyclists onto motorways and straight arterials. Riders who want a fun trip have to hand-plan routes around roads they already know are good — knowledge that lives in their head, not in any tool. Without a way to capture "this stretch is great" and have routing actually prefer it, every trip planning session starts from scratch and good roads get forgotten.

v1 solves this for a single rider (Stefan): capture favourite road sections easily, and generate one-way and round-trip routes that deliberately pass through them and through curvy roads. Community ratings are the long-term vision but are **not** in v1.

## Goals

1. **Capturing a favourite is effortless.** A road section can be saved in ≤ 3 taps while riding, or ≤ 30 s on the map at home.
2. **Routes are measurably more fun than a fastest route.** Generated routes include ≥ 50 % more favourited + high-curvature distance than the fastest route between the same points, within a user-set detour budget.
3. **Both trip types work.** One-way (A→B) and round-trip (start → loop of target length/time → start) are generated on the phone in < 10 s for trips up to 300 km.
4. **The route is rideable in existing nav.** Every route exports as GPX / hands off to a nav app without manual fixing.
5. **Architecture is ready for community + iOS.** Rust core holds scoring, section model and routing logic; data model supports multi-user ratings without migration pain.

## Non-Goals

- **Community ratings / accounts / sync** — v1 is single-user. The whole point of the project needs it eventually, but it adds backend, auth, moderation and privacy (GDPR) work before the core loop is proven.
- **Built-in turn-by-turn navigation** — OsmAnd/Google Maps/etc. already do this well; building voice guidance and rerouting would dwarf everything else.
- **iOS app** — deferred per decision log; keep logic in Rust so the port is cheap later.
- **Social features** (sharing rides, groups, feeds) — no users to share with yet.
- **Hazard/traffic/weather data** — nice context, not core to "find fun roads".

## Architecture Decisions

Decided 2026-09-22. Full records are in [`docs/adr/`](adr/):

- AD3 → [ADR-0001: Routing engine — custom Rust, on-device](adr/0001-routing-engine-custom-rust-on-device.md)
- AD1 → [ADR-0002: Map widget — MapLibre Native Android](adr/0002-map-widget-maplibre-native.md)
- AD2 → [ADR-0003: Map tiles — OpenFreeMap](adr/0003-map-tiles-openfreemap.md)
- AD4 → [ADR-0004: License — AGPL-3.0-only + CLA + trademark](adr/0004-license-agpl-cla.md)

**AD4. License: AGPL-3.0-only + CLA + trademark**

- \+ Anyone shipping or hosting a modified version must share their source; Stefan keeps sole relicensing rights (via CLA) for paid apps, App Store publishing and commercial licenses.
- − The CLA adds contributor friction; some companies avoid AGPL.

**AD1. Map widget: MapLibre Native Android**

- \+ Open source (BSD), no API key or billing, offline regions, fully stylable vector maps, iOS SDK exists, renders OSM data so routes and sections line up exactly with the drawn roads.
- − Needs a tile source; less polished than Google out of the box; no built-in place search (add Nominatim/Photon later).
- Rejected: Google Maps SDK (no offline control, ToS grey zone mixing with OSM routes, geometry mismatch), osmdroid (raster only, not cross-platform).
- One map widget does three jobs: **pick** (tap → lat/lon), **draw** (route + favourite sections as GeoJSON line layers), **hit-test** (tap a drawn section via rendered-feature query). It never does routing or snapping.

**AD2. Map tiles: OpenFreeMap**

- \+ Free, no API key, OSM vector tiles, zero setup.
- − No SLA, limited styles. Tile URL/style is config, so switching to MapTiler or self-hosted PMTiles later is a config change.
- Show OSM/OpenFreeMap attribution on the map.

**AD3. Routing engine: custom Rust, on-device**

- \+ Full control of the favourite/curvature cost function, works offline, lives in the shared Rust core so iOS reuses it, no server to run.
- − Most work: OSM import, graph build, A\*/bidirectional search and round-trip algorithm written ourselves; memory and file size must be tuned for the phone.
- Rejected: GraphHopper server (needs a server, no offline, per-user weights awkward), BRouter on-device (Java, not reusable on iOS, hard to inject favourites), hybrid (builds twice).
- Snapping taps/GPS points to roads (R2, R3, R4) lives in the Rust core next to the routing graph — one road network for sections, snapping and routing.

**Data flow**

- Offline build step (desktop/CI, Rust CLI): OSM extract (e.g. Geofabrik Sweden) → filtered motorcycle-routable graph + per-segment curvature → compact region file.
- App: region file loaded by Rust core → `snap(lat, lon)`, `route(a, b, opts)`, `round_trip(start, target, opts)` via UniFFI → GeoJSON/encoded polyline → MapLibre line layer.

## User Stories

Persona: **Solo rider (Stefan)** — plans rides at home, rides in southern Sweden, uses an Android phone mounted on the bike.

**Capturing favourites**

- As a rider, I want to quick-tag the road I'm on while riding so that I don't forget a great stretch I just discovered.
- As a rider, I want to record my ride's GPS track so that I can mark the best stretches of it afterwards.
- As a rider, I want to select a road section on the map by picking a start and end point so that I can add roads I already know are good.
- As a rider, I want to rate or unfavourite a saved section so that my routes reflect my current taste.
- As a rider, I want to see all my favourite sections on the map so that I can review and tidy them.

**Planning routes**

- As a rider, I want a one-way route from A to B that passes through my favourites and curvy roads so that the trip is fun, not just fast.
- As a rider, I want a round trip from my location with a target distance or duration so that I can go for a ride without a destination.
- As a rider, I want to limit the detour (e.g. max +40 % time vs fastest) so that the route stays realistic for the time I have.
- As a rider, I want to avoid motorways, gravel and ferries so that the route suits my bike and mood.
- As a rider, I want to see how much of a route is favourites / curvy, plus distance and time, so that I can compare options.

**Riding the route**

- As a rider, I want to export the route as GPX or open it in my nav app so that I get turn-by-turn guidance.

**Edge cases**

- As a rider with no favourites yet, I still want curvature-based routes so that the app is useful from day one.
- As a rider in an area with no mobile signal, I want quick-tag and track recording to work offline so that nothing is lost.
- As a rider, when no route satisfies my constraints, I want a clear explanation and the nearest alternative instead of a silent failure.

## Requirements

### Must-Have (P0)

**R1. Road section model (Rust core)**

A section is an ordered sequence of OSM way segments (with direction-agnostic default), plus rating, created date and source (map/tag/track).

- [ ] Sections snap to the road network; stored geometry survives app restarts.
- [ ] Model includes a `rider_id` field (always the local user in v1) so community aggregation can be added later.
- [ ] Sections that no longer match the map (OSM changes) are flagged, not silently dropped.

**R2. Mark section on map**

- [ ] Given the map is open, when I tap a start and end point on roads, then the app proposes the connecting road stretch and I can save it.
- [ ] I can adjust endpoints before saving.

**R3. Quick-tag while riding**

- [ ] One large on-screen button (usable with gloves) creates a tag at the current position/heading.
- [ ] Works offline; tags are stored locally.
- [ ] Post-ride, each tag becomes a suggested section (e.g. ±1–2 km along the road) that I confirm, trim or discard.
- [ ] Hardware trigger (media/volume button or BT remote) is P1.

**R4. Ride recording + mark from track**

- [ ] Background GPS recording with foreground service; survives screen off.
- [ ] After the ride, I can select a stretch of the track and save it as a section (map-matched).
- [ ] Battery use ≤ ~8 %/hour while recording (measure on Stefan's phone).

**R5. Curvature scoring**

- [ ] Every routable road segment gets a curvature score computed from OSM geometry.
- [ ] Scoring lives in the Rust core and is covered by golden-route regression tests.

**R6. Route generation — one-way**

- [ ] Given A, B and a detour budget, the app returns a route maximising favourite + curvature score within the budget.
- [ ] Avoid options: motorways, unpaved, ferries (toggleable).
- [ ] Computed on-device by the Rust core; returns in < 10 s for ≤ 300 km on Stefan's phone, fully offline once the region file is installed.

**R7. Route generation — round trip**

- [ ] Given a start and a target distance or duration (±15 %), returns a loop that does not reuse the same road in both directions for > 10 % of its length.
- [ ] Offers at least 2 alternative loops.

**R8. Route summary**

- [ ] Shows distance, estimated time, % favourite sections, % high-curvature, and delta vs fastest route.

**R9. Export / handoff**

- [ ] Export as GPX (track + route points) via Android share sheet.
- [ ] Verified to import correctly into at least OsmAnd and one other nav app.

**R10. Local storage & backup**

- [ ] All data stored on-device; manual export/import of sections (e.g. GeoJSON) so nothing is lost when changing phones.

**R11. Map view (MapLibre)**

- [ ] Shows OpenFreeMap vector basemap with correct attribution and current GPS position.
- [ ] Tap on map returns coordinates to the Rust core for snapping; snapped point is shown within 500 ms.
- [ ] Favourite sections and the suggested route are drawn as separate, distinguishable line layers; tapping a section opens it.
- [ ] The same map view is used for picking, reviewing favourites and showing routes.

**R12. Routing region data**

- [ ] A Rust CLI builds the region file (graph + curvature) from an OSM extract; start with Skåne, then Sweden.
- [ ] App can install/replace the region file (bundled or downloaded) and reports its OSM data date.
- [ ] Sweden region file size and peak routing memory are measured and fit comfortably on Stefan's phone (targets set after first build).

### Nice-to-Have (P1)

- **Hardware quick-tag** via Bluetooth handlebar remote or volume key.
- **Via points / must-include sections** in a route.
- **Rating scale** (e.g. 1–5) instead of binary favourite, weighting routes accordingly.
- **Save & name routes** for re-riding.
- **Import GPX** from other apps/rides as a source for marking sections.
- **Offline map tiles & routing data** for a chosen region (Skåne/southern Sweden first).

### Future Considerations (P2)

- **Community ratings:** accounts, sync, aggregated popularity weighting ("the more riders like it, the more likely it's included"), anti-gaming. → Keep `rider_id` in model; keep scoring function composable (personal + community weight).
- **iOS app** reusing the Rust core via UniFFI. → No Android types in core APIs.
- **Built-in navigation.**
- **Road surface / seasonal info** (e.g. roadworks, gravel on spring roads).
- **Section discovery:** suggest unrated high-curvature roads nearby.

## Success Metrics

Single-user v1, so metrics are personal and measured with local app logs + ride notes.

**Leading (first weeks of use)**

- Favourites captured: ≥ 30 sections within the first 10 rides.
- Quick-tag conversion: ≥ 70 % of quick-tags confirmed into sections after the ride.
- Route generation success: ≥ 95 % of requests return a route within the time target.
- Export success: 100 % of exported GPX open without fixes in the chosen nav app.

**Lagging (over a riding season)**

- Stefan uses the app to plan ≥ 75 % of leisure rides (vs manual planning or other apps).
- Post-ride self-rating of routes averages ≥ 4/5 for "fun".
- Fun-road share: median generated route has ≥ 50 % more favourite + curvy distance than fastest route (golden-route set).

## Open Questions

- ~~Routing engine~~ → decided: custom Rust on-device (AD3). ~~Map SDK~~ → decided: MapLibre Native + OpenFreeMap (AD1, AD2).
- **[Engineering — blocking]** Region file format and graph representation (e.g. CSR arrays + memory-mapped file); speed-up technique if plain A\* is too slow at 300 km (contraction hierarchies don't fit per-user weights well — consider ALT landmarks or a coarse/fine two-level graph).
- **[Engineering]** OSM refresh cadence and how to re-match saved sections after OSM edits.
- **[Engineering]** Offline basemap: MapLibre offline regions from OpenFreeMap vs a bundled PMTiles file for the region (P1).
- **[Engineering]** Round-trip algorithm: heuristic loop generation vs scoring many candidate loops; how to guarantee variety.
- **[Design]** Quick-tag UX with gloves and a mounted phone — screen button size, confirmation feedback (sound/vibration), accidental taps.
- ~~**[Product]** Is a section direction-dependent (some roads are better one way)?~~ → decided 2026-09-23: good in both directions by default, optionally marked one-way; rated good / great / epic; stored by the Rust core in SQLite ([ADR-0006](adr/0006-section-and-track-storage.md)).
- **[Product]** How to express the detour budget — % time, absolute minutes, or "fun level" slider?

## Timeline Considerations

No hard deadline; hobby pace, phased by milestones. Nice-to-align: usable for real rides by the start of the Swedish riding season.

1. **M0 — Foundations:** ADRs 0001–0003 written (commit to repo); Rust core skeleton with UniFFI + cargo-ndk running on the phone; MapLibre map with OpenFreeMap; Rust CLI builds a Skåne region file; tap → snap → draw a straight shortest path end-to-end (R11, R12).
2. **M1 — Capture:** R1–R4 + R10. Ride with recording and quick-tags; build up a real favourites set.
3. **M2 — Favourite and curvy routing:** R5, R6, R8, R9, in two parts. **M2a:** one-way routes over the rider's favourites within a detour budget, exported to nav app; golden-route regression set established. **M2b:** curvature added to the same cost, so riders without favourites still get curvy routes.
4. **M3 — Round trips:** R7. First "just go for a ride" loops.
5. **M4 — Polish / P1s** based on real-ride feedback.

**Dependencies:** OSM data licensing (ODbL attribution), OpenFreeMap availability (swappable tile source), Android background location permissions (Play policy only matters if later published).
