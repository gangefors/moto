// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Counts changes to the saved rides (one finished, imported or deleted),
 * so the map rebuilds the roads they have been on for routing (ADR-0010).
 */
object RideChanges {
    private val count = MutableStateFlow(0)
    val version: StateFlow<Int> = count

    fun changed() = count.update { it + 1 }
}
