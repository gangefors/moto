// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
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
    }
}
