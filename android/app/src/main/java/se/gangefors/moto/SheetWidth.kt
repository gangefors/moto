// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/** Narrowest a left-aligned sheet gets, so its content still fits (dp). */
const val MIN_SIDE_SHEET_DP = 360f

/** Material 3's default sheet maximum width (dp). */
const val DEFAULT_SHEET_MAX_DP = 640f

/**
 * The widest a bottom sheet gets, in dp. By default (upright, or any sheet
 * that is not at the side) the Material default: the whole width, capped
 * at 640 dp. A side sheet in [landscape] is half the screen wide, but
 * never narrower than [MIN_SIDE_SHEET_DP] (nor wider than the screen), so
 * the map stays visible at its right.
 */
fun sheetMaxWidthDp(landscape: Boolean, atSide: Boolean, screenWidthDp: Float): Float {
    val screen = screenWidthDp.coerceAtLeast(0f)
    if (!(landscape && atSide)) return minOf(screen, DEFAULT_SHEET_MAX_DP)
    return maxOf(screen / 2, minOf(MIN_SIDE_SHEET_DP, screen))
}

/**
 * How far a sheet centred in a container [containerWidth] wide must move
 * to sit at the left edge (a negative or zero shift, any unit); none when
 * the sheet is not at the side or fills the container.
 */
fun sideSheetShift(atSide: Boolean, containerWidth: Float, sheetWidth: Float): Float =
    if (atSide) -((containerWidth - sheetWidth) / 2).coerceAtLeast(0f) else 0f
