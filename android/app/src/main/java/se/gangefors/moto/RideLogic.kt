// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan
import se.gangefors.moto.core.FollowPhase
import se.gangefors.moto.core.FollowState
import se.gangefors.moto.core.FollowedRoute
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Route

/**
 * Riding a route (ADR-0011): what is ridden, and the small decisions the
 * service and the ride screen make from the core's follow state. Pure, so
 * it is unit tested.
 */
data class RideRoute(
    val name: String,
    val isLoop: Boolean,
    val durationS: Double,
    val line: List<LatLon>,
    val favouriteParts: List<List<LatLon>>,
    val favouriteRatings: List<Rating>,
    /** Gravel stretches, for the dashes; not kept in the store. */
    val unpavedParts: List<List<LatLon>> = emptyList(),
)

fun rideRouteOf(route: Route, name: String, isLoop: Boolean): RideRoute {
    // Parts without a rating (older routes) are left out.
    val n = minOf(route.favouriteParts.size, route.favouriteRatings.size)
    return RideRoute(
        name = name,
        isLoop = isLoop,
        durationS = route.durationS,
        line = route.geometry,
        favouriteParts = route.favouriteParts.take(n),
        favouriteRatings = route.favouriteRatings.take(n),
        unpavedParts = route.unpavedParts,
    )
}

fun RideRoute.toFollowed() = FollowedRoute(name, isLoop, durationS, line, favouriteParts, favouriteRatings)

fun FollowedRoute.toRideRoute() = RideRoute(name, isLoop, durationS, line, favouriteParts, favouriteRatings)

/** A ride's following, as the screen and the notification see it. */
data class Following(
    val route: RideRoute,
    val state: FollowState,
    /** The way back to the route, while off it or joining. */
    val back: List<LatLon>? = null,
    val backM: Double? = null,
    val turnRound: Boolean = false,
    /** When the recording stops by itself after the end, ms since the epoch. */
    val stopsAtMs: Long? = null,
)

/** The recording stops this long after the end of the route (the rider). */
const val FINISH_COUNTDOWN_MS = 15_000L

/** An off-route alert sounds at most this often. */
const val ALERT_EVERY_MS = 30_000L

/** The way back is asked for again at most this often. */
const val REJOIN_EVERY_MS = 10_000L

/** Joining: a way to the route is shown when further than this. */
const val JOIN_LINE_M = 40.0

/** The progress bar is at least this full, so it reads as one (the rider). */
const val MIN_PROGRESS = 0.02f

/** Zoom while riding: closer when slow, wider at speed. */
const val RIDE_ZOOM_SLOW = 15.5
const val RIDE_ZOOM_FAST = 14.0
private const val SLOW_MPS = 30 / 3.6
private const val FAST_MPS = 90 / 3.6

/** The rider sits this far down the screen while followed (0 top, 1 bottom). */
const val RIDER_DOWN = 0.7

/** The zoom while riding at [speedMps], moved by [offset] zoom levels
 * (closer when positive: Ride settings' step, [rideZoomOffset]). */
fun rideZoom(speedMps: Double?, offset: Double = 0.0): Double {
    val off = offset.takeIf { it.isFinite() } ?: 0.0
    val v = speedMps?.takeIf { it.isFinite() } ?: return RIDE_ZOOM_SLOW + off
    val f = ((v - SLOW_MPS) / (FAST_MPS - SLOW_MPS)).coerceIn(0.0, 1.0)
    return RIDE_ZOOM_SLOW + f * (RIDE_ZOOM_FAST - RIDE_ZOOM_SLOW) + off
}

/** Ride settings' "Zoom while riding": steps from closer (0) to wider;
 * [RIDE_ZOOM_DEFAULT_STEP] is the zoom by speed as it always was. */
const val RIDE_ZOOM_STEPS = 7
const val RIDE_ZOOM_DEFAULT_STEP = 2

/** One tap on + or − changes the zoom this much: a whole level (the map
 * half or twice as wide), so six taps cover the whole range (the rider). */
const val ZOOM_BUTTON_STEP = 1.0

/** Zoom levels + and − may add to the zoom while riding, each way. */
private const val MAX_RIDE_ZOOM_NUDGE = 3.0

/** [nudge] after a tap on + (positive [by]) or − on a ride. */
fun nudgeRideZoom(nudge: Double, by: Double): Double =
    (nudge + by).coerceIn(-MAX_RIDE_ZOOM_NUDGE, MAX_RIDE_ZOOM_NUDGE)

/** How many zoom levels step [step] moves the zoom while riding: half a
 * level a step, from one closer (half as wide) to two wider (four times as
 * wide). Steps out of range count as the nearest. */
fun rideZoomOffset(step: Int): Double = (RIDE_ZOOM_DEFAULT_STEP - step.coerceIn(0, RIDE_ZOOM_STEPS - 1)) * 0.5

