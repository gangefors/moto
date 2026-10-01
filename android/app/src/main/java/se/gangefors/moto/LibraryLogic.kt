// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

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

    data class Route(val route: SavedRoute) : LibraryItem {
        override val key get() = "route-${route.id}"
        override val at get() = route.createdAt
    }

    data class Ride(val track: Track) : LibraryItem {
        override val key get() = "ride-${track.id}"
        override val at get() = track.startedAt
    }
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

/** What became of one file of a ride import: rides of
 * [Imported.distancesM] metres (one per track segment) and how many of
 * its rides were already saved ([Imported.alreadySaved]), or nothing,
 * with the file's [Failed.name] and why. */
sealed interface RideImport {
    data class Imported(val distancesM: List<Double>, val alreadySaved: Int = 0) : RideImport
    data class Failed(val name: String, val reason: String) : RideImport
}

/** A ride import in numbers, for its message: [imported] rides from
 * [files] files, [km] in all, [alreadySaved] rides left out as already
 * saved, the first [failed] files not imported and how many more
 * ([moreFailed]). */
data class RideImportSummary(
    val imported: Int,
    val files: Int,
    val km: Double,
    val alreadySaved: Int,
    val failed: List<RideImport.Failed>,
    val moreFailed: Int,
)

/** Sums up [results], one per file in the order picked, listing at most
 * [listed] of the files not imported. */
fun rideImportSummary(results: List<RideImport>, listed: Int = 3): RideImportSummary {
    val imported = results.filterIsInstance<RideImport.Imported>()
    val failed = results.filterIsInstance<RideImport.Failed>()
    return RideImportSummary(
        imported = imported.sumOf { it.distancesM.size },
        files = results.size,
        km = sectionKm(imported.sumOf { it.distancesM.sum() }),
        alreadySaved = imported.sumOf { it.alreadySaved },
        failed = failed.take(listed),
        moreFailed = (failed.size - listed).coerceAtLeast(0),
    )
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
