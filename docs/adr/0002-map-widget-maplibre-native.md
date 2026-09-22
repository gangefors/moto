# ADR-0002: Map widget — MapLibre Native Android

**Status:** Accepted · **Date:** 2026-09-22 · **Deciders:** Stefan · **Repo path:** `docs/adr/0002-map-widget-maplibre-native.md`

## Context

The app needs one interactive map view (PRD R11) that:

1. **Picks:** turns a tap into lat/lon for snapping (R2), route start/end and round-trip start.
2. **Draws:** shows the suggested route, alternatives and saved favourite sections as line layers.
3. **Hit-tests:** tells which drawn section the rider tapped.
4. Shows the rider's GPS position.

Routing and snapping are **not** map concerns. They live in the Rust core ([ADR-0001](0001-routing-engine-custom-rust-on-device.md)). The map only exchanges coordinates and GeoJSON with the core.

Forces: routing data is OpenStreetMap, so drawn lines must line up with drawn roads. Offline use matters. iOS comes later. It's a hobby project with no budget for API billing surprises.

## Decision

Use **MapLibre Native for Android** as the only map widget. It renders vector tiles (tile source: [ADR-0003](0003-map-tiles-openfreemap.md)).

- Route and sections: GeoJSON sources plus line layers (route, alternatives, favourites, and later curvature highlights).
- Tap handling: map click → coordinates → Rust `snap()`. Section taps use a rendered-feature query on the favourites layer.
- Map style URL and tile source live in app config, not in code.

## Options Considered

### Option A: MapLibre Native (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low–Med |
| Cost | Free, open source (BSD-2-Clause); no API key |
| Offline | Offline regions / local tile packages supported |
| Data alignment | Renders OSM-based tiles, so it matches the routing graph |
| Styling | Full vector styling (can emphasise curvy roads, fade motorways) |
| iOS | MapLibre Native iOS uses the same style spec |

**Pros:** no vendor lock-in or billing; fully stylable; offline capable; the same approach works on iOS.

**Cons:** needs a tile source; less polished defaults than Google; no built-in place/address search.

### Option B: Google Maps SDK for Android

| Dimension | Assessment |
| --- | --- |
| Complexity | Low |
| Cost | API key and billing account; terms of service apply |
| Offline | No developer-controlled offline maps |
| Data alignment | Google road geometry ≠ OSM geometry; lines may sit beside roads, and roads missing from OSM still show |
| Styling | Limited (cloud styling) |

**Pros:** polished, familiar map; excellent places and address data.

**Cons:** no offline control. The Maps Platform terms restrict using Google map content together with non-Google map data, which is at least a grey area for OSM-computed routes. Geometry doesn't match our graph.

### Option C: osmdroid

**Pros:** pure OSM; simple; offline raster tiles.

**Cons:** raster only (no vector styling); older API; Android only; less active development.

## Trade-off Analysis

The key trade-off is **polish and place search (Google)** against **data consistency, offline use, styling and portability (MapLibre)**. For a route planner whose output is OSM-derived lines, a map that renders the same data wins. Place search is a P1 and can be added separately (Nominatim or Photon). Google's ToS uncertainty alone would be reason enough to avoid building the core experience on it.

## Consequences

- **Easier:** exact alignment of routes and sections with roads; custom "motorcycle" styling; offline maps; the same style file on iOS.
- **Harder:** we choose, configure and attribute a tile source; address search needs its own service.
- **Revisit if:** MapLibre Android has blocking bugs on Stefan's phone, or a future feature truly needs Google data (unlikely for v1).

## Action Items

- [ ] Add the MapLibre Android dependency; a map screen with GPS location on Stefan's phone (M0).
- [ ] GeoJSON line layers for route / alternatives / favourites with distinct styling.
- [ ] Tap → Rust `snap()` → marker, measured < 500 ms (R11).
- [ ] Section hit-testing via rendered-feature query on the favourites layer.
- [ ] Map style URL in config; attribution control visible.
