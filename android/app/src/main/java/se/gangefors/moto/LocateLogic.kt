// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The location button (the rider's option C, 2026-09-28). The first tap
 * follows the rider, fixing the zoom only when it is far off (a country
 * or more in view, or single houses); the next tap shows the planned
 * route or loop with the rider (or, with nothing planned, switches between
 * the area and close by); the tap after an overview follows again at the
 * zoom from before. Pure logic in map widths (metres across the map), so
 * it is independent of the map widget and unit-tested.
 */

/** The map's width when following after a zoom far off: the neighbourhood. */
const val AREA_SPAN_M = 50_000.0

/** The map's width close by: junctions and small roads readable. */
const val CLOSE_SPAN_M = 5_000.0

/** Wider than this is zoomed far out (a country or more). */
const val TOO_WIDE_M = 300_000.0

/** Narrower than this is zoomed far in (single houses). */
const val TOO_NARROW_M = 1_000.0

/** Where the location button stands. */
sealed interface LocateState {
    /** Not following the rider. */
    data object Idle : LocateState

    /** Following, with the map this wide. */
    data class Following(val spanM: Double) : LocateState

    /** Showing the plan with the rider; the next tap follows at [spanM]. */
    data class Overview(val spanM: Double) : LocateState
}

/** What a tap asks the map to do. */
sealed interface LocateAction {
    /** Follow the rider, at this map width, or at the current one if null. */
    data class Follow(val spanM: Double?) : LocateAction

    /** Show the planned route or loop and the rider, not following. */
    data object ShowPlan : LocateAction
}

/**
 * A tap on the location button in [state], with the map [spanM] wide now
 * and [hasPlan] when a route or loop is on the map: the new state and what
 * the map should do.
 */
fun onLocateTap(state: LocateState, spanM: Double, hasPlan: Boolean): Pair<LocateState, LocateAction> =
    when (state) {
        LocateState.Idle -> {
            val fixed = if (spanM > TOO_WIDE_M || spanM < TOO_NARROW_M) AREA_SPAN_M else null
            LocateState.Following(fixed ?: spanM) to LocateAction.Follow(fixed)
        }
        is LocateState.Following -> if (hasPlan) {
            LocateState.Overview(state.spanM) to LocateAction.ShowPlan
        } else {
            // Close by when nearer the area's width, else the area.
            val target = if (spanM > sqrt(AREA_SPAN_M * CLOSE_SPAN_M)) CLOSE_SPAN_M else AREA_SPAN_M
            LocateState.Following(target) to LocateAction.Follow(target)
        }
        is LocateState.Overview -> LocateState.Following(state.spanM) to LocateAction.Follow(state.spanM)
    }

/** Metres per logical pixel at zoom 0 on the equator (512-pixel tiles). */
private const val METRES_PER_PX_AT_ZOOM_0 = 78_271.517

/** How many metres across a map [widthDp] wide shows at [zoom] and [latitude]. */
fun spanAtZoom(zoom: Double, widthDp: Double, latitude: Double): Double =
    METRES_PER_PX_AT_ZOOM_0 * cos(Math.toRadians(latitude)) * widthDp / 2.0.pow(zoom)

/** The zoom at which a map [widthDp] wide shows [spanM] across at [latitude]. */
fun zoomForSpan(spanM: Double, widthDp: Double, latitude: Double): Double =
    ln(METRES_PER_PX_AT_ZOOM_0 * cos(Math.toRadians(latitude)) * widthDp / spanM) / ln(2.0)
