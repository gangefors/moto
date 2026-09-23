// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.MotoException

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

/** Route summary for the label: distance in km (one decimal) and whole minutes. */
data class RouteSummary(val km: Double, val minutes: Int)

fun summarize(distanceM: Double, durationS: Double): RouteSummary =
    RouteSummary(
        km = Math.round(distanceM / 100.0) / 10.0,
        minutes = Math.round(durationS / 60.0).toInt(),
    )

/**
 * Whether the bundled region must be (re)installed: when there is no
 * installed file, or it was installed by another build of the app (a
 * different [currentStamp]).
 */
fun needsInstall(installedExists: Boolean, installedStamp: String?, currentStamp: String): Boolean =
    !installedExists || installedStamp != currentStamp
