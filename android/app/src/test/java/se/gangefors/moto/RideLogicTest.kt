// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.FollowPhase
import se.gangefors.moto.core.FollowState
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating

class RideLogicTest {
    private fun state(phase: FollowPhase, along: Double = 0.0, total: Double = 10_000.0, off: Double? = null) =
        FollowState(phase, along, total - along, 0.0, total, 0u, 0.0, off, null, emptyList())

    @Test
    fun zoomGoesFromCloseWhenSlowToWideAtSpeed() {
        assertEquals(RIDE_ZOOM_SLOW, rideZoom(null), 1e-9)
        assertEquals(RIDE_ZOOM_SLOW, rideZoom(5.0), 1e-9)
        assertEquals(RIDE_ZOOM_FAST, rideZoom(30.0), 1e-9)
        assertEquals((RIDE_ZOOM_SLOW + RIDE_ZOOM_FAST) / 2, rideZoom(60 / 3.6), 1e-9)
        assertEquals(RIDE_ZOOM_SLOW, rideZoom(Double.NaN), 1e-9)
    }

    @Test
    fun theRiderSitsLowOnTheScreen() {
        // With top padding p the camera centre is at (p + h) / 2.
        val h = 2000
        val p = riderTopPadding(h)
        assertEquals(RIDER_DOWN, (p + h) / 2.0 / h, 0.001)
        assertEquals(0, riderTopPadding(0))
    }

    @Test
    fun progressIsNeverLessThanTwoPercent() {
        assertEquals(MIN_PROGRESS, progressShown(state(FollowPhase.ON_ROUTE, 0.0)), 1e-6f)
        assertEquals(0.5f, progressShown(state(FollowPhase.ON_ROUTE, 5_000.0)), 1e-6f)
        assertEquals(1f, progressShown(state(FollowPhase.ON_ROUTE, 20_000.0)), 1e-6f)
        assertEquals(MIN_PROGRESS, progressShown(state(FollowPhase.ON_ROUTE, 0.0, total = 0.0)), 1e-6f)
    }

    @Test
    fun lineProgressFollowsTheMapsLength() {
        // Two equal steps north at 60°: half way after the first.
        val line = listOf(LatLon(60.0, 14.0), LatLon(60.01, 14.0), LatLon(60.02, 14.0))
        val p = mercatorProgress(line)
        assertEquals(0.0, p[0], 1e-9)
        assertEquals(0.5, p[1], 0.001)
        assertEquals(1.0, p[2], 1e-9)
        assertEquals(0.25, lineProgressAt(p, 0, 0.5), 0.001)
        assertEquals(1.0, lineProgressAt(p, 5, 3.0), 1e-9)
        assertEquals(0.0, lineProgressAt(DoubleArray(0), 0, 0.5), 1e-9)
        // A line of one place: no division by zero.
        val still = mercatorProgress(listOf(LatLon(60.0, 14.0), LatLon(60.0, 14.0)))
        assertEquals(0.0, still[1], 1e-9)
    }

    @Test
    fun alertsAtMostEveryThirtySeconds() {
        assertTrue(alertDue(null, 0))
        assertFalse(alertDue(1_000, 20_000))
        assertTrue(alertDue(1_000, 31_000))
    }

    @Test
    fun theWayBackIsAskedForOffTheRouteAndWhenJoiningFromAfar() {
        assertTrue(rejoinDue(state(FollowPhase.OFF_ROUTE), null, 0))
        assertFalse(rejoinDue(state(FollowPhase.OFF_ROUTE), 0, 5_000))
        assertTrue(rejoinDue(state(FollowPhase.OFF_ROUTE), 0, 10_000))
        assertTrue(rejoinDue(state(FollowPhase.JOINING, off = null), null, 0))
        assertTrue(rejoinDue(state(FollowPhase.JOINING, off = 300.0), null, 0))
        assertFalse(rejoinDue(state(FollowPhase.JOINING, off = 20.0), null, 0))
        assertFalse(rejoinDue(state(FollowPhase.ON_ROUTE), null, 0))
        assertFalse(rejoinDue(state(FollowPhase.FINISHED), null, 0))
    }

    @Test
    fun countdownRoundsUp() {
        assertNull(countdownS(null, 0))
        assertEquals(15, countdownS(15_000, 0))
        assertEquals(12, countdownS(15_000, 3_500))
        assertEquals(0, countdownS(15_000, 20_000))
    }

    @Test
    fun cardDistances() {
        assertEquals(0.4, rideKm(420.0), 1e-9)
        assertEquals(9.9, rideKm(9_940.0), 1e-9)
        assertEquals(42.2, rideKm(42_200.0), 1e-9)
    }

