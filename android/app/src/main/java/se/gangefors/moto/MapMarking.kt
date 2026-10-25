// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import se.gangefors.moto.core.FavouriteSurface
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.NewSurfaceCorrection
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionDraft
import se.gangefors.moto.core.SectionUpdate
import se.gangefors.moto.core.SurfaceCorrection
import se.gangefors.moto.core.Tag
import se.gangefors.moto.debug.DebugTools

/** Tag review shows a tag or the section proposed for it close up, as
 * wide as a few streets. */
internal const val TAG_REVIEW_SPAN_M = 600.0


internal fun MapScreenScope.showDraft() {
    with(state) {
        val o = overlays ?: return
        when (val st = marker.state) {
            SectionMarker.State.Off, SectionMarker.State.PickStart -> o.draft.show(null, null, null)
            is SectionMarker.State.PickEnd -> o.draft.show(st.start, null, null)
            // A pin being dragged leaves the overlay: the one under the
            // finger stands for it (MarkHandles).
            is SectionMarker.State.Proposed -> {
                // A new favourite's sheet open: the line shows the
                // direction Save would give, with arrows.
                val (line, arrows) = draftPreviewLine(draft?.geometry.orEmpty(), savingDraft, draftPreview)
                o.draft.show(
                    st.start.takeIf { dragging != SectionMarker.End.START },
                    st.end.takeIf { dragging != SectionMarker.End.END },
                    line.takeIf { draft != null },
                    arrows,
                )
            }
        }
    }
}

internal fun MapScreenScope.stopMarking() {
    with(state) {
        marker.cancel()
        marking = false
        draft = null
        savingDraft = false
        keptDraftChoice = null
        draftPreview = null
        markKind = MarkKind.FAVOURITE
        surfaceChoice = null
        replacingCorrection = null
        dragging = null
        // A favourite's ends left any other way: what its sheet held goes.
        if (reshaping != null) pendingEdit = null
        reshaping = null
        // The marking steps ("10 km. Tap near an end…") end with it.
        message = null
        showDraft()
    }
}

internal fun MapScreenScope.draftMessage(d: SectionDraft) = with(state) {
    val text = if (markKind == MarkKind.SURFACE) R.string.surface_proposed else R.string.section_proposed
    resources.getString(text, sectionKm(d.distanceM))
}

/** Reads the number of tags waiting for review again; a failed read
 * keeps the last count (and the flag) as it was. */
internal fun MapScreenScope.refreshPendingTags() {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ready.store.listTags().size }.getOrNull()
            }?.let { pendingTags = it }
        }
    }
}

/** Quick-tag: saves the rider's current fix as a tag and buzzes. */
internal fun MapScreenScope.quickTag() {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        val active = recording as? Recording.State.Active
        val fix = chooseTagFix(active?.lastFix, mapFix(map), System.currentTimeMillis())
        if (fix == null) {
            buzz(context, ok = false)
            notify(resources.getString(R.string.tag_no_fix), long = true)
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ready.store.addTag(newTag(fix, active?.trackId)) } }
            buzz(context, ok = result.isSuccess)
            result.fold(
                onSuccess = { Toasts.show(resources.getString(R.string.tag_saved)) },
                onFailure = { notify(resources.getString(R.string.tag_failed, it.message ?: it.toString()), long = true) },
            )
            refreshPendingTags()
        }
    }
}

internal fun MapScreenScope.endReview(text: String?) {
    with(state) {
        stopMarking()
        review = null
        reviewTag = null
        message = null
        text?.let { notify(it) }
        refreshPendingTags()
    }
}

/** Shows [tag]'s suggested section in "mark section" mode, ready to trim or save. */
internal fun MapScreenScope.showTag(tag: Tag?) {
    with(state) {
        val r = review
        val ready = store as? StoreState.Ready
        val engine = (region as? RegionState.Ready)?.engine
        if (tag == null || r == null || ready == null || engine == null) {
            val skipped = r?.skipped ?: 0
            endReview(
                if (skipped > 0) {
                    resources.getQuantityString(R.plurals.tag_review_done_skipped, skipped, skipped)
                } else {
                    resources.getString(R.string.tag_review_done)
                },
            )
            return
        }
        stopMarking()
        reviewTag = tag
        marking = true
        markSession++
        val session = markSession
        proposing = true
        message = resources.getString(R.string.tag_review_loading, r.position, r.size)
        scope.launch {
            val result = busy.run(R.string.busy_section) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val track = tag.trackId?.let { ready.store.trackPoints(it) }
                        DebugTools.query("favourite suggestion") { engine.suggestSection(tag, track) }
                    }
                }
            }
            proposing = false
            if (session != markSession) return@launch
            result.fold(
                onSuccess = { d ->
                    val first = d.geometry.first()
                    val last = d.geometry.last()
                    marker.propose(LatLng(first.lat, first.lon), LatLng(last.lat, last.lon))
                    draft = d
                    message = resources.getString(R.string.tag_review_suggested, r.position, r.size, sectionKm(d.distanceM))
                    showOnMap(listOf(d.geometry), always = true, minSpanM = TAG_REVIEW_SPAN_M)
                },
                onFailure = { e ->
                    marker.begin()
                    message = resources.getString(R.string.tag_review_none, r.position, r.size, e.message ?: e.toString())
                    showOnMap(listOf(listOf(tag.position)), always = true, minSpanM = TAG_REVIEW_SPAN_M)
                },
            )
            showDraft()
        }
    }
}

