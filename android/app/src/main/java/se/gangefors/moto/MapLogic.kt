// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.MotoException
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.TimeBudget

/**
 * Picking a route with long-presses: the first sets the start, the second
 * the end (completing the pick), the next starts a new pick. A round trip
 * takes the start instead of waiting for an end. Pure logic,
 * independent of the map, so it can be unit tested.
 */
class RoutePicker<P> {
    sealed interface Step<P> {
        data class StartSet<P>(val start: P) : Step<P>
        data class Complete<P>(val start: P, val end: P) : Step<P>
    }

    var start: P? = null
        private set

    fun onLongPress(point: P): Step<P> {
        val s = start
        return if (s == null) {
            start = point
            Step.StartSet(point)
        } else {
            start = null
            Step.Complete(s, point)
        }
    }

    /** The start, forgotten: a loop from it takes the place of the end
     * (null when there is none). */
    fun takeStart(): P? = start.also { start = null }

    /** Forgets a start that turned out to be unusable. */
    fun reset() {
        start = null
    }
}

/** How the map screen should describe an error from the Rust core. */
enum class CoreProblem { OUTSIDE_REGION, NO_ROAD_NEARBY, NO_ROUTE, OTHER }

fun classify(e: Throwable): CoreProblem = when (e) {
    is MotoException.OutsideRegion -> CoreProblem.OUTSIDE_REGION
    is MotoException.NoRoadNearby -> CoreProblem.NO_ROAD_NEARBY
    is MotoException.NoRoute -> CoreProblem.NO_ROUTE
    else -> CoreProblem.OTHER
}

/**
 * Route summary for the route card: distance in km (one decimal), whole
 * minutes, whole minutes over the fastest route (never negative), the
 * whole percent of the distance on favourite sections and on curvy roads,
 * and the km on gravel (one decimal).
 */
data class RouteSummary(
    val km: Double,
    val minutes: Int,
    val favouritePercent: Int = 0,
    val extraMinutes: Int = 0,
    val curvyPercent: Int = 0,
    val gravelKm: Double = 0.0,
)

fun summarize(
    distanceM: Double,
    durationS: Double,
    favouriteShare: Double = 0.0,
    fastestDurationS: Double = durationS,
    curvyShare: Double = 0.0,
    unpavedM: Double = 0.0,
): RouteSummary =
    RouteSummary(
        km = Math.round(distanceM / 100.0) / 10.0,
        minutes = Math.round(durationS / 60.0).toInt(),
        favouritePercent = percent(favouriteShare),
        extraMinutes = Math.round(((durationS - fastestDurationS) / 60.0).coerceAtLeast(0.0)).toInt(),
        curvyPercent = percent(curvyShare),
        gravelKm = Math.round(unpavedM.coerceIn(0.0, distanceM.coerceAtLeast(0.0)) / 100.0) / 10.0,
    )

private fun percent(share: Double): Int = Math.round(share.coerceIn(0.0, 1.0) * 100.0).toInt()

/**
 * Extra time over the fastest route the rider can give a route, in
 * percent; the favourites pull as hard as it allows. 0 is the fastest
 * route.
 */
val BUDGET_CHOICES = listOf(0, 20, 40, 60)

/** The core's default budget (40 % extra). */
const val DEFAULT_BUDGET_PERCENT = 40

/** A stored budget, or the default when it isn't one of the choices
 * (preferences are read back as untrusted input). */
fun budgetPercentOf(stored: Int?): Int = stored?.takeIf { it in BUDGET_CHOICES } ?: DEFAULT_BUDGET_PERCENT

/** The gravel choices, in the order they are offered. */
val GRAVEL_CHOICES: List<Gravel> = listOf(Gravel.AVOID, Gravel.ALLOW, Gravel.PREFER)

/** How a gravel choice is stored in preferences. */
fun gravelKey(g: Gravel): String = g.name.lowercase()

/** A stored gravel choice; without one, [legacyAllow] (the old Allow
 * gravel switch) or else avoid. Preferences are read back as untrusted
 * input: anything else counts as unset. */
