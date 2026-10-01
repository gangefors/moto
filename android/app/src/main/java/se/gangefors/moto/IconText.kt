// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The buttons text may show, by the key it names them with ("[mark]"):
 * the button's own icon, drawn in the text's colour so it reads as well
 * as the words, and what a screen reader says in its place, words that
 * fit the sentence ("the bin"). TAG is drawn as the button itself, a
 * label on orange. [then] is an arrow between menu steps, read "then".
 */
internal class ButtonIcon(val drawable: Int?, val spoken: Int)

internal val BUTTON_ICONS = mapOf(
    "menu" to ButtonIcon(R.drawable.ic_menu, R.string.say_menu),
    "location" to ButtonIcon(R.drawable.ic_my_location, R.string.say_location),
    "loop" to ButtonIcon(R.drawable.ic_loop, R.string.say_loop),
    "directions" to ButtonIcon(R.drawable.ic_directions, R.string.say_directions),
    "share" to ButtonIcon(R.drawable.ic_share, R.string.say_share),
    "save" to ButtonIcon(R.drawable.ic_bookmark, R.string.say_save),
    "mark" to ButtonIcon(R.drawable.ic_add_road, R.string.say_mark),
    "sections" to ButtonIcon(R.drawable.ic_star, R.string.say_sections),
    "record" to ButtonIcon(R.drawable.ic_record_dot, R.string.say_record),
    "tag" to ButtonIcon(null, R.string.say_tag),
    "flag" to ButtonIcon(R.drawable.ic_flag, R.string.say_flag),
    "settings" to ButtonIcon(R.drawable.ic_settings, R.string.say_settings),
    "bin" to ButtonIcon(R.drawable.ic_delete, R.string.say_bin),
    "ride" to ButtonIcon(R.drawable.ic_ride, R.string.say_ride),
    "motorway" to ButtonIcon(R.drawable.ic_motorway, R.string.say_motorway),
    "ferry" to ButtonIcon(R.drawable.ic_ferry, R.string.say_ferry),
    "toll" to ButtonIcon(R.drawable.ic_toll, R.string.say_toll),
    "time" to ButtonIcon(R.drawable.ic_time, R.string.say_time),
    "curvy" to ButtonIcon(R.drawable.ic_curvy, R.string.say_curvy),
    "gravel" to ButtonIcon(R.drawable.ic_gravel, R.string.say_gravel),
    "clock" to ButtonIcon(R.drawable.ic_clock, R.string.say_clock),
    "pin" to ButtonIcon(R.drawable.ic_pin, R.string.say_pin),
    "compass" to ButtonIcon(R.drawable.ic_compass, R.string.say_compass),
    "star_off" to ButtonIcon(R.drawable.ic_star_off, R.string.say_star_off),
    "unridden" to ButtonIcon(R.drawable.ic_unridden, R.string.say_unridden),
)

/**
 * [text] with its "[key]"s drawn as the buttons' icons ([BUTTON_ICONS]),
 * at text size, so text that names a button shows the rider which one.
 * Lines of the form "**Term** what it means" make a list: each term with
 * its meaning lined up beside it ([textBlocks]). The terms stay in plain
 * type: the list is enough to set them apart, and bold would look like
 * the setting headings on How to use.
 */
@Composable
fun IconText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val blocks = textBlocks(text)
    val single = blocks.singleOrNull()
    if (single is TextBlock.Paragraph || blocks.isEmpty()) {
        IconLine(text.trim(), modifier, style)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is TextBlock.Paragraph -> IconLine(block.text, style = style)
                is TextBlock.Terms -> TermList(block.rows, style)
            }
        }
    }
}

/**
 * Terms with what each means beside them, the meanings lined up after
 * the widest term and wrapping under themselves.
 */
@Composable
private fun TermList(rows: List<Pair<String, String>>, style: TextStyle) {
    Layout(
        content = {
            for ((term, meaning) in rows) {
                Text(term, style = style)
                IconLine(meaning, style = style)
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val rowGap = 2.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val terms = measurables.filterIndexed { i, _ -> i % 2 == 0 }.map { it.measure(loose) }
        val termWidth = terms.maxOf { it.width }
        val meaningWidth = (constraints.maxWidth - termWidth - gap).coerceAtLeast(0)
        val meanings = measurables.filterIndexed { i, _ -> i % 2 == 1 }
            .map { it.measure(loose.copy(maxWidth = meaningWidth)) }
        val heights = terms.indices.map { maxOf(terms[it].height, meanings[it].height) }
        val height = heights.sum() + rowGap * (heights.size - 1).coerceAtLeast(0)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else termWidth + gap + meanings.maxOf { it.width }
        layout(width, height) {
            var y = 0
            for (i in terms.indices) {
                terms[i].placeRelative(0, y)
                meanings[i].placeRelative(termWidth + gap, y)
                y += heights[i] + rowGap
            }
        }
    }
}

/** One paragraph of [IconText]: words with the buttons' icons inline. */
@Composable
private fun IconLine(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val parts = helpParts(text, BUTTON_ICONS.keys)
    val spoken = BUTTON_ICONS.mapValues { stringResource(it.value.spoken) }
    val annotated = buildAnnotatedString {
        for (part in parts) {
            when (part) {
                is HelpPart.Words -> append(part.text)
                is HelpPart.Icon -> appendInlineContent(part.key, spoken.getValue(part.key))
            }
        }
    }
    val inline = BUTTON_ICONS.mapValues { (key, icon) ->
        val width = if (icon.drawable == null) 2.6.em else 1.3.em
        InlineTextContent(Placeholder(width, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            if (icon.drawable != null) {
                Icon(
                    painterResource(icon.drawable),
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (key == "tag") {
                Box(
                    Modifier.fillMaxSize().background(TAG_COLOR, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.tag_button), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    Text(annotated, modifier, style = style, inlineContent = inline)
}


/**
 * [text] as a screen reader would say it, for a spoken label: each
 * "[key]" becomes the words for its button ("the location button").
 */
@Composable
fun iconTextWords(text: String): String {
    val spoken = BUTTON_ICONS.mapValues { stringResource(it.value.spoken) }
    return helpWords(helpParts(text, BUTTON_ICONS.keys), spoken)
}
