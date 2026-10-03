# ADR-0011: Ride a route — follow a planned or saved route in the app, with progress and an off-route alert, no turn-by-turn

**Status:** Accepted · **Date:** 2026-10-02 · **Deciders:** Stefan · **Repo path:** `docs/adr/0011-ride-a-route.md`

## Context

Routes and loops leave the app as GPX for a nav app (PRD R9; Kurviger checked 2026-10-01). Stefan wants to ride a route in the app itself: see where he is on it, how far is left, when the next favourite comes, and be told when he leaves it, while the ride is recorded. Spoken turn-by-turn stays out (PRD non-goal): it needs turn restrictions, which the region file doesn't have, manoeuvre texts and voice timing, several times the work, and Kurviger already does it well.

What exists: the recording service keeps GPS running with the screen off (`RecordingService`, a foreground service); routes are lines with favourite parts and a time (`Route`); the core checks lines from the app (`handoff`) and can route between any two points.

Forces:

- **Glanceable on a moving bike:** large figures, few words, glove-sized targets; the phone sits in a holder, so vibration does little (Stefan).
- **Works with the screen off and when Android stops the app.**
- **The core stays portable** (CLAUDE.md): following is pure logic in `moto-core`; Kotlin only draws, alerts and wires the service.
- **Cheap per fix:** one call a second for hours, on a phone that is also recording.
- **Untrusted input:** the line and the fixes come over the FFI and are checked like any input.

## Decision

