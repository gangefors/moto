// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/*
 * The location button (Stefan's option C, 2026-09-28; zoom levels set
 * by the rider, 10 and 14 by default). The first tap follows the rider, fixing the zoom only when
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

/**
 * The two zoom levels the location button uses, set by the rider in My
 * data: [area] (the neighbourhood; also where the app starts) and [close]
 * (junctions and small roads readable). [of] keeps them sensible.
 */
data class LocateZooms(val area: Int = DEFAULT_AREA_ZOOM, val close: Int = DEFAULT_CLOSE_ZOOM) {
    companion object {
        /** Zooms from settings: each within [MIN_ZOOM]..[MAX_ZOOM], close
         * at least a step closer than area, defaults for anything missing. */
        fun of(area: Int?, close: Int?): LocateZooms {
            val a = (area ?: DEFAULT_AREA_ZOOM).coerceIn(MIN_ZOOM, MAX_ZOOM - 1)
            val c = (close ?: DEFAULT_CLOSE_ZOOM).coerceIn(a + 1, MAX_ZOOM)
            return LocateZooms(a, c)
        }
    }
}

/** Default zoom for the area: about 17 km across a phone in Skåne. */
const val DEFAULT_AREA_ZOOM = 10

/** Default zoom close by. */
const val DEFAULT_CLOSE_ZOOM = 14

/** The zooms a rider can pick for the location button. */
const val MIN_ZOOM = 6
const val MAX_ZOOM = 18

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
fun onLocateTap(
    view: LocateView,
    zoom: Double,
    hasPlan: Boolean,
    zoomBeforeOverview: Double?,
    zooms: LocateZooms = LocateZooms(),
): LocateAction {
    val area = zooms.area.toDouble()
    val close = zooms.close.toDouble()
    return when (view) {
        LocateView.ELSEWHERE ->
            LocateAction.Follow(if (zoom <= FAR_OUT_ZOOM || zoom >= FAR_IN_ZOOM) area else null)
        LocateView.ON_RIDER -> when {
            hasPlan -> LocateAction.ShowPlan
            // Close by when nearer the area's zoom, else the area.
            zoom < (area + close) / 2 -> LocateAction.Follow(close)
            else -> LocateAction.Follow(area)
        }
        LocateView.OVERVIEW -> LocateAction.Follow(zoomBeforeOverview)
    }
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

/** A zoom on a slider that runs from close ([MAX_ZOOM]) on the left to
 * wide ([MIN_ZOOM]) on the right, and back: the same both ways. */
fun flipZoom(zoom: Float): Float = MIN_ZOOM + MAX_ZOOM - zoom

/**
 * The zooms a range slider over [flipZoom]ed values stands for: the left
 * thumb ([start]) close by, the right one ([end]) the area; null when the
 * thumbs would meet or cross, so close by always stays a step closer.
 */
fun zoomsFromRange(start: Float, end: Float): LocateZooms? {
    val c = kotlin.math.round(flipZoom(start)).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
    val a = kotlin.math.round(flipZoom(end)).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
    return if (c > a) LocateZooms(a, c) else null
}

/** A map's width as a rider reads it. */
sealed interface Span {
    /** Whole kilometres, from 10 km. */
    data class Km(val km: Int) : Span

    /** Kilometres to a tenth, from 1 km. */
    data class KmTenths(val km: Double) : Span

    /** Metres to the nearest 50, below 1 km. */
    data class Metres(val m: Int) : Span
}

/** [metres] rounded the way [Span] says. */
fun readableSpan(metres: Double): Span {
    // Rounded first, so 999.9 m reads as 1.0 km, not 1000 m.
    val tenths = kotlin.math.round(metres / 100.0) / 10.0
    return when {
        tenths >= 10.0 -> Span.Km(kotlin.math.round(metres / 1000.0).toInt())
        tenths >= 1.0 -> Span.KmTenths(tenths)
        else -> Span.Metres((kotlin.math.round(metres / 50.0).toInt() * 50).coerceAtLeast(50))
    }
}

/** The latitude the settings page shows map widths for (Skåne). */
const val SETTINGS_LATITUDE = 56.0

/** The zoom at which a map [widthDp] wide shows [metres] across at
 * [latitude]: the inverse of [spanAtZoom]. */
fun zoomForSpan(metres: Double, widthDp: Double, latitude: Double): Double =
    kotlin.math.log2(METRES_PER_PX_AT_ZOOM_0 * kotlin.math.cos(Math.toRadians(latitude)) * widthDp / metres)

/** How many metres across a map [widthDp] wide shows at [zoom] and [latitude]. */
fun spanAtZoom(zoom: Double, widthDp: Double, latitude: Double): Double =
    METRES_PER_PX_AT_ZOOM_0 * kotlin.math.cos(Math.toRadians(latitude)) * widthDp / Math.pow(2.0, zoom)
