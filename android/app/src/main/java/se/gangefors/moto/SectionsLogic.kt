// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.Description
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionRidden
import se.gangefors.moto.core.SectionStatus

/** A saved section as the Sections page lists it: its length, and the
 * core's description of it once found (null before, or with no region). */
data class SectionRow(
    val section: Section,
    val lengthM: Double,
    val description: Description? = null,
    /** How often and when last it was ridden; null until counted. */
    val ridden: SectionRidden? = null,
) {
    val curvyShare: Double get() = description?.curvyShare?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
}

/** How the page orders sections. */
enum class SectionSort { RATING, LENGTH, CURVY, NEWEST, NEAREST, LONGEST_UNRIDDEN }

/** A sort stored by name; rating for none or an unknown one. */
fun sectionSortOf(name: String?): SectionSort = SectionSort.entries.firstOrNull { it.name == name } ?: SectionSort.RATING

/** Which sections the page shows: those rated one of [ratings] (all when
 * empty), or only those that no longer fit the map ([attention]). */
data class SectionFilter(val ratings: Set<Rating> = emptySet(), val attention: Boolean = false)

/**
 * The Sections page filter to go back to after section [deletedId] is
 * deleted: the one the page had when that section was shown from it
 * ([shownFromPage]: its id and the filter), else null (stay on the map).
 */
fun pageAfterDelete(shownFromPage: Pair<Long, SectionFilter>?, deletedId: Long): SectionFilter? =
    shownFromPage?.takeIf { it.first == deletedId }?.second

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
 * curviest, newest, nearest to [here] (then as by rating; without a
 * position, as by rating), or longest since ridden (never ridden first;
 * the best rated first among equals).
 * Ties keep a stable order by id.
 */
fun sortSections(rows: List<SectionRow>, sort: SectionSort, here: LatLon?): List<SectionRow> {
    val byRating = compareBy<SectionRow>({ rank(it.section.rating) }, { -it.lengthM }, { it.section.id })
    val order = when (sort) {
        SectionSort.RATING -> byRating
        SectionSort.LENGTH -> compareBy<SectionRow>({ -it.lengthM }, { it.section.id })
        SectionSort.CURVY -> compareBy<SectionRow>({ -it.curvyShare }, { rank(it.section.rating) }, { it.section.id })
        SectionSort.NEWEST -> compareBy<SectionRow>({ -it.section.createdAt }, { it.section.id })
        // Never ridden first, then the longest since; the best of equals.
        SectionSort.LONGEST_UNRIDDEN -> compareBy<SectionRow>({ it.ridden?.lastAt ?: Long.MIN_VALUE }).then(byRating)
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

/**
 * The day of a ride for a list: "12 Aug", with the year when it isn't
 * [nowSec]'s ("12 Aug 2025"). In [zone], in [locale]'s words.
 */
fun rideDay(atSec: Long, nowSec: Long, zone: java.time.ZoneId, locale: java.util.Locale): String {
    val at = java.time.Instant.ofEpochSecond(atSec).atZone(zone)
    val now = java.time.Instant.ofEpochSecond(nowSec).atZone(zone)
    val pattern = if (at.year == now.year) "d MMM" else "d MMM yyyy"
    return at.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * Descriptions of lines found with one engine [E] (a region), kept while
 * it stays loaded and dropped when another takes its place. Keyed by the
 * line itself, never by a section's id: SQLite gives a new section the id
 * of a deleted last one, and a saved section's line never changes.
 */
class DescriptionCache<E : Any, D : Any> {
    private var owner: E? = null
    private val byLine = HashMap<List<LatLon>, D>()

    /** The description of [line] found with [engine], if any. */
    @Synchronized
    fun get(engine: E?, line: List<LatLon>): D? = if (engine != null && engine === owner) byLine[line] else null

    /** Describes those of [lines] not yet described (with [describe]; null
     * leaves one out) and returns the descriptions of all of [lines]. */
    @Synchronized
    fun fill(engine: E, lines: List<List<LatLon>>, describe: (List<LatLon>) -> D?): Map<List<LatLon>, D> {
        if (engine !== owner) {
            byLine.clear()
            owner = engine
        }
        for (line in lines) {
            if (!byLine.containsKey(line)) describe(line)?.let { byLine[line] = it }
        }
        return lines.mapNotNull { l -> byLine[l]?.let { l to it } }.toMap()
    }
}

/** The name the app gives a new section when saving it ("Map 2026-09-28
 * 14:02, 3.2 km"), which is no name of the rider's (see [autoSectionName]). */
private val AUTO_NAME = Regex("""^(Map|Tag) \d{4}-\d{2}-\d{2} \d{2}:\d{2}, \d+\.\d km$""")

/** The rider's own name for a section, or null when it has only the name
 * the app gave it (or none): then it goes by where it runs. */
fun riderName(name: String): String? = name.trim().takeUnless { it.isEmpty() || AUTO_NAME.matches(it) }

/** The name to store for what the rider typed: cleaned as route names
 * are; empty (go by where it runs) when nothing is left. */
fun sectionNameToStore(typed: String): String = cleanRouteName(typed) ?: ""

/**
 * The name change to store when the rider saves a section named [stored]
 * with [typed] in its name field: the typed name (cleaned); empty when
 * they cleared a name of their own; null (no change) when the field is
 * empty and it had only the app's name.
 */
fun nameUpdate(stored: String, typed: String): String? {
    val name = sectionNameToStore(typed)
    return when {
        name.isNotEmpty() -> name.takeIf { it != stored }
        riderName(stored) != null -> ""
        else -> null
    }
}
