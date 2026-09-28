// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.TrackPoint

class PositionLogicTest {
    private val now = 1_790_000_000_000L

    private fun fix(ageMs: Long, lat: Double = 55.7) =
        TrackPoint(now - ageMs, LatLon(lat, 13.2), 5.0, 20.0, 90.0)

    @Test
    fun theNewerFixWins() {
        val p = riderPosition(fix(10_000, lat = 55.1), fix(1_000, lat = 55.2), now)
        assertEquals(55.2, (p as RiderPosition.Fresh).fix.position.lat, 0.0)
        assertEquals(55.1, riderPosition(fix(1_000, lat = 55.1), fix(10_000), now).fix!!.position.lat, 0.0)
        assertEquals(55.1, riderPosition(fix(1_000, lat = 55.1), null, now).fix!!.position.lat, 0.0)
    }

    @Test
    fun ageDecidesFreshLastKnownOrNone() {
        assertEquals(RiderPosition.Fresh(fix(FRESH_POSITION_MS)), riderPosition(null, fix(FRESH_POSITION_MS), now))
        assertEquals(RiderPosition.LastKnown(fix(FRESH_POSITION_MS + 1)), riderPosition(null, fix(FRESH_POSITION_MS + 1), now))
        assertEquals(RiderPosition.LastKnown(fix(MAX_POSITION_AGE_MS)), riderPosition(null, fix(MAX_POSITION_AGE_MS), now))
        assertEquals(RiderPosition.None, riderPosition(null, fix(MAX_POSITION_AGE_MS + 1), now))
        assertEquals(RiderPosition.None, riderPosition(null, null, now))
        assertNull(RiderPosition.None.fix)
    }

    @Test
    fun aFixFromTheFutureCountsOnlyWithinClockSkew() {
        assertEquals(RiderPosition.Fresh(fix(-2_000)), riderPosition(fix(-2_000), null, now))
        assertEquals(RiderPosition.None, riderPosition(fix(-60_000), null, now))
        // A wrong future fix doesn't hide a good one.
        assertEquals(55.2, riderPosition(fix(-60_000), fix(5_000, lat = 55.2), now).fix!!.position.lat, 0.0)
    }
}
