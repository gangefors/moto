// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.Track

/**
 * One entry of Menu → Routes & rides: a saved route (planned, to ride
 * again) or a ride (recorded or imported). They are stored apart (a ride
 * keeps every GPS fix; a route only its line) but listed and handled
 * alike.
 */
sealed interface LibraryItem {
    /** A key unique across both kinds. */
    val key: String

    /** When it was made: saved, or when the ride started (epoch seconds). */
    val at: Long

    /** Its length, metres. */
    val distanceM: Double

    data class Route(val route: SavedRoute) : LibraryItem {
        override val key get() = routeKey(route.id)
        override val at get() = route.createdAt
        override val distanceM get() = route.distanceM
    }

    data class Ride(val track: Track) : LibraryItem {
        override val key get() = rideKey(track.id)
        override val at get() = track.startedAt
        override val distanceM get() = track.distanceM
    }
}

/** [LibraryItem.key] of saved route [id] and of ride [id]. */
fun routeKey(id: Long): String = "route-$id"
fun rideKey(id: Long): String = "ride-$id"

/** The orders Routes & rides can list in (Stefan, 2026-10-03). */
enum class LibrarySort { NEWEST, OLDEST, LONGEST, NAME, NEAREST }

/** The order named [name] (as stored), else newest first. */
fun librarySortOf(name: String?): LibrarySort = LibrarySort.entries.firstOrNull { it.name == name } ?: LibrarySort.NEWEST

/**
 * [items] in [sort]'s order: newest or oldest first (a ride by when it
 * started, a route by when it was saved), longest first, by [title] A–Z
 * (ignoring case), or nearest first by where each starts ([starts], by
 * [LibraryItem.key]) from [here]; those with no known start go last.
 * Without [here], Nearest lists newest first. Ties go newest first.
 */
fun sortLibrary(
    items: List<LibraryItem>,
    sort: LibrarySort,
    here: LatLon?,
    starts: Map<String, LatLon>,
    title: (LibraryItem) -> String,
): List<LibraryItem> {
    val newest = compareByDescending<LibraryItem> { it.at }.thenBy { it.key }
    val order = when (sort) {
        LibrarySort.NEWEST -> newest
        LibrarySort.OLDEST -> compareBy<LibraryItem> { it.at }.thenBy { it.key }
        LibrarySort.LONGEST -> compareByDescending<LibraryItem> { it.distanceM }.then(newest)
        LibrarySort.NAME -> compareBy<LibraryItem, String>(String.CASE_INSENSITIVE_ORDER) { title(it) }.then(newest)
        LibrarySort.NEAREST -> if (here == null) {
            newest
        } else {
            compareBy<LibraryItem> { item -> starts[item.key]?.let { approxDistanceM(here, it) } ?: Double.MAX_VALUE }.then(newest)
        }
    }
    return items.sortedWith(order)
}

/** Routes and rides together, newest first (null while either loads). */
fun libraryItems(routes: List<SavedRoute>?, tracks: List<Track>?): List<LibraryItem>? {
    if (routes == null || tracks == null) return null
    return (routes.map { LibraryItem.Route(it) } + tracks.map { LibraryItem.Ride(it) })
        .sortedWith(compareByDescending<LibraryItem> { it.at }.thenBy { it.key })
}

/** Which of Routes & rides to list. */
enum class LibraryFilter { ALL, ROUTES, RIDES }

/** The filter named [name] (as stored), else all. */
fun libraryFilterOf(name: String?): LibraryFilter = LibraryFilter.entries.firstOrNull { it.name == name } ?: LibraryFilter.ALL

/** [items] that [filter] lets through, in their order. */
fun filterLibrary(items: List<LibraryItem>, filter: LibraryFilter): List<LibraryItem> = when (filter) {
    LibraryFilter.ALL -> items
    LibraryFilter.ROUTES -> items.filterIsInstance<LibraryItem.Route>()
    LibraryFilter.RIDES -> items.filterIsInstance<LibraryItem.Ride>()
}

/** Most GPX files one import takes (several can be picked at once, Stefan
 * 2026-10-01): a bound on the work a single pick can start. */
