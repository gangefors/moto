// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.RoadClass
import se.gangefors.moto.core.RoadInfo
import se.gangefors.moto.core.RoadPoint
import se.gangefors.moto.core.Surface

class RoadInfoLogicTest {
    private fun info(
        curviness: Double = 0.0,
        distanceM: Double = 3.0,
        paved: Boolean = true,
        oneWay: Boolean = false,
        toll: Boolean = false,
        ferry: Boolean = false,
        destinationOnly: Boolean = false,
        lengthM: Double = 1234.0,
    ) = RoadInfo(
        point = RoadPoint(LatLon(55.7, 13.2), distanceM, 0u, 0.5),
        `class` = RoadClass.SECONDARY,
        surface = if (paved) Surface.ASPHALT else Surface.GRAVEL,
        paved = paved,
        speedKmh = 80u,
        oneWay = oneWay,
        toll = toll,
        ferry = ferry,
        destinationOnly = destinationOnly,
        curviness = curviness,
        lengthM = lengthM,
        wayId = 42L,
    )

    @Test
    fun curvinessReadsInFourSteps() {
        assertEquals(Curviness.STRAIGHT, curvinessOf(0.0))
        assertEquals(Curviness.STRAIGHT, curvinessOf(0.09))
        assertEquals(Curviness.GENTLE, curvinessOf(0.1))
        assertEquals(Curviness.CURVY, curvinessOf(0.35))
        assertEquals(Curviness.VERY_CURVY, curvinessOf(0.7))
        assertEquals(Curviness.VERY_CURVY, curvinessOf(1.0))
        assertEquals(Curviness.STRAIGHT, curvinessOf(Double.NaN))
    }

    @Test
    fun figuresAreRounded() {
        val f = roadFacts(info(curviness = 0.456))
        assertEquals(46, f.curvyPercent)
        assertEquals(Curviness.CURVY, f.curviness)
        assertEquals(80, f.speedKmh)
        assertEquals(1.234, f.lengthKm, 1e-9)
        assertEquals(emptyList<RoadNote>(), f.notes)
    }

    @Test
    fun notesComeInAFixedOrder() {
        val f = roadFacts(info(paved = false, oneWay = true, toll = true, ferry = true, destinationOnly = true))
        assertEquals(
            listOf(RoadNote.ONE_WAY, RoadNote.UNPAVED, RoadNote.TOLL, RoadNote.FERRY, RoadNote.DESTINATION_ONLY),
            f.notes,
        )
    }

    @Test
    fun aTapOnTheRoadSaysNothingOfTheDistance() {
        assertNull(roadFacts(info(distanceM = 3.0)).tapOffM)
        assertNull(roadFacts(info(distanceM = ON_ROAD_M.toDouble())).tapOffM)
        assertEquals(120, roadFacts(info(distanceM = 120.4)).tapOffM)
    }

    @Test
    fun oddNumbersStayInRange() {
        val f = roadFacts(info(curviness = Double.NaN, distanceM = Double.POSITIVE_INFINITY, lengthM = -5.0))
        assertEquals(0, f.curvyPercent)
        assertNull(f.tapOffM)
        assertEquals(0.0, f.lengthKm, 0.0)
        assertEquals(100, roadFacts(info(curviness = 7.0)).curvyPercent)
    }
}
