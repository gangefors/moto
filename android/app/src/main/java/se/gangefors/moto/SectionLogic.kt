// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionStatus

/**
 * "Mark section" mode (PRD R2): tap the start, tap the end, and the core
 * proposes the road between them. While a proposal is shown, a tap moves
 * the nearer end there and the section is proposed again. Pure logic,
 * independent of the map, so it can be unit tested; [distance] measures
 * how near a tap is to each end.
 */
class SectionMarker<P>(private val distance: (P, P) -> Double) {
    sealed interface State<out P> {
        /** Not marking a section. */
        data object Off : State<Nothing>
        data object PickStart : State<Nothing>
        data class PickEnd<P>(val start: P) : State<P>
        /** The core is asked for, or shows, the section between these points. */
        data class Proposed<P>(val start: P, val end: P) : State<P>
    }

    var state: State<P> = State.Off
        private set

    private var previous: State<P> = State.Off

    val isActive: Boolean get() = state != State.Off

    fun begin() = moveTo(State.PickStart)

    fun cancel() = moveTo(State.Off)

    /** Starts with a section already proposed between [start] and [end]
     * (a quick-tag's suggestion), for the rider to adjust or save. */
    fun propose(start: P, end: P) = moveTo(State.Proposed(start, end))

    /**
     * Handles a tap on the map and returns the new state. A [State.Proposed]
     * result means the section between its points should be (re)proposed.
     */
    fun onTap(point: P): State<P> {
        when (val s = state) {
            State.Off -> Unit
            State.PickStart -> moveTo(State.PickEnd(point))
            is State.PickEnd -> moveTo(State.Proposed(s.start, point))
            is State.Proposed -> moveTo(
                if (distance(point, s.start) <= distance(point, s.end)) {
                    State.Proposed(point, s.end)
                } else {
                    State.Proposed(s.start, point)
                },
            )
        }
        return state
    }

    /** Undoes the last tap, whose point the core could not use. */
    fun rejectLast() {
        state = previous
    }

    private fun moveTo(next: State<P>) {
        previous = state
        state = next
    }
}

/**
 * Approximate distance in metres between two nearby points (equirectangular),
 * enough to tell which end of a section a tap is nearer to.
 */
fun approxDistanceM(a: LatLon, b: LatLon): Double {
    val meanLat = Math.toRadians((a.lat + b.lat) / 2)
    val dx = Math.toRadians(b.lon - a.lon) * cos(meanLat)
    val dy = Math.toRadians(b.lat - a.lat)
    return EARTH_RADIUS_M * sqrt(dx * dx + dy * dy)
}

private const val EARTH_RADIUS_M = 6_371_000.0

/** Approximate length in metres of a line. */
fun lengthM(line: List<LatLon>): Double = line.zipWithNext(::approxDistanceM).sum()

/**
 * The name a new section is stored under. Riders never see or type names
 * (sections are found on the map), but the store keeps one: where it came
 * from, when, and how long, e.g. "Tag 2026-09-24 14:05, 1.2 km".
 */
fun autoSectionName(fromTag: Boolean, savedAtSec: Long, distanceM: Double, zone: java.time.ZoneId): String =
    String.format(
        java.util.Locale.ROOT,
        "%s %s, %.1f km",
        if (fromTag) "Tag" else "Map",
        rideTitle(savedAtSec, zone),
        sectionKm(distanceM),
    )

/** Length for labels: one decimal in km, e.g. 12.3. */
fun sectionKm(distanceM: Double): Double = (distanceM / 100.0).roundToInt() / 10.0

/** Line colour of a saved section on the map, by rating. */
fun ratingColor(rating: Rating): String = when (rating) {
    Rating.GOOD -> "#f9ab00"
    Rating.GREAT -> "#e8710a"
    Rating.EPIC -> "#a142f4"
}

/** Ratings from lowest to highest, as offered to the rider. */
val RATINGS: List<Rating> = listOf(Rating.GOOD, Rating.GREAT, Rating.EPIC)

fun isOneWay(direction: Direction): Boolean = direction == Direction.FORWARD

fun directionOf(oneWay: Boolean): Direction = if (oneWay) Direction.FORWARD else Direction.BOTH

/**
 * The section id stored in a map feature's properties, if the feature is a
 * saved section. Feature properties come back from the map as numbers of any
 * type, so anything that isn't a whole, positive id is ignored.
 */
fun sectionIdOf(value: Any?): Long? {
    val n = value as? Number ?: return null
    val d = n.toDouble()
    if (d.isNaN() || d < 1 || d > MAX_EXACT_ID || d != Math.floor(d)) return null
    return d.toLong()
}

/** Largest integer a double (the map's number type) holds exactly. */
private const val MAX_EXACT_ID = 9_007_199_254_740_992.0

/**
 * Whether a section is drawn as fitting the current map's roads. A section
 * waiting to be re-matched after a map update, or that no longer fits,
 * is drawn apart until it is re-matched.
 */
fun fitsTheMap(status: SectionStatus): Boolean = status == SectionStatus.OK

/** The sections to draw: those that fit the map, and the others only if
 * the rider chose to see them (hidden by default after a map update). */
fun visibleSections(sections: List<Section>, showUnmatched: Boolean): List<Section> =
    if (showUnmatched) sections else sections.filter { fitsTheMap(it.status) }
