// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.LatLon

class MapFitTest {
    private fun p(lat: Double, lon: Double) = LatLon(lat, lon)

    @Test
    fun boundsCoverEveryLineAndSkipNonsense() {
        val a = listOf(p(55.8, 13.6), p(55.9, 13.7))
        val b = listOf(p(55.7, 13.65), p(55.85, 13.9), p(Double.NaN, 13.0), p(95.0, 13.0))
        assertEquals(GeoBounds(55.7, 13.6, 55.9, 13.9), boundsOf(listOf(a, b)))
        assertNull(boundsOf(emptyList()))
        assertNull(boundsOf(listOf(emptyList(), listOf(p(Double.NaN, Double.NaN)))))
    }

    @Test
    fun shortRoutesAndPointsGetAMinimumSpan() {
        val point = GeoBounds(55.8, 13.6, 55.8, 13.6).withMinSpan()
        // About 2 km each way, around the point.
        assertEquals(2_000.0 / 111_195.0, point.latSpan, 1e-9)
        assertEquals(55.8, (point.south + point.north) / 2, 1e-9)
        assertTrue(point.lonSpan > point.latSpan) // degrees of longitude are shorter up north
        // A large box is left alone.
        val big = GeoBounds(55.5, 13.2, 56.0, 13.9)
        assertEquals(big, big.withMinSpan())
    }

    @Test
    fun paddingKeepsClearOfThePanels() {
        val pad = fitPadding(Panels(1080, 2200, left = 0, top = 500, right = 250, bottom = 400), margin = 40)
        assertEquals(FitPadding(40, 540, 290, 440), pad)
    }

    @Test
    fun paddingShrinksWhenThePanelsLeaveTooLittleMap() {
        // A card over most of a small screen: the route still gets 35 %.
        val pad = fitPadding(Panels(720, 1000, left = 0, top = 700, right = 0, bottom = 200), margin = 20)
        assertEquals(650.0, (pad.top + pad.bottom).toDouble(), 1.0)
        assertTrue(pad.top > pad.bottom)
        // Nonsense sizes never give negative padding.
        val odd = fitPadding(Panels(0, 0, left = -5, top = -5, right = 10, bottom = 10), margin = 0)
        assertEquals(FitPadding(0, 0, 10, 10), odd)
    }

    @Test
    fun theMapMovesOnlyWhenTheRouteIsOutOfViewOrTooSmall() {
        val view = GeoBounds(55.5, 13.2, 56.0, 13.9)
        // Well inside and filling the view: stays still.
        assertFalse(needsFit(view, GeoBounds(55.55, 13.3, 55.95, 13.8)))
        // Filling it one way only is enough.
        assertFalse(needsFit(view, GeoBounds(55.55, 13.5, 55.95, 13.6)))
        // Partly out of view.
        assertTrue(needsFit(view, GeoBounds(55.55, 13.3, 56.05, 13.8)))
        // Tiny in a large view.
        assertTrue(needsFit(view, GeoBounds(55.7, 13.5, 55.8, 13.6)))
        // No view known yet.
        assertTrue(needsFit(null, GeoBounds(55.7, 13.5, 55.8, 13.6)))
        assertTrue(needsFit(GeoBounds(55.7, 13.5, 55.7, 13.5), GeoBounds(55.7, 13.5, 55.8, 13.6)))
    }
}
