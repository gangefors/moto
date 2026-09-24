// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.MotoException
import se.gangefors.moto.core.RouteOptions
import se.gangefors.moto.core.TimeBudget

/**
 * Picking a route with long-presses: the first sets the start, the second
 * the end (completing the pick), the next starts a new pick. Pure logic,
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
 * minutes, whole minutes over the fastest route (never negative), and the
 * whole percent of the distance on favourite sections and on curvy roads.
 */
data class RouteSummary(
    val km: Double,
    val minutes: Int,
    val favouritePercent: Int = 0,
    val extraMinutes: Int = 0,
    val curvyPercent: Int = 0,
)

fun summarize(
    distanceM: Double,
    durationS: Double,
    favouriteShare: Double = 0.0,
    fastestDurationS: Double = durationS,
    curvyShare: Double = 0.0,
): RouteSummary =
    RouteSummary(
        km = Math.round(distanceM / 100.0) / 10.0,
        minutes = Math.round(durationS / 60.0).toInt(),
        favouritePercent = percent(favouriteShare),
        extraMinutes = Math.round(((durationS - fastestDurationS) / 60.0).coerceAtLeast(0.0)).toInt(),
        curvyPercent = percent(curvyShare),
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

/**
 * [base] (the core's defaults) with [percent] extra time allowed, and
 * gravel (unpaved) roads allowed or avoided. Avoided means "where
 * possible": the core counts them as much slower, it doesn't ban them.
 */
fun routeOptions(base: RouteOptions, percent: Int, allowGravel: Boolean = false): RouteOptions =
    base.copy(
        budget = TimeBudget.Extra(percent / 100.0),
        avoid = base.avoid.copy(unpaved = !allowGravel),
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

