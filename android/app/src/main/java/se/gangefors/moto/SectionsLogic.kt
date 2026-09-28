// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.Description
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionStatus

/** A saved section as the Sections page lists it: its length, and the
 * core's description of it once found (null before, or with no region). */
data class SectionRow(val section: Section, val lengthM: Double, val description: Description? = null) {
    val curvyShare: Double get() = description?.curvyShare?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
}

/** How the page orders sections. */
enum class SectionSort { RATING, LENGTH, CURVY, NEWEST, NEAREST }

/** Which sections the page shows: those rated one of [ratings] (all when
 * empty), or only those that no longer fit the map ([attention]). */
data class SectionFilter(val ratings: Set<Rating> = emptySet(), val attention: Boolean = false)

/** A section the rider should look at: re-matching after a map update
 * found it no longer fits the roads. */
fun needsAttention(s: Section): Boolean = s.status == SectionStatus.UNMATCHED

fun filterSections(rows: List<SectionRow>, filter: SectionFilter): List<SectionRow> = rows.filter {
    (!filter.attention || needsAttention(it.section)) &&
        (filter.ratings.isEmpty() || it.section.rating in filter.ratings)
}

/** Rating order, best first. */
private fun rank(r: Rating): Int = when (r) {
    Rating.EPIC -> 0
    Rating.GREAT -> 1
    Rating.GOOD -> 2
}

/**
 * [rows] in [sort] order: best rated first (then longest), longest,
 * curviest, newest, or nearest to [here] (then as by rating; without a
 * position, as by rating). Ties keep a stable order by id.
 */
fun sortSections(rows: List<SectionRow>, sort: SectionSort, here: LatLon?): List<SectionRow> {
    val byRating = compareBy<SectionRow>({ rank(it.section.rating) }, { -it.lengthM }, { it.section.id })
    val order = when (sort) {
        SectionSort.RATING -> byRating
        SectionSort.LENGTH -> compareBy<SectionRow>({ -it.lengthM }, { it.section.id })
        SectionSort.CURVY -> compareBy<SectionRow>({ -it.curvyShare }, { rank(it.section.rating) }, { it.section.id })
        SectionSort.NEWEST -> compareBy<SectionRow>({ -it.section.createdAt }, { it.section.id })
        SectionSort.NEAREST -> if (here == null) {
            byRating
        } else {
            compareBy<SectionRow>({ distanceToLineM(here, it.section.geometry) }).then(byRating)
        }
    }
    return rows.sortedWith(order)
}

/** About how far [p] is from the nearest point of [line], metres (by its
 * points; sections are drawn densely enough for a list's order). */
fun distanceToLineM(p: LatLon, line: List<LatLon>): Double =
    line.minOfOrNull { approxDistanceM(p, it) } ?: Double.MAX_VALUE

/** The figures at the top of the page. */
data class SectionsSummary(
    val count: Int,
    val totalKm: Double,
    val epic: Int,
    val great: Int,
    val good: Int,
    val attention: Int,
)

fun summarize(rows: List<SectionRow>): SectionsSummary = SectionsSummary(
    count = rows.size,
    totalKm = sectionKm(rows.sumOf { it.lengthM }),
    epic = rows.count { it.section.rating == Rating.EPIC },
    great = rows.count { it.section.rating == Rating.GREAT },
    good = rows.count { it.section.rating == Rating.GOOD },
    attention = rows.count { needsAttention(it.section) },
)

/** [ratings] with [r] added or taken out. */
fun toggled(ratings: Set<Rating>, r: Rating): Set<Rating> = if (r in ratings) ratings - r else ratings + r

/**
 * The ends of a section to ride it from [here]: the nearer end first,
 * then the other, so a route rides it all the way; a one-way section
 * always from its start. Null for a line of fewer than two points.
 */
fun sectionEnds(line: List<LatLon>, oneWay: Boolean, here: LatLon): Pair<LatLon, LatLon>? {
    if (line.size < 2) return null
    val (a, b) = line.first() to line.last()
    return if (oneWay || approxDistanceM(here, a) <= approxDistanceM(here, b)) a to b else b to a
}
