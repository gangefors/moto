// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import org.maplibre.android.MapLibre
import se.gangefors.moto.core.defaultRouteOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The map style is always light, so the status bar always needs dark
        // icons, whatever the system theme. MapScreen adds a scrim behind them.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        checkCoreLoads()
        setContent {
            MaterialTheme {
                MapScreen()
            }
        }
    }

    /** M0 smoke test: one call into the Rust core proves the native library loads. */
    private fun checkCoreLoads() {
        try {
            val opts = defaultRouteOptions()
            Log.i(TAG, "moto core loaded (default max detour ${opts.maxDetour})")
        } catch (e: Throwable) {
            Log.e(TAG, "moto core failed to load", e)
        }
    }

    private companion object {
        const val TAG = "moto"
    }
}
