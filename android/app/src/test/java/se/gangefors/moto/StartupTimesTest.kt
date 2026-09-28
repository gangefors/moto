// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTimesTest {
    @Test
    fun linesSayHowLongAndWhen() {
        assertEquals("region open: 7.41 s (at 8.02 s)", startupLine("region open", 7_410, 8_020))
        assertEquals("map style loaded (at 1.50 s)", startupLine("map style loaded", null, 1_500))
    }
}
