// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** The touch box over each end pin. */
private val HANDLE_SIZE = 48.dp
/** The pin under the finger, and its white ring. */
private val PIN_SIZE = 18.dp
private val PIN_RING = 3.dp
/** How far above the finger a dragged pin sits, so it can be seen. */
private val PIN_LIFT = 32.dp
/** Where the dragged pin would land, on the road. */
private val LANDING_DOT = 8.dp

private val START_PIN = Color(0xFF188038)
private val END_PIN = Color(0xFFC5221F)

/**
 * Drag handles over the marked stretch's two end pins (ADR-0018): for a
 * new favourite, a favourite's new start and end, and a surface
 * correction. A drag moves that end along the road: the pin lifts above
 * the finger, a dot shows where it lands (the road under it), and on
 * release the stretch is found again between the ends. Touches elsewhere
 * reach the map as before (a tap still moves the nearer end).
 */
@Composable
internal fun BoxScope.MarkHandles(screen: MapScreenScope) {
    with(screen) {
        with(state) {
            val m = map ?: return
            val ready = region as? RegionState.Ready ?: return
            val proposal = marker.state as? SectionMarker.State.Proposed
            if (!marking || proposal == null || proposing) return
            // Positions follow the map as it moves.
            var moved by remember { mutableIntStateOf(0) }
            DisposableEffect(m) {
                val listener = MapLibreMap.OnCameraMoveListener { moved++ }
                m.addOnCameraMoveListener(listener)
                onDispose { m.removeOnCameraMoveListener(listener) }
            }
            // Read, so the handles move with the map.
            if (moved < 0) return
            val haptic = LocalHapticFeedback.current
            var finger by remember { mutableStateOf<Offset?>(null) }
            var landing by remember { mutableStateOf<LatLng?>(null) }
            val current by rememberUpdatedState(proposal)
            val liftPx = with(density) { PIN_LIFT.toPx() }

            // The road under the lifted pin, or why there is none.
            fun landingFor(f: Offset): Result<LatLng> {
                val at = m.projection.fromScreenLocation(PointF(f.x, f.y - liftPx))
                return runCatching { ready.engine.snap(at.toLatLon()).position }.map { LatLng(it.lat, it.lon) }
            }
            // Snapped once per frame while a pin is dragged, not on every
            // touch event.
            LaunchedEffect(dragging) {
                var snappedFor: Offset? = null
                while (dragging != null) {
                    withFrameNanos { }
                    val f = finger ?: continue
                    if (f != snappedFor) {
                        snappedFor = f
                        landing = landingFor(f).getOrNull()
                    }
                }
            }

            // The end's box above the start's.
            for (end in listOf(SectionMarker.End.START, SectionMarker.End.END)) {
                val at = if (end == SectionMarker.End.START) proposal.start else proposal.end
                val p = m.projection.toScreenLocation(at)
                Box(
                    Modifier
                        .offset { centred(p.x, p.y, HANDLE_SIZE) }
                        .size(HANDLE_SIZE)
                        .pointerInput(end) {
                            detectDragGestures(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val pin = current.let { if (end == SectionMarker.End.START) it.start else it.end }
                                    val s = m.projection.toScreenLocation(pin)
                                    // The finger starts on the pin; the pin
                                    // lifts above it.
                                    finger = Offset(s.x, s.y + liftPx)
                                    dragging = end
                                    showDraft()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val f = (finger ?: return@detectDragGestures) + amount
                                    finger = f
                                },
                                onDragEnd = {
                                    // Where it lands now, exactly.
                                    val to = finger?.let { landingFor(it) }
                                    finger = null
                                    landing = null
                                    dragging = null
                                    val point = to?.getOrNull()
                                    if (point == null) {
                                        // No road there: the end stays, the
                                        // task card's step too.
                                        showDraft()
                                        to?.exceptionOrNull()?.let { notify(coreErrorMessage(resources, it), long = true) }
                                    } else {
                                        val next = marker.moveEnd(end, point)
                                        if (next is SectionMarker.State.Proposed) proposeBetween(ready, next.start, next.end)
                                    }
                                },
                                onDragCancel = {
                                    finger = null
                                    landing = null
                                    dragging = null
                                    showDraft()
                                },
                            )
                        },
                )
            }
            val f = finger
            val end = dragging
            if (f != null && end != null) {
                landing?.let { l ->
                    val s = m.projection.toScreenLocation(l)
                    Box(
                        Modifier
                            .offset { centred(s.x, s.y, LANDING_DOT) }
                            .size(LANDING_DOT)
                            .background(if (end == SectionMarker.End.START) START_PIN else END_PIN, CircleShape),
                    )
                }
                Box(
                    Modifier
                        .offset { centred(f.x, f.y - liftPx, PIN_SIZE + PIN_RING * 2) }
                        .size(PIN_SIZE + PIN_RING * 2)
                        .background(if (end == SectionMarker.End.START) START_PIN else END_PIN, CircleShape)
                        .border(PIN_RING, Color.White, CircleShape),
                )
            }
        }
    }
}

/** The offset that centres a box [size] wide on ([x], [y]) px. */
private fun androidx.compose.ui.unit.Density.centred(x: Float, y: Float, size: Dp): IntOffset {
    val half = size.toPx() / 2
    return IntOffset((x - half).roundToInt(), (y - half).roundToInt())
}
