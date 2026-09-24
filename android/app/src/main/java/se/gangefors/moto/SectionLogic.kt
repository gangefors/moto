// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating

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

/** Longest section name, in characters; the core's limit. */
const val MAX_SECTION_NAME_CHARS = 200

/**
 * The name to save: [raw] without control characters (the core rejects
 * them), line breaks turned into spaces, trimmed and cut to the core's
 * length limit. A blank name becomes [fallback].
 */
fun cleanSectionName(raw: String, fallback: String): String {
    val cleaned = buildString {
        raw.codePoints().forEach { cp ->
            when {
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> append(' ')
                Character.isISOControl(cp) -> Unit
                else -> appendCodePoint(cp)
            }
        }
    }.trim()
    return takeCodePoints(cleaned.ifEmpty { fallback }, MAX_SECTION_NAME_CHARS).trimEnd()
}

/** The first [n] characters (code points, never half a surrogate pair) of [s]. */
fun takeCodePoints(s: String, n: Int): String =
    if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

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
