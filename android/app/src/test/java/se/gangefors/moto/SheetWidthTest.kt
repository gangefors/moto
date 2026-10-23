// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetWidthTest {
    @Test fun portraitKeepsTheDefault() {
        assertEquals(SheetPlacement(360f, 0f), sheetPlacement(false, 360f))
        assertEquals(SheetPlacement(640f, 80f), sheetPlacement(false, 800f))
    }

    @Test fun landscapeIsHalfAndAtTheLeft() {
        assertEquals(SheetPlacement(500f, 0f), sheetPlacement(true, 1000f))
        assertEquals(SheetPlacement(450f, 0f), sheetPlacement(true, 900f))
    }

    @Test fun landscapeNeverNarrowerThanTheMinimum() {
        assertEquals(SheetPlacement(360f, 0f), sheetPlacement(true, 640f))
    }

    @Test fun narrowScreenNeverGetsAWiderSheet() {
        assertEquals(SheetPlacement(300f, 0f), sheetPlacement(true, 300f))
        assertEquals(SheetPlacement(0f, 0f), sheetPlacement(true, -5f))
    }
}
