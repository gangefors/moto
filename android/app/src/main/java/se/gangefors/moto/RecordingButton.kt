// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The record button while a ride is recorded: the same size as the other
 * map buttons, a filled stop square in the button's own content colour
 * (dark on the light theme), and a red arc running round its edge, so it
 * is plain at a glance that recording is on. [description] says what a
 * tap does (with the distance so far) to a screen reader. With animations
 * turned off on the phone, the arc stands still.
 */
@Composable
fun RecordingButton(onStop: () -> Unit, description: String, modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "recording").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(ARC_TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "arc",
    )
    val shape = FloatingActionButtonDefaults.shape
    FloatingActionButton(
        onClick = onStop,
        shape = shape,
        modifier = modifier
            .semantics { contentDescription = description }
            .drawWithContent {
                drawContent()
                val stroke = 3.dp.toPx()
                val out = stroke / 2 + 2.dp.toPx()
                drawRoundRect(
                    brush = arcBrush(turn, center),
                    topLeft = Offset(-out, -out),
                    size = Size(size.width + 2 * out, size.height + 2 * out),
                    cornerRadius = CornerRadius(16.dp.toPx() + out),
                    style = Stroke(stroke),
                )
            },
    ) {
        Icon(painterResource(R.drawable.ic_stop), contentDescription = null, tint = LocalContentColor.current)
    }
}

/**
 * A red sweep round [centre], fading in over a third of the way round,
 * turned by [degrees]. The centre is given, as the ring is drawn outside
 * the button and the shader works in the button's coordinates.
 */
private fun arcBrush(degrees: Float, centre: Offset) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val cx = centre.x
        val cy = centre.y
        val red = RECORD_RED.toArgb()
        val clear = Color.Transparent.toArgb()
        return SweepGradient(cx, cy, intArrayOf(clear, clear, red), floatArrayOf(0f, 0.6f, 1f)).apply {
            setLocalMatrix(Matrix().apply { setRotate(degrees, cx, cy) })
        }
    }
}

/** How long the arc takes to go once round, ms. */
private const val ARC_TURN_MS = 3200