    @Test
    fun followedRouteRoundTrips() {
        val r = RideRoute(
            "Loop", true, 600.0,
            listOf(LatLon(57.0, 14.0), LatLon(57.1, 14.0)),
            listOf(listOf(LatLon(57.0, 14.0), LatLon(57.05, 14.0))),
            listOf(Rating.EPIC),
            unpavedParts = listOf(listOf(LatLon(57.0, 14.0), LatLon(57.01, 14.0))),
        )
        // The gravel dashes are not kept.
        assertEquals(r.copy(unpavedParts = emptyList()), r.toFollowed().toRideRoute())
    }

    // About 1.1 km per 0.01° of latitude.
    private fun north(lat: Double) = LatLon(lat, 13.5)

    @Test
    fun aRideEndingWhereItStartedIsRiddenAgainAsALoop() {
        val out = listOf(north(55.70), north(55.72), LatLon(55.72, 13.53))
        val back = listOf(LatLon(55.70, 13.53), LatLon(55.7005, 13.5005))
        val line = rideAgainLine(listOf(out, back))!!
        assertTrue(rideAgainIsLoop(line))
        // Closed back to its start, so the follower sees a loop.
        assertEquals(out.first(), line.last())
        assertEquals(6, line.size)
    }

    @Test
    fun aRideEndingElsewhereIsARoute() {
        val line = rideAgainLine(listOf(listOf(north(55.70), north(55.73))))!!
        assertFalse(rideAgainIsLoop(line))
        assertEquals(2, line.size)
    }

    @Test
    fun tooLittleOfARideIsNotRiddenAgain() {
        assertNull(rideAgainLine(emptyList()))
        assertNull(rideAgainLine(listOf(listOf(north(55.70)))))
        assertNull(rideAgainLine(listOf(listOf(north(55.70), north(55.7001)))))
    }

    @Test
    fun aRideTakesAsLongAsItDid() {
        assertEquals(3600.0, rideAgainDurationS(1_000, 4_600), 1e-9)
        assertEquals(0.0, rideAgainDurationS(1_000, null), 1e-9)
        assertEquals(0.0, rideAgainDurationS(5_000, 1_000), 1e-9)
    }

    @Test
    fun theRideZoomSettingMovesBothEnds() {
        // The default step is the zoom as it always was.
        assertEquals(0.0, rideZoomOffset(RIDE_ZOOM_DEFAULT_STEP), 1e-9)
        // Closest: one level in (half as wide); widest: two out (four times).
        assertEquals(1.0, rideZoomOffset(0), 1e-9)
        assertEquals(-2.0, rideZoomOffset(RIDE_ZOOM_STEPS - 1), 1e-9)
        assertEquals(1.0, rideZoomOffset(-3), 1e-9)
        assertEquals(-2.0, rideZoomOffset(99), 1e-9)
        assertEquals(RIDE_ZOOM_SLOW + 1.0, rideZoom(null, 1.0), 1e-9)
        assertEquals(RIDE_ZOOM_FAST - 2.0, rideZoom(30.0, -2.0), 1e-9)
        assertEquals(RIDE_ZOOM_SLOW, rideZoom(null, Double.NaN), 1e-9)
    }

    @Test
    fun zoomButtonsNudgeTheRideZoomWithinLimits() {
        assertEquals(1.0, nudgeRideZoom(0.0, ZOOM_BUTTON_STEP), 1e-9)
        assertEquals(-1.5, nudgeRideZoom(-0.5, -ZOOM_BUTTON_STEP), 1e-9)
        // Six taps from one end to the other.
        assertEquals(6, (1..20).takeWhile { nudgeRideZoom(-3.0 + (it - 1) * ZOOM_BUTTON_STEP, ZOOM_BUTTON_STEP) > -3.0 + (it - 1) * ZOOM_BUTTON_STEP }.count())
        assertEquals(3.0, nudgeRideZoom(3.0, ZOOM_BUTTON_STEP), 1e-9)
        assertEquals(-3.0, nudgeRideZoom(-3.0, -ZOOM_BUTTON_STEP), 1e-9)
    }

    @Test
    fun theRecordingCardCountsWholeMinutes() {
        assertEquals(0L, recordingMinutes(1_000, 1_000))
        assertEquals(1L, recordingMinutes(0, 119_999))
        assertEquals(72L, recordingMinutes(0, 72 * 60_000L))
        assertEquals(0L, recordingMinutes(5_000, 1_000))
    }

    @Test
    fun arrowsPointTheWayOnATurnedMap() {
        // North-up map: as the bearing.
        assertEquals(90f, arrowOnMap(90.0, 0f), 1e-4f)
        // Map turned so east is up: a favourite to the east is straight up.
        assertEquals(0f, arrowOnMap(90.0, 90f), 1e-4f)
        assertEquals(270f, arrowOnMap(0.0, 90f), 1e-4f)
        assertEquals(350f, arrowOnMap(340.0, 350f), 1e-4f)
    }
}
