// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.junit.Assert.assertEquals
import org.junit.Test
import se.gangefors.moto.LicenceBlock.Heading
import se.gangefors.moto.LicenceBlock.Paragraph

class LicenceTextTest {
    @Test
    fun splitsHeadingsAndParagraphs() {
        val text = "## moto\n\nFree software.\nSource: x\n\n\n### 1 · LICENSE-MIT\n\nUsed by: a, b\n\nMIT License\r\n  Copyright\n ### not a heading\n"
        assertEquals(
            listOf(
                Heading(1, "moto"),
                Paragraph("Free software.\nSource: x"),
                Heading(2, "1 · LICENSE-MIT"),
                Paragraph("Used by: a, b"),
                Paragraph("MIT License\n  Copyright\n ### not a heading"),
            ),
            licenceBlocks(text),
        )
    }

    @Test
    fun emptyAndOddInputGiveNoBlocksOrPlainParagraphs() {
        assertEquals(emptyList<LicenceBlock>(), licenceBlocks(""))
        assertEquals(emptyList<LicenceBlock>(), licenceBlocks("\n\n   \n"))
        assertEquals(listOf(Paragraph("##no space"), Heading(1, "")), licenceBlocks("##no space\n## "))
    }
}
