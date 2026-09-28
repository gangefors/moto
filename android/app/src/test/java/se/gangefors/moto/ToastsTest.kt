// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test

class ToastsTest {
    @Test
    fun aToastShowsForItsTimeFromWhenItWasPosted() {
        val t = ToastMessage("Deleted", atMs = 10_000)
        assertEquals(TOAST_MS, toastRemainingMs(t, 10_000))
        assertEquals(TOAST_MS - 1_000, toastRemainingMs(t, 11_000))
        // A screen opened after it is over doesn't show it again.
        assertEquals(0L, toastRemainingMs(t, 10_000 + TOAST_MS))
        assertEquals(0L, toastRemainingMs(t, 60_000))
        assertEquals(0L, toastRemainingMs(null, 10_000))
        // A clock that went backwards never makes it longer.
        assertEquals(TOAST_MS, toastRemainingMs(t, 5_000))
    }
}
