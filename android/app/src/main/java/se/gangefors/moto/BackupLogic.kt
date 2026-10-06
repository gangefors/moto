// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.BackupSetting
import se.gangefors.moto.core.BackupSettingValue
import se.gangefors.moto.core.RestoreReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Largest file the app copies in to restore (the core's own limit). */
const val MAX_BACKUP_FILE_BYTES = 1L shl 30

/** Longest text setting kept (the core's own limit). */
private const val MAX_SETTING_TEXT = 256

/** What a setting holds in a backup, and the whole numbers allowed. */
sealed interface SettingSpec {
    data object Bool : SettingSpec
    data class Int(val range: IntRange) : SettingSpec
    /** Text, read by the setting's own parser (unknown text counts as unset). */
    data object Text : SettingSpec
}

/**
 * The settings a backup carries (ADR-0012): Ride settings, the map's
 * zooms, sort orders and the theme. Not the map hints shown, nor what is
 * on this phone only (map regions, debug tools).
 */
val BACKUP_SETTINGS: Map<String, SettingSpec> = mapOf(
    RoutePrefs.GRAVEL to SettingSpec.Text,
    RoutePrefs.FAVOURITES to SettingSpec.Text,
    RoutePrefs.UNRIDDEN to SettingSpec.Text,
    RoutePrefs.ALLOWED_ROADS to SettingSpec.Text,
    RoutePrefs.LOOP to SettingSpec.Text,
    RoutePrefs.LOOP_DIRECTION to SettingSpec.Text,
    RoutePrefs.AREA_ZOOM to SettingSpec.Int(MIN_ZOOM..MAX_ZOOM),
    RoutePrefs.CLOSE_ZOOM to SettingSpec.Int(MIN_ZOOM..MAX_ZOOM),
    RoutePrefs.KEEP_SCREEN_ON to SettingSpec.Bool,
    RoutePrefs.SHOW_RIDDEN to SettingSpec.Bool,
    RoutePrefs.TURN_MAP to SettingSpec.Bool,
    RoutePrefs.RIDE_ZOOM_STEP to SettingSpec.Int(0 until RIDE_ZOOM_STEPS),
    RoutePrefs.OFF_ROUTE_ALERT to SettingSpec.Bool,
    RoutePrefs.SECTIONS_SORT to SettingSpec.Text,
    RoutePrefs.LIBRARY_FILTER to SettingSpec.Text,
    RoutePrefs.LIBRARY_SORT to SettingSpec.Text,
    RoutePrefs.DARK_THEME to SettingSpec.Text,
)

/** [value] as [spec] allows it, or null. */
private fun allowed(spec: SettingSpec, value: Any?): Any? = when (spec) {
    SettingSpec.Bool -> value as? Boolean
    is SettingSpec.Int -> (value as? kotlin.Int)?.takeIf { it in spec.range }
    SettingSpec.Text -> (value as? String)?.takeIf { it.length <= MAX_SETTING_TEXT && it.none(Char::isISOControl) }
}

/** The stored settings ([all], as the preferences hold them) a backup carries. */
fun settingsForBackup(all: Map<String, *>): List<BackupSetting> =
    BACKUP_SETTINGS.mapNotNull { (key, spec) ->
        when (val v = allowed(spec, all[key])) {
            is Boolean -> BackupSetting(key, BackupSettingValue.Bool(v))
            is kotlin.Int -> BackupSetting(key, BackupSettingValue.Int(v.toLong()))
            is String -> BackupSetting(key, BackupSettingValue.Text(v))
            else -> null
        }
    }.sortedBy { it.key }

/**
 * The settings of a restored backup to apply, by key: only keys this app
 * knows, of the right type and in range. Anything else is left out, so
 * the phone's own setting stays.
 */
fun settingsFromBackup(settings: List<BackupSetting>): Map<String, Any> =
    settings.mapNotNull { s ->
        val spec = BACKUP_SETTINGS[s.key] ?: return@mapNotNull null
        val raw: Any? = when (val v = s.value) {
            is BackupSettingValue.Bool -> v.value
            is BackupSettingValue.Int -> v.value.takeIf { it in kotlin.Int.MIN_VALUE..kotlin.Int.MAX_VALUE }?.toInt()
            is BackupSettingValue.Text -> v.value
        }
        allowed(spec, raw)?.let { s.key to it }
    }.toMap()

/** The file name a backup made on [date] is offered under. */
fun backupFileDate(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

/** The regions of a backup ([backup]) that aren't on the phone ([installed]), valid ids only. */
fun missingRegions(backup: List<String>, installed: Set<String>): List<String> =
    backup.filter { isRegionId(it) && it !in installed }.distinct()

/** A region id as a name, for a region not on the phone: "north-sweden" → "North sweden". */
fun regionIdName(id: String): String =
    id.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.UK) }

/** A day and time for "Made …" and "Last backup …", e.g. "12 Sept 2026, 21:04". */
fun backupWhen(ms: Long, zone: ZoneId, locale: Locale = Locale.UK): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(Instant.ofEpochMilli(ms).atZone(zone))

/** How a restore went, in counts: what was added, and how many were already here. */
data class RestoreCounts(
    val favourites: Int,
    val rides: Int,
    val routes: Int,
    val tags: Int,
    val alreadyHere: Int,
) {
    val added: Int get() = favourites + rides + routes + tags
}

/** A count from the core, capped to fit an Int. */
internal fun ULong.count(): Int = coerceAtMost(Int.MAX_VALUE.toULong()).toInt()

/**
 * What a restore did, for its toast. Tags left out because they lie on a
 * favourite the phone already had count as already here, like duplicates.
 */
fun restoreCounts(r: RestoreReport) = RestoreCounts(
    favourites = r.favouritesAdded.count(),
    rides = r.ridesAdded.count(),
    routes = r.routesAdded.count(),
    tags = r.tagsAdded.count(),
    alreadyHere = (r.favouritesSkipped + r.ridesSkipped + r.routesSkipped + r.tagsSkipped + r.tagsOnFavourites).count(),
)
