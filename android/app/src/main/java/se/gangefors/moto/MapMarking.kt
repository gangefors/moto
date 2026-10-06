// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import se.gangefors.moto.core.SectionDraft
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
            is SectionMarker.State.Proposed -> o.draft.show(st.start, st.end, draft?.geometry)
        }
    }
}

internal fun MapScreenScope.stopMarking() {
    with(state) {
        marker.cancel()
        marking = false
        draft = null
        savingDraft = false
        // The marking steps ("10 km. Tap near an end…") end with it.
        message = null
        showDraft()
    }
}

internal fun MapScreenScope.draftMessage(d: SectionDraft) = with(state) { resources.getString(R.string.section_proposed, sectionKm(d.distanceM)) }

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
            is SectionMarker.State.Proposed -> {
                proposing = true
                overlays?.draft?.show(st.start, st.end, draft?.geometry)
                message = resources.getString(R.string.section_proposing)
                val session = markSession
                scope.launch {
                    val result = busy.run(R.string.busy_section) {
                        withContext(Dispatchers.Default) {
                            runCatching {
                                DebugTools.query("favourite draft") { ready.engine.sectionBetween(st.start.toLatLon(), st.end.toLatLon()) }
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
                            message = resources.getString(R.string.section_pick_end)
                            notify(coreErrorMessage(resources, e), long = true)
                        },
                    )
                    showDraft()
                }
            }
            else -> Unit
        }
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
        // A proposal still being found for the old points is dropped.
        markSession++
        proposing = false
        draft = null
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
