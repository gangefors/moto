# Golden routes across borders

Cases that need several regions linked at their borders (ADR-0009), in the same format as `../golden/` (see its README). CI runs them on small cuts of the Swedish, Norwegian and Danish regions, each cut at its country's border polygon:

```sh
moto-regionbuild sweden-latest.osm.pbf se.region --country SE --poly se.poly --bbox 55.3,10.9,59.35,14.0
moto-regionbuild norway-latest.osm.pbf no.region --country NO --poly no.poly --bbox 58.9,10.6,59.4,11.8
moto-regionbuild denmark-latest.osm.pbf dk.region --country DK --poly dk.poly --bbox 55.5,12.3,55.8,13.0
moto-regionbuild --golden se.region,no.region,dk.region moto-core/tests/golden-border
```

The polygons come from `https://polygons.openstreetmap.fr/get_poly.py?id=<relation>&params=0` (Sweden 52822, Norway 2978650, Denmark 50046). `motorways: true` and `tolls: true` allow motorways and toll roads (the app avoids them, and ferries, by default), for the Öresund bridge.
