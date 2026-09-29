# ADR-0009: Linked regions — one file per country, joined on the phone at the borders

**Status:** Accepted · **Date:** 2026-09-29 · **Deciders:** the owner · **Repo path:** `docs/adr/0009-linked-regions.md`

## Context

The app holds one region at a time (ADR-0008): all of Sweden. Riders cross borders, and the rider wants riders to download the countries they ride in and then route and loop wherever they like, over land borders included (2026-09-29). Today a second country would replace Sweden: routes stop at the border, and the sections in the other country are flagged as no longer fitting the map.

The first step covers the Nordic countries: **Sweden, Denmark, Norway and Finland** (2026-09-29; the rest of Europe is too big a task for now). The design must still carry more countries, and big countries split into parts, without a new design.

Forces:

- **Security first** (CLAUDE.md): region files and everything that joins them are hostile input until verified; linking must never read out of bounds or panic on a crafted file.
- **Portability:** opening, linking and routing are core logic iOS needs too; Kotlin only stores files and choices.
- **Performance:** routing inside one region must stay as fast as today (the benchmark guards it); memory stays a few memory maps plus small tables.
- **Consistency:** two regions built from different months' data must still join, or fail safe where they don't.
- **Rules per country:** default speed limits differ (ADR-0005, "Speeds by country"), so a region belongs to one country.

Rejected earlier (2026-09-29): building regions on the phone from OSM extracts. Raw extracts are much larger than built regions (Norway 1.4 GB against about 240 MB), the build needs gigabytes of memory, and it would move parsing of large untrusted files onto the device.

## Decision

- **One region per country**, built in CI from that country's extract with its speed rules (`--country`). A country too big to build in one CI job is split into parts later; parts join exactly like countries.
- **Cut at the national border.** The builder clips the extract to the country's national boundary: the exact polygon of its OSM `admin_level=2` boundary relation (territorial waters included), fetched as a `.poly` file from polygons.openstreetmap.fr with no simplification or buffer (`params=0`). Neighbours' polygons share their border vertices exactly (checked 2026-09-29: Sweden–Norway 4 010, Sweden–Finland 8 910, Norway–Finland 6 918, Sweden–Denmark 70 at sea), so both sides cut on the same line. Point-in-polygon runs in integer arithmetic on 1e-7° coordinates, so both builds classify a shared point the same way; a point on the line counts as inside.
- **Stubs.** Each file keeps its roads up to and including the **first node past the border** (a *stub*). The road then crosses the border as the same edge in both files: A → B in one, with B a stub; A → B in the other, with A a stub. Stubs, points on the line, and the nodes next to them are always routing nodes (ways are split there), so the node a stub stands for exists as a routing node on the other side.
- **Border table** (format 1.3, a minor bump: older readers ignore it, ADR-0005): a section of border nodes (OSM node id, local node index, stub or not), sorted by OSM id, and a section with the region's ISO country code. Validated like every other section: ids strictly increasing, node indexes in range, a stub has at most a few edges and all of them lead inside, and a cap on the table's size.
- **Linking on the phone.** The core opens every *enabled* region and links each stub to the non-stub node with the same OSM id in another open region, as a zero-length, zero-cost connection. Routing works on the union: a node is (region, local index); routes, loops, snapping, map matching, road info, road names and coverage all run across the open regions. The link table is small (thousands of entries per border) and is built at open time, never stored.
- **Tolerant links.** A stub whose node is missing on the other side (the road at the border was rebuilt between two months' data, or the neighbour isn't open) simply doesn't link: that crossing is closed until both sides carry the same node; everything else links. The app notes when an open neighbour is from an older month.
- **Monthly, and only if changed.** The regions workflow runs monthly (and by hand). Before anything is downloaded, each country's sources (the extract's ETag and date, the border polygon's SHA-256, and the builder as the git tree of `core/`) are compared with those its published region was built from (`sources-vN.json` in the release); a country whose sources are all the same is not downloaded or built again (2026-09-29). A rebuilt region's file is uploaded only when its gzip's SHA-256 differs from the one in the published manifest (gzip `-n` makes equal regions byte-equal). The manifest keeps its schema (ADR-0008), so apps already installed keep working; the country comes from the region file itself.
- **Several regions installed; enable and disable.** The app installs each region into its own file (`regions/<id>.region`, the id checked as in ADR-0008), with its own fingerprint and data date, and offers updates per region. A region can be **disabled** without removing it: it stays on the phone but is not opened (no routing, snapping or linking through it, and the veil covers it); **enabling** it again needs no download. Updates are offered for disabled regions too but never fetched on their own.
- **Map region page.** One row per country (parts grouped under it): download or bin, an enable switch, size and data date; the total size on the phone at the top.
- **Sections.** Sections are matched against the open regions. A section that no open region covers (its region disabled or removed) is kept and shown as **not on an active map**, not as needing attention; it is matched again when its region is back. Sections across a border are matched across it, like routes.
- **Security.** At most 16 regions open at once; the border table is capped (1 M entries); links are built only between files that passed validation (full check or fingerprint, ADR-0005); every lookup is checked (`get`, checked arithmetic); corruption tests cover the border table and linking. The signed manifest and the per-file checksums are unchanged.
- **Licence.** Region files and the border polygons come from OpenStreetMap (ODbL); the polygons are fetched at build time and never stored in the repository.