/** Camera top padding, px, that puts the followed rider [RIDER_DOWN] of
 * the way down a map [heightPx] high. */
fun riderTopPadding(heightPx: Int): Int = max(0, ((2 * RIDER_DOWN - 1) * heightPx).toInt())

/** The progress bar's fill for the route [state] describes. */
fun progressShown(state: FollowState): Float {
    val f = if (state.totalM > 0) (state.alongM / state.totalM).toFloat() else 0f
    return f.coerceIn(MIN_PROGRESS, 1f)
}

/**
 * The share of [line]'s length on the map (web Mercator, as MapLibre's
 * line-progress measures it) up to each point, 0 to 1.
 */
fun mercatorProgress(line: List<LatLon>): DoubleArray {
    val out = DoubleArray(line.size)
    var total = 0.0
    for (i in 1 until line.size) {
        val (x0, y0) = mercator(line[i - 1])
        val (x1, y1) = mercator(line[i])
        total += Math.hypot(x1 - x0, y1 - y0)
        out[i] = total
    }
    if (total > 0) for (i in out.indices) out[i] /= total
    return out
}

/** How far along the drawn line the rider is, 0–1, for segment [segment]
 * at [t] along it. */
fun lineProgressAt(progress: DoubleArray, segment: Int, t: Double): Double {
    if (progress.isEmpty()) return 0.0
    val i = segment.coerceIn(0, progress.size - 1)
    val j = (i + 1).coerceAtMost(progress.size - 1)
    return (progress[i] + t.coerceIn(0.0, 1.0) * (progress[j] - progress[i])).coerceIn(0.0, 1.0)
}

private fun mercator(p: LatLon): Pair<Double, Double> {
    val lat = p.lat.coerceIn(-85.0, 85.0) * PI / 180
    return p.lon * PI / 180 to ln(tan(PI / 4 + lat / 2))
}

/** Whether to sound the alert on leaving the route now. */
fun alertDue(lastAlertMs: Long?, nowMs: Long): Boolean = lastAlertMs == null || nowMs - lastAlertMs >= ALERT_EVERY_MS

/** Whether to ask for the way back now, in [state]. */
fun rejoinDue(state: FollowState, lastAskedMs: Long?, nowMs: Long): Boolean {
    val wanted = when (state.phase) {
        FollowPhase.OFF_ROUTE -> true
        FollowPhase.JOINING -> state.offM.let { it == null || it > JOIN_LINE_M }
        else -> false
    }
    return wanted && (lastAskedMs == null || nowMs - lastAskedMs >= REJOIN_EVERY_MS)
}

/** Seconds left of the countdown at the end, rounded up; null when none. */
fun countdownS(stopsAtMs: Long?, nowMs: Long): Int? =
    stopsAtMs?.let { ((it - nowMs).coerceAtLeast(0) + 999) / 1000 }?.toInt()

/** Distances on the card and in the notification, in km to a tenth. */
fun rideKm(m: Double): Double = sectionKm(m)

/** A ride that ends within this of where it started is ridden again as a
 * loop. */
const val RIDE_AGAIN_LOOP_M = 200.0

/** A ride's line to ride it again: its segments joined (straight across
 * a gap in the recording), and closed back to its start when it ends
 * within [RIDE_AGAIN_LOOP_M] of it, so it is followed as a loop; null
 * when there is too little of it to follow. */
fun rideAgainLine(segments: List<List<LatLon>>): List<LatLon>? {
    val line = segments.flatten()
    if (line.size < 2 || lengthM(line) < 2 * RIDE_AGAIN_LOOP_M) return null
    return if (rideAgainIsLoop(line)) line + line.first() else line
}

/** Whether a ride's [line] is ridden again as a loop. */
fun rideAgainIsLoop(line: List<LatLon>): Boolean =
    line.size >= 2 && lengthM(line) >= 2 * RIDE_AGAIN_LOOP_M &&
        approxDistanceM(line.first(), line.last()) <= RIDE_AGAIN_LOOP_M

/** The time to ride a ride again: as long as it took (0 while it is
 * still going or its times are odd). */
fun rideAgainDurationS(startedAt: Long, endedAt: Long?): Double =
    if (endedAt == null || endedAt < startedAt) 0.0 else (endedAt - startedAt).toDouble()

/** Whole minutes a recording started at [startedAtMs] has run at [nowMs]
 * (never below 0). */
fun recordingMinutes(startedAtMs: Long, nowMs: Long): Long = ((nowMs - startedAtMs).coerceAtLeast(0L)) / 60_000L

/** How far to turn an arrow pointing [bearingDeg] (from north) on a map
 * turned [mapBearing] degrees, so it points that way on screen (0–360). */
fun arrowOnMap(bearingDeg: Double, mapBearing: Float): Float =
    ((bearingDeg - mapBearing) % 360.0 + 360.0).toFloat() % 360f
