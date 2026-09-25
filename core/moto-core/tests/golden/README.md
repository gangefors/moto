# Golden routes

The regression set for route scoring (M2a) and round trips (M3). Each `*.json` file is one route or round trip on the M0 region: a start and an end, the favourite sections a rider has, and the properties the route must have. `moto-regionbuild --golden` routes every case and checks it; CI runs it on every build and shows this build's figures next to the last `main` build's.

```sh
cd core
cargo run --release -p moto-regionbuild -- --golden m0.region moto-core/tests/golden --json golden.json
```

## A case

```json
{
  "name": "Klippan to Helsingborg over a great road",
  "description": "Why the expectations hold; shown when the case fails.",
  "from": [56.134, 13.133],
  "to": [56.046, 12.694],
  "max_detour": 0.4,
  "min_gain": 1.0,
  "gravel": "avoid",
  "curvy": true,
  "favourites": [
    {"from": [56.13504, 13.00886], "to": [56.11903, 12.9975], "rating": "great", "one_way": false}
  ],
  "expect": {
    "min_favourite_share": 0.3,
    "max_favourite_share": 1.0,
    "min_curvy_share": 0.0,
    "max_detour_ratio": 1.4,
    "pass": [[56.09132, 12.97399]],
    "avoid": []
  }
}
```

- Points are `[lat, lon]`. The time budget is either `max_detour` (extra over the fastest route, default 0.4 as in the app) or `max_minutes` (the most minutes in all, as when arriving by a set time). The favourites pull as hard as the budget allows. `min_gain` is the guard: seconds of rating-weighted favourite riding (epic 1, great 0.7, good 0.4) each extra second must buy (default 1; 0 spends the whole budget). `max_detour_ratio` defaults to 1 + `max_detour`, and `max_minutes` is checked too. `curvy` (default true, as in the app) lets curvy roads pull the route too; cases about favourites or gravel alone turn it off. `min_curvy_share` checks the share of curvy road (each metre counted by how curvy it is), `min_unpaved_km` the kilometres on gravel. `gravel` is `"avoid"` (where possible, the default), `"allow"` or `"prefer"`, like the app's gravel choice; preferred gravel pulls the route like curvy roads do, and gravel on a favourite is never avoided.
- A favourite is marked like in the app: the road between two points, so a long road is a chain of short pieces (about 2 km), each ending where the next starts. `rating` is `good`, `great` or `epic`; `one_way` means only from `from` to `to`.
- `pass` points must be within 50 m of the route, `avoid` points further away.

## A round trip

```json
{
  "name": "Loop of 50 km from Höör",
  "description": "Why the expectations hold.",
  "from": [55.937, 13.542],
  "loop": {"km": 50},
  "expect": {"min_loops": 2, "min_curvy_share": 0.2}
}
```

- `loop` replaces `to`: `{"km": …}` or `{"minutes": …}`. Round trips have no time budget, so `max_detour`, `max_minutes`, `min_gain` and `max_detour_ratio` are refused.
- Every loop returned must be within ±15 % of the target, come back to the start, and ride at most 10 % of its length twice outside the home zone (the way out of town and home, 2–5 km around the start). The runner measures reuse from the line itself, not from the core. At least `min_loops` loops (default 2) must come back.
- The other expectations (`pass`, `avoid`, the shares) apply to the best loop.

## Adding cases

- When a real ride or route shows bad routing, add a case for it instead of tuning weights until that one route looks right. Never tune against a single route.
- Put checkpoints on the road itself, not at junction loops, and write down in `description` why the expectation is right.
- Change the scoring weights (`moto-core/src/scoring.rs`) only with a one-line hypothesis and a before/after comparison of all cases.

The first cases (2026-09-24) are synthetic, and so are the gravel cases (a public gravel road found by comparing routes with and without gravel): the favourite roads are real roads beside the fastest routes between towns, found by routing via points a few kilometres to the side, not roads anyone rated. Cases from real rides should follow.
