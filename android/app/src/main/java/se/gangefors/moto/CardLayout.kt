// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.min

/*
 * Pure rules for where the cards sit (the planning sheet and the info and
 * start cards) and for the one-info-card rule (2026-10-04), so they can be
 * unit tested. In portrait they span the width at the bottom; in landscape
 * they are the same cards in a column at the bottom left, the info cards
 * moving to a column at the bottom right while a plan is open.
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
 * Whether the info cards go in their own column at the bottom right: in
 * landscape while a plan is open, as the left column is the planning
 * sheet's. Otherwise they stack with the other cards.
 */
fun infoCardsAtRight(landscape: Boolean, planning: Boolean): Boolean = landscape && planning

/**
 * The width in dp of the info cards' column at the right in landscape: the
 * same cap as the left column ([leftColumnWidthDp]), less what the left
 * column (and its [insetLeftDp] cutout) and the [insetRightDp] bar leave,
 * so the two never overlap.
 */
fun infoColumnWidthDp(windowWidthDp: Int, insetLeftDp: Float, insetRightDp: Float): Float {
    val cap = leftColumnWidthDp(windowWidthDp)
    val room = windowWidthDp - cap - insetLeftDp - insetRightDp
    return min(cap, room.coerceAtLeast(0f))
}

/**
 * How far up from the window's bottom, in pixels, the info cards at the
 * right reach ([rightCardsTop], their measured top, [Int.MAX_VALUE] when
 * none show): the map's scale and attribution rise above them.
 */
fun rightCardCover(mapHeight: Int, rightCardsTop: Int): Int =
    if (rightCardsTop < mapHeight) mapHeight - rightCardsTop else 0

/** How high the planning sheet may grow upright and pulled up, as a share of the window's height. */
const val SHEET_MAX_SHARE = 0.55f

/**
 * How high the planning sheet may grow, in pixels. At rest it wraps its
 * whole content (figures, stats, switcher, chips), capped only by the room
 * below the status bar ([insetTop]); only if even that does not fit does
 * it scroll. Pulled up it grows the same way in landscape, and upright
 * to [SHEET_MAX_SHARE] of the window's [mapHeight].
 */
fun sheetMaxHeightPx(landscape: Boolean, expanded: Boolean, mapHeight: Int, insetTop: Int): Int =
    if (landscape || !expanded) {
        (mapHeight - insetTop).coerceAtLeast(0)
    } else {
        (mapHeight.coerceAtLeast(0) * SHEET_MAX_SHARE).toInt()
    }

/** What stays fixed at the top of a pulled-up planning sheet while the rest scrolls. */
enum class SheetPinned { NONE, TITLE, HEADER }

/**
 * What the pulled-up planning sheet keeps fixed below its handle: in
 * landscape only the title row (figures and the close button), so the
 * settings get the sheet's height; upright the whole header when it
 * takes at most half the room ([fixesTop]); at rest nothing (everything
 * scrolls, if it must).
 */
fun sheetPinned(landscape: Boolean, expanded: Boolean, headerPx: Int, roomPx: Float): SheetPinned = when {
    !expanded -> SheetPinned.NONE
    landscape -> SheetPinned.TITLE
    fixesTop(headerPx, roomPx) -> SheetPinned.HEADER
    else -> SheetPinned.NONE
}

/**
 * How far up from the window's bottom, in pixels, the map's controls at the
 * bottom (the logo and attribution, the scale bar and the buttons at the
 * right) sit so no card hides them, in either orientation: right above
 * the highest thing under them, or the bottom bar ([insetBottom]) when nothing is. Upright
 * that is the planning sheet ([sheetTop]) or the cards above it
 * ([cardsTop]); in landscape the sheet and the other cards are at the
 * left, so only the info cards at the right ([rightCardsTop]) count.
 * Tops are measured, [Int.MAX_VALUE] when not shown. The caller adds the
 * usual gap.
 */
fun controlsBottomPx(
    landscape: Boolean,
    insetBottom: Int,
    mapHeight: Int,
    sheetTop: Int,
    cardsTop: Int,
    rightCardsTop: Int,
): Int {
    val cover = if (landscape) rightCardCover(mapHeight, rightCardsTop) else rightCardCover(mapHeight, minOf(sheetTop, cardsTop))
    return maxOf(insetBottom, cover)
}

/**
 * Where the map's controls and fitted routes start from the left edge, in
 * pixels: past the cards' column ([columnRight], its measured right edge,
 * 0 when none shows) in landscape, else just the left inset. In portrait
 * the cards span the width, so only the bottom is kept clear.
 */
fun leftClearance(landscape: Boolean, insetLeft: Int, columnRight: Int): Int =
    if (landscape) maxOf(insetLeft, columnRight) else insetLeft

/**
 * What the panels cover of a [width] × [height] map, for fitting routes
 * clear of them (all measured tops and edges in pixels, [Int.MAX_VALUE] /
 * 0 when not shown): the status bar and the card at the top, the cards'
 * column at the left in landscape ([columnRight]), the buttons at the right
 * ([buttonsLeft]) or above the sheet while planning ([buttonsTop]), the tag
 * and edit panels, and at the bottom the sheet and cards upright or the
 * info cards at the right in landscape ([rightCardsTop]).
 */
