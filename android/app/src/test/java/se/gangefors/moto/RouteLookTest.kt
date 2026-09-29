// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLookTest {
    @Test
    fun sectionsFadeWhileARouteIsShown() {
        val normal = sectionLook(routeShown = false)
        val faded = sectionLook(routeShown = true)
        assertEquals(SectionLook(5f, 1f, 0.8f, 1f), normal)
        assertTrue(faded.lineWidth < normal.lineWidth)
        assertTrue(faded.lineOpacity < normal.lineOpacity)
        assertEquals(0f, faded.casingOpacity)
        // Still there as context, and thinner than the route (5 dp) so the
        // route hides the section it runs along.
        assertTrue(faded.lineOpacity > 0f && faded.arrowOpacity > 0f)
        assertTrue(faded.lineWidth < 5f)
    }

    @Test
    fun theOutlineIsFainterOnTheDarkMap() {
        assertEquals(1f, outlineStrength(darkMap = false), 0f)
        assertTrue(outlineStrength(darkMap = true) in 0.3f..0.6f)
        assertEquals(0.8f, sectionLook(routeShown = false).casingOpacity, 1e-6f)
        assertTrue(sectionLook(routeShown = false, darkMap = true).casingOpacity < 0.8f)
        // Faded sections under a route have no outline either way.
        assertEquals(0f, sectionLook(routeShown = true, darkMap = true).casingOpacity, 0f)
    }
}
