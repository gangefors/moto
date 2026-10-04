// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.min

/*
 * Pure rules for where the cards sit (the planning sheet and the info and
 * start cards) and for the one-info-card rule (2026-10-04), so they can be
 * unit tested. In portrait they span the width at the bottom; in landscape
 * they are the same cards in a column at the bottom left.
 */

/** In landscape the cards' column is at most this share of the window's width... */
const val LEFT_COLUMN_MAX_SHARE = 0.5f

/** ...and at most this wide, in dp. */
const val LEFT_COLUMN_MAX_WIDTH_DP = 400f

/** Whether the window is wider than it is high. */
fun isLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp

/** The width in dp of the cards' column in landscape: half the window, at most 400 dp. */
fun leftColumnWidthDp(windowWidthDp: Int): Float =
    min(windowWidthDp.coerceAtLeast(0) * LEFT_COLUMN_MAX_SHARE, LEFT_COLUMN_MAX_WIDTH_DP)

/**
 * Whether the info cards show while a plan is open: always, except in
 * landscape with the planning sheet pulled up, where the window is too
 * low for both (the one difference from portrait).
 */
fun showInfoCardsWithPlan(landscape: Boolean, expanded: Boolean): Boolean = !(landscape && expanded)

/** How high the planning sheet may grow, as a share of the window's height. */
fun sheetMaxShare(landscape: Boolean, expanded: Boolean): Float =
    if (landscape && expanded) 0.85f else 0.55f

/**
 * Where the map's controls and fitted routes start from the left edge, in
 * pixels: past the cards' column ([columnRight], its measured right edge,
 * 0 when none shows) in landscape, else just the left inset. In portrait
 * the cards span the width, so only the bottom is kept clear.
 */
fun leftClearance(landscape: Boolean, insetLeft: Int, columnRight: Int): Int =
    if (landscape) maxOf(insetLeft, columnRight) else insetLeft

/** The cards that tell about one thing on the map; at most one is open. */
enum class InfoCard { ROAD, FAVOURITE, SECTION, RIDE, SAVED_ROUTE }

/** The info cards to close when [opening] opens: all the others (the newest wins). */
fun infoCardsToClose(opening: InfoCard): Set<InfoCard> = InfoCard.entries.toSet() - opening

/**
 * The info cards to close when a plan (a route or a loop) starts: all
 * but a saved route's, which stays above the start card (the plan is not
 * an info card). None while a plan is already [planOpen], as when its end
 * moves or its loops are shuffled: what the rider opened meanwhile stays.
 */
fun infoCardsToCloseOnPlanStart(planOpen: Boolean): Set<InfoCard> =
    if (planOpen) emptySet() else InfoCard.entries.toSet() - InfoCard.SAVED_ROUTE

/** The info cards open after [opening] opens while [open] were: only it. */
fun infoCardsAfterOpening(open: Set<InfoCard>, opening: InfoCard): Set<InfoCard> =
    (open - infoCardsToClose(opening)) + opening
