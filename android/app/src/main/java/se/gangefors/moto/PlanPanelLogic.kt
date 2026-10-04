// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.min

/*
 * Pure rules for the planning side panel and for the one-info-card rule
 * (2026-10-04), so they can be unit tested.
 */

/** A window lower than this (in dp), and wider than it is high, plans in a side panel. */
const val PANEL_MAX_WINDOW_HEIGHT_DP = 600

/** The side panel is this share of the window's width... */
const val PANEL_WIDTH_SHARE = 0.4f

/** ...but at most this wide, in dp. */
const val PANEL_MAX_WIDTH_DP = 360f

/** Space between the panel and the screen edge (past the cutout), and between the panel and the map controls, in dp. */
const val PANEL_GAP_DP = 8

/**
 * Whether the route and loop cards (and the info cards) form a side panel
 * at the left instead of a sheet at the bottom: in landscape (wider than
 * high) on a window under [PANEL_MAX_WINDOW_HEIGHT_DP] dp high, so a
 * phone on its side but not a tablet or a phone upright.
 */
fun usesPlanningPanel(widthDp: Int, heightDp: Int): Boolean =
    widthDp > heightDp && heightDp < PANEL_MAX_WINDOW_HEIGHT_DP

/** The side panel's width in dp for a window [windowWidthDp] wide: 40 %, at most 360 dp. */
fun planningPanelWidthDp(windowWidthDp: Int): Float =
    min(windowWidthDp.coerceAtLeast(0) * PANEL_WIDTH_SHARE, PANEL_MAX_WIDTH_DP)

/** Where the panel ends, from the left edge of the window: it starts [gap] past the left inset. */
fun panelRightEdge(insetLeft: Int, panelWidth: Int, gap: Int): Int = insetLeft + gap + panelWidth

/** The left padding to fit routes in the map area right of the panel: its edge and a gap. */
fun panelFitLeft(insetLeft: Int, panelWidth: Int, gap: Int): Int = panelRightEdge(insetLeft, panelWidth, gap) + gap

/** How far the map's scale bar and attribution move right of where they sit without a panel: its width and a gap. */
fun panelControlsShift(panelWidth: Int, gap: Int): Int = panelWidth + gap

/** The cards that tell about one thing on the map; at most one is open. */
enum class InfoCard { ROAD, FAVOURITE, SECTION, RIDE, SAVED_ROUTE }

/** The info cards to close when [opening] opens: all the others (the newest wins). */
fun infoCardsToClose(opening: InfoCard): Set<InfoCard> = InfoCard.entries.toSet() - opening

/** The info cards open after [opening] opens while [open] were: only it. */
fun infoCardsAfterOpening(open: Set<InfoCard>, opening: InfoCard): Set<InfoCard> =
    (open - infoCardsToClose(opening)) + opening