internal fun MapScreenScope.startReview() {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val tags = withContext(Dispatchers.IO) {
                runCatching { ready.store.listTags() }.getOrDefault(emptyList())
            }
            review = TagReview(tags)
            showTag(review?.current)
        }
    }
}

/** Leaves the tag under review for the next review and moves on. */
internal fun MapScreenScope.skipTag() {
    with(state) {
        if (reviewTag == null) return
        showTag(review?.skip())
    }
}

/** Moves the review on from [tag] once it is handled; [removed]: deleted. */
internal fun MapScreenScope.tagHandled(tag: Tag, removed: Boolean) {
    with(state) {
        val r = review ?: return
        if (r.finish(tag.id, removed)) showTag(r.current)
    }
}

/** Discards the tag under review (deletes it) and moves on. */
internal fun MapScreenScope.discardTag() {
    with(state) {
        val tag = reviewTag ?: return
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val removed = withContext(Dispatchers.IO) { runCatching { ready.store.deleteTag(tag.id) }.isSuccess }
            tagHandled(tag, removed)
        }
    }
}

/** A tap in "mark section" mode: set the start, the end, or move the nearer end. */
internal fun MapScreenScope.onMarkTap(ready: RegionState.Ready, point: LatLng) {
    with(state) {
        if (proposing) return
        when (val st = marker.onTap(point)) {
            is SectionMarker.State.PickEnd -> {
                // Check the start lies on a road before keeping it.
                val problem = runCatching { DebugTools.query("snap") { ready.engine.snap(point.toLatLon()) } }.exceptionOrNull()
                if (problem != null) {
                    marker.rejectLast()
                    notify(coreErrorMessage(resources, problem), long = true)
                } else {
                    message = resources.getString(R.string.section_pick_end)
                }
                showDraft()
            }
            is SectionMarker.State.Proposed -> proposeBetween(ready, st.start, st.end)
            else -> Unit
        }
    }
}

/**
 * Finds the road between [start] and [end] (a tap moved an end, or a pin
 * was dragged there) and shows it as the draft. One the core can't find
 * puts the ends back as they were, with a notice.
 */
internal fun MapScreenScope.proposeBetween(ready: RegionState.Ready, start: LatLng, end: LatLng) {
    with(state) {
        proposing = true
        overlays?.draft?.show(start, end, draft?.geometry)
        message = resources.getString(R.string.section_proposing)
        val session = markSession
        scope.launch {
            val result = busy.run(R.string.busy_section) {
                withContext(Dispatchers.Default) {
                    runCatching {
                        DebugTools.query("favourite draft") { ready.engine.sectionBetween(start.toLatLon(), end.toLatLon()) }
                    }
                }
            }
            proposing = false
            if (!marking || session != markSession) return@launch
            result.fold(
                onSuccess = { d ->
                    draft = d
                    message = draftMessage(d)
                },
                onFailure = { e ->
                    marker.rejectLast()
                    val kept = draft
                    message = if (kept != null && marker.state is SectionMarker.State.Proposed) {
                        draftMessage(kept)
                    } else {
                        resources.getString(R.string.section_pick_end)
                    }
                    notify(coreErrorMessage(resources, e), long = true)
                },
            )
            showDraft()
        }
    }
}

/** Shows [d] as the proposal, its pins at its ends, and fits the map to
 * it above the task card. */
private fun MapScreenScope.proposeDraft(d: SectionDraft) {
    with(state) {
        val first = d.geometry.first()
        val last = d.geometry.last()
        marker.propose(LatLng(first.lat, first.lon), LatLng(last.lat, last.lon))
        draft = d
        message = draftMessage(d)
        showOnMap(listOf(d.geometry), always = true, minSpanM = TAG_REVIEW_SPAN_M)
        showDraft()
    }
}

/**
 * Starts marking a surface correction (ADR-0018): from the road card,
 * the OSM way under [at]; from a correction's pencil, [existing]'s own
 * stretch and surface. A plan stays (its sheet steps aside meanwhile);
 * the road's card closes.
 */
internal fun MapScreenScope.startSurfaceMarking(ready: RegionState.Ready, at: LatLon?, existing: SurfaceCorrection?) {
    with(state) {
        stopMarking()
        roadInfo = null
        roadCorrection = null
        overlays?.snap?.clear()
        marking = true
        markKind = MarkKind.SURFACE
        markSession++
        surfaceChoice = firstSurfaceChoice(existing)
        replacingCorrection = existing?.id
        if (existing != null) {
            proposeDraft(SectionDraft(existing.ways, existing.geometry, lengthM(existing.geometry)))
            return
        }
        val point = at ?: return stopMarking()
        val session = markSession
        proposing = true
        message = resources.getString(R.string.section_proposing)
        scope.launch {
            val result = busy.run(R.string.busy_section) {
                withContext(Dispatchers.Default) {
                    runCatching { DebugTools.query("surface stretch") { ready.engine.surfaceStretchAt(point) } }
                }
            }
            proposing = false
            if (!marking || session != markSession) return@launch
            result.fold(
                onSuccess = { proposeDraft(it) },
                onFailure = { e ->
                    stopMarking()
                    notify(coreErrorMessage(resources, e), long = true)
                },
            )
        }
    }
}

