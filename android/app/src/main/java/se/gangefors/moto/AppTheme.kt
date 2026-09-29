// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Whether the app is drawn dark: as the rider chose ([chosen]), else as the phone is. */
fun themeIsDark(chosen: Boolean?, phoneDark: Boolean): Boolean = chosen ?: phoneDark

/** Whether the app is drawn dark, and how to flip it (the menu's sun/moon button). */
class ThemeChoice(val dark: Boolean, val toggle: () -> Unit)

val LocalTheme = compositionLocalOf { ThemeChoice(dark = false) {} }

/**
 * The app's light or dark theme (Material 3's standard colours). It
 * follows the phone until the rider flips it in the menu; that choice is
 * kept. The map keeps its own (light) style either way.
 */
@Composable
fun MotoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(RoutePrefs.darkTheme(context)) }
    val dark = themeIsDark(chosen, isSystemInDarkTheme())
    val choice = ThemeChoice(dark) {
        chosen = !dark
        RoutePrefs.setDarkTheme(context, !dark)
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalTheme provides choice, content = content)
    }
}
