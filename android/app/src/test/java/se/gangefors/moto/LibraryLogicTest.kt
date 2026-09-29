// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gangefors.moto.core.SavedRoute
import se.gangefors.moto.core.Track

class LibraryLogicTest {
    private fun route(id: Long, at: Long) =
        SavedRoute(id, "local", "Route $id", false, at, 10_000.0, 600.0)

    private fun ride(id: Long, at: Long) =
        Track(id, "local", null, at, at + 600, 100uL, 10_000.0)

    @Test
    fun routesAndRidesTogetherNewestFirst() {
        val items = libraryItems(
            listOf(route(1, 100), route(2, 300)),
            listOf(ride(1, 200), ride(2, 400)),
        )!!
        assertEquals(listOf("ride-2", "route-2", "ride-1", "route-1"), items.map { it.key })
        // The same time: a fixed order, not a flicker between reloads.
        val tie = libraryItems(listOf(route(5, 100)), listOf(ride(5, 100)))!!
        assertEquals(listOf("ride-5", "route-5"), tie.map { it.key })
    }

    @Test
    fun nothingUntilBothHaveLoaded() {
        assertNull(libraryItems(null, emptyList()))
        assertNull(libraryItems(emptyList(), null))
        assertEquals(emptyList<LibraryItem>(), libraryItems(emptyList(), emptyList()))
    }

    @Test
    fun filtersRoutesOrRidesKeepingTheOrder() {
        val items = libraryItems(listOf(route(1, 300), route(2, 100)), listOf(ride(3, 200), ride(4, 400)))!!
        assertEquals(items, filterLibrary(items, LibraryFilter.ALL))
        assertEquals(listOf("route-1", "route-2"), filterLibrary(items, LibraryFilter.ROUTES).map { it.key })
        assertEquals(listOf("ride-4", "ride-3"), filterLibrary(items, LibraryFilter.RIDES).map { it.key })
    }

    @Test
    fun anUnknownStoredFilterListsAll() {
        assertEquals(LibraryFilter.RIDES, libraryFilterOf("RIDES"))
        assertEquals(LibraryFilter.ALL, libraryFilterOf(null))
        assertEquals(LibraryFilter.ALL, libraryFilterOf("nonsense"))
    }
}
