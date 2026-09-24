// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/**
 * A piece of the licence notices file (`licenses/third_party.txt`, built by
 * `.github/scripts/third_party.py`): a heading ("## " is a part, "### " a
 * licence text), or a paragraph, kept with its line breaks (licence texts
 * are laid out by hand).
 */
sealed interface LicenceBlock {
    data class Heading(val level: Int, val text: String) : LicenceBlock
    data class Paragraph(val text: String) : LicenceBlock
}

/** Splits the notices into blocks for a lazy list; blank lines end a
 * paragraph. */
fun licenceBlocks(text: String): List<LicenceBlock> {
    val blocks = mutableListOf<LicenceBlock>()
    val paragraph = mutableListOf<String>()
    fun endParagraph() {
        if (paragraph.isNotEmpty()) blocks += LicenceBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    for (line in text.lineSequence().map { it.trimEnd('\r') }) {
        when {
            line.startsWith("## ") -> {
                endParagraph()
                blocks += LicenceBlock.Heading(1, line.removePrefix("## ").trim())
            }
            line.startsWith("### ") -> {
                endParagraph()
                blocks += LicenceBlock.Heading(2, line.removePrefix("### ").trim())
            }
            line.isBlank() -> endParagraph()
            else -> paragraph += line
        }
    }
    endParagraph()
    return blocks
}
