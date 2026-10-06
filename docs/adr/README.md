# Architecture Decision Records

Each ADR uses the same template: Context, Decision, Options Considered (one `Dimension | Assessment` table per option, plus pros/cons), Trade-off Analysis, Consequences and Action Items.

| # | Title | Status | Date |
| --- | --- | --- | --- |
| [0001](0001-routing-engine-custom-rust-on-device.md) | Routing engine — custom Rust, on-device | Accepted | 2026-09-22 |
| [0002](0002-map-widget-maplibre-native.md) | Map widget — MapLibre Native Android | Accepted | 2026-09-22 |
| [0003](0003-map-tiles-openfreemap.md) | Map tiles — OpenFreeMap | Accepted | 2026-09-22 |
| [0004](0004-license-agpl-cla.md) | License — AGPL-3.0-only + sole copyright + trademark (CLA dropped 2026-10-01) | Accepted | 2026-09-22 |
| [0005](0005-region-file-format.md) | Region file format — memory-mapped binary sections | Accepted | 2026-09-23 |
| [0006](0006-section-and-track-storage.md) | Sections and tracks — stored by the Rust core in SQLite | Accepted | 2026-09-23 |
| [0007](0007-round-trip-generation.md) | Round trips — waypoint loops with a reuse penalty, scored by worth | Accepted | 2026-09-24 |
| [0008](0008-region-download.md) | Region download — built in CI, published as GitHub release files, installed by the core | Accepted | 2026-09-27 |
| [0009](0009-linked-regions.md) | Linked regions — one file per country, joined on the phone at the borders | Accepted | 2026-09-29 |
| [0010](0010-unridden-roads.md) | Unridden roads — rides as a query-time overlay that makes ridden curvy roads pull less | Accepted | 2026-10-01 |
| [0011](0011-ride-a-route.md) | Ride a route — follow a route in the app with progress and an off-route alert, no turn-by-turn | Accepted | 2026-10-02 |
| [0012](0012-backup-and-restore.md) | Backup and restore — one zip of standard files (GPX, GeoJSON, JSON), merged back in by the core | Accepted | 2026-10-03 |
| [0013](0013-agentic-change-workflow.md) | Change workflow — a pipeline of agents with two rider gates, routed by risk | Accepted | 2026-10-05 |
| [0014](0014-map-screen-structure.md) | Map screen structure — one state holder, a per-composition scope, pieces by concern | Accepted | 2026-10-05 |
| [0015](0015-tags-kept-until-reviewed.md) | Quick-tags — kept only until reviewed; backups hold the unreviewed ones | Proposed | 2026-10-05 |
