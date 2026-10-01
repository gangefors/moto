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

    @Test
    fun aRideImportOfManyFilesSumsUpAndListsWhatFailed() {
        val one = rideImportSummary(listOf(RideImport.Imported(listOf(12_345.0))))
        assertEquals(RideImportSummary(1, 1, 12.3, 0, emptyList(), 0), one)

        val results = listOf(
            // A file with three rides (track segments), one already saved.
            RideImport.Imported(listOf(10_000.0, 2_000.0), alreadySaved = 1),
            RideImport.Failed("a.gpx", "no track points"),
            RideImport.Imported(listOf(5_060.0)),
            RideImport.Failed("b.gpx", "not GPX"),
            RideImport.Failed("c.gpx", "too large"),
            RideImport.Failed("d.gpx", "not GPX"),
            // Everything in it already saved.
            RideImport.Imported(emptyList(), alreadySaved = 2),
        )
        val s = rideImportSummary(results)
        assertEquals(3, s.imported)
        assertEquals(7, s.files)
        assertEquals(17.1, s.km, 1e-9)
        assertEquals(3, s.alreadySaved)
        // The first three not imported, in the order picked, and a count.
        assertEquals(listOf("a.gpx", "b.gpx", "c.gpx"), s.failed.map { it.name })
        assertEquals(1, s.moreFailed)

        val none = rideImportSummary(listOf(RideImport.Failed("x", "bad")))
        assertEquals(0, none.imported)
        assertEquals(0.0, none.km, 0.0)
        assertEquals(0, none.moreFailed)
        assertEquals(100, MAX_GPX_FILES)
    }

    @Test
    fun aPickedFilesNameIsMadeSafeToShow() {
        assertEquals("Morning ride.gpx", shownFileName("  Morning ride.gpx ", "a file"))
        // Control and bidi characters from another app are dropped.
        assertEquals("evil.gpx", shownFileName("ev\u202Eil\n.gpx", "a file"))
        assertEquals("a file", shownFileName(null, "a file"))
        assertEquals("a file", shownFileName("\u0007 ", "a file"))
        val long = shownFileName("x".repeat(100), "a file")
        assertEquals(40, long.length)
        assertEquals('…', long.last())
    }
}
