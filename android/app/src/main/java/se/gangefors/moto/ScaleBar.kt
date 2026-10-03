// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The scale bar is at most this long, dp. */
const val SCALE_MAX_DP = 120.0

/** A scale bar: [metres] long (a round number), drawn [dp] long. */
data class ScaleLength(val metres: Double, val dp: Double)

/**
 * The scale for a map showing [metresPerDp] metres per dp: the longest
 * round length (1, 2 or 5 × a power of ten metres) that fits in [maxDp],
 * so the bar is between about two fifths of it and all of it. Null for a
 * scale that makes no sense (zero, negative, not finite).
 */
fun scaleLength(metresPerDp: Double, maxDp: Double = SCALE_MAX_DP): ScaleLength? {
    if (!(metresPerDp.isFinite() && metresPerDp > 0 && maxDp > 0)) return null
    val most = metresPerDp * maxDp
    val power = 10.0.pow(floor(log10(most)))
    val metres = listOf(5.0, 2.0, 1.0).map { it * power }.first { it <= most }
    return ScaleLength(metres, metres / metresPerDp)
}

/**
 * How much of the map a length takes, metric only (2026-10-03):
 * the length above a thin bracket, dark with a light halo on the light
 * map and the other way round on the dark one. It takes no taps and
 * TalkBack skips it.
 */
@Composable
fun ScaleBar(metresPerDp: Double, darkMap: Boolean, modifier: Modifier = Modifier) {
    val scale = scaleLength(metresPerDp) ?: return
    val ink = if (darkMap) Color.White else Color(0xFF1D1B20)
    val halo = if (darkMap) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.85f)
    val label = if (scale.metres >= 1000) {
        stringResource(R.string.span_km, (scale.metres / 1000).toInt())
    } else {
        stringResource(R.string.span_m, scale.metres.toInt())
    }
    Column(modifier.clearAndSetSemantics {}) {
        Text(
            label,
            color = ink,
            style = MaterialTheme.typography.labelSmall.copy(shadow = Shadow(halo, blurRadius = 4f)),
        )
        Canvas(Modifier.width(scale.dp.dp).height(6.dp)) {
            val bracket = Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width, 0f)
            }
            val w = 2.dp.toPx()
            drawPath(bracket, halo, style = Stroke(w + 2.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
            drawPath(bracket, ink, style = Stroke(w, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
    }
}
