// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long the steps of a cold start take, to find what makes opening a
 * large region slow on the phone: each step's own time and when it was
 * done since the app's process started. Logged (tag "moto") and shown in
 * My data → Map region in debug builds.
 */
object StartupTimes {
    private val _steps = MutableStateFlow<List<String>>(emptyList())
    val steps: StateFlow<List<String>> = _steps.asStateFlow()

    /** Runs [block] as step [name] and records how long it took. */
    fun <T> measure(name: String, block: () -> T): T {
        val start = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            record(name, SystemClock.uptimeMillis() - start)
        }
    }

    /** Records that [name] happened (took [ms], if known). */
    fun record(name: String, ms: Long? = null) {
        val since = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        val line = startupLine(name, ms, since)
        Log.i("moto", "startup: $line")
        _steps.value = (_steps.value + line).takeLast(MAX_STEPS)
    }

    private const val MAX_STEPS = 20
}

/** "region open: 7.41 s (at 8.02 s)". */
fun startupLine(name: String, ms: Long?, sinceStartMs: Long): String {
    fun s(v: Long) = String.format(java.util.Locale.ROOT, "%.2f s", v / 1000.0)
    return if (ms == null) "$name (at ${s(sinceStartMs)})" else "$name: ${s(ms)} (at ${s(sinceStartMs)})"
}
