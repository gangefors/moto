// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetWidthTest {
    @Test fun portraitKeepsTheDefault() {
        assertEquals(360f, sheetMaxWidthDp(false, true, 360f))
        assertEquals(640f, sheetMaxWidthDp(false, true, 800f))
    }

    @Test fun otherSheetsKeepTheDefaultInLandscape() {
        assertEquals(640f, sheetMaxWidthDp(true, false, 1000f))
    }

    @Test fun landscapeSideSheetIsHalf() {
        assertEquals(500f, sheetMaxWidthDp(true, true, 1000f))
        assertEquals(450f, sheetMaxWidthDp(true, true, 900f))
    }

    @Test fun landscapeNeverNarrowerThanTheMinimum() {
        assertEquals(360f, sheetMaxWidthDp(true, true, 640f))
    }

    @Test fun narrowScreenNeverGetsAWiderSheet() {
        assertEquals(300f, sheetMaxWidthDp(true, true, 300f))
        assertEquals(0f, sheetMaxWidthDp(true, true, -5f))
    }

    @Test fun shiftMovesTheCentredSheetToTheLeft() {
        assertEquals(-250f, sideSheetShift(true, 1000f, 500f))
        assertEquals(0f, sideSheetShift(true, 500f, 500f))
        assertEquals(0f, sideSheetShift(true, 400f, 500f))
        assertEquals(0f, sideSheetShift(false, 1000f, 500f))
    }
}
