// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import java.io.File
import se.gangefors.moto.core.SectionStore

/** What the app knows about the rider's saved sections. */
sealed interface StoreState {
    data object Loading : StoreState
    data class Ready(val store: SectionStore) : StoreState
    data class Failed(val message: String) : StoreState
}

/**
 * The rider's section store (ADR-0006): one SQLite database, `moto.db`, in
 * app-private storage, opened by the Rust core. Call off the main thread.
 */
object SavedSections {
    private const val FILE = "moto.db"

    private var opened: StoreState.Ready? = null

    /** Opens (creating if needed) the store; later calls reuse it. */
    @Synchronized
    fun open(context: Context): StoreState = opened ?: try {
        StoreState.Ready(SectionStore.open(File(context.filesDir, FILE).path)).also { opened = it }
    } catch (e: Exception) {
        StoreState.Failed(e.message ?: e.toString())
    }
}
