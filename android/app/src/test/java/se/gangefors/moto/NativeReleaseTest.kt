// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import se.gangefors.moto.core.Disposable

class NativeReleaseTest {
    private class Counted : Disposable {
        var destroyed = 0
        override fun destroy() {
            destroyed++
        }
    }

    @Test
    fun aReplacedObjectIsReleasedAfterTheGrace() = runBlocking {
        val obj = Counted()
        val job = NativeRelease.later(this, obj, graceMs = 50)
        // Still usable during the grace.
        assertEquals(0, obj.destroyed)
        job.join()
        assertEquals(1, obj.destroyed)
    }
}
