// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackStepTest {
    /** The map screen's Back handling as it was written inline: the
     * `enabled` expression and the order of the `when`. */
    private fun oracle(b: BooleanArray): BackStep? {
        val (marking, addingVia, viaSelected, road, favourite) = b.take(5)
        val (route, loop, startPicked, savedRoute, section) = b.drop(5).take(5)
        val ride = b[10]
        val planning = route || loop
        val enabled = marking || addingVia || viaSelected || road || favourite || planning ||
            startPicked || ride || savedRoute || section
        if (!enabled) return null
        return when {
            marking -> BackStep.MARK
            addingVia -> BackStep.VIA_PICK
            viaSelected -> BackStep.VIA_SELECTED
            road -> BackStep.ROAD
            favourite -> BackStep.FAVOURITE
            route -> BackStep.ROUTE
            loop -> BackStep.LOOP
            startPicked -> BackStep.START
            savedRoute -> BackStep.SAVED_ROUTE
            section -> BackStep.SECTION
            ride -> BackStep.RIDE
            else -> error("enabled with no step")
        }
    }

    @Test
    fun everyCombinationMatchesTheInlineRule() {
        for (bits in 0 until (1 shl 11)) {
            val b = BooleanArray(11) { bits and (1 shl it) != 0 }
            val step = backStep(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10])
            assertEquals("inputs $bits", oracle(b), step)
        }
    }

    @Test
    fun nothingOpenLeavesBackToTheApp() {
        assertNull(backStep(false, false, false, false, false, false, false, false, false, false, false))
    }

    @Test
    fun markingIsLeftBeforeAnythingElse() {
        assertEquals(BackStep.MARK, backStep(true, true, true, true, true, true, true, true, true, true, true))
    }

    @Test
    fun aShownRideIsClosedLast() {
        assertEquals(BackStep.RIDE, backStep(false, false, false, false, false, false, false, false, false, false, true))
        assertEquals(BackStep.SECTION, backStep(false, false, false, false, false, false, false, false, false, true, true))
    }

    @Test
    fun aRouteClosesBeforeALoopAndTheStart() {
        assertEquals(BackStep.ROUTE, backStep(false, false, false, false, false, true, true, true, false, false, false))
        assertEquals(BackStep.LOOP, backStep(false, false, false, false, false, false, true, true, false, false, false))
    }
}
