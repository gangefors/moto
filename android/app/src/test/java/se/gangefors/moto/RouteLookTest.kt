// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating

class RouteLookTest {
    @Test
    fun sectionsFadeWhileARouteIsShown() {
        val normal = sectionLook(routeShown = false)
        val faded = sectionLook(routeShown = true)
        assertEquals(SectionLook(5f, 1f, 0.8f, 1f), normal)
        assertTrue(faded.lineWidth < normal.lineWidth)
        assertTrue(faded.lineOpacity < normal.lineOpacity)
        assertEquals(0f, faded.casingOpacity)
        // Still there as context, and thinner than the route (5 dp) so the
        // route hides the section it runs along.
        assertTrue(faded.lineOpacity > 0f && faded.arrowOpacity > 0f)
        assertTrue(faded.lineWidth < 5f)
        // Less faded on the dark map, where half strength sank into it.
        val dark = sectionLook(routeShown = true, darkMap = true)
        assertEquals(0.5f, faded.lineOpacity, 0f)
        assertEquals(0.8f, dark.lineOpacity, 0f)
        assertEquals(dark.lineOpacity, dark.arrowOpacity, 0f)
        assertTrue(dark.lineOpacity < normal.lineOpacity)
    }

    @Test
    fun favouritesAreThinnerZoomedOutButNeverTooThinForGravel() {
        // Full from zoom 12; thinner far out, but never under 3 dp.
        assertEquals(3f, SECTION_MIN_WIDTH, 0f)
        assertEquals(3f, sectionWidthAt(5f, 6f), 1e-4f)
        assertEquals(3f, sectionWidthAt(5f, 8f), 1e-4f)
        assertEquals(4f, sectionWidthAt(5f, 10f), 1e-4f)
        assertEquals(5f, sectionWidthAt(5f, 12f), 1e-4f)
        assertEquals(5f, sectionWidthAt(5f, 17f), 1e-4f)
        // While planning (3 dp) they stay as they are.
        val planning = sectionLook(routeShown = true).lineWidth
        for (z in listOf(6f, 8f, 10f, 12f)) assertEquals(3f, sectionWidthAt(planning, z), 1e-4f)
        // A gravel dash keeps at least 1 dp of colour on each side, and is
        // at least 1 dp itself, at every width a section is drawn at.
        assertEquals(2f, sectionGravelWidth(5f), 1e-4f)
        assertEquals(1f, sectionGravelWidth(3f), 1e-4f)
        for (z in DASH_ZOOMS) {
            for (full in listOf(5f, planning)) {
                val line = sectionWidthAt(full, z.toFloat())
                val gravel = sectionGravelWidth(line)
                assertTrue("$full at $z", gravel >= 1f - 1e-4f && (line - gravel) / 2 >= 1f - 1e-4f)
            }
        }
    }

    @Test
    fun aShownRideIsInkAndNarrowsWithTheZoom() {
        // Not red: a great favourite's colour was too near.
        assertEquals("#202124", riddenColor(darkMap = false))
        assertEquals(3f, sectionWidthAt(RIDE_WIDTH, 8f), 1e-4f)
        assertEquals(4f, sectionWidthAt(RIDE_WIDTH, 12f), 1e-4f)
        assertEquals(4f, sectionWidthAt(RIDE_WIDTH, 16f), 1e-4f)
    }

    @Test
    fun theOutlineIsFainterOnTheDarkMap() {
        assertEquals(1f, outlineStrength(darkMap = false), 0f)
        assertTrue(outlineStrength(darkMap = true) in 0.3f..0.6f)
        assertEquals(0.8f, sectionLook(routeShown = false).casingOpacity, 1e-6f)
        assertTrue(sectionLook(routeShown = false, darkMap = true).casingOpacity < 0.8f)
        // Faded sections under a route have no outline either way.
        assertEquals(0f, sectionLook(routeShown = true, darkMap = true).casingOpacity, 0f)
    }

    @Test
    fun theShownSectionKeepsItsRatingColour() {
        for (r in RATINGS) assertEquals(ratingColor(r), shownSectionLook(r, fits = true).color)
        for (r in RATINGS) {
            assertEquals(ratingColor(r, darkMap = true), shownSectionLook(r, fits = true, darkMap = true).color)
        }
        // One that no longer fits the map is grey, as in the list.
        assertEquals(UNMATCHED_SECTION_COLOR, shownSectionLook(Rating.EPIC, fits = false).color)
    }

