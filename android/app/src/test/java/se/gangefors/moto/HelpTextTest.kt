// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test

class HelpTextTest {
    private val icons = setOf("mark", "flag")

    @Test
    fun knownKeysBecomeIcons() {
        assertEquals(
            listOf(HelpPart.Words("Tap "), HelpPart.Icon("mark"), HelpPart.Words(" to mark a road.")),
            helpParts("Tap [mark] to mark a road.", icons),
        )
    }

    @Test
    fun iconsSideBySideAndAtTheEnds() {
        assertEquals(
            listOf(HelpPart.Icon("flag"), HelpPart.Icon("mark")),
            helpParts("[flag][mark]", icons),
        )
    }

    @Test
    fun otherBracketsStayAsWritten() {
        assertEquals(listOf(HelpPart.Words("a [b] c [d")), helpParts("a [b] c [d", icons))
        assertEquals(listOf(HelpPart.Words("no icons")), helpParts("no icons", icons))
        assertEquals(emptyList<HelpPart>(), helpParts("", icons))
    }
}