## Options Considered

### A. Separate regions, one at a time (today)

| Dimension | Assessment |
| --- | --- |
| Complexity | None: already built |
| Borders | Routes stop at the border; switching country flags the other country's sections |
| Size | One country on the phone |

**Pros:** nothing to build. **Cons:** no trips across borders; switching is clumsy.

### B. One file for all four countries

| Dimension | Assessment |
| --- | --- |
| Complexity | Low: a bigger bbox and a merged extract |
| Size | About 1.5 GB on the phone for everyone, whatever they ride |
| CI | Memory for all four extracts at once exceeds a GitHub runner (Sweden alone peaks at 5.2 GB) |
| Growth | Doesn't scale to more countries |

**Pros:** cross-border routing with today's engine. **Cons:** huge for riders of one country; can't grow.

### C. Merge installed regions into one file on the phone

| Dimension | Assessment |
| --- | --- |
| Complexity | High: the builder's graph, grid and derive steps on the phone |
| Time and space | A full rewrite of hundreds of MB on every install, update and disable |
| Security | More code on the device writing files the core then trusts |

**Pros:** the engine stays single-file. **Cons:** slow, heavy, and a large new attack surface.

### D. Build regions on the phone from OSM extracts

| Dimension | Assessment |
| --- | --- |
| Download | Raw extracts, much larger than built regions |
| Device | Gigabytes of memory, long runs on battery |
| Security | Parses large untrusted files on the device |

**Pros:** any area. **Cons:** all of the above; rider-chosen areas are better served by smaller CI-built parts that link like countries.

### E. Linked regions (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: border cutting and a border table in the builder; a multi-region engine in the core; app storage and a page |
| Size | Only the countries a rider wants |
| Borders | Routes and loops across land borders, bridges and ferries that both sides carry |
| Growth | More countries and parts with the same machinery |
| Security | Small validated tables; links only between validated files |

**Pros:** what riders need, and it grows. **Cons:** a real refactor of the engine's node identity; a crossing can be missing for a while after one side is updated.

## Trade-off Analysis

E is the only option that gives cross-border trips without making every rider carry every country, and it keeps all checking in the core. The cost is the engine refactor: node ids become (region, index) pairs. Keeping links out of the files (they are built at open time from two small sorted tables) keeps each region file self-contained and independently updatable, which is what lets updates be monthly and per region. The price of that independence is the tolerant link: rarely, a border crossing is missing until both neighbours are updated; the alternative, publishing all regions together, would force downloads of countries that didn't change.

Exact national polygons make the cut consistent without coordination between the builds: both sides use the same OSM border ways, and integer point-in-polygon never disagrees on a shared point. The extract's own copy of the boundary relation was checked first: Sweden's extract holds all 178 of its ways but misses 27 of its 14 599 nodes (far out at sea), so the polygon service is used instead.

## Consequences

- **Easier:** a rider downloads the countries they ride in; routes and loops cross the Norwegian and Finnish land borders and the Öresund bridge; more countries later are data, not design.
- **Harder:** the engine routes over several memory maps; the benchmark and golden set gain cross-border cases; CI runs four build jobs a month; the app manages several files and choices.
- **Revisit if:** crossings go missing often after updates (then publish neighbours together), memory with many regions open is too high on the phone, or rider-chosen areas are wanted (smaller parts, same links).

## Action Items

- [x] Speed rules for SE, DK, NO and FI in the builder (`--country`, ADR-0005 "Speeds by country").
- [x] Builder: `--poly` (parse strictly, cap size), cut at the border with stubs, forced routing nodes at the border, border table and country code (format 1.3) (ca1a2ec). Nordic builds (2026-09-29): Sweden 404 MiB, 117 stubs; Denmark 150 MiB, 47; Norway 471 MiB, 101 (peak 10 GB); Finland 268 MiB, 31.
- [x] Core: open several regions and link their border tables (`Net`, 318cada); routing, loops, snapping, map matching, road info and names across regions; the four open with 184 links; Strömstad–Halden, Haparanda–Tornio, Stockholm–Oslo (538 km, 0.76 s), Kiruna–Narvik and Malmö–Copenhagen over the Öresund bridge route across. Benchmark on one region unchanged.
- [x] Cross-border golden cases in CI (e6b3af9): Strömstad–Halden over the old Svinesund bridge and Malmö–Copenhagen over the Öresund bridge, both ways, on small Swedish, Norwegian and Danish cuts built and cached in CI.
- [ ] A cross-border benchmark case (the benchmark still runs on the M0 region alone).
- [x] CI: monthly regions workflow building the four countries in turn with the `.poly` fetch; skip countries whose sources are unchanged, upload only regions that changed (cdf4629, 2cbc6e3).
- [x] App: one file per region, migration of today's Sweden file, the Map region page with enable/disable and per-region updates, the veil over all enabled regions (their outlines joined into one on open, 9f9130d: separate outlines overlap along the border, and the veil drew the overlap), "not on an active map" for sections (ac9363c, 1f95e19). Sections off the open map keep the status `needs_rematch` rather than a new one: widening the store's status check would rebuild the sections table, which cascades to their way spans.
- [ ] Phone: download two neighbouring countries, route and loop across the border, disable and enable one, and time long routes with all four installed.
