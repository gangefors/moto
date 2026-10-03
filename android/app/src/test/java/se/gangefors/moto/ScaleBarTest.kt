// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleBarTest {
    @Test
    fun theScaleIsTheLongestRoundLengthThatFits() {
        // 2 m a dp: up to 240 m fits, so 200 m, 100 dp.
        assertEquals(ScaleLength(200.0, 100.0), scaleLength(2.0))
        // 10 m a dp: up to 1200 m, so 1 km, 100 dp.
        assertEquals(ScaleLength(1000.0, 100.0), scaleLength(10.0))
        // 4 m a dp: up to 480 m, so 200 m, 50 dp.
        assertEquals(ScaleLength(200.0, 50.0), scaleLength(4.0))
        // 50 m a dp: up to 6 km, so 5 km.
        assertEquals(5000.0, scaleLength(50.0)!!.metres, 1e-9)
    }

    @Test
    fun theBarStaysBetweenTwoFifthsAndAllOfItsRoom() {
        var mpd = 0.05
        while (mpd < 5000) {
            val s = scaleLength(mpd)!!
            assertTrue("$mpd", s.dp <= SCALE_MAX_DP + 1e-9 && s.dp >= SCALE_MAX_DP * 0.4 - 1e-9)
            mpd *= 1.07
        }
    }

    @Test
    fun aScaleThatMakesNoSenseShowsNone() {
        assertNull(scaleLength(0.0))
        assertNull(scaleLength(-1.0))
        assertNull(scaleLength(Double.NaN))
        assertNull(scaleLength(Double.POSITIVE_INFINITY))
    }
}
