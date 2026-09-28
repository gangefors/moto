// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test

class LocateLogicTest {
    @Test
    fun theFirstTapFollowsAndFixesOnlyAFarOffZoom() {
        // Europe in view: to the neighbourhood.
        assertEquals(
            LocateState.Following(AREA_SPAN_M) to LocateAction.Follow(AREA_SPAN_M),
            onLocateTap(LocateState.Idle, 2_000_000.0, hasPlan = false),
        )
        // Single houses: out to the neighbourhood too.
        assertEquals(LocateAction.Follow(AREA_SPAN_M), onLocateTap(LocateState.Idle, 300.0, hasPlan = true).second)
        // A sensible zoom is kept.
        assertEquals(
            LocateState.Following(20_000.0) to LocateAction.Follow(null),
            onLocateTap(LocateState.Idle, 20_000.0, hasPlan = true),
        )
    }

    @Test
    fun theNextTapShowsThePlanThenFollowsAgain() {
        val (overview, show) = onLocateTap(LocateState.Following(20_000.0), 20_000.0, hasPlan = true)
        assertEquals(LocateState.Overview(20_000.0) to LocateAction.ShowPlan, overview to show)
        // Back to following at the zoom from before, whatever the overview's.
        assertEquals(
            LocateState.Following(20_000.0) to LocateAction.Follow(20_000.0),
            onLocateTap(overview, 120_000.0, hasPlan = true),
        )
    }

    @Test
    fun withNothingPlannedTheNextTapSwitchesAreaAndCloseBy() {
        val (close, toClose) = onLocateTap(LocateState.Following(AREA_SPAN_M), AREA_SPAN_M, hasPlan = false)
        assertEquals(LocateAction.Follow(CLOSE_SPAN_M), toClose)
        assertEquals(LocateAction.Follow(AREA_SPAN_M), onLocateTap(close, CLOSE_SPAN_M, hasPlan = false).second)
        // From a width of its own: whichever of the two is further away.
        assertEquals(LocateAction.Follow(CLOSE_SPAN_M), onLocateTap(LocateState.Following(30_000.0), 30_000.0, false).second)
        assertEquals(LocateAction.Follow(AREA_SPAN_M), onLocateTap(LocateState.Following(8_000.0), 8_000.0, false).second)
    }

    @Test
    fun zoomAndWidthConvertBothWays() {
        // About 50 km across a 400 dp map in Skåne is zoom 8.46.
        val z = zoomForSpan(50_000.0, 400.0, 55.7)
        assertEquals(8.46, z, 0.01)
        assertEquals(50_000.0, spanAtZoom(z, 400.0, 55.7), 1.0)
    }
}
