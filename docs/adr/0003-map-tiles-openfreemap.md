# ADR-0003: Map tiles — OpenFreeMap

**Status:** Accepted · **Date:** 2026-09-22 · **Deciders:** Stefan

## Context

MapLibre ([ADR-0002](0002-map-widget-maplibre-native.md)) needs a vector tile source and style. v1 has a single user, no budget, and no deadline. The tiles should be OSM-based so they match the routing graph ([ADR-0001](0001-routing-engine-custom-rust-on-device.md)). Offline basemaps are P1 and not required for M0–M2.

## Decision

Use **OpenFreeMap** hosted vector tiles and one of its OSM-based styles for v1.

- The style URL is in app config, so switching provider is a config change.
- Show the required attribution (OpenFreeMap, OpenMapTiles, © OpenStreetMap contributors).
- Revisit offline basemaps with the P1 "offline region" work.

## Options Considered

### Option A: OpenFreeMap (chosen)

- **Complexity:** Very low: a style URL
- **Cost:** Free, no API key or registration
- **Reliability:** Community/donation-run, no SLA
- **Styles:** A few ready styles (e.g. Liberty, Bright, Positron)
- **Offline:** Online only; offline needs a separate solution

**Pros:** zero setup; OSM data; no key to leak or quota to hit.

**Cons:** no SLA; limited styles; ties us to its update schedule.

### Option B: MapTiler (free tier)

**Pros:** polished styles, including outdoor/terrain with contours; good docs; commercial support.

**Cons:** needs an API key; free-tier usage limits; commercial dependency.

### Option C: Self-hosted PMTiles (Protomaps)

**Pros:** full control. One file (e.g. a Sweden extract) can be served from any static host or **bundled on the phone for offline use**. Stable.

**Cons:** we build and refresh the tiles and maintain a style.

## Trade-off Analysis

For a single-user v1, setup time matters more than an SLA. OpenFreeMap costs nothing to start with. Because the style URL is configurable, moving to MapTiler (for nicer styles) or PMTiles (for offline use) is cheap. PMTiles is the likely end state for offline maps, and it fits well with the region file we already ship for routing.

## Consequences

- **Easier:** getting a map on screen in M0; no keys or billing.
- **Harder:** nothing now. Offline basemaps are deferred.
- **Revisit when:** starting the P1 offline-region work (evaluate a bundled PMTiles file for Skåne/Sweden), if OpenFreeMap has an outage or rate problem, or if a terrain/contour style is wanted.

## Action Items

- [ ] Pick the OpenFreeMap style (start with Liberty) and put its URL in config.
- [ ] Attribution visible on the map screen.
- [ ] (P1) Spike: build a Skåne PMTiles file and load it offline in MapLibre.
