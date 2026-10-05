// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.SectionStore

/**
 * Runs [action] on the store off the main thread, then reloads the
 * sections and shows the message [action] returns.
 */
internal fun MapScreenScope.changeSectionsThen(action: (SectionStore) -> String?) {
    with(state) {
        val ready = store as? StoreState.Ready ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val done = action(ready.store)
                    done to ready.store.list(null)
                }
            }
            result.fold(
                onSuccess = { (done, list) ->
                    sections = list
                    // Saved or updated: a toast, like the menu pages (a
                    // delete has none: the bin already said "Deleted").
                    done?.let { Toasts.show(it) }
                },
                onFailure = { notify(resources.getString(R.string.sections_failed, it.message ?: it.toString()), long = true) },
            )
        }
    }
}

/** Runs [action] on the store off the main thread, then reloads the sections. */
internal fun MapScreenScope.changeSections(done: String?, action: (SectionStore) -> Unit) = with(state) {
    changeSectionsThen {
        action(it)
        done
    }
}