fun fitPanels(
    landscape: Boolean,
    width: Int,
    height: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int,
    topPanelBottom: Int = 0,
    columnRight: Int = 0,
    buttonsLeft: Int = Int.MAX_VALUE,
    buttonsTop: Int = Int.MAX_VALUE,
    tagTop: Int = Int.MAX_VALUE,
    editTop: Int = Int.MAX_VALUE,
    sheetTop: Int = Int.MAX_VALUE,
    cardsTop: Int = Int.MAX_VALUE,
    rightCardsTop: Int = Int.MAX_VALUE,
): Panels = Panels(
    width = width,
    height = height,
    left = leftClearance(landscape, insetLeft, columnRight),
    top = maxOf(topPanelBottom, insetTop),
    right = maxOf(width - buttonsLeft, insetRight),
    bottom = maxOf(
        height - tagTop,
        height - editTop,
        height - buttonsTop,
        insetBottom,
        // Upright the sheet and the cards span the width; in landscape the
        // sheet is at the left, so only the info cards at the right count.
        if (landscape) rightCardCover(height, rightCardsTop) else height - minOf(sheetTop, cardsTop),
    ),
)

/** The cards that tell about one thing on the map. */
enum class InfoCard { ROAD, FAVOURITE, SECTION, RIDE, SAVED_ROUTE }

/** What was tapped on the map: at most one of these cards is open, above the shown ones. */
val TAPPED_CARDS: Set<InfoCard> = setOf(InfoCard.ROAD, InfoCard.FAVOURITE, InfoCard.SECTION)

/** What is shown on the map (a ride or a saved route): at most one of these cards is open. */
val SHOWN_CARDS: Set<InfoCard> = setOf(InfoCard.RIDE, InfoCard.SAVED_ROUTE)

/**
 * The info cards to close when [opening] opens. A shown ride or saved
 * route (opened from a list or the map) clears the map first: all the
 * others close. A favourite section shown on its own does the same, as it
 * takes the map's route layer. A tapped road or favourite only replaces
 * another tapped card: tapping never closes a shown route or ride, whose
 * card stays below.
 */
fun infoCardsToClose(opening: InfoCard): Set<InfoCard> = when (opening) {
    InfoCard.ROAD, InfoCard.FAVOURITE -> TAPPED_CARDS - opening
    InfoCard.SECTION, InfoCard.RIDE, InfoCard.SAVED_ROUTE -> InfoCard.entries.toSet() - opening
}

/**
 * The info cards to close when a plan (a route or a loop) starts: all of
 * them, a saved route's too (never a saved route and a new plan on screen
 * together; the plan is not an info card). None while a plan is already
 * [planOpen], as when its end moves or its loops are shuffled: what the
 * rider opened meanwhile stays.
 */
fun infoCardsToCloseOnPlanStart(planOpen: Boolean): Set<InfoCard> =
    if (planOpen) emptySet() else InfoCard.entries.toSet()

/** The info cards open after [opening] opens while [open] were: only it. */
fun infoCardsAfterOpening(open: Set<InfoCard>, opening: InfoCard): Set<InfoCard> =
    (open - infoCardsToClose(opening)) + opening


/**
 * The tallest the stack of cards may be, in pixels: the window's
 * [mapHeight] less the [topLimit] (the status bar or the notices, whichever
 * reaches lower) and the [bottomLimit] (the sheet, or the bottom bar),
 * less the gaps around the stack ([gapPx], twice). Taller, the info
 * cards in it scroll.
 */
fun cardStackMaxHeightPx(mapHeight: Int, topLimit: Int, bottomLimit: Int, gapPx: Int): Int =
    (mapHeight - topLimit.coerceAtLeast(0) - bottomLimit.coerceAtLeast(0) - 2 * gapPx.coerceAtLeast(0)).coerceAtLeast(0)

/** What Back closes on the map screen, in the order it closes them. */
enum class BackStep { MARK, VIA_PICK, VIA_SELECTED, ROAD, FAVOURITE, ROUTE, LOOP, START, SAVED_ROUTE, SECTION, RIDE }

/**
 * What Back closes first: a marking step, a via pick, a selected via, a
 * road or favourite card, the route or loop sheet, the start card, a
 * saved route, a favourite section, then a shown ride; null when nothing
 * is open and Back leaves the app.
 */
fun backStep(
    marking: Boolean,
    addingVia: Boolean,
    viaSelected: Boolean,
    road: Boolean,
    favourite: Boolean,
    route: Boolean,
    loop: Boolean,
    startPicked: Boolean,
    savedRoute: Boolean,
    section: Boolean,
    ride: Boolean,
): BackStep? = when {
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
    else -> null
}

/** Which cards are open on the map screen: a [task] card, an [info] card,
 * [any] card, and whether the [left] column (with the sheet's) has one. */
data class CardsOpen(val task: Boolean, val info: Boolean, val any: Boolean, val left: Boolean)

/**
 * The cards open on the map screen. A task card is a message, a marking
 * step, the start card (offering a loop) or a selected via; an info card
 * is a shown ride, saved route or favourite section, a tapped road, or a
 * tapped favourite (not in ride mode). Info cards at the right
 * ([infoAtRight], landscape while planning) leave the left column to the
 * task cards.
 */
fun cardsOpen(
    message: Boolean,
    marking: Boolean,
    offerLoop: Boolean,
    viaSelected: Boolean,
    ride: Boolean,
    savedRoute: Boolean,
    section: Boolean,
    road: Boolean,
    favourite: Boolean,
    rideMode: Boolean,
    infoAtRight: Boolean,
): CardsOpen {
    val task = message || marking || offerLoop || viaSelected
    val info = ride || savedRoute || section || road || (favourite && !rideMode)
    return CardsOpen(task = task, info = info, any = task || info, left = task || (info && !infoAtRight))
}
