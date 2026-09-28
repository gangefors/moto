// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocateLogicTest {
    @Test
    fun awayFromTheRiderATapFollowsAndFixesOnlyAFarOffZoom() {
        // Europe in view, or single houses: to the neighbourhood.
        assertEquals(LocateAction.Follow(AREA_ZOOM), onLocateTap(LocateView.ELSEWHERE, 5.0, false, null))
        assertEquals(LocateAction.Follow(AREA_ZOOM), onLocateTap(LocateView.ELSEWHERE, 18.0, true, null))
        // A sensible zoom is kept.
        assertEquals(LocateAction.Follow(null), onLocateTap(LocateView.ELSEWHERE, 12.5, true, null))
    }

    @Test
    fun onTheRiderATapSwitchesAreaAndCloseByWithNothingPlanned() {
        // Each tap on the rider switches, however the map got there (the
        // taps that "did nothing" on the phone).
        assertEquals(LocateAction.Follow(CLOSE_ZOOM), onLocateTap(LocateView.ON_RIDER, AREA_ZOOM, false, null))
        assertEquals(LocateAction.Follow(AREA_ZOOM), onLocateTap(LocateView.ON_RIDER, CLOSE_ZOOM, false, null))
        assertEquals(LocateAction.Follow(CLOSE_ZOOM), onLocateTap(LocateView.ON_RIDER, 9.0, false, null))
        assertEquals(LocateAction.Follow(AREA_ZOOM), onLocateTap(LocateView.ON_RIDER, 16.0, false, null))
    }

    @Test
    fun withAPlanTheRiderAndTheOverviewTakeTurns() {
        assertEquals(LocateAction.ShowPlan, onLocateTap(LocateView.ON_RIDER, 13.0, true, null))
        assertEquals(LocateAction.Follow(13.0), onLocateTap(LocateView.OVERVIEW, 9.5, true, 13.0))
        // An overview without a remembered zoom follows at the current one.
        assertEquals(LocateAction.Follow(null), onLocateTap(LocateView.OVERVIEW, 9.5, true, null))
    }

    @Test
    fun centredMeansWithinAFewPercentOfTheMapWidth() {
        assertTrue(isCentredOnRider(100.0, 10_000.0))
        assertFalse(isCentredOnRider(1_000.0, 10_000.0))
    }

    @Test
    fun mapWidthAtAZoom() {
        // Zoom 11 on a 400 dp map in Skåne: about 8.6 km across.
        assertEquals(8_615.0, spanAtZoom(11.0, 400.0, 55.7), 10.0)
        assertEquals(spanAtZoom(11.0, 400.0, 55.7) / 2, spanAtZoom(12.0, 400.0, 55.7), 1e-6)
    }
}
