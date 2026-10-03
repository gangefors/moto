# ADR-0012: Backup and restore — one zip of standard files (GPX, GeoJSON, JSON), merged back in by the core

**Status:** Accepted · **Date:** 2026-10-03 · **Deciders:** the owner · **Repo path:** `docs/adr/0012-backup-and-restore.md`

## Context

Everything the rider makes lives only in the app's private storage (ADR-0006): favourite sections, recorded and imported rides, saved routes and loops, quick-tags, and the settings. Reinstalling the app (not upgrading it) wipes all of it. Android's own backup is off (`allowBackup="false"`) on purpose: rides are a location history and must not go to a cloud the rider didn't choose.

Today the rider can export favourite sections (GeoJSON, PRD R10) and share rides one at a time as GPX. Nothing saves rides as a whole, saved routes, tags or settings. The rider (2026-10-03): a backup and restore that keeps everything that can't be fetched again, so the app can be reinstalled without losing data. Map regions and cached map tiles are left out: they are downloaded again.

Forces:

- **Security first.** A backup file comes back from outside the app, so it is hostile input (CLAUDE.md, Security): it must never crash, panic, run out of memory, write outside app storage or bring in anything that isn't validated.
- **Privacy.** The file holds where the rider has been. It goes only where the rider puts it.
- **Standard formats** (2026-10-03): rides and routes are GPX, settings a versioned JSON schema; no format of our own where a standard one fits.
- **Portable core.** Packing and reading the backup is logic iOS will need too, so it lives in `moto-core`, behind a coarse FFI call.
- **Big enough.** Years of rides: a 5-hour ride at one fix a second is 18 000 points, about 3 MB of GPX; hundreds of rides make a backup of hundreds of MB.

## Decision

- **One zip file**, `moto-backup-YYYY-MM-DD.zip`, saved through Android's file picker (`ACTION_CREATE_DOCUMENT`) wherever the rider chooses (Downloads, a USB stick, a cloud drive of their own). No new permission. It contains only known names:
  - `manifest.json`: the backup format version, the app version and schema version that wrote it, when it was made, the map regions installed when it was made, the SHA-256 and size of every other entry, and what GPX has no field for: each ride's name, start and end (Record pressed and stopped), each route's name, kind (route or loop), length, time and when it was saved, and each tag's review state and ride. The manifest is the one place the restore reads metadata from (no XML text to unescape); the GPX files also carry names, for other apps.
  - `favourites.geojson`: the favourite sections, exactly as the Sections page exports them today (the same file and the same tested importer, R10: name, rating, direction, OSM way spans and the section's own line). A second format for sections would be one more parser to keep safe. A restore also keeps each section's source and created and changed times, which the export already writes (an import ignores them).
  - `rides/NNNNN.gpx`: one GPX 1.1 file per ride, as Share writes it today: `<trk><name>`, a `<trkseg>` for each stretch recorded without a gap (the segment breaks, ADR-0011), `<time>` to the millisecond, and accuracy, speed and bearing in the existing `moto` extension (to two decimals). Oldest first, so a restore adds them in the same order.
  - `routes/NNNNN.gpx`: one GPX file per saved route or loop: `<trk><name>` and the line as found, without times.
  - `tags.gpx`: the quick-tags as GPX waypoints (`<wpt>` with `<time>`; heading as the bearing and speed in the `moto` extension), in the manifest's order.
  - `settings.json`: the app's settings (Ride settings, the map's zooms, sort orders, theme; not the map hints shown) as `{"schema": 1, "settings": {"key": value}}`: simple keys, and only booleans, whole numbers and text of at most 256 characters, at most 256 settings. They cross the FFI as typed pairs, and the core writes and reads the JSON (the app has no JSON parser); the app then applies only the keys it knows, of the right type and range, so an older or newer backup can still be read.
