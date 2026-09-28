// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.SavedRoute

/** A saved route drawn on the map, and its line. */
data class ShownSavedRoute(val route: SavedRoute, val line: List<LatLon>)

/**
 * Asks for a route's name, starting from [initial]. Save is offered once
 * the name has something in it after cleaning (see [cleanRouteName]).
 */
@Composable
fun RouteNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // The name is selected, ready to type over, with the keyboard up;
    // the keyboard's Done saves.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val text = field.text
    val clean = cleanRouteName(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = if (it.text.length <= MAX_ROUTE_NAME_CHARS * 2) it else it.copy(text = it.text.take(MAX_ROUTE_NAME_CHARS * 2))
                },
                label = { Text(stringResource(R.string.route_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { clean?.let(onSave) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { clean?.let(onSave) }, enabled = clean != null) {
                OneLine(stringResource(R.string.section_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { OneLine(stringResource(R.string.cancel)) } },
    )
}

/** A saved route on the map: its name and figures, Share, and a cross to
 * hide it. */
@Composable
fun SavedRouteCard(
    shown: ShownSavedRoute,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapCard(
        title = shown.route.name,
        supporting = savedRouteSummary(shown.route),
        onClose = onClose,
        closeDescription = stringResource(R.string.saved_route_hide),
        modifier = modifier,
        actions = {
            IconButton(onClick = onShare) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.route_share))
            }
        },
    )
}

@Composable
private fun savedRouteSummary(r: SavedRoute): String =
    stringResource(
        if (r.isLoop) R.string.library_loop else R.string.library_route,
        sectionKm(r.distanceM),
        durationText((r.durationS / 60).toInt()),
    )
