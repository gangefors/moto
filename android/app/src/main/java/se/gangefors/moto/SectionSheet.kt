// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.Rating

/** What the rider chose in the section sheet. */
data class SectionChoice(val rating: Rating, val oneWay: Boolean, val name: String = "", val reverse: Boolean = false)

/**
 * A bottom sheet to rate a section and set its direction: for a new one
 * before it is saved, or a saved one (then [onDelete] is set, shown as a
 * bin on the left that asks for a second tap). X, Back or a swipe down
 * leave it without saving. The name is the rider's own and may stay
 * empty: the section then goes by where it runs ([suggestion], shown in
 * the empty field). A saved one-way section can be turned round
 * ([canReverse]): the swap toggle beside the switch, applied on Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionSheet(
    title: String,
    initial: SectionChoice,
    onSave: (SectionChoice) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    suggestion: String? = null,
    canReverse: Boolean = false,
) {
    // A text field state (kept over rotation), for the field's label on
    // the border; the name is what it holds.
    val nameState = rememberTextFieldState(initial.name)
    val name = nameState.text.toString()
    var rating by remember { mutableStateOf(initial.rating) }
    var oneWay by remember { mutableStateOf(initial.oneWay) }
    var reverse by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 16.dp)) {
                // The title, and X to leave without saving (as do Back and a
                // swipe down).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.task_close))
                    }
                }
                // The choices scroll when large text makes them taller than
                // the screen; the buttons below stay in reach.
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .scrollHints(scroll)
                        .verticalScroll(scroll)
                        .padding(end = 12.dp),
                ) {
                    OutlinedTextField(
                        state = nameState,
                        inputTransformation = InputTransformation.maxLength(MAX_ROUTE_NAME_CHARS * 2),
                        label = { Text(stringResource(R.string.section_name)) },
                        // The label stays on the border, so the suggestion
                        // shows in the empty field from the start.
                        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
                        // The suggestion is faded and slanted, so an empty
                        // field doesn't look named; X clears a name in one tap.
                        placeholder = suggestion?.let {
                            {
                                Text(
                                    it,
                                    maxLines = 1,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        },
                        trailingIcon = if (name.isEmpty()) null else {
                            {
                                IconButton(onClick = { nameState.clearText() }) {
                                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.section_name_clear))
                                }
                            }
                        },
                        supportingText = { Text(stringResource(R.string.section_name_hint)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        onKeyboardAction = { focus.clearFocus() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OptionHeading(stringResource(R.string.section_rating))
                    SingleChoice(
                        options = RATINGS,
                        selected = rating,
                        label = { stringResource(ratingLabel(it)) },
                        onSelect = { rating = it },
                    )
                    // The whole row flips the switch.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .toggleable(value = oneWay, role = Role.Switch, onValueChange = {
                                oneWay = it
                                if (!it) reverse = false
                            }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The label and (i) take the room left; the switch
                        // sits at the right edge, in line with Save.
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.section_one_way), modifier = Modifier.weight(1f, fill = false))
                            InfoButton(stringResource(R.string.section_one_way), stringResource(R.string.section_one_way_hint))
                        }
                        // Turn a one-way section round (on Save), for one
                        // marked from the wrong end.
                        if (canReverse && oneWay) {
                            IconToggleButton(
                                checked = reverse,
                                onCheckedChange = { reverse = it },
                                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(painterResource(R.drawable.ic_swap), stringResource(R.string.section_reverse))
                            }
                        }
                        Switch(checked = oneWay, onCheckedChange = null)
                    }
                }
                // The bin (a saved section) on the left, Save on the right.
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onDelete != null) {
                        DeleteButton(
                            confirming = confirmDelete,
                            onArm = { confirmDelete = true },
                            onDelete = onDelete,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(SectionChoice(rating, oneWay, name, reverse = oneWay && reverse)) }) {
                        OneLine(stringResource(R.string.section_save))
                    }
                }
            }
            // The bin's toasts, inside the sheet (its own window).
            ToastHost(Modifier.align(Alignment.BottomCenter))
        }
    }
}

fun ratingLabel(rating: Rating): Int = when (rating) {
    Rating.GOOD -> R.string.rating_good
    Rating.GREAT -> R.string.rating_great
    Rating.EPIC -> R.string.rating_epic
}

