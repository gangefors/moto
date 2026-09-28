// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollHintsTest {
    @Test
    fun noThumbWhenNothingScrolls() {
        assertNull(scrollThumb(viewport = 500f, max = 0f, value = 0f, minLength = 30f))
        assertNull(scrollThumb(viewport = 0f, max = 100f, value = 0f, minLength = 30f))
    }

    @Test
    fun thumbShowsTheShareInView() {
        // Half the content in view: half the track.
        assertEquals(Thumb(0f, 250f), scrollThumb(500f, 500f, 0f, 30f))
    }

    @Test
    fun thumbFollowsTheScroll() {
        assertEquals(Thumb(125f, 250f), scrollThumb(500f, 500f, 250f, 30f))
        assertEquals(Thumb(250f, 250f), scrollThumb(500f, 500f, 500f, 30f))
    }

    @Test
    fun thumbKeepsItsMinimumLength() {
        // 1 % in view would be 5 px; stays grabbable-looking at 30.
        val t = scrollThumb(500f, 49_500f, 49_500f, 30f)!!
        assertEquals(30f, t.length)
        assertEquals(470f, t.start)
    }

    @Test
    fun thumbStaysOnTheTrack() {
        // Overscroll and a minimum longer than the track.
        assertEquals(Thumb(250f, 250f), scrollThumb(500f, 500f, 900f, 30f))
        assertEquals(Thumb(0f, 250f), scrollThumb(500f, 500f, -40f, 30f))
        assertEquals(Thumb(0f, 20f), scrollThumb(20f, 500f, 0f, 30f))
    }
}
