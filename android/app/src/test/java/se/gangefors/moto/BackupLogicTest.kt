// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.BackupSetting
import se.gangefors.moto.core.BackupSettingValue
import java.time.LocalDate
import java.time.ZoneId

class BackupLogicTest {
    @Test
    fun aBackupCarriesTheKnownSettingsAsStored() {
        val stored = mapOf(
            RoutePrefs.TURN_MAP to false,
            RoutePrefs.RIDE_ZOOM_STEP to 3,
            RoutePrefs.DARK_THEME to "dark",
            // Not carried: hints shown, unknown keys, wrong types, out of range.
            "map_hints_shown" to 2,
            "something_else" to true,
            RoutePrefs.SHOW_RIDDEN to "yes",
            RoutePrefs.AREA_ZOOM to 99,
            RoutePrefs.LOOP to "x".repeat(300),
        )
        assertEquals(
            listOf(
                BackupSetting(RoutePrefs.DARK_THEME, BackupSettingValue.Text("dark")),
                BackupSetting(RoutePrefs.TURN_MAP, BackupSettingValue.Bool(false)),
                BackupSetting(RoutePrefs.RIDE_ZOOM_STEP, BackupSettingValue.Int(3)),
            ),
            settingsForBackup(stored),
        )
        assertTrue(settingsForBackup(emptyMap<String, Any>()).isEmpty())
    }

    @Test
    fun aRestoreAppliesOnlyKnownKeysOfTheRightTypeAndRange() {
        val restored = listOf(
            BackupSetting(RoutePrefs.TURN_MAP, BackupSettingValue.Bool(true)),
            BackupSetting(RoutePrefs.RIDE_ZOOM_STEP, BackupSettingValue.Int(6)),
            BackupSetting(RoutePrefs.CLOSE_ZOOM, BackupSettingValue.Int(14)),
            BackupSetting(RoutePrefs.LIBRARY_SORT, BackupSettingValue.Text("NAME")),
            // Left out, so the phone's own setting stays.
            BackupSetting("map_hints_shown", BackupSettingValue.Int(0)),
            BackupSetting("unknown_key", BackupSettingValue.Bool(true)),
            BackupSetting(RoutePrefs.SHOW_RIDDEN, BackupSettingValue.Int(1)),
            BackupSetting(RoutePrefs.KEEP_SCREEN_ON, BackupSettingValue.Text("true")),
            BackupSetting(RoutePrefs.AREA_ZOOM, BackupSettingValue.Int(Long.MAX_VALUE)),
            BackupSetting(RoutePrefs.AREA_ZOOM, BackupSettingValue.Int(-1)),
            BackupSetting(RoutePrefs.GRAVEL, BackupSettingValue.Text("a\nb")),
        )
        assertEquals(
            mapOf(
                RoutePrefs.TURN_MAP to true,
                RoutePrefs.RIDE_ZOOM_STEP to 6,
                RoutePrefs.CLOSE_ZOOM to 14,
                RoutePrefs.LIBRARY_SORT to "NAME",
            ),
            settingsFromBackup(restored),
        )
        assertTrue(settingsFromBackup(listOf(BackupSetting(RoutePrefs.RIDE_ZOOM_STEP, BackupSettingValue.Int(RIDE_ZOOM_STEPS.toLong())))).isEmpty())
    }

    @Test
    fun settingsSurviveARoundTrip() {
        val stored = mapOf<String, Any>(
            RoutePrefs.GRAVEL to "prefer",
            RoutePrefs.OFF_ROUTE_ALERT to false,
            RoutePrefs.AREA_ZOOM to 9,
        )
        assertEquals(stored, settingsFromBackup(settingsForBackup(stored)))
    }

    @Test
    fun everyCarriedKeyIsASimpleKeyTheCoreAccepts() {
        for (key in BACKUP_SETTINGS.keys) {
            assertTrue(key, key.matches(Regex("[a-z0-9_]{1,64}")))
        }
        assertTrue("map hints stay on the phone", "map_hints_shown" !in BACKUP_SETTINGS)
    }

    @Test
    fun missingRegionsAreThoseNotOnThePhone() {
        assertEquals(listOf("denmark"), missingRegions(listOf("sweden", "denmark", "denmark", "../x", "Bad"), setOf("sweden")))
        assertTrue(missingRegions(listOf("sweden"), setOf("sweden")).isEmpty())
        assertEquals("North sweden", regionIdName("north-sweden"))
    }

    @Test
    fun namesAndDates() {
        assertEquals("2026-10-03", backupFileDate(LocalDate.of(2026, 10, 3)))
        val utc = ZoneId.of("UTC")
        assertEquals("12 Sept 2026, 21:04", backupWhen(1_789_247_040_000, utc).replace("Sep ", "Sept "))
    }
}
