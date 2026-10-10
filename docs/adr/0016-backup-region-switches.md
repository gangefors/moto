# ADR-0016: Backup format 3 — a backup records which map regions were switched off

**Status:** Proposed · **Date:** 2026-10-10 · **Deciders:** the owner · **Repo path:** `docs/adr/0016-backup-region-switches.md`

Amends [ADR-0012](0012-backup-and-restore.md) (what the manifest holds, the formats read, the region offer after a restore) and [ADR-0015](0015-tags-kept-until-reviewed.md) (format 2 is no longer the only format read).

## Context

A region can be switched off without removing it (ADR-0009): it stays on the phone but isn't opened. A backup (ADR-0012, format 2) lists the map regions installed, on or off, in the manifest's `regions`, and nothing says which were off. After a restore the app offers to download the listed regions that aren't on the phone, and every download is installed switched on. So after a reinstall every restored region is on, also those the rider had switched off. The rider (2026-10-10): only the regions that were on before should be on afterwards.

Backups already made (format 2) must still restore.

Forces:

- **Security first.** The manifest is hostile input (ADR-0012): every new value is validated, and anything that doesn't fit is refused with a typed error, changing nothing.
- **Older backups.** A format-2 backup says nothing about the switches; the app has to pick a state for them.
- **Clear versions.** An app that meets a newer format should ask for an update, not call the file damaged (ADR-0012). Today the reader parses the whole manifest strictly (`deny_unknown_fields`) before it looks at `format`, so a newer manifest with a new field is reported as damaged.
- **Coarse FFI**, nothing Android-specific in the core (ADR-0001).

## Decision

- **Format 3.** The manifest gains `regions_disabled`: the keys of the regions that were switched off, a subset of `regions` (which still lists every region installed, on or off). The writer always writes it (`[]` when every region is on) and drops any key that isn't in `regions` or comes twice, so what it writes always reads back.
- **The reader reads formats 2 and 3.** Format 2 has no `regions_disabled`: a format-2 manifest with one is refused, and so is a format-3 manifest without one (`null` counts as missing). In format 3 every key must be in `regions` (so a valid region key) and listed once. Anything else is refused as not a valid backup, and nothing changes. Format 1 and older stay refused; a newer one asks for an app update.
- **A format-2 backup restores with every region switched on**, as it did before. It says nothing about the switches, every region it lists was installed, and on is the state in which the app can route straight after the download. Switching one off is one tap on the Map region page, and nothing is lost either way (the files are downloaded in both cases). Leaving them all off would leave the app with no map to route on after a reinstall, and no hint why.
- **The format is read first.** Before the manifest is parsed strictly, its `format` alone is read (other fields ignored): newer than the app knows asks for an update, older than 2 is not a valid backup. Only then is the whole manifest parsed with `deny_unknown_fields`. So the next format change gets the right message. (Apps already installed still call a format-3 backup damaged; the rider updates the app first.)
- **A restore keeps what the phone has.** The switches apply only to the regions the restore downloads (listed and not on the phone): one the backup had off is downloaded and installed switched off, in the same write that records it as installed and before the network is opened again, so it is never opened. A region already on the phone keeps its switch: the phone's choice is newer than the backup's. Which regions are offered and downloaded doesn't change (all listed and missing), nor does the offer's dialog.
- **FFI:** `SectionStore.write_backup(path, app, regions, regions_disabled, settings)`; `BackupSummary.regions_disabled` and `RestoreReport.regions_disabled` (empty for format 2).
- **App:** a backup passes the ids of the installed regions switched off. After a restore the offer carries the missing regions and those of them the backup had off, and its download installs those switched off. A region downloaded later from the Map region page (after "Not now", or after a failed download stopped the queue) is installed switched on, as today.

## Options Considered

### A. A `regions_disabled` list beside `regions`, format 3 (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low: one field, a subset check, the format read first |
| Security | Strong: checked against `regions` (already validated), no repeats, strict per format |
| Older backups | Format 2 read as before, every region on |
| Upgrades | A clear format step; the next one gets the right message |

**Pros:** additive; `regions` keeps its meaning, so the download offer is unchanged; mirrors the app's own "off" flag (absent means on).
**Cons:** an app from before this change can't read a format-3 backup (it calls it damaged).

### B. `regions` as a list of `{ "key", "enabled" }` objects, format 3

| Dimension | Assessment |
| --- | --- |
| Complexity | Higher: two shapes of one field, by format |
| Security | Fine, but a second parser path for the same data on hostile input |
| Older backups | Needs the old shape read alongside |

**Pros:** one list, each region with its state.
**Cons:** more parsing code on hostile input for no gain over A.

### C. An optional field without a format change

| Dimension | Assessment |
| --- | --- |
| Complexity | Lowest |
| Security | Same as A |
| Upgrades | Unclear: an older app calls a backup with the field damaged and reads one without it as all on |

**Pros:** backups with every region on stay readable by older apps.
**Cons:** the format number no longer says what a file holds, against ADR-0012's versioning.

### D. The switches as settings in `settings.json`

| Dimension | Assessment |
| --- | --- |
| Complexity | Low in the core, awkward in the app (a key per region; region keys have `-`, setting keys don't) |
| Security | Same caps as settings |

**Pros:** no manifest change.
**Cons:** settings apply at once, a region's switch only after its download; regions are kept out of settings on purpose (ADR-0012).

## Trade-off Analysis

A and B carry the same information; A does it with one field and no second shape to parse, and leaves the download offer as it is. C saves a version number but makes a file's meaning depend on the app that reads it. D puts phone state where it doesn't belong. A costs older apps the ability to read new backups, which matters little: a restore runs on the newest app.

## Consequences

- **Easier:** a reinstall brings the regions back as they were, on and off.
- **Harder:** the reader keeps two formats tested; dropping format 2 later is its own decision.
- **Security:** more manifest checks (subset, repeats, the field per format), covered by malformed-manifest tests; reading the format first parses the manifest twice (at most 8 MiB, already capped).

## Action Items

- [ ] Core: format 3 with `regions_disabled` (always written, checked per format, a subset of `regions` without repeats); formats 2 and 3 read; the format read before the strict parse; `BackupInfo`, `BackupSummary` and `RestoreReport` carry it; reproducing, format-2 and malformed-manifest tests.
- [ ] FFI: `write_backup` takes `regions_disabled`; `BackupSummary` and `RestoreReport` return it.
- [ ] App: a backup passes the switched-off regions; the restore offer installs them switched off (`Regions`, `BackupState`, `BackupLogic`), with unit tests.
- [ ] Phone test: two regions, one switched off; back up, uninstall, install, restore and download: only the one that was on is on. A format-2 backup restores with both on.
