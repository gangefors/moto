// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/** Narrowest a left-aligned sheet gets, so its content still fits (dp). */
const val MIN_SIDE_SHEET_DP = 360f

/** How a bottom sheet sits on the screen: its width and left edge (dp). */
data class SheetPlacement(val widthDp: Float, val leftDp: Float)

/**
 * Upright the sheet spans the whole width, as the Material default does
 * (centred, capped at 640 dp). In landscape it is left-aligned and half
 * the screen wide, but never narrower than [MIN_SIDE_SHEET_DP] (nor wider
 * than the screen), so the map stays visible at its right.
 */
fun sheetPlacement(landscape: Boolean, screenWidthDp: Float): SheetPlacement {
    val screen = screenWidthDp.coerceAtLeast(0f)
    if (!landscape) return SheetPlacement(minOf(screen, DEFAULT_SHEET_MAX_DP), (screen - minOf(screen, DEFAULT_SHEET_MAX_DP)) / 2)
    return SheetPlacement(maxOf(screen / 2, minOf(MIN_SIDE_SHEET_DP, screen)), 0f)
}

/** Material 3's default sheet maximum width (dp). */
const val DEFAULT_SHEET_MAX_DP = 640f
