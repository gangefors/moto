// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import androidx.core.content.edit
import se.gangefors.moto.core.Gravel

/**
 * The route settings the rider last chose, in app-private preferences
 * (never backed up; see the data extraction rules).
 */
object RoutePrefs {
    private const val FILE = "route"
    /** The old Allow gravel switch (a boolean), read once as a fallback. */
    private const val ALLOW_GRAVEL = "allow_gravel"
    private const val GRAVEL = "gravel"
    private const val LOOP = "loop_length"
    private const val AREA_ZOOM = "locate_area_zoom"
    private const val CLOSE_ZOOM = "locate_close_zoom"
    private const val KEEP_SCREEN_ON = "keep_screen_on_recording"

    /** What routes do with gravel (unpaved) roads; avoided by default.
     * A value of another type throws; it counts as unset. */
    fun gravel(context: Context): Gravel {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return gravelOf(
            runCatching { prefs.getString(GRAVEL, null) }.getOrNull(),
            legacyAllow = runCatching { prefs.getBoolean(ALLOW_GRAVEL, false) }.getOrDefault(false),
        )
    }

    fun setGravel(context: Context, gravel: Gravel) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(GRAVEL, gravelKey(gravel))
            remove(ALLOW_GRAVEL)
        }
    }

    /** The length a new loop starts at (Ride settings), or the default. */
    fun loopChoice(context: Context): LoopChoice =
        loopChoiceOf(runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LOOP, null) }.getOrNull())

    fun setLoopChoice(context: Context, choice: LoopChoice) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(LOOP, choice.key) }
    }

    /** Whether the screen stays on while a ride is recording. */
    fun keepScreenOn(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEEP_SCREEN_ON, false) }.getOrDefault(false)

    fun setKeepScreenOn(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEEP_SCREEN_ON, on) }
    }

    /** The location button's zoom levels, as last set (defaults 10 and 14). */
    fun locateZooms(context: Context): LocateZooms {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        fun int(key: String) = runCatching { prefs.getInt(key, -1) }.getOrDefault(-1).takeIf { it >= 0 }
        return LocateZooms.of(int(AREA_ZOOM), int(CLOSE_ZOOM))
    }

    fun setLocateZooms(context: Context, zooms: LocateZooms) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putInt(AREA_ZOOM, zooms.area)
            putInt(CLOSE_ZOOM, zooms.close)
        }
    }
}
