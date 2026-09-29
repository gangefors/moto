// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarContrastTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun lumaOfBlackAndWhite() {
        assertEquals(0f, averageLuma(IntArray(4) { argb(0, 0, 0) }), 1e-6f)
        assertEquals(1f, averageLuma(IntArray(4) { argb(255, 255, 255) }), 1e-6f)
    }

    @Test
    fun lumaWeightsGreenOverBlue() {
        assertTrue(averageLuma(intArrayOf(argb(0, 255, 0))) > averageLuma(intArrayOf(argb(0, 0, 255))))
    }

    @Test
    fun lumaAveragesMixedPixels() {
        val half = averageLuma(intArrayOf(argb(0, 0, 0), argb(255, 255, 255)))
        assertEquals(0.5f, half, 1e-6f)
    }

    @Test
    fun emptyInputCountsAsWhite() {
        assertEquals(1f, averageLuma(IntArray(0)), 0f)
    }

    @Test
    fun lightBackgroundGetsDarkIcons() {
        assertTrue(wantsDarkIcons(0.9f, darkIconsNow = false))
    }

    @Test
    fun darkBackgroundGetsLightIcons() {
        assertFalse(wantsDarkIcons(0.1f, darkIconsNow = true))
    }

    @Test
    fun midToneKeepsCurrentIcons() {
        assertTrue(wantsDarkIcons(0.5f, darkIconsNow = true))
        assertFalse(wantsDarkIcons(0.5f, darkIconsNow = false))
    }

    @Test
    fun navigationButtonsContrastWithThePanelBehindThem() {
        // The light planning sheet behind the bar: dark buttons, even in
        // dark mode (the case where they all but disappeared).
        assertTrue(navigationIconsDark(0.95f, appDark = true, darkIconsNow = false))
        assertFalse(navigationIconsDark(0.05f, appDark = false, darkIconsNow = true))
        // A mid-tone panel keeps the buttons as they are, like the status bar.
        assertTrue(navigationIconsDark(0.5f, appDark = true, darkIconsNow = true))
        // No panel: the app theme's, over its scrim.
        assertTrue(navigationIconsDark(null, appDark = false, darkIconsNow = false))
        assertFalse(navigationIconsDark(null, appDark = true, darkIconsNow = true))
    }
}