- **Not in the backup:** map regions and tile caches (downloaded again), derived data rebuilt after a restore (the OSM ways a ride was matched to, ADR-0010; sections' match status), a ride still being recorded, and the route a ride in progress follows. Debug data stays out.
- **Restore merges; it never deletes.** The rider picks a file (`ACTION_OPEN_DOCUMENT`). The core reads the manifest first and the app shows what is in it (dated, with counts) before anything is written. Restore adds what the phone doesn't have:
  - sections through the existing import and its overlap rules (so, as an import, a shorter favourite an added one covers is replaced);
  - a ride is skipped when one with the same fixes is already there (as a GPX import checks: each within a second and about a metre); its tags then belong to that ride;
  - a route is skipped when one with the same name and the same line is already there;
  - a tag is skipped when one at the same time and place is already there;
  - each setting in the backup replaces the phone's, and a setting the backup doesn't have stays as it is (2026-10-03; the dialog says so).

  Everything goes in one SQLite transaction: a restore lands whole or not at all. Restoring the same file twice changes nothing. Afterwards rides and sections are matched again in the background, as after an import (ADR-0006, ADR-0010). When settings were applied the activity is made again, so every screen reads them. If regions listed in the manifest aren't installed the app offers to download them: Download fetches the list of regions and downloads those, one after another, and opens Map region to show them coming.
- **Hostile-input rules** for the reader in `moto-core`:
  - The app copies the picked file into its own cache under a fixed name (at most 1 GiB, counted while copying) and hands the core that path; nothing from the file names a path. Entry names are looked up from a fixed list (`manifest.json`, `favourites.geojson`, `settings.json`, `tags.gpx`, `rides/` and `routes/` plus five digits and `.gpx`); any other name, or a name twice, is rejected.
  - Before the zip reader sees the file, the core checks the zip's end record: at the very end (backups have no comment), one disk, at most 20 000 entries and a directory of sane size, no zip64. So a crafted directory can't make the reader allocate without bound.
  - Caps: the file at most 1 GiB; at most 20 000 entries (9 990 rides, 9 990 routes, 100 000 tags); each entry read through a counting reader capped at its kind's limit (GPX 64 MiB as today, GeoJSON 64 MiB as today, manifest 8 MiB, settings 64 KiB) and at the size the manifest gives; at most 4 GiB unpacked in all. Entries are read one at a time, so memory stays at one entry.
  - Every entry's SHA-256 must match the manifest, and every value in the manifest is range-checked before anything is written. A mismatch, a missing entry or an entry the manifest doesn't list fails the restore, and the transaction leaves the database as it was.
  - GPX is read by the existing scanner (no XML parser, no entities or DTDs) and JSON by `serde_json` into strict types (`deny_unknown_fields`); every value is range-checked as on import today.
  - A backup with a newer format version than the app knows is refused with "This backup is from a newer version of moto. Update the app first."
  - Errors cross the FFI as `MotoError`; corruption tests (truncated, bit-flipped, oversized, wrong hashes, zip bombs, path tricks) prove the reader never panics.
- **FFI** (coarse, whole request in and whole result out): `SectionStore.write_backup(path, app, regions, settings) -> BackupSummary`, `backup_summary(path) -> BackupSummary` and `SectionStore.restore_backup(path, engine) -> RestoreReport` (counts added and skipped, the settings to apply, the regions to offer).
- **UI** (mockup [Backup and restore](https://claude.ai/artifact/RVnm7f6oKXDzEaBRwcwLQp), v2): a menu item "Backup" under Map region (2026-10-03), which opens a dialog titled Backup, explaining what a backup holds, with Backup, Restore and Cancel (the rider: "Backup" on the button too). Busy pill while writing or reading; a toast with the counts when done; the dialog shows when the last backup was made. Restore shows the backup's date and contents and asks first. A file that isn't a backup or is damaged, and one from a newer app, each get one message, and nothing changes.
- **No encryption in v1.** The file is plain, and the dialog says it holds where you've ridden so it belongs somewhere private. Encrypting with a password (Argon2id and an AEAD from a maintained crate) is a later option if the rider wants backups in places he doesn't trust.

## Options Considered

### A. Our own zip of standard files, merged by the core (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: the GPX and GeoJSON writers and readers exist; new are the manifest, settings schema, archive reader with caps, merge rules |
| Security | Strong: every entry goes through a validated reader with caps; nothing runs SQL from the file |
| Portability | Good: standard files any GPX tool can open; the core does it all, so iOS gets it |
| Upgrades | Good: a format version and the schema version; older backups read by newer apps |

**Pros:** safe by construction, readable outside the app, merges rather than overwrites, reuses tested code.
**Cons:** more code than copying a file; rides and routes lose nothing today, but new data kinds need adding to the format.

### B. Copy the SQLite database (and settings) into the zip

| Dimension | Assessment |
| --- | --- |
| Complexity | Low to write (`VACUUM INTO`), but restore needs hardening |
| Security | Weak: opening a database file from outside the app is a known SQLite attack surface (crafted b-trees, triggers, views); needs defensive mode, `integrity_check`, schema checks and copying row by row through validation anyway |
| Portability | Poor: only this app (and this schema family) can read it |
| Upgrades | Good: the migrations run on it |

**Pros:** complete by definition; quick to back up.
**Cons:** the restore path is the risky one, and making it safe ends up as much work as A; replace rather than merge.

### C. Android Auto Backup (Google Drive)

| Dimension | Assessment |
| --- | --- |
| Complexity | Low |
| Security / privacy | Rides go to Google's cloud with no choice; off by decision |
| Portability | Android only, tied to one Google account |

**Pros:** automatic.
**Cons:** against the privacy rule; doesn't help on a phone without Google or for a reinstall from a fresh account.

## Trade-off Analysis

A costs more than B to build but keeps the restore path inside readers we already harden and test, and gives files that outlive the app. B's apparent simplicity disappears once restore is made safe. C is ruled out by privacy.

## Consequences

- **Easier:** reinstalling, moving to a new phone, keeping rides as GPX outside the app.
- **Harder:** every new kind of rider data must be added to the backup format (and its tests) when it is added to the store.
- **Security:** a new hostile-input path, covered by caps, a fixed entry list, hashes and corruption tests.

## Action Items

- [x] Core: `backup` module: manifest and settings types, writer (zip, deflate), reader (end record check, fixed entry names, caps, hashes), merge rules and dedupe in one transaction; corruption tests (c4af60b; settings typed e7dbdb8).
- [x] Core: GPX writer for a line without times (routes) and for tags as waypoints; GPX reader for waypoints (c4af60b).
- [x] FFI: `write_backup`, `backup_summary`, `restore_backup` (b6b8723, e7dbdb8).
- [x] App: settings for a backup and from a restore (`RoutePrefs`, `BackupLogic`), validated; menu item, dialogs, busy pill, toasts, region download offer; the menu's line on the how-to page (d4ab751).
- [ ] Phone test: back up, uninstall, install, restore; restore twice; a damaged file.
