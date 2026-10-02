// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.LatLon
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
    fun aRideImportOfManyFilesSumsUpAndGroupsItsFiles() {
        val one = rideImportSummary(listOf(RideImport.Imported("a.gpx", listOf(12_345.0))))
        assertEquals(RideImportSummary(1, 1, 12.3, 0, 0), one)

        val day = RideImport.Imported("day.gpx", listOf(10_000.0, 2_000.0), alreadySaved = 1, rideNames = listOf("A → B"))
        val bad = RideImport.Failed("a.gpx", "no track points")
        val single = RideImport.Imported("one.gpx", listOf(5_060.0))
        val old = RideImport.Imported("old.gpx", emptyList(), alreadySaved = 2)
        val results = listOf(day, bad, single, old)
        val s = rideImportSummary(results)
        assertEquals(RideImportSummary(imported = 3, files = 4, km = 17.1, alreadySaved = 3, failed = 1), s)
        // Files in the order picked; a file with rides of both kinds in
        // both groups.
        val g = rideImportGroups(results)
        assertEquals(listOf(bad), g.failed)
        assertEquals(listOf(day, old), g.alreadySaved)
        assertEquals(listOf(day, single), g.imported)
        assertEquals(100, MAX_GPX_FILES)
    }

    @Test
    fun theReportShowsWhenSomethingWasLeftOut() {
        val ok = RideImport.Imported("a.gpx", listOf(1_000.0))
        val fail = RideImport.Failed("b.gpx", "bad")
        val allThere = RideImport.Imported("c.gpx", emptyList(), alreadySaved = 1)
        val someThere = RideImport.Imported("d.gpx", listOf(1_000.0), alreadySaved = 1)
        // Everything imported: the short message.
        assertFalse(rideImportNeedsReport(listOf(ok)))
        assertFalse(rideImportNeedsReport(listOf(ok, ok)))
        // One file, all already there: the short message says it all.
        assertFalse(rideImportNeedsReport(listOf(allThere)))
        // A file not imported, even alone; rides left out otherwise.
        assertTrue(rideImportNeedsReport(listOf(fail)))
        assertTrue(rideImportNeedsReport(listOf(ok, fail)))
        assertTrue(rideImportNeedsReport(listOf(someThere)))
        assertTrue(rideImportNeedsReport(listOf(ok, allThere)))
    }

    @Test
    fun aRideBackWhereItBeganIsNamedAsALoop() {
        val start = LatLon(55.70, 13.20)
        val out = LatLon(55.80, 13.40)
        assertTrue(rideIsLoop(listOf(start, out, LatLon(55.71, 13.20))))
        assertFalse(rideIsLoop(listOf(start, out, LatLon(55.75, 13.20))))
        assertFalse(rideIsLoop(listOf(start)))
        assertEquals(2_000.0, RIDE_LOOP_M, 0.0)
    }

    @Test
    fun aShortRideIsNotALoop() {
        // About 200 m straight on: it ends near its start only because it
        // never went anywhere.
        val start = LatLon(55.70, 13.20)
        assertFalse(rideIsLoop(listOf(start, LatLon(55.701, 13.20), LatLon(55.7018, 13.20))))
        // Ended 1.5 km away (within 2 km of its start), and went no
        // farther out than that: from one place to another.
        assertFalse(rideIsLoop(listOf(start, LatLon(55.7135, 13.20))))
        // Out 1.1 km and back to the start: a loop.
        assertTrue(rideIsLoop(listOf(start, LatLon(55.71, 13.20), LatLon(55.7001, 13.20))))
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
