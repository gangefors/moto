// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.PlaceKind
import se.gangefors.moto.core.PlaceName
import se.gangefors.moto.core.RoadLabel

class DescriptionsTest {
    private fun place(name: String) = PlaceName(name, PlaceKind.TOWN, 500.0)

    private fun d(start: String?, end: String?, vararg roads: RoadLabel) =
        Description(roads.toList(), start?.let(::place), end?.let(::place), 0.0)

    @Test
    fun placesReadAsFromToOrNear() {
        assertEquals(PlaceSpan.Between("Höör", "Sjöbo"), placeSpan(d("Höör", "Sjöbo")))
        assertEquals(PlaceSpan.Near("Höör"), placeSpan(d("Höör", "Höör")))
        assertEquals(PlaceSpan.Near("Sjöbo"), placeSpan(d(null, "Sjöbo")))
        assertEquals(PlaceSpan.Near("Höör"), placeSpan(d("Höör", null)))
        assertNull(placeSpan(d(null, null)))
        assertNull(placeSpan(null))
    }

    @Test
    fun roadsAreNamedOnceEach() {
        val main = RoadLabel("11", "Tomelillavägen", 0.7)
        val second = RoadLabel("1119", "Hörbyvägen", 0.25)
        assertEquals(
            listOf(RoadWords("11", "Tomelillavägen"), RoadWords("1119", null)),
            roadWords(d(null, null, main, second)),
        )
        // A second road without a number goes by its name.
        val street = RoadLabel(null, "Storgatan", 0.3)
        assertEquals(RoadWords(null, "Storgatan"), roadWords(d(null, null, main, street))[1])
        assertEquals(emptyList<RoadWords>(), roadWords(d(null, null, RoadLabel(null, null, 1.0))))
        assertEquals(emptyList<RoadWords>(), roadWords(null))
    }

    @Test
    fun plainNumbersGetTheWordRoad() {
        assertTrue(isPlainRoadNumber("13"))
        assertTrue(isPlainRoadNumber("1121"))
        assertFalse(isPlainRoadNumber("E22"))
        assertFalse(isPlainRoadNumber(""))
    }
}