    @Test
    fun theShownSectionIsWiderAndEdged() {
        val shown = shownSectionLook(Rating.GOOD, fits = true)
        val normal = sectionLook(routeShown = false)
        // Wider than a section at full strength, with the dark edge showing
        // round the line and the white outline round the edge.
        assertTrue(shown.lineWidth > normal.lineWidth)
        assertTrue(shown.edgeWidth > shown.lineWidth)
        assertTrue(shown.casingWidth > shown.edgeWidth)
        assertEquals(0.8f, shown.casingOpacity, 1e-6f)
        assertTrue(shownSectionLook(Rating.GOOD, fits = true, darkMap = true).casingOpacity < 0.8f)
    }

    @Test
    fun favouritesGlowInTheirRatingsColour() {
        val a = listOf(LatLon(55.0, 13.0), LatLon(55.1, 13.0))
        val b = listOf(LatLon(55.1, 13.0), LatLon(55.2, 13.0))
        val glow = favouriteGlowColors(listOf(a, b), listOf(Rating.GREAT, Rating.GOOD))
        assertEquals(listOf(a to ratingColor(Rating.GREAT), b to ratingColor(Rating.GOOD)), glow)
        // Without ratings (an older core) every stretch glows epic purple,
        // and a stretch of one point is left out.
        val bare = favouriteGlowColors(listOf(a, listOf(LatLon(55.3, 13.0))), emptyList())
        assertEquals(listOf(a to ratingColor(Rating.EPIC)), bare)
        // The dark map's lighter tints.
        val dark = favouriteGlowColors(listOf(a), listOf(Rating.GREAT), darkMap = true)
        assertEquals(listOf(a to ratingColor(Rating.GREAT, darkMap = true)), dark)
    }

    @Test
    fun theRouteIsLighterOnTheLightMapSoTheInkDashesShow() {
        fun lum(c: String): Double {
            val ch = listOf(1, 3, 5).map { c.substring(it, it + 2).toInt(16) / 255.0 }
                .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]
        }
        fun contrast(a: String, b: String): Double {
            val (x, y) = listOf(lum(a), lum(b)).sortedDescending()
            return (x + 0.05) / (y + 0.05)
        }
        assertEquals("#669df6", routeBlue(darkMap = false))
        assertEquals("#1a73e8", routeBlue(darkMap = true))
        // Near-black dashes on the route: well over the 3:1 a line needs.
        assertTrue(contrast(routeBlue(false), riddenColor(false)) > 5.0)
        assertTrue(contrast(routeBlue(true), riddenColor(true)) > 3.0)
    }

    @Test
    fun riddenRoadsAreDarkOnTheLightMapAndLightOnTheDark() {
        assertEquals("#202124", riddenColor(darkMap = false))
        assertEquals("#e8eaed", riddenColor(darkMap = true))
        // Thin, a little wider zoomed in.
        assertEquals(listOf(1.8f, 2.4f, 3.2f), RIDDEN_WIDTHS.map { it.second })
        assertEquals(70_000.0, RIDDEN_MAX_SPAN_M, 0.0)
    }

    @Test
    fun dashedLinesAreSetForEachZoom() {
        // The ridden roads' width as the map draws it, between and beyond
        // its stops.
        assertEquals(1.8f, riddenWidth(5f), 1e-4f)
        assertEquals(2.1f, riddenWidth(10f), 1e-4f)
        assertEquals(2.8f, riddenWidth(14f), 1e-4f)
        assertEquals(3.2f, riddenWidth(18f), 1e-4f)
        // On screen, dash and gap grow a little with every zoom.
        assertEquals(5.5f to 4.5f, dashDp(8))
        val screen = DASH_ZOOMS.map(::dashDp)
        for ((a, b) in screen.zipWithNext()) {
            assertTrue(b.first > a.first && b.second > a.second)
        }
        // The map gets them in line widths: times the width, the same dp,
        // whatever the line's width (ridden roads, gravel).
        for (z in DASH_ZOOMS) {
            val (dash, gap) = dashDp(z)
            for (width in listOf(riddenWidth(z.toFloat()), GRAVEL_DASH_WIDTH)) {
                val units = dashArray(z, width)
                assertEquals(dash, units[0] * width, 1e-4f)
                assertEquals(gap, units[1] * width, 1e-4f)
            }
            assertTrue(riddenDashes(z).contentEquals(dashArray(z, riddenWidth(z.toFloat()))))
            assertTrue(gravelDashes(z).contentEquals(dashArray(z, GRAVEL_DASH_WIDTH)))
        }
    }

    @Test
    fun theRiddenRoadsRunOnUnderSectionsTheMapLeavesOut() {
        assertEquals(listOf(2L, 4L), hiddenSectionIds(listOf(1L, 2L, 3L, 4L), listOf(1L, 3L)))
        assertEquals(emptyList<Long>(), hiddenSectionIds(listOf(1L), listOf(1L)))
    }
}
