// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long a toast shows. */
const val TOAST_MS = 2_500L

/** A toast: its text and when it was posted (uptime ms). */
data class ToastMessage(val text: String, val atMs: Long)

/** How long [toast] still shows at [nowMs]; 0 when it is over. */
fun toastRemainingMs(toast: ToastMessage?, nowMs: Long): Long =
    if (toast == null) 0 else (toast.atMs + TOAST_MS - nowMs).coerceIn(0, TOAST_MS)

/**
 * Short confirmations that go by themselves ("Deleted", "Ride exported"),
 * like Android's toasts but drawn by the app, so they keep clear of the
 * navigation bar (Android places its own toasts itself). Any screen that
 * has a [ToastHost] shows the current one.
 */
object Toasts {
    private val _current = MutableStateFlow<ToastMessage?>(null)
    val current: StateFlow<ToastMessage?> = _current.asStateFlow()

    fun show(text: String) {
        _current.value = ToastMessage(text, SystemClock.uptimeMillis())
    }
}

/**
 * Shows the current toast at the bottom of this screen, well above the
 * navigation bar, for what remains of its time. Place it last in a Box,
 * aligned at the bottom centre.
 */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val toast by Toasts.current.collectAsState()
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toast) {
        val left = toastRemainingMs(toast, SystemClock.uptimeMillis())
        if (left == 0L) return@LaunchedEffect
        shown = toast
        visible = true
        delay(left)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.safeDrawingPadding().padding(start = 24.dp, end = 24.dp, bottom = 48.dp).widthIn(max = 480.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                shown?.text ?: "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
