// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {
    @Test
    fun theMapsKeyFollowsTheButtonsOnTheMap() {
        val map = HELP_TOPICS.first { it.id == HelpTopicId.MAP }
        assertEquals(MAP_BUTTON_ORDER, map.keys.map { it.icon })
    }

    @Test
    fun everySeeLinkLeadsToAnotherTopicOnThePage() {
        val ids = HELP_TOPICS.map { it.id }.toSet()
        for (topic in HELP_TOPICS) {
            for (key in topic.keys) {
                val see = key.see ?: continue
                assertTrue("${key.icon} in ${topic.id}", see in ids && see != topic.id)
            }
        }
    }

    @Test
    fun topicsComeOnceInPageOrder() {
        assertEquals(HelpTopicId.entries.toList(), HELP_TOPICS.map { it.id })
    }

    @Test
    fun everyKeyHasAButtonIcon() {
        for (key in HELP_TOPICS.flatMap { it.keys + it.figures + it.summary }) assertTrue(key.icon, key.icon in BUTTON_ICONS)
    }

    @Test
    fun routingSettingsFollowTheLoopSheetsOrder() {
        val settings = HELP_TOPICS.first { it.id == HelpTopicId.SETTINGS }
        val routing = settings.groups.first { it.title == R.string.settings_group_routing }
        assertEquals(
            listOf(
                R.string.settings_loop_length,
                R.string.settings_loop_direction,
                R.string.routing_gravel,
                R.string.favourites_label,
                R.string.avoid_heading,
            ),
            routing.settings.map { it.name },
        )
    }

    @Test
    fun theFiguresKeyHasEveryFigureWithAnIcon() {
        val plan = HELP_TOPICS.first { it.id == HelpTopicId.PLAN }
        // Fastest is a word, not an icon; every other figure is in the key.
        assertEquals(RouteStatKind.entries.size - 1, plan.figures.size)
        assertEquals(listOf("time", "sections", "curvy", "gravel", "toll"), plan.figures.map { it.icon })
    }

    @Test
    fun theSummaryKeyHasEveryKindOfChip() {
        val plan = HELP_TOPICS.first { it.id == HelpTopicId.PLAN }
        // Every summary chip with an icon (the length is in words).
        assertEquals(listOf("clock", "pin", "compass", "gravel", "star_off", "motorway"), plan.summary.map { it.icon })
    }
}
