// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/*
 * The location button (Stefan's option C, 2026-09-28; zoom levels
 * 2026-09-28). The first tap follows the rider, fixing the zoom only when
 * it is far off; with the map on the rider, the next tap shows the planned
 * route or loop with the rider (or, with nothing planned, switches between
 * the area and close by); the tap after an overview follows again at the
 * zoom from before.
 *
 * Which step a tap is comes from what the map shows (centred on the
 * rider, or still the overview), not from remembered state: the map's
 * own following can stop without telling (a glide, a fit), and a
 * remembered state then made taps do nothing.
 */

/** Zoom when following after a zoom far off: the neighbourhood. */
const val AREA_ZOOM = 11.0

/** Zoom close by: junctions and small roads readable. */
const val CLOSE_ZOOM = 14.0

/** At or below this zoom the map is far out (a country or more). */
const val FAR_OUT_ZOOM = 8.0

/** At or above this zoom the map is far in (single houses). */
const val FAR_IN_ZOOM = 17.0

/** What the map shows when the button is tapped. */
enum class LocateView {
    /** Somewhere else than the rider. */
    ELSEWHERE,

    /** Centred on the rider. */
    ON_RIDER,

    /** The overview of the plan, untouched since the last tap. */
    OVERVIEW,
}

/** What a tap asks the map to do. */
sealed interface LocateAction {
    /** Follow the rider, at this zoom, or at the current one if null. */
    data class Follow(val zoom: Double?) : LocateAction

    /** Show the planned route or loop and the rider, not following. */
    data object ShowPlan : LocateAction
}

/**
 * A tap on the location button while the map shows [view] at [zoom], with
 * [hasPlan] when a route or loop is on the map; [zoomBeforeOverview] is the
 * zoom the map had on the rider before the overview.
 */
fun onLocateTap(view: LocateView, zoom: Double, hasPlan: Boolean, zoomBeforeOverview: Double?): LocateAction =
    when (view) {
        LocateView.ELSEWHERE ->
            LocateAction.Follow(if (zoom <= FAR_OUT_ZOOM || zoom >= FAR_IN_ZOOM) AREA_ZOOM else null)
        LocateView.ON_RIDER -> when {
            hasPlan -> LocateAction.ShowPlan
            // Close by when nearer the area's zoom, else the area.
            zoom < (AREA_ZOOM + CLOSE_ZOOM) / 2 -> LocateAction.Follow(CLOSE_ZOOM)
            else -> LocateAction.Follow(AREA_ZOOM)
        }
        LocateView.OVERVIEW -> LocateAction.Follow(zoomBeforeOverview)
    }

/**
 * Whether the map is centred on the rider: the camera's centre within a
 * few percent of the map's width from the rider's position ([offsetM]
 * apart, the map [widthM] wide).
 */
fun isCentredOnRider(offsetM: Double, widthM: Double): Boolean = offsetM <= widthM * CENTRED_SHARE

/** How far off centre still counts as centred on the rider. */
private const val CENTRED_SHARE = 0.05

/** Metres per logical pixel at zoom 0 on the equator (512-pixel tiles). */
private const val METRES_PER_PX_AT_ZOOM_0 = 78_271.517

/** How many metres across a map [widthDp] wide shows at [zoom] and [latitude]. */
fun spanAtZoom(zoom: Double, widthDp: Double, latitude: Double): Double =
    METRES_PER_PX_AT_ZOOM_0 * kotlin.math.cos(Math.toRadians(latitude)) * widthDp / Math.pow(2.0, zoom)
