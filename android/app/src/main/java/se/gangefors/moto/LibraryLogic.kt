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
