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
        for (key in HELP_TOPICS.flatMap { it.keys }) assertTrue(key.icon, key.icon in BUTTON_ICONS)
    }
}