const val MAX_GPX_FILES = 100

/** What became of one file ([name], as shown) of a ride import: rides of
 * [Imported.distancesM] metres (one per track segment) named
 * [Imported.rideNames] (where a name was found), and how many of its
 * rides were already saved ([Imported.alreadySaved]); or nothing, and
 * why ([Failed.reason]). */
sealed interface RideImport {
    val name: String

    data class Imported(
        override val name: String,
        val distancesM: List<Double>,
        val alreadySaved: Int = 0,
        val rideNames: List<String> = emptyList(),
    ) : RideImport

    data class Failed(override val name: String, val reason: String) : RideImport
}

/** A ride import in numbers: [imported] rides from [files] files, [km] in
 * all, [alreadySaved] rides left out as already saved, and [failed]
 * files not imported. */
data class RideImportSummary(
    val imported: Int,
    val files: Int,
    val km: Double,
    val alreadySaved: Int,
    val failed: Int,
)

/** Sums up [results], one per file in the order picked. */
fun rideImportSummary(results: List<RideImport>): RideImportSummary {
    val imported = results.filterIsInstance<RideImport.Imported>()
    return RideImportSummary(
        imported = imported.sumOf { it.distancesM.size },
        files = results.size,
        km = sectionKm(imported.sumOf { it.distancesM.sum() }),
        alreadySaved = imported.sumOf { it.alreadySaved },
        failed = results.count { it is RideImport.Failed },
    )
}

/** Whether an import gets the report dialog (mockup Import GPX report)
 * rather than the short message: when a file was not imported, or rides
 * were left out as already saved, except a single file whose rides were
 * all already saved ("That ride is already in the app" says it all). */
fun rideImportNeedsReport(results: List<RideImport>): Boolean {
    val s = rideImportSummary(results)
    if (s.failed > 0) return true
    if (s.alreadySaved == 0) return false
    return !(s.files == 1 && s.imported == 0)
}

/** The files of an import for the report, in the order picked: those not
 * imported, those with rides already saved, and those with rides
 * imported (a file can be in the last two). */
data class RideImportGroups(
    val failed: List<RideImport.Failed>,
    val alreadySaved: List<RideImport.Imported>,
    val imported: List<RideImport.Imported>,
)

fun rideImportGroups(results: List<RideImport>): RideImportGroups {
    val read = results.filterIsInstance<RideImport.Imported>()
    return RideImportGroups(
        failed = results.filterIsInstance<RideImport.Failed>(),
        alreadySaved = read.filter { it.alreadySaved > 0 },
        imported = read.filter { it.distancesM.isNotEmpty() },
    )
}

/** How near its start a ride must end to be named as a loop ("Loop from
 * Lund via Höör") rather than from one place to another. */
const val RIDE_LOOP_M = 2_000.0

/** How far out a ride must have gone to be named as a loop: at least
 * this, and twice as far as it ended from its start, so a short ride
 * (ending within [RIDE_LOOP_M] because it never went anywhere) is not. */
const val RIDE_LOOP_OUT_M = 1_000.0

/** Whether a ride along [line] went out and came back to where it began. */
fun rideIsLoop(line: List<se.gangefors.moto.core.LatLon>): Boolean {
    if (line.size < 2) return false
    val gap = approxDistanceM(line.first(), line.last())
    if (gap > RIDE_LOOP_M) return false
    val out = farthestPoint(line)?.let { approxDistanceM(line.first(), it) } ?: return false
    return out >= maxOf(RIDE_LOOP_OUT_M, 2 * gap)
}

/** A picked file's name as it may be shown in a message: the name comes
 * from another app, so control and formatting characters are dropped and
 * it is cut to [MAX_SHOWN_NAME] characters; [fallback] when nothing is
 * left. */
fun shownFileName(name: String?, fallback: String): String {
    val clean = name.orEmpty()
        .filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
        .trim()
    if (clean.isEmpty()) return fallback
    return if (clean.length <= MAX_SHOWN_NAME) clean else clean.take(MAX_SHOWN_NAME - 1).trimEnd() + "…"
}

private const val MAX_SHOWN_NAME = 40
