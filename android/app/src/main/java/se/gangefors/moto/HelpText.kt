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
 * is its button's name from [names].
 */
fun helpWords(parts: List<HelpPart>, names: Map<String, String>): String =
    parts.joinToString("") {
        when (it) {
            is HelpPart.Words -> it.text
            is HelpPart.Icon -> names[it.key] ?: it.key
        }
    }