/** Saves the marked surface correction, replacing the one edited. */
internal fun MapScreenScope.saveSurfaceCorrection() {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        val d = draft ?: return
        val choice = surfaceChoice ?: return
        val replaces = replacingCorrection
        val session = markSession
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ready.store.addSurfaceCorrection(NewSurfaceCorrection(choice, d.ways, d.geometry), replaces)
                }
            }
            result.fold(
                onSuccess = {
                    surfaceVersion++
                    Toasts.show(resources.getString(R.string.surface_saved))
                    if (session == markSession) stopMarking()
                },
                // Marking stays, to try again.
                onFailure = { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }
}

/** Deletes a standalone surface correction (the bin already confirmed). */
internal fun MapScreenScope.deleteSurfaceCorrection(id: Long) {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ready.store.deleteSurfaceCorrection(id) }
            }
            result.fold(
                onSuccess = { surfaceVersion++ },
                onFailure = { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }
}

/** Sets a favourite's own surface back to the map's; the favourite stays. */
internal fun MapScreenScope.clearFavouriteSurface(sectionId: Long) {
    changeSections(null) { st -> st.update(sectionId, SectionUpdate(name = null, rating = null, direction = null, reverse = false, surface = FavouriteSurface.MAP)) }
}

/**
 * "Change start and end" in a favourite's edit sheet: the sheet closes,
 * keeping its choices, and the favourite's line (the one already moved
 * to, if any; marked afresh between its ends when it no longer fits)
 * is proposed with its pins, to drag or tap.
 */
internal fun MapScreenScope.startReshape(section: Section, choice: SectionChoice) {
    with(state) {
        val ready = region as? RegionState.Ready
        if (ready == null) {
            notify(regionStatus(resources, region), long = true)
            return
        }
        editing = null
        stopMarking()
        reshaping = Reshape(section, choice)
        marking = true
        markKind = MarkKind.FAVOURITE
        markSession++
        val pending = pendingFor(pendingEdit, section.id)?.line
        val line = pending?.let { it.ways to it.geometry } ?: reshapeStart(section)
        if (line != null) {
            proposeDraft(SectionDraft(line.first, line.second, lengthM(line.second)))
        } else {
            val first = section.geometry.first()
            val last = section.geometry.last()
            val start = LatLng(first.lat, first.lon)
            val end = LatLng(last.lat, last.lon)
            marker.propose(start, end)
            showOnMap(listOf(section.geometry), always = true, minSpanM = TAG_REVIEW_SPAN_M)
            proposeBetween(ready, start, end)
        }
    }
}

/** Save… while changing a favourite's start and end: back to its edit
 * sheet, with the new line pending and the choices kept. */
internal fun MapScreenScope.finishReshape() {
    with(state) {
        val r = reshaping ?: return
        val d = draft ?: return
        reshaping = null
        stopMarking()
        val (section, choice) = reshapeBack(r)
        pendingEdit = PendingEdit(section.id, choice, d)
        editing = section
    }
}

/** X or Back while changing a favourite's start and end: back to its
 * edit sheet as it was, the choices kept. */
internal fun MapScreenScope.reshapeCancel() {
    with(state) {
        val r = reshaping ?: return
        reshaping = null
        stopMarking()
        val (section, choice) = reshapeBack(r)
        // The line marked before, if any, stays pending.
        pendingEdit = PendingEdit(section.id, choice, pendingFor(pendingEdit, section.id)?.line)
        editing = section
    }
}

/** Back while marking: the last point placed goes, or marking (or the
 * tag review) stops when nothing is placed. */
internal fun MapScreenScope.markBack() {
    with(state) {
        if (reviewTag != null) {
            endReview(null)
            return
        }
        if (reshaping != null) {
            reshapeCancel()
            return
        }
        // A surface correction: Back cancels it, as in tag review.
        if (markKind == MarkKind.SURFACE) {
            stopMarking()
            return
        }
        // A proposal still being found for the old points is dropped.
        markSession++
        proposing = false
        draft = null
        // A pin taken away: the choices kept were for another line.
        keptDraftChoice = null
        when (marker.back()) {
            SectionMarker.State.Off -> {
                stopMarking()
                message = null
                return
            }
            SectionMarker.State.PickStart -> message = resources.getString(R.string.section_pick_start)
            is SectionMarker.State.PickEnd -> message = resources.getString(R.string.section_pick_end)
            is SectionMarker.State.Proposed -> Unit
        }
        showDraft()
    }
}