- **Core: `follow`.** A `RouteFollower` built from the route's line, its favourite parts with their ratings, and its time. It checks the line as `handoff` does (2 to `MAX_LINE_POINTS` points, finite, in range), precomputes the distance along the line at each point and a coarse grid of segments, and places each favourite part along the line. `update(fix)` takes one GPS fix (position, accuracy, speed, bearing if any, time) and returns the state:
  - **Matching** searches only a window around the last matched place (100 m back to max(1 km, 3 × speed × time since the last fix) ahead), so a road ridden twice (out and back, a figure eight, a loop's last kilometre on its first) matches the right pass; the bearing breaks ties between the two directions.
  - **Joining** until the rider reaches the route heading along it, anywhere on it (within its first kilometre with no bearing yet); progress then counts from where they joined. **Started** once they have been at its start heading along it; a loop joined part way begins at 0 when the rider reaches its start, rather than finishing there. **The wrong way:** 4 fixes on the route against its direction send it back to joining, flagged so the app can say so.
  - **On the route:** distance along, distance and time left (the route's time in proportion to the distance left), and the favourites that start within the next 2 km or that the rider is on (at most two, nearest first; touching favourites with the same rating count as one).
  - **Off the route:** more than max(40 m, 1.5 × accuracy) away for 3 fixes and at least 5 s; never declared with accuracy worse than 50 m (tunnels, forest). Back on within 25 m for 2 fixes; while off, the whole rest of the route is searched, so rejoining further on skips ahead.
  - **Finished:** within 30 m of the end with at least 90 % passed (a loop's start is not its end).
- **Core: the way back.** `Engine::rejoin` gives the quickest way from the rider to the route at the earliest point it can reach without riding the route backwards: it tries the last matched place and points 1, 3 and 8 km on, and keeps the least of (time to get there + time of the route skipped), so favourites are not given up for a small saving. It also says whether the way starts behind the rider ("Turn round": its first 100 m turn more than 120° from the bearing). Asked for after 5 s off, then at most every 10 s; when joining, it goes to the route's first kilometre.
- **Core: the ride being followed is kept** until the ride ends, in a new table beside the ride (schema 8): the line, favourite parts, name, time and whether it is a loop. **Segment breaks:** where recording restarts on the same ride, the store marks the first fix after the gap (a new table of breaks); GPX export starts a new `trkseg` there, and distance, drawing and matching don't bridge the gap.
- **FFI:** a `RouteFollower` object (a mutex inside) with `new` and `update`; `Engine::rejoin`. One call per fix.
- **App: service.** The route is handed to `RecordingService` in-process (unexported; nothing through intents). Each fix goes to `update` on the service's thread; `Recording.State.Active` carries the follow state, so it works with the screen off. Ride follows without recording until the rider has started the route at its start (Stefan, 2026-10-02); Record stays one tap away for the way there or a ride joined part way, and a ride already being recorded carries on. A recording Ride started itself is discarded when the rider turns out to be on the route the wrong way, and starts again at the start. Ending a ride that never recorded saves nothing.
- **App: alert.** A notification channel "Off the route": high importance, the channel's sound, no vibration. Posted once when the rider leaves the route, at most every 30 s, removed when back on. No new permission. The recording notification shows the progress ("42.2 km left · arrive 13:42").
- **App: ride screen** (mockup [Ride a route](https://claude.ai/artifact/5pVQN8xd3N3o3i5K21fRQx), v5 and Stefan's answers 2026-10-02):
  - **Start:** a filled Ride button in the planning sheet's switcher row, with Shuffle made an icon-only button; a Ride button on a saved route's card. New icon `ic_navigation` (a filled arrowhead).
  - **Map:** follows the rider, the way they are going up, the rider about 70 % down the screen, zoom by speed (15.5 slow to 14 at 90 km/h), moved by Ride settings' "Zoom while riding" (seven steps, one level closer to two wider) and by + and − on the map for the rest of the ride (Stefan, 2026-10-03: pinching is hard with gloves); a pan stops following and shows Recentre. MapLibre's own compass, under the card, shows while the map is turned; a tap fixes north up for the rest of the ride. (Our own always-shown compass was dropped on 2026-10-03 as it didn't match the map's; a better switch between north up and turning with the rider is to come.)
  - **Route look:** ahead as planned, behind grey, through a `line-gradient` on a source with line metrics, so each fix changes one paint property and the line is never sent again. The way back is a dashed line. Ridden roads hidden.
  - **Card** at the top: distance and time left; an 8 dp progress bar (at least 2 % filled) with the arrival time after it; one row per favourite near (at most two): star and rating in its colour and a distance, the one being ridden tinted and counting down what is left of it. Off the route: the card in the error colours, "Off the route", "Turn round: back on it in 0.4 km" when it applies. Joining: "To the route"; the wrong way: "To the start". The card's X stops following and keeps recording.
  - **End:** "Back at the start" / "You've arrived", a 15 s countdown, then recording stops; the ride is always saved and named (the rider deletes it if unwanted); Stop now ends it at once; the X keeps recording without the route.
  - **Carrying on:** if Android stops the app mid-ride, the next start asks "Carry on riding?". Carry on starts recording again on the same ride (a segment break) and follows the route from where the rider is; Save the ride finishes it as today and drops the route.
  - **While riding:** the menu, Ride settings, Loop and Add favourite buttons step aside; the tag button and Stop stay.
  - **Ride settings:** a group "Riding a route" with "Turn the map with your direction" and "Alert when off the route", both on; "Zoom while riding" in the Map group.
- **Debug tools:** time per update (mean and max per ride), rejoin timings, off-route events.

## Options Considered

### A. Follow and alert, no turn-by-turn (chosen)

| Dimension | Assessment |
| --- | --- |
| Work | Moderate: one core module, a store table, one screen |
| Data | Nothing new in the region file |
| Rider | Progress, favourites ahead, back to the route; directions still from the line on the map |

**Pros:** most of the value for a rider who planned the route. **Cons:** no spoken directions.

### B. Full turn-by-turn with voice

| Dimension | Assessment |
| --- | --- |
| Work | Several times A: turn restrictions in the region file and router, manoeuvres, roundabouts, voice timing |
| Risk | Without turn restrictions it would say illegal turns |

**Pros:** no nav app needed. **Cons:** cost; the PRD non-goal.

### C. Keep handing off to a nav app only

| Dimension | Assessment |
| --- | --- |
| Work | None |
| Rider | Two apps on a ride; the recording and favourites live in one, the route in the other |

**Pros:** nothing to build. **Cons:** what Stefan asked for is not there.

## Trade-off Analysis

A gives a ride in one app with what matters for a fun-roads route (progress, favourites ahead, back on the route) without the data and work spoken directions need. Keeping the logic in the core makes it testable with made-up rides and ready for iOS; drawing ahead and behind with a gradient keeps the map cheap on long routes. The off-route rule trades a few seconds of delay for no false alerts on noisy GPS.

## Consequences

- **Easier:** riding a planned loop without a second app; the ride is recorded and named as usual.
- **Harder:** the recording service carries more state; the store keeps the followed route and segment breaks.
- **Security:** the line and fixes are checked by the core; the followed route is read back like any row and checked; no new exported components or permissions.
- **Privacy:** the route and positions stay on the phone; the kept route is deleted when the ride ends.
- **Revisit if:** off-route alerts come too late or too often on real rides (tune the thresholds), or riders want spoken directions after all (needs turn restrictions first).

## Action Items

- [x] Core: `follow` with tests (straight, loop, out and back, figure eight, noise, gaps, poor accuracy, skipping ahead, joining, finishing, malformed input, a random-fix test that it never panics) (416401e); replayed in the benchmark along its Skåne routes instead of a golden route: 81 426 noisy fixes over 1628 km, none off the route, all 20 rides to the end.
- [x] Core: `rejoin` with tests; benchmark entries in `--check` and the CI comparison (`follow_us_mean`, `rejoin_ms_mean`, `rejoin_ms_p95`): 2.1 µs per fix, ways back 0.5 ms (65f8912).
- [x] Store: schema 8 (the followed route, segment breaks); distance, ride matching, ridden stats and GPX export honour breaks; tests (09c552a).
- [x] FFI and bindings (4a0d82f).
- [x] App: service, state, alert channel, notification text, Ride settings; ride screen (camera, compass, route look, card, Recentre, end countdown, carrying on); Ride buttons (sheet, saved route card), Shuffle icon-only, How to use topic, `ic_navigation`, debug timings; shown rides drawn by segment (128abd5).
- [x] Saved routes keep only their line: their favourite parts are worked out from the favourites as they are now when shown or ridden (Stefan, 2026-10-02: no stored parts to update when favourites change), and glow on the map too (`favourite_parts_along`).
- [x] Ride records from the route's start; joins anywhere along it; the wrong way back to joining (974cb03, e74f260).
- [x] Map's compass instead of our own; zoom while riding setting; + and − (9163bee, 00a2a05, 60d1d28).
- [ ] Phone: a loop with a deliberate wrong turn, the screen off, forest, the finish; carrying on after force-stopping the app.
