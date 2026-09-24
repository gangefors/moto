// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A buzz the rider feels through gloves: one firm pulse when a quick-tag
 * is saved, three short ones when it failed. Uses the vibrator directly
 * (VIBRATE, a normal install-time permission), because touch feedback
 * follows a system setting that is often off.
 */
fun buzz(context: Context, ok: Boolean) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    val effect = if (ok) {
        VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
    } else {
        VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80, 80, 80), -1)
    }
    vibrator.vibrate(effect)
}
