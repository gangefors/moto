// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/** A piece of help text: words, or the icon of a button they refer to. */
sealed interface HelpPart {
    data class Words(val text: String) : HelpPart
    data class Icon(val key: String) : HelpPart
}

/**
 * [text] split into words and icons: "[key]" stands for the icon named
 * key when it is one of [icons], so the rider sees the very button the
 * text means. Anything else in brackets stays as it is written.
 */
fun helpParts(text: String, icons: Set<String>): List<HelpPart> {
    val parts = mutableListOf<HelpPart>()
    val words = StringBuilder()
    var i = 0
    while (i < text.length) {
        val close = if (text[i] == '[') text.indexOf(']', i + 1) else -1
        val key = if (close > i) text.substring(i + 1, close) else null
        if (key != null && key in icons) {
            if (words.isNotEmpty()) parts += HelpPart.Words(words.toString())
            words.clear()
            parts += HelpPart.Icon(key)
            i = close + 1
        } else {
            words.append(text[i])
            i++
        }
    }
    if (words.isNotEmpty()) parts += HelpPart.Words(words.toString())
    return parts
}

/**
 * [parts] as words only, for a screen reader or a plain label: each icon
 * is the words for its button from [spoken].
 */
fun helpWords(parts: List<HelpPart>, spoken: Map<String, String>): String =
    parts.joinToString("") {
        when (it) {
            is HelpPart.Words -> it.text
            is HelpPart.Icon -> spoken[it.key] ?: it.key
        }
    }

/** A block of text with icons: a paragraph, or a list of terms. */
sealed interface TextBlock {
    data class Paragraph(val text: String) : TextBlock

    /** Terms and what each means, one per line: "Avoid" – "stay off …". */
    data class Terms(val rows: List<Pair<String, String>>) : TextBlock
}

private val TERM_LINE = Regex("""^\*\*(.+?)\*\*\s+(.+)$""")

/**
 * [text] as blocks: lines of the form "**Term** what it means" that follow
 * each other make a list of terms, the choices standing out from the
 * text around them; other lines are paragraphs.
 */
fun textBlocks(text: String): List<TextBlock> {
    val blocks = mutableListOf<TextBlock>()
    val terms = mutableListOf<Pair<String, String>>()
    fun flush() {
        if (terms.isNotEmpty()) blocks += TextBlock.Terms(terms.toList())
        terms.clear()
    }
    for (line in text.split('\n')) {
        val m = TERM_LINE.find(line.trim())
        if (m != null) {
            terms += m.groupValues[1] to m.groupValues[2]
        } else {
            flush()
            if (line.isNotBlank()) blocks += TextBlock.Paragraph(line.trim())
        }
    }
    flush()
    return blocks
}
