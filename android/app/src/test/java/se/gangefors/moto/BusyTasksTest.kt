// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusyTasksTest {
    @Test
    fun showsTheLatestTaskWhileItRuns() = runBlocking {
        val busy = BusyTasks()
        assertNull(busy.current)
        val result = busy.run(1) {
            assertEquals(1, busy.current)
            busy.run(2) { assertEquals(2, busy.current) }
            assertEquals(1, busy.current)
            "done"
        }
        assertEquals("done", result)
        assertNull(busy.current)
    }

    @Test
    fun aFailedTaskIsNoLongerListed() = runBlocking {
        val busy = BusyTasks()
        runCatching { busy.run(1) { error("boom") } }
        assertNull(busy.current)
    }
}
