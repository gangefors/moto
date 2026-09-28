// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where a scrollbar's thumb sits along a track [viewport] px long:
 * its start and length, px; null when nothing scrolls. */
data class Thumb(val start: Float, val length: Float)

/**
 * The thumb for content [max] px longer than the [viewport], scrolled
 * [value] px: as long as the share of the content in view (at least
 * [minLength]), and as far along as the scroll.
 */
fun scrollThumb(viewport: Float, max: Float, value: Float, minLength: Float): Thumb? {
    if (viewport <= 0f || max <= 0f) return null
    val length = (viewport * viewport / (viewport + max)).coerceIn(minLength.coerceAtMost(viewport), viewport)
    val start = (viewport - length) * (value / max).coerceIn(0f, 1f)
    return Thumb(start, length)
}

/**
 * Shows that content scrolls, the way Android's own lists do: the content
 * fades out at an edge where more of it is ([before], [after]), and
 * a thin bar at the right shows how much is in view and where
 * ([thumb], from [scrollThumb]).
 */
private fun Modifier.scrollHints(before: Boolean, after: Boolean, thumb: (minLength: Float) -> Thumb?, barColor: Color, fade: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = fade.toPx().coerceAtMost(size.height / 3)
            if (before) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = h),
                    size = Size(size.width, h),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (after) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
                    topLeft = Offset(0f, size.height - h),
                    size = Size(size.width, h),
                    blendMode = BlendMode.DstIn,
                )
            }
            thumb(MIN_THUMB.toPx())?.let { t ->
                val w = 4.dp.toPx()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(size.width - w - 2.dp.toPx(), t.start),
                    size = Size(w, t.length),
                    cornerRadius = CornerRadius(w / 2, w / 2),
                )
            }
        }

private val FADE = 32.dp
private val MIN_THUMB = 32.dp

/** [scrollHints] for a scrolling Column ([state] is its ScrollState). */
@Composable
fun Modifier.scrollHints(state: ScrollState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    return scrollHints(
        before = state.canScrollBackward,
        after = state.canScrollForward,
        thumb = { min -> scrollThumb(state.viewportSize.toFloat(), state.maxValue.toFloat(), state.value.toFloat(), min) },
        barColor = color,
        fade = FADE,
    )
}

/** [scrollHints] for a LazyColumn ([state] is its LazyListState): the
 * fades, and a bar from the share of its items in view. */
@Composable
fun Modifier.scrollHints(state: LazyListState): Modifier {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    return scrollHints(
        before = state.canScrollBackward,
        after = state.canScrollForward,
        thumb = { min ->
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (total == 0 || visible >= total || !(state.canScrollBackward || state.canScrollForward)) {
                null
            } else {
                // Items as the unit: the share in view, and how far along.
                val itemPx = viewport / visible
                scrollThumb(viewport, (total - visible) * itemPx, state.firstVisibleItemIndex * itemPx, min)
            }
        },
        barColor = color,
        fade = FADE,
    )
}
