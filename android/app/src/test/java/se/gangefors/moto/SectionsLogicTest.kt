// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionRidden
import se.gangefors.moto.core.SectionSource
import se.gangefors.moto.core.SectionStatus

class SectionsLogicTest {
    private fun row(
        id: Long,
        rating: Rating,
        km: Double,
        curvy: Double = 0.0,
        createdAt: Long = id,
        lat: Double = 55.7,
        status: SectionStatus = SectionStatus.OK,
    ) = SectionRow(
        Section(
            id, "local", "", rating, Direction.BOTH, SectionSource.MAP, status, createdAt, createdAt,
            emptyList(), listOf(LatLon(lat, 13.2), LatLon(lat + 0.01, 13.2)),
        ),
        lengthM = km * 1000,
        description = Description(emptyList(), null, null, curvy),
    )

    private val rows = listOf(
        row(1, Rating.GOOD, 20.0, curvy = 0.9, createdAt = 300, lat = 56.5),
        row(2, Rating.EPIC, 5.0, curvy = 0.2, createdAt = 100, lat = 55.0),
        row(3, Rating.EPIC, 12.0, curvy = 0.5, createdAt = 200, lat = 55.7),
        row(4, Rating.GREAT, 8.0, curvy = 0.9, createdAt = 400, lat = 55.72, status = SectionStatus.UNMATCHED),
    )

    private fun ids(list: List<SectionRow>) = list.map { it.section.id }

