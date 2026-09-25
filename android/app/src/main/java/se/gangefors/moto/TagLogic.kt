// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.NewTag
import se.gangefors.moto.core.Tag
import se.gangefors.moto.core.TrackPoint

/** A fix older than this is too stale to tag the road the rider is on. */
const val MAX_TAG_FIX_AGE_MS = 30_000L

/**
 * The fix a quick-tag uses: the fresher of the recording's last fix and
 * the map's last known location, if one is at most [maxAgeMs] old at
 * [nowMs]; null if there is none (no GPS yet).
 */
fun chooseTagFix(
    recording: TrackPoint?,
    map: TrackPoint?,
    nowMs: Long,
    maxAgeMs: Long = MAX_TAG_FIX_AGE_MS,
): TrackPoint? =
    listOfNotNull(recording, map)
        .filter { nowMs - it.timeMs in 0..maxAgeMs || it.timeMs in nowMs..nowMs + CLOCK_SKEW_MS }
        .maxByOrNull { it.timeMs }

/** Fix times a little ahead of the phone clock still count (GPS vs system time). */
private const val CLOCK_SKEW_MS = 5_000L

/** The tag for [fix], on the ride [trackId] if one is recording. */
fun newTag(fix: TrackPoint, trackId: Long?): NewTag =
    NewTag(
        timeMs = fix.timeMs,
        position = fix.position,
        headingDeg = fix.bearingDeg,
        speedMps = fix.speedMps,
        trackId = trackId,
    )

/**
 * Post-ride review of pending tags, oldest first: one tag at a time, each
 * saved as a section, discarded, or skipped (it stays pending for the next
 * review), until the last one or until the rider stops.
 */
class TagReview(tags: List<Tag>) {
    private val queue = tags.toList()
    private var index = 0

    val size: Int get() = queue.size

    /** Tags skipped so far in this review; they stay pending. */
    var skipped: Int = 0
        private set

    /** The tag under review, or null when all are done. */
    val current: Tag? get() = queue.getOrNull(index)

    /** 1-based position of [current], for "Tag 2 of 5". */
    val position: Int get() = index + 1

    /** Moves on to the next tag and returns it, or null at the end. */
    fun next(): Tag? {
        if (index < queue.size) index++
        return current
    }

    /** Leaves [current] pending and moves on, like [next]. */
    fun skip(): Tag? {
        if (current != null) skipped++
        return next()
    }
}
