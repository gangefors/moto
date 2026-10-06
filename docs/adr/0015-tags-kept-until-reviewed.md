# ADR-0015: Quick-tags are kept only until they are reviewed

**Status:** Proposed · **Date:** 2026-10-05 · **Deciders:** the owner · **Repo path:** `docs/adr/0015-tags-kept-until-reviewed.md`

Amends ADR-0006 (the `tags` table) and ADR-0012 (the tags in a backup).

## Context

A quick-tag (PRD R3) marks a spot while riding; after the ride the review turns each one into a favourite section, discards it, or skips it for later. Today (ADR-0006, schema 3) a reviewed tag stays in the database with a status (`pending`, `used`, `discarded`), and a backup (ADR-0012) carries every tag with its review state. Nothing ever reads a reviewed tag again: the favourite it became remembers the road (with source "tag"), and a discarded one has no use. A restore brings reviewed tags back too, counted as tags in its message though no review will ever show them.

The rider (2026-10-05, option 1): only tags not yet reviewed are worth keeping, so he can come back to them later; delete a tag once it is reviewed; the backup keeps the unreviewed ones so nothing is lost; remove the code that only served reviewed tags. The app is not public, so there are no older apps or backups to stay compatible with.

Forces:

- **Clean data.** Every row in `tags` should mean "waiting for review"; no state to keep consistent.
- **Nothing lost.** A tag the rider hasn't looked at must survive skips, crashes, a failed save and a backup and restore.
- **Security.** The backup stays hostile input (ADR-0012): validated, capped, typed errors, corruption tests.
- **Coarse FFI** (ADR-0001): fewer calls, not more.

## Decision

- **A tag is deleted when it is reviewed.** Saved as a favourite section: the tag is deleted after the save returns (saved, or already covered by a favourite); if the save fails the tag stays. Discarded: deleted. Skipped, or the review stopped: kept. Every stored tag is unreviewed.
- **Schema 9:** reviewed rows (`status` 1 or 2) are deleted, the `tags_status` index and the `status` column are dropped, and an index on `time_ms` is added (the list order and the restore's duplicate check use it). One migration in one transaction, like the others; `secure_delete` overwrites the deleted rows.
- **Core and FFI:** `TagStatus`, `Tag.status` and `set_tag_status` go; `list_tags()` lists all tags (all unreviewed), oldest first; `delete_tag(id)` is the review's one write. The tag count for the map's flag is the length of that list.
- **Backup format 2:** the manifest's tag entries carry only the ride they were made on (no review state); `tags.gpx` and every check stay as in ADR-0012. The reader reads format 2 only: format 1 is refused as an unknown format and a newer one asks for an app update.
- **Restore adds every tag in the backup as unreviewed, except:**
  1. a tag at the same time (to the millisecond) and place (to 1e-7°) as one already there is a duplicate (counted in `tags_skipped`), so restoring twice adds nothing;
  2. otherwise, a tag lying on a favourite that was on the phone **before the restore** is taken as already reviewed (the rider, 2026-10-06: it was most likely saved as that favourite after the backup was made) and left out (counted in `tags_on_favourites`, a new field of the restore report). "On a favourite" is the distance part of the overlap rules' "covers" test (`overlap.rs`): within the same 15 m of the favourite's line, with the same distance function, in either direction (a tag has no rating or chosen direction, so those parts don't apply). Favourites the restore itself adds don't count: every tag in a backup was waiting when it was made, so after a reinstall all of them come back, also those on a favourite.

  The app counts both kinds as "already here" in its toast (no new text).
- **The review flag shows whenever there are unreviewed tags and today's rules allow it:** not while recording or riding a route (the rider kept this, 2026-10-06), not while marking, planning or with cards open, and only with a map region (a review needs the map); its place and look don't change. The count is read again after each tag and at the end of a review (as today) and now also after a restore; a failed read keeps the last count instead of hiding the flag.

## Options Considered

### 1. Delete a tag once reviewed (chosen)

| Dimension | Assessment |
| --- | --- |
| Complexity | Lower than today: one state fewer in the store, the FFI and the backup |
| Data | Only unreviewed tags, in the database and in backups |
| Restore | Duplicate check by time and place against the tags still waiting; a tag on a favourite the phone already had counts as reviewed |
| Risk | A migration that deletes rows and drops a column; tested through `Store::open` |

**Pros:** clean database and backup; less code; the count is simply the number of rows.
**Cons:** a restore can't know a discarded tag was reviewed after the backup was made, so it comes back; a quick-tag conversion rate (PRD success metrics) can't be counted from tags any more.

### 2. Keep the status, back up only unreviewed tags

| Dimension | Assessment |
| --- | --- |
| Complexity | As today plus a filter |
| Data | Reviewed tags pile up on the phone, unused |

**Pros:** smallest change; a reviewed tag still blocks its own restore.
**Cons:** keeps code and data the rider called useless.

### 3. Keep everything as today, show reviewed tags somewhere

| Dimension | Assessment |
| --- | --- |
| Complexity | Higher: a new screen for data nobody asked for |

**Pros:** full history.
**Cons:** no use for it in v1.

## Trade-off Analysis

Option 1 trades a rare restore case (a backup made before a review, restored after it on the same phone) for a model with one meaning per row and less code. The "on a favourite the phone already had" rule closes most of that case without keeping anything: on the same phone a backed-up tag that is no longer there was reviewed (a skipped tag is still there and is a duplicate), so if it lies on a favourite it was almost surely saved as one. Measuring against the phone's favourites before the restore, not the backup's, keeps a reinstall from dropping tags that were still waiting. What comes back is only extra review work, never lost data. Keeping tombstones of reviewed tags instead would be keeping reviewed tags under another name.

## Consequences

- **Easier:** the review, the backup and the store each lose a state; the badge count can't disagree with what a review shows.
- **Harder:** restoring an older backup on the same phone still brings back tags discarded since it was made when they lie off every favourite, and tags whose favourite was deleted since; the rider bins them again. A tag that left the phone without a review (only by losing data, e.g. app data cleared and favourites imported again) is dropped if it lies on a favourite. A tag within 15 m of a favourite is "on" it even where another road passes close by (a junction, a parallel road). Measuring quick-tag conversion would need its own counters (saved, discarded) if ever wanted.
- **Cost:** the check runs only on restore: the phone's favourites are turned into shapes once, then each non-duplicate tag is a bounding-box test and, near one, a distance to its line.
- **Data:** the first start after the update deletes the reviewed tags on the phone; unreviewed ones keep their ids, times, places and rides. Backups made before the update can't be restored (pre-release, none are kept).

## Action Items

- [ ] Core: schema 9 (delete reviewed tags, drop `tags.status` and its index, index `time_ms`); `TagStatus`, `Tag.status`, `set_tag_status` removed; `list_tags()` without a filter; migration test through `Store::open`.
- [ ] Core: backup format 2 (tag entries without a state, format 1 refused); restore adds every tag not already there and not on a favourite the phone had before the restore (`Shape::covers_point` in `overlap.rs`, sharing the cover distance with `covers`); `RestoreReport.tags_on_favourites`; tests, corruption tests still passing.
- [ ] FFI: `TagStatus`, `Tag.status`, `set_tag_status` removed; `list_tags()` without a filter; `RestoreReport.tags_on_favourites`.
- [ ] App: the review deletes a tag when it is saved as a favourite (after the save) or discarded; a failed save or delete keeps it and counts it as skipped; the flag's count is read again after a restore, and a failed read keeps the last count.
- [ ] Phone test: update over a database with reviewed and unreviewed tags; review, back up, restore, restore again.
