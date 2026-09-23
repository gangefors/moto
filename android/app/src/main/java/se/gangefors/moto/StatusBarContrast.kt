// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/**
 * Status bar icons are either all light or all dark, so the app picks the
 * set that contrasts with whatever is drawn behind the status bar. These
 * helpers are pure so they can be unit tested; MapScreen feeds them pixels
 * sampled from the map.
 */

/** Switch to dark icons once the background is brighter than this. */
internal const val DARK_ICONS_ABOVE = 0.55f

/** Switch to light icons once the background is darker than this. */
internal const val LIGHT_ICONS_BELOW = 0.45f

/**
 * Average perceived brightness of ARGB [pixels], from 0 (black) to 1 (white),
 * using Rec. 709 luma weights on the encoded sRGB values. Empty input counts
 * as white.
 */
internal fun averageLuma(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 1f
    var sum = 0.0
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    return (sum / (pixels.size * 255.0)).toFloat()
}

/**
 * Whether the status bar should show dark icons over a background of the
 * given [luma]. Between the two thresholds the current choice is kept, so the
 * icons don't flicker over mid-tone backgrounds.
 */
internal fun wantsDarkIcons(luma: Float, darkIconsNow: Boolean): Boolean = when {
    luma > DARK_ICONS_ABOVE -> true
    luma < LIGHT_ICONS_BELOW -> false
    else -> darkIconsNow
}
