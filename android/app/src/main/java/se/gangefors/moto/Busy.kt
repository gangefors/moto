// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long a wait may take before the app shows it is working. */
const val BUSY_DELAY_MS = 300L

/**
 * What the app is working on: tasks run through [run] are listed while
 * they run, the latest one shown ([current]), so a long wait never looks
 * like the app froze.
 */
class BusyTasks {
    private val tasks = mutableStateListOf<Int>()

    /** The label (a string resource) of the latest task still running. */
    val current: Int? get() = tasks.lastOrNull()

    /** Runs [block] listed under [label]; removed again however it ends. */
    suspend fun <T> run(label: Int, block: suspend () -> T): T {
        tasks.add(label)
        try {
            return block()
        } finally {
            tasks.remove(label)
        }
    }
}

/**
 * A small pill with a spinner and what the app is doing, shown once
 * [label] has been set for [BUSY_DELAY_MS], so quick answers don't flash.
 */
@Composable
fun BusyPill(label: Int?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    // Kept while the pill fades out, so its text doesn't vanish first.
    var text by remember { mutableStateOf(label) }
    LaunchedEffect(label) {
        if (label == null) {
            visible = false
        } else {
            if (!visible) delay(BUSY_DELAY_MS)
            text = label
            visible = true
        }
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = CircleShape, tonalElevation = 3.dp, shadowElevation = 3.dp) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                text?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
