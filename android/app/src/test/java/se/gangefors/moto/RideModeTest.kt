// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideModeTest {
    /** The map screen's ride mode flags as they were written inline. */
    private fun oracle(b: BooleanArray): RideModeFlags {
        val (recordingTrack, riding, planning, startPicked, marking) = b.toList()
        val freeRiding = recordingTrack && !riding &&
            !planning && !startPicked && !marking
        val rideMode = riding || freeRiding
        val pauseWanted = recordingTrack && !riding &&
            (planning || startPicked)
        return RideModeFlags(freeRiding, rideMode, pauseWanted)
    }

    @Test
    fun everyCombinationMatchesTheInlineRule() {
        for (bits in 0 until (1 shl 5)) {
            val b = BooleanArray(5) { bits and (1 shl it) != 0 }
            assertEquals("inputs $bits", oracle(b), rideModeOf(b[0], b[1], b[2], b[3], b[4]))
        }
    }

    @Test
    fun recordingWithoutARoutePausesWhilePlanningOrWithAStartPicked() {
        val planning = rideModeOf(recordingTrack = true, riding = false, planning = true, startPicked = false, marking = false)
        assertTrue(planning.pauseWanted)
        assertFalse(planning.freeRiding)
        assertFalse(planning.rideMode)
        val started = rideModeOf(recordingTrack = true, riding = false, planning = false, startPicked = true, marking = false)
        assertTrue(started.pauseWanted)
        assertFalse(started.rideMode)
    }

    @Test
    fun recordingWithNothingInHandIsFreeRiding() {
        assertEquals(
            RideModeFlags(freeRiding = true, rideMode = true, pauseWanted = false),
            rideModeOf(recordingTrack = true, riding = false, planning = false, startPicked = false, marking = false),
        )
    }

    @Test
    fun ridingARouteIsRideModeEvenWhilePlanning() {
        assertEquals(
            RideModeFlags(freeRiding = false, rideMode = true, pauseWanted = false),
            rideModeOf(recordingTrack = true, riding = true, planning = true, startPicked = false, marking = false),
        )
    }
}
