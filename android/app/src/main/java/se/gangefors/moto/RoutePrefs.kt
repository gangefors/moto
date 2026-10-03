// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import androidx.core.content.edit
import se.gangefors.moto.core.Avoid
import se.gangefors.moto.core.BackupSetting
import se.gangefors.moto.core.FavouritesMode
import se.gangefors.moto.core.Gravel
import se.gangefors.moto.core.UnriddenMode

/**
 * The route settings the rider last chose, in app-private preferences
 * (never in Android's backup, see the data extraction rules; in the app's
 * own backup file, see [BACKUP_SETTINGS]).
 */
object RoutePrefs {
    private const val FILE = "route"
    /** The old Allow gravel switch (a boolean), read once as a fallback. */
    private const val ALLOW_GRAVEL = "allow_gravel"
    internal const val GRAVEL = "gravel"
    internal const val FAVOURITES = "favourites"
    internal const val UNRIDDEN = "unridden"
    /** The kinds of road allowed (motorways, ferries, tolls); unset: all
     * avoided. */
    internal const val ALLOWED_ROADS = "allowed_roads"
    internal const val LOOP = "loop_length"
    internal const val LOOP_DIRECTION = "loop_direction"
    internal const val AREA_ZOOM = "locate_area_zoom"
    internal const val CLOSE_ZOOM = "locate_close_zoom"
    internal const val KEEP_SCREEN_ON = "keep_screen_on_recording"
    internal const val SHOW_RIDDEN = "show_ridden_roads"
    internal const val TURN_MAP = "ride_turn_map"
    internal const val RIDE_ZOOM_STEP = "ride_zoom_step"
    internal const val OFF_ROUTE_ALERT = "ride_off_route_alert"
    private const val MAP_HINTS = "map_hints_shown"
    internal const val SECTIONS_SORT = "sections_sort"
    internal const val LIBRARY_FILTER = "library_filter"
    internal const val LIBRARY_SORT = "library_sort"
    internal const val DARK_THEME = "dark_theme"

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

    /** Whether routes prefer roads none of the rider's rides has been on;
     * any road by default (ADR-0010). */
    fun unridden(context: Context): UnriddenMode = unriddenOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(UNRIDDEN, null) }.getOrNull(),
    )

    fun setUnridden(context: Context, unridden: UnriddenMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(UNRIDDEN, unriddenKey(unridden)) }
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

    /** The way every new loop heads at first (Ride settings); any way by
     * default. A change on the loop sheet is for that loop alone. */
    fun loopDirection(context: Context): LoopDirection = loopDirectionOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LOOP_DIRECTION, null) }.getOrNull(),
    )

    fun setLoopDirection(context: Context, direction: LoopDirection) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(LOOP_DIRECTION, loopDirectionKey(direction)) }
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

    /** Whether the map draws the roads the rides have been on (ADR-0010);
     * off by default. */
    fun showRidden(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SHOW_RIDDEN, false) }.getOrDefault(false)

    fun setShowRidden(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(SHOW_RIDDEN, on) }
    }

    /** Riding a route (ADR-0011): the map turns with the rider's direction. */
    fun turnMap(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(TURN_MAP, true) }.getOrDefault(true)

    fun setTurnMap(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(TURN_MAP, on) }
    }

    /** How close the map zooms while riding (see [rideZoomOffset]). */
    fun rideZoomStep(context: Context): Int = runCatching {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(RIDE_ZOOM_STEP, RIDE_ZOOM_DEFAULT_STEP)
    }.getOrDefault(RIDE_ZOOM_DEFAULT_STEP).coerceIn(0, RIDE_ZOOM_STEPS - 1)

    fun setRideZoomStep(context: Context, step: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putInt(RIDE_ZOOM_STEP, step.coerceIn(0, RIDE_ZOOM_STEPS - 1)) }
    }

    /** Riding a route: a sound when the rider leaves it. */
    fun offRouteAlert(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(OFF_ROUTE_ALERT, true) }.getOrDefault(true)

    fun setOffRouteAlert(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(OFF_ROUTE_ALERT, on) }
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
    /** The order Routes & rides lists in (kept between visits). */
    fun librarySort(context: Context): LibrarySort = librarySortOf(
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LIBRARY_SORT, null) }.getOrNull(),
    )

    fun setLibrarySort(context: Context, sort: LibrarySort) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(LIBRARY_SORT, sort.name) }
    }

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

    /** The settings a backup carries (ADR-0012), as they are stored now. */
    fun forBackup(context: Context): List<BackupSetting> =
        settingsForBackup(runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all }.getOrDefault(emptyMap()))

    /**
     * Applies a restored backup's settings: each one this app knows, of
     * the right type and range, replaces the phone's; the rest stay.
     */
    fun restore(context: Context, settings: List<BackupSetting>) {
        val apply = settingsFromBackup(settings)
        if (apply.isEmpty()) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            apply.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
            // The new gravel setting replaces the old switch.
            if (GRAVEL in apply) remove(ALLOW_GRAVEL)
        }
    }
}
