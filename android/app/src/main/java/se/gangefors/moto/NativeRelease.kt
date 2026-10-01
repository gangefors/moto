// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.gangefors.moto.core.Disposable

/** How long a replaced core object stays usable before it is released. */
const val RELEASE_GRACE_MS = 10_000L

/**
 * Frees core objects (an engine, favourites) once they are replaced. On
 * its own, a core object's memory goes only when the garbage collector
 * finds the Kotlin side unused, and with a small Java heap that can take
 * long: a replaced favourites set or engine (its region files mapped)
 * lingered meanwhile. A call already running keeps its object until it
 * returns; [RELEASE_GRACE_MS] lets code that took the old one just
 * before the swap start its call too.
 */
object NativeRelease {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Releases [obj] after [graceMs]. */
    fun later(obj: Disposable, graceMs: Long = RELEASE_GRACE_MS): Job = later(scope, obj, graceMs)

    /** As [later], in [scope] (for tests). */
    fun later(scope: CoroutineScope, obj: Disposable, graceMs: Long): Job = scope.launch {
        delay(graceMs)
        obj.destroy()
    }
}
