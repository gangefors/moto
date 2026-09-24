// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.SectionMarker.State
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating

class SectionLogicTest {
    /** Points on a line; the distance is how far apart they are. */
    private fun marker() = SectionMarker<Double> { a, b -> abs(a - b) }

    @Test
    fun tapsDoNothingUntilMarkingBegins() {
        val m = marker()
        assertFalse(m.isActive)
        assertEquals(State.Off, m.onTap(1.0))
        m.begin()
        assertTrue(m.isActive)
        assertEquals(State.PickStart, m.state)
    }

    @Test
    fun startThenEndProposesASection() {
        val m = marker()
        m.begin()
        assertEquals(State.PickEnd(1.0), m.onTap(1.0))
        assertEquals(State.Proposed(1.0, 9.0), m.onTap(9.0))
    }

    @Test
    fun aTapMovesTheNearerEnd() {
        val m = marker()
        m.begin()
        m.onTap(1.0)
        m.onTap(9.0)
        assertEquals(State.Proposed(2.0, 9.0), m.onTap(2.0))
        assertEquals(State.Proposed(2.0, 12.0), m.onTap(12.0))
        // Beyond the start moves the start, not the end.
        assertEquals(State.Proposed(-3.0, 12.0), m.onTap(-3.0))
        // Exactly halfway moves the start.
        assertEquals(State.Proposed(4.5, 12.0), m.onTap(4.5))
    }

    @Test
    fun rejectLastUndoesOneTap() {
        val m = marker()
        m.begin()
        m.onTap(1.0)
        m.rejectLast()
        assertEquals(State.PickStart, m.state)

        m.onTap(1.0)
        m.onTap(9.0)
        m.onTap(8.0)
        m.rejectLast()
        assertEquals(State.Proposed(1.0, 9.0), m.state)
    }

    @Test
    fun cancelEndsMarking() {
        val m = marker()
        m.begin()
        m.onTap(1.0)
        m.cancel()
        assertFalse(m.isActive)
        assertEquals(State.Off, m.onTap(2.0))
        m.begin()
        assertEquals(State.PickEnd(3.0), m.onTap(3.0))
    }

    @Test
    fun measuresShortDistances() {
        // 0.01° of latitude is about 1112 m anywhere.
        assertEquals(1112.0, approxDistanceM(LatLon(55.70, 13.20), LatLon(55.71, 13.20)), 2.0)
        // 0.01° of longitude at 55.7° N is about 627 m.
        assertEquals(627.0, approxDistanceM(LatLon(55.70, 13.20), LatLon(55.70, 13.21)), 2.0)
        assertEquals(0.0, approxDistanceM(LatLon(55.7, 13.2), LatLon(55.7, 13.2)), 0.0)
        val line = listOf(LatLon(55.70, 13.20), LatLon(55.71, 13.20), LatLon(55.72, 13.20))
        assertEquals(2224.0, lengthM(line), 4.0)
        assertEquals(0.0, lengthM(emptyList()), 0.0)
        assertEquals(0.0, lengthM(line.take(1)), 0.0)
    }

    @Test
    fun namesSectionsAutomatically() {
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        // 2026-09-24 12:05 UTC is 14:05 in Stockholm.
        val at = 1_790_251_500L
        assertEquals("Tag 2026-09-24 14:05, 1.2 km", autoSectionName(true, at, 1_234.0, stockholm))
        assertEquals("Map 2026-09-24 14:05, 0.0 km", autoSectionName(false, at, 10.0, stockholm))
    }

    @Test
    fun formatsLengths() {
        assertEquals(12.3, sectionKm(12_345.0), 0.0)
        assertEquals(0.0, sectionKm(40.0), 0.0)
        assertEquals(0.1, sectionKm(50.0), 0.0)
    }

    @Test
    fun everyRatingHasItsOwnColour() {
        assertEquals(listOf(Rating.GOOD, Rating.GREAT, Rating.EPIC), RATINGS)
        val colours = RATINGS.map(::ratingColor)
        assertEquals(RATINGS.size, colours.toSet().size)
        colours.forEach { assertTrue(it, Regex("#[0-9a-f]{6}").matches(it)) }
    }

    @Test
    fun oneWayIsTheForwardDirection() {
        assertTrue(isOneWay(Direction.FORWARD))
        assertFalse(isOneWay(Direction.BOTH))
        assertEquals(Direction.FORWARD, directionOf(oneWay = true))
        assertEquals(Direction.BOTH, directionOf(oneWay = false))
    }

    @Test
    fun readsSectionIdsFromMapFeatures() {
        assertEquals(7L, sectionIdOf(7.0))
        assertEquals(7L, sectionIdOf(7L))
        assertEquals(7L, sectionIdOf(7))
        assertEquals(9_007_199_254_740_992L, sectionIdOf(9_007_199_254_740_992.0))
        assertNull(sectionIdOf(null))
        assertNull(sectionIdOf("7"))
        assertNull(sectionIdOf(0.0))
        assertNull(sectionIdOf(-1))
        assertNull(sectionIdOf(1.5))
        assertNull(sectionIdOf(Double.NaN))
        assertNull(sectionIdOf(Double.POSITIVE_INFINITY))
        assertNull(sectionIdOf(1e300))
    }
}