    @Test
    fun sortsFiveWays() {
        val here = LatLon(55.69, 13.2)
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(sortSections(rows, SectionSort.RATING, here)))
        assertEquals(listOf(1L, 3L, 4L, 2L), ids(sortSections(rows, SectionSort.LENGTH, here)))
        // Equally curvy: the better rated first.
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(sortSections(rows, SectionSort.CURVY, here)))
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(sortSections(rows, SectionSort.NEWEST, here)))
        assertEquals(listOf(3L, 4L, 2L, 1L), ids(sortSections(rows, SectionSort.NEAREST, here)))
        // Nearest without a position: as by rating.
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(sortSections(rows, SectionSort.NEAREST, null)))
    }

    @Test
    fun filtersByRatingAndAttention() {
        assertEquals(rows, filterSections(rows, SectionFilter()))
        assertEquals(listOf(2L, 3L), ids(filterSections(rows, SectionFilter(setOf(Rating.EPIC)))))
        assertEquals(
            listOf(1L, 4L),
            ids(filterSections(rows, SectionFilter(setOf(Rating.GOOD, Rating.GREAT)))),
        )
        assertEquals(listOf(4L), ids(filterSections(rows, SectionFilter(attention = true))))
        assertEquals(emptyList<Long>(), ids(filterSections(rows, SectionFilter(setOf(Rating.EPIC), attention = true))))
    }

    @Test
    fun summarizesTheList() {
        assertEquals(SectionsSummary(4, 45.0, 2, 1, 1, 1), summarize(rows))
        assertEquals(SectionsSummary(0, 0.0, 0, 0, 0, 0), summarize(emptyList()))
    }

    @Test
    fun curvinessIsZeroUntilDescribed() {
        val bare = rows[0].copy(description = null)
        assertEquals(0.0, bare.curvyShare, 0.0)
        val odd = rows[0].copy(description = Description(emptyList(), null, null, Double.NaN))
        assertEquals(0.0, odd.curvyShare, 0.0)
    }

    @Test
    fun ratingChipsToggle() {
        assertEquals(setOf(Rating.EPIC), toggled(emptySet(), Rating.EPIC))
        assertEquals(emptySet<Rating>(), toggled(setOf(Rating.EPIC), Rating.EPIC))
        assertTrue(needsAttention(rows[3].section))
        assertFalse(needsAttention(rows[0].section))
        // Waiting for its region is not something to look at.
        val waiting = row(9, Rating.GOOD, 1.0, status = SectionStatus.NEEDS_REMATCH).section
        assertFalse(needsAttention(waiting))
        assertTrue(offTheMap(waiting))
        assertFalse(offTheMap(rows[3].section))
    }

    @Test
    fun ridesASectionFromItsNearerEndUnlessOneWay() {
        val line = listOf(LatLon(55.70, 13.20), LatLon(55.705, 13.21), LatLon(55.71, 13.22))
        val south = LatLon(55.60, 13.20)
        val north = LatLon(55.80, 13.22)
        assertEquals(line.first() to line.last(), sectionEnds(line, oneWay = false, here = south))
        assertEquals(line.last() to line.first(), sectionEnds(line, oneWay = false, here = north))
        // One-way: always from its start.
        assertEquals(line.first() to line.last(), sectionEnds(line, oneWay = true, here = north))
        assertEquals(null, sectionEnds(line.take(1), oneWay = false, here = north))
    }

    @Test
    fun longestUnriddenComesFirstNeverRiddenFirstOfAll() {
        fun ridden(r: SectionRow, last: Long?) = r.copy(ridden = SectionRidden(r.section.id, if (last == null) 0u else 1u, last))
        val list = listOf(ridden(rows[0], 500), ridden(rows[1], null), ridden(rows[2], 100), ridden(rows[3], null))
        // 2 and 4 never ridden (epic before great), then 3 (long ago), then 1.
        assertEquals(listOf(2L, 4L, 3L, 1L), ids(sortSections(list, SectionSort.LONGEST_UNRIDDEN, null)))
    }

    @Test
    fun rideDaysShowTheYearOnlyWhenNotThisOne() {
        val zone = java.time.ZoneId.of("Europe/Stockholm")
        val now = 1_790_000_000L // 2026-09-21
        assertEquals("12 Aug", rideDay(1_786_500_000L, now, zone, java.util.Locale.ENGLISH))
        assertEquals("12 Aug 2025", rideDay(1_754_964_000L, now, zone, java.util.Locale.ENGLISH))
    }

    @Test
    fun aStoredSortReadsBack() {
        SectionSort.entries.forEach { assertEquals(it, sectionSortOf(it.name)) }
        assertEquals(SectionSort.RATING, sectionSortOf(null))
        assertEquals(SectionSort.RATING, sectionSortOf("SIDEWAYS"))
    }

    @Test
    fun aNewSectionWithADeletedOnesIdGetsItsOwnDescription() {
        // A section is deleted and a new one saved gets its id: the new
        // one must be described afresh, not given the old one's words.
        val cache = DescriptionCache<Any, String>()
        val engine = Any()
        val old = rows[0].section
        val new = old.copy(geometry = listOf(LatLon(55.9, 13.5), LatLon(55.91, 13.5)))
        var described = 0
        fun describe(line: List<LatLon>): String {
            described++
            return if (line == old.geometry) "old" else "new"
        }
        assertEquals(mapOf(old.geometry to "old"), cache.fill(engine, listOf(old.geometry), ::describe))
        assertEquals(null, cache.get(engine, new.geometry))
        assertEquals(mapOf(new.geometry to "new"), cache.fill(engine, listOf(new.geometry), ::describe))
        assertEquals("new", cache.get(engine, new.geometry))
        // Described once each; asking again describes nothing.
        cache.fill(engine, listOf(old.geometry, new.geometry), ::describe)
        assertEquals(2, described)
    }

    @Test
    fun anotherRegionDropsTheDescriptions() {
        val cache = DescriptionCache<Any, String>()
        val (a, b) = Any() to Any()
        val line = rows[0].section.geometry
        cache.fill(a, listOf(line)) { "on a" }
        assertEquals("on a", cache.get(a, line))
        assertEquals(null, cache.get(b, line))
        assertEquals(null, cache.get(null, line))
        assertEquals(mapOf(line to "on b"), cache.fill(b, listOf(line)) { "on b" })
        assertEquals(null, cache.get(a, line))
        // A line that can't be described is left out, not cached.
        assertEquals(emptyMap<List<LatLon>, String>(), cache.fill(b, listOf(listOf(LatLon(1.0, 1.0)))) { null })
    }

    @Test
    fun theAppsOwnNamesAreNoNameOfTheRiders() {
        assertEquals(null, riderName("Map 2026-09-28 14:02, 3.2 km"))
        assertEquals(null, riderName("Tag 2026-01-05 09:30, 12.0 km"))
        assertEquals(null, riderName("  "))
        assertEquals("Coast road", riderName(" Coast road "))
        // Only the app's exact pattern: a rider's name like it stays.
        assertEquals("Map 2026 favourite", riderName("Map 2026 favourite"))
    }

    @Test
    fun savingTheNameFieldChangesOnlyWhatTheRiderChanged() {
        val auto = "Map 2026-09-28 14:02, 3.2 km"
        // Empty field on an app-named section: no change.
        assertEquals(null, nameUpdate(auto, "  "))
        // A name typed: stored, cleaned.
        assertEquals("Coast road", nameUpdate(auto, " Coast road\n"))
        // The same name again: no change.
        assertEquals(null, nameUpdate("Coast road", "Coast road"))
        // The rider's own name cleared: goes by where it runs again.
        assertEquals("", nameUpdate("Coast road", ""))
        assertEquals("", sectionNameToStore(" \t "))
    }

    @Test
    fun deletingASectionShownFromThePageGoesBackToItAsItWas() {
        val filter = SectionFilter(attention = true)
        assertEquals(filter, pageAfterDelete(7L to filter, deletedId = 7L))
        // Another section, or one picked on the map: stay on the map.
        assertNull(pageAfterDelete(7L to filter, deletedId = 8L))
        assertNull(pageAfterDelete(null, deletedId = 7L))
    }

    @Test
    fun aFavouritesImportGetsTheReportWhenNotEverythingWasAdded() {
        val item = se.gangefors.moto.core.ImportItem("Ridge road", se.gangefors.moto.core.Rating.GREAT, 3_200.0)
        fun report(added: Int, skipped: Int = 0, replaced: Int = 0, unmatched: Int = 0) = se.gangefors.moto.core.ImportReport(
            added = added.toULong(),
            skipped = skipped.toULong(),
            replaced = replaced.toULong(),
            unmatched = unmatched.toULong(),
            addedItems = List(added - unmatched) { item },
            unmatchedItems = List(unmatched) { item },
            skippedItems = List(skipped) { item },
            replacedItems = List(replaced) { item },
        )
        // All added and fitting: the short message.
        org.junit.Assert.assertFalse(sectionImportNeedsReport(report(3)))
        // Anything else: the report, to read each by name.
        org.junit.Assert.assertTrue(sectionImportNeedsReport(report(0, skipped = 2)))
        org.junit.Assert.assertTrue(sectionImportNeedsReport(report(1, replaced = 1)))
        org.junit.Assert.assertTrue(sectionImportNeedsReport(report(2, unmatched = 1)))
    }
}
