# Golden routes

The regression set for route scoring (M2a). Each `*.json` file is one route on the M0 region: a start and an end, the favourite sections a rider has, and the properties the route must have. `moto-regionbuild --golden` routes every case and checks it; CI runs it on every build and shows this build's figures next to the last `main` build's.

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
  "favourites": [
    {"from": [56.13504, 13.00886], "to": [56.11903, 12.9975], "rating": "great", "one_way": false}
  ],
  "expect": {
    "min_favourite_share": 0.3,
    "max_favourite_share": 1.0,
    "max_detour_ratio": 1.4,
    "pass": [[56.09132, 12.97399]],
    "avoid": []
  }
}
```

- Points are `[lat, lon]`. `max_detour` is the detour budget (default 0.4, as in the app); `max_detour_ratio` defaults to 1 + that budget.
- A favourite is marked like in the app: the road between two points, so a long road is a chain of short pieces (about 2 km), each ending where the next starts. `rating` is `good`, `great` or `epic`; `one_way` means only from `from` to `to`.
- `pass` points must be within 50 m of the route, `avoid` points further away.

## Adding cases

- When a real ride or route shows bad routing, add a case for it instead of tuning weights until that one route looks right. Never tune against a single route.
- Put checkpoints on the road itself, not at junction loops, and write down in `description` why the expectation is right.
- Change the scoring weights (`moto-core/src/scoring.rs`) only with a one-line hypothesis and a before/after comparison of all cases.

The first twelve cases (2026-09-24) are synthetic: the favourite roads are real roads beside the fastest routes between towns, found by routing via points a few kilometres to the side, not roads anyone rated. Cases from real rides should follow.