fun gravelOf(stored: String?, legacyAllow: Boolean = false): Gravel =
    GRAVEL_CHOICES.firstOrNull { gravelKey(it) == stored } ?: if (legacyAllow) Gravel.ALLOW else Gravel.AVOID

/**
 * [base] (the core's defaults) with [percent] extra time allowed, and
 * gravel (unpaved) roads avoided, allowed or preferred. Avoided means
 * "where possible": the core counts them as much slower, it doesn't ban
 * them; preferred means they pull the route within the extra time.
 */
fun routeOptions(base: RouteOptions, percent: Int, gravel: Gravel = Gravel.AVOID): RouteOptions =
    base.copy(
        budget = TimeBudget.Extra(percent / 100.0),
        gravel = gravel,
    )

/**
 * Whether the bundled region must be (re)installed: when there is no
 * installed file, or it was installed by another build of the app (a
 * different [currentStamp]).
 */
fun needsInstall(installedExists: Boolean, installedStamp: String?, currentStamp: String): Boolean =
    !installedExists || installedStamp != currentStamp

/** Exported route files: "moto-route-2026-09-24-1830.gpx", in local time. */
fun routeFileName(atSec: Long, zone: java.time.ZoneId): String =
    "moto-route-" + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", java.util.Locale.ROOT)) + ".gpx"

/** Whether [name] is one of our exported route files (only those are
 * cleaned up from the share folder). */
fun isRouteFileName(name: String): Boolean = ROUTE_FILE.matches(name)

private val ROUTE_FILE = Regex("moto-route-[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}\\.gpx")

/** The name a route carries inside its GPX, shown by the nav app:
 * "moto 2026-09-24 18:30, 57.4 km". */
fun routeGpxName(atSec: Long, zone: java.time.ZoneId, km: Double): String =
    "moto " + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)) +
        String.format(java.util.Locale.ROOT, ", %.1f km", km)


/** Longest saved-route name, in characters (the core's limit). */
const val MAX_ROUTE_NAME_CHARS = 200

/** The name a saved route gets unless the rider types one:
 * "Loop 2026-09-24 18:30, 57.4 km" (or "Route …"). */
fun defaultRouteName(atSec: Long, zone: java.time.ZoneId, km: Double, isLoop: Boolean): String =
    (if (isLoop) "Loop " else "Route ") + java.time.Instant.ofEpochSecond(atSec).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)) +
        String.format(java.util.Locale.ROOT, ", %.1f km", km)

/** A typed route name as the core accepts it: control characters dropped
 * (a pasted newline becomes a space), trimmed and cut to
 * [MAX_ROUTE_NAME_CHARS] characters; null when nothing is left. */
fun cleanRouteName(typed: String): String? {
    val text = typed.map { if (it == '\n' || it == '\t') ' ' else it }
        .filterNot { Character.isISOControl(it) }
        .joinToString("")
        .trim()
    if (text.isEmpty()) return null
    val cps = text.codePointCount(0, text.length)
    return if (cps <= MAX_ROUTE_NAME_CHARS) text else text.substring(0, text.offsetByCodePoints(0, MAX_ROUTE_NAME_CHARS)).trimEnd()
}

/** Most via points a route may pass (the core's limit). */
const val MAX_VIA_POINTS = 8

/**
 * [vias] with [p] put where it lengthens the trip [start] → vias → [end]
 * the least, measured in straight lines: a via point dropped anywhere on
 * the map lands in the leg it belongs to, however the rider added the
 * others.
 */
fun insertVia(start: LatLon, vias: List<LatLon>, end: LatLon, p: LatLon): List<LatLon> {
    val stops = listOf(start) + vias + listOf(end)
    val best = (0 until stops.size - 1).minBy { i ->
        approxDistanceM(stops[i], p) + approxDistanceM(p, stops[i + 1]) - approxDistanceM(stops[i], stops[i + 1])
    }
    return vias.subList(0, best) + p + vias.subList(best, vias.size)
}
