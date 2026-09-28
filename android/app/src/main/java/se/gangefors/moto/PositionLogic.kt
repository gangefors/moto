// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.TrackPoint

/** A fix at most this old is where the rider is now. */
const val FRESH_POSITION_MS = 2 * 60_000L

/** An older fix than this is too far from where the rider may be to plan from. */
const val MAX_POSITION_AGE_MS = 30 * 60_000L

/** Fix times a little ahead of the phone clock still count (GPS vs system time). */
private const val POSITION_CLOCK_SKEW_MS = 5_000L

/** Where the rider is, for a loop or route from their position. */
sealed interface RiderPosition {
    /** A fix from the last [FRESH_POSITION_MS]. */
    data class Fresh(val fix: TrackPoint) : RiderPosition

    /** An older fix, up to [MAX_POSITION_AGE_MS]; the app says so. */
    data class LastKnown(val fix: TrackPoint) : RiderPosition

    /** No fix, or none recent enough: waiting for GPS. */
    data object None : RiderPosition
}

/** The fix to plan from, if there is one. */
val RiderPosition.fix: TrackPoint?
    get() = when (this) {
        is RiderPosition.Fresh -> fix
        is RiderPosition.LastKnown -> fix
        RiderPosition.None -> null
    }

/**
 * Where the rider is at [nowMs]: the newer of the recording's last fix and
 * the map's last known location, fresh or last known by its age.
 */
fun riderPosition(recording: TrackPoint?, map: TrackPoint?, nowMs: Long): RiderPosition {
    val newest = listOfNotNull(recording, map)
        .filter { it.timeMs <= nowMs + POSITION_CLOCK_SKEW_MS }
        .maxByOrNull { it.timeMs }
        ?: return RiderPosition.None
    val age = nowMs - newest.timeMs
    return when {
        age <= FRESH_POSITION_MS -> RiderPosition.Fresh(newest)
        age <= MAX_POSITION_AGE_MS -> RiderPosition.LastKnown(newest)
        else -> RiderPosition.None
    }
}
