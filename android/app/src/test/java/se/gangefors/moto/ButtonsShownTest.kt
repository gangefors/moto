// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonsShownTest {
    @Test
    fun tagsToReviewMatchesTheInlineRule() {
        for (pendingTags in listOf(0, 1, 3)) {
            for (recording in listOf(false, true)) {
                for (regionReady in listOf(false, true)) {
                    val inline = pendingTags > 0 && !recording && regionReady
                    assertEquals("$pendingTags $recording $regionReady", inline, tagsToReview(pendingTags, recording, regionReady))
                }
            }
        }
    }

    @Test
    fun noTagsToReviewWhileRecording() {
        assertFalse(tagsToReview(pendingTags = 3, recording = true, regionReady = true))
        assertTrue(tagsToReview(pendingTags = 3, recording = false, regionReady = true))
    }

    /** The map screen's button flags as they were written inline. */
    private fun oracle(b: BooleanArray): ButtonsShown {
        val (regionReady, storeReady, hasLocation, rideMode, ridePanned) = b.take(5)
        val recording = b[5]
        val reviewShown = b[6]
        val showAddLoop = regionReady && hasLocation && !rideMode
        val showAdd = showAddLoop && storeReady
        val showLocate = hasLocation && (!rideMode || ridePanned)
        val showZoom = recording
        val showFlag = storeReady && reviewShown
        return ButtonsShown(showAddLoop, showAdd, showLocate, showZoom, showFlag)
    }

    @Test
    fun everyButtonCombinationMatchesTheInlineRule() {
        for (bits in 0 until (1 shl 7)) {
            val b = BooleanArray(7) { bits and (1 shl it) != 0 }
            assertEquals("inputs $bits", oracle(b), buttonsShown(b[0], b[1], b[2], b[3], b[4], b[5], b[6]))
        }
    }

    @Test
    fun everyColumnCombinationMatchesTheInlineRule() {
        for (bits in 0 until (1 shl 4)) {
            val (showFlag, showAdd, showAddLoop, showZoom) = BooleanArray(4) { bits and (1 shl it) != 0 }.toList()
            val inline = buildList {
                if (showFlag) add(MAP_BUTTON_DP)
                if (showAdd) add(MAP_BUTTON_DP)
                if (showAddLoop) add(MAP_BUTTON_DP)
                if (showZoom) add(ZOOM_BUTTONS_DP)
                add(MAP_BUTTON_DP)
            }
            assertEquals("inputs $bits", inline, rightColumnDp(showFlag, showAdd, showAddLoop, showZoom))
        }
    }

    @Test
    fun theRightColumnRunsFlagAddLoopZoomRecord() {
        assertEquals(listOf(56f, 56f, 56f, 105f, 56f), rightColumnDp(flag = true, addFavourite = true, loop = true, zoom = true))
        assertEquals(listOf(56f), rightColumnDp(flag = false, addFavourite = false, loop = false, zoom = false))
    }

    @Test
    fun theLocateButtonReturnsInRideModeOnlyAfterAPan() {
        val still = buttonsShown(
            regionReady = true, storeReady = true, hasLocation = true, rideMode = true, ridePanned = false,
            recording = false, reviewShown = false,
        )
        assertFalse(still.locate)
        assertFalse(still.loop)
        val panned = buttonsShown(
            regionReady = true, storeReady = true, hasLocation = true, rideMode = true, ridePanned = true,
            recording = false, reviewShown = false,
        )
        assertTrue(panned.locate)
    }
}
