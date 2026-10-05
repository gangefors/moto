// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.LatLon

class LocateLogicTest {
    @Test
    fun awayFromTheRiderATapFollowsAndFixesOnlyAFarOffZoom() {
        // Europe in view, or single houses: to the neighbourhood.
        assertEquals(LocateAction.Follow(10.0), onLocateTap(LocateView.ELSEWHERE, 5.0, false, null))
        assertEquals(LocateAction.Follow(10.0), onLocateTap(LocateView.ELSEWHERE, 18.0, true, null))
        // A sensible zoom is kept.
        assertEquals(LocateAction.Follow(null), onLocateTap(LocateView.ELSEWHERE, 12.5, true, null))
    }

    @Test
    fun onTheRiderATapSwitchesAreaAndCloseByWithNothingPlanned() {
        // Each tap on the rider switches, however the map got there (the
        // taps that "did nothing" on the phone).
        assertEquals(LocateAction.Follow(14.0), onLocateTap(LocateView.ON_RIDER, 10.0, false, null))
        assertEquals(LocateAction.Follow(10.0), onLocateTap(LocateView.ON_RIDER, 14.0, false, null))
        assertEquals(LocateAction.Follow(14.0), onLocateTap(LocateView.ON_RIDER, 9.0, false, null))
        assertEquals(LocateAction.Follow(10.0), onLocateTap(LocateView.ON_RIDER, 16.0, false, null))
        // The rider's own zooms.
        val mine = LocateZooms(area = 9, close = 15)
        assertEquals(LocateAction.Follow(15.0), onLocateTap(LocateView.ON_RIDER, 9.0, false, null, mine))
        assertEquals(LocateAction.Follow(9.0), onLocateTap(LocateView.ELSEWHERE, 3.0, false, null, mine))
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
        // The inverse: the zoom that shows a span.
        assertEquals(11.0, zoomForSpan(spanAtZoom(11.0, 400.0, 55.7), 400.0, 55.7), 1e-9)
        // 70 km across a 360 dp phone in southern Sweden: about zoom 7.8.
        assertEquals(7.8, zoomForSpan(70_000.0, 360.0, 56.0), 0.05)
    }

    @Test
    fun zoomSettingsStaySensible() {
        assertEquals(LocateZooms(10, 14), LocateZooms.of(null, null))
        assertEquals(LocateZooms(9, 15), LocateZooms.of(9, 15))
        // Close always at least a step closer than the area, both in range.
        assertEquals(LocateZooms(12, 13), LocateZooms.of(12, 11))
        assertEquals(LocateZooms(MIN_ZOOM, MIN_ZOOM + 1), LocateZooms.of(1, 2))
        assertEquals(LocateZooms(MAX_ZOOM - 1, MAX_ZOOM), LocateZooms.of(40, 50))
    }

    @Test
    fun rangeSliderGivesZooms() {
        // Close by on the left, the area (wider) on the right.
        assertEquals(LocateZooms(10, 14), zoomsFromRange(flipZoom(14f) + 0.2f, flipZoom(10f) - 0.2f))
        assertEquals(LocateZooms(MIN_ZOOM, MAX_ZOOM), zoomsFromRange(-5f, 40f))
        // Thumbs meeting or crossing: no change.
        assertNull(zoomsFromRange(12f, 12.3f))
        assertNull(zoomsFromRange(14f, 11f))
        // The flip is its own inverse.
        assertEquals(10f, flipZoom(flipZoom(10f)))
        assertEquals(MIN_ZOOM.toFloat(), flipZoom(MAX_ZOOM.toFloat()))
    }

    @Test
    fun spansReadable() {
        assertEquals(Span.Km(18), readableSpan(17_600.0))
        assertEquals(Span.Km(10), readableSpan(9_960.0))
        assertEquals(Span.KmTenths(4.4), readableSpan(4_380.0))
        assertEquals(Span.KmTenths(1.0), readableSpan(999.9))
        assertEquals(Span.Metres(550), readableSpan(560.0))
        assertEquals(Span.Metres(50), readableSpan(3.0))
    }

    private val spot = LatLon(59.0, 18.0)

    @Test
    fun startPutsAZoomedInMapBackAtTheAreaZoom() {
        // The decision that undid a location tap in the first seconds.
        assertEquals(StartStep.MOVE_AND_FOLLOW, startStep(false, false, spot, spot, 14.0, 10))
    }

    @Test
    fun startLeavesAMapTheRiderMoved() {
        // The same inputs as the zoomed-in map above, once the rider has moved it.
        assertEquals(StartStep.RIDER_MOVED, startStep(true, false, spot, spot, 14.0, 10))
        assertEquals(StartStep.RIDER_MOVED, startStep(true, true, spot, spot, 14.0, 10))
        assertEquals(StartStep.RIDER_MOVED, startStep(true, false, null, null, 10.0, 10))
    }

    @Test
    fun startDoesNothingWhileRidingOrWithoutAPosition() {
        assertEquals(StartStep.NOTHING, startStep(false, true, spot, spot, 10.0, 10))
        assertEquals(StartStep.NOTHING, startStep(false, false, null, spot, 10.0, 10))
    }

    @Test
    fun startOnTheRiderAtTheAreaZoomOnlyFollows() {
        assertEquals(StartStep.FOLLOW, startStep(false, false, spot, spot, 10.0, 10))
        assertEquals(StartStep.FOLLOW, startStep(false, false, spot, spot, 10.2, 10))
        assertEquals(StartStep.FOLLOW, startStep(false, false, spot, spot, 9.8, 10))
        assertEquals(StartStep.MOVE_AND_FOLLOW, startStep(false, false, spot, spot, 10.5, 10))
        assertEquals(StartStep.MOVE_AND_FOLLOW, startStep(false, false, spot, spot, 9.5, 10))
    }

    @Test
    fun startMovesAMapCentredAwayFromTheRider() {
        assertEquals(StartStep.MOVE_AND_FOLLOW, startStep(false, false, spot, LatLon(59.003, 18.0), 10.0, 10))
        assertEquals(StartStep.MOVE_AND_FOLLOW, startStep(false, false, spot, LatLon(59.0, 18.003), 10.0, 10))
        assertEquals(StartStep.FOLLOW, startStep(false, false, spot, LatLon(59.001, 18.001), 10.0, 10))
        assertEquals(StartStep.MOVE_AND_FOLLOW, startStep(false, false, spot, null, 10.0, 10))
    }
}
