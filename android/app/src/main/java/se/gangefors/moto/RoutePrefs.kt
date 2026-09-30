// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import androidx.core.content.edit
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.FavouritesMode
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
    private const val FAVOURITES = "favourites"
    /** The kinds of road allowed (motorways, ferries, tolls); unset: all
     * avoided. */
    private const val ALLOWED_ROADS = "allowed_roads"
    private const val LOOP = "loop_length"
    private const val AREA_ZOOM = "locate_area_zoom"
    private const val CLOSE_ZOOM = "locate_close_zoom"
    private const val KEEP_SCREEN_ON = "keep_screen_on_recording"
    private const val MAP_HINTS = "map_hints_shown"
    private const val SECTIONS_SORT = "sections_sort"
    private const val LIBRARY_FILTER = "library_filter"
    private const val DARK_THEME = "dark_theme"

    /** How many starts show how to use the map. */
    private const val MAP_HINT_STARTS = 3

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

    /** Whether routes prefer or avoid the rider's favourites; preferred
     * by default. */
    fun favourites(context: Context): FavouritesMode = favouritesOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(FAVOURITES, null) }.getOrNull(),
    )

    fun setFavourites(context: Context, favourites: FavouritesMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(FAVOURITES, favouritesKey(favourites)) }
    }

    /** The roads routes avoid; everything avoided until the rider allows
     * something. A value of another type throws; it counts as unset. */
    fun avoid(context: Context): Avoid = avoidOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(ALLOWED_ROADS, null) }.getOrNull(),
    )

    fun setAvoid(context: Context, avoid: Avoid) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(ALLOWED_ROADS, avoidKey(avoid)) }
    }

    /** The length a new loop starts at (Ride settings), or the default. */
    fun loopChoice(context: Context): LoopChoice =
        loopChoiceOf(runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LOOP, null) }.getOrNull())

    fun setLoopChoice(context: Context, choice: LoopChoice) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(LOOP, choice.key) }
    }

    /** Whether this start shows how to use the map (the first few do);
     * counts it. */
    fun takeMapHint(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val shown = runCatching { prefs.getInt(MAP_HINTS, 0) }.getOrDefault(0)
        if (shown >= MAP_HINT_STARTS) return false
        prefs.edit { putInt(MAP_HINTS, shown + 1) }
        return true
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

    /** The order the Sections page lists in, as last chosen (by rating
     * at first, or for an unknown value). */
    fun sectionsSort(context: Context): SectionSort = sectionSortOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(SECTIONS_SORT, null) }.getOrNull(),
    )

    fun setSectionsSort(context: Context, sort: SectionSort) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(SECTIONS_SORT, sort.name) }
    }

    /** What Routes & rides lists, as last chosen (all at first). */
    fun libraryFilter(context: Context): LibraryFilter = libraryFilterOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LIBRARY_FILTER, null) }.getOrNull(),
    )

    fun setLibraryFilter(context: Context, filter: LibraryFilter) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(LIBRARY_FILTER, filter.name) }
    }

    /** The theme the rider chose in the menu: dark, light, or null (as the
     * phone is, until chosen). */
    fun darkTheme(context: Context): Boolean? = runCatching {
        when (context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(DARK_THEME, null)) {
            "dark" -> true
            "light" -> false
            else -> null
        }
    }.getOrNull()

    fun setDarkTheme(context: Context, dark: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(DARK_THEME, if (dark) "dark" else "light") }
    }
}
