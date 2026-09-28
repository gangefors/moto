// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import se.gangefors.moto.core.Description

/*
 * Sections, routes and rides in words the rider recognises, from the
 * core's description of a line (ADR-0005, format 1.2): the places it runs
 * between ("Höör → Sjöbo") and the roads it runs on ("Road 13 ·
 * Storgatan"). A region file without names describes nothing; callers
 * then fall back to their own labels.
 */

/** A road number that is only digits reads as "Road 13"; others (E22)
 * as they are. */
fun isPlainRoadNumber(ref: String): Boolean = ref.isNotEmpty() && ref.all { it.isDigit() }

/** Where a line runs, from the places at its ends. */
sealed interface PlaceSpan {
    /** From one place to another: "Höör → Sjöbo". */
    data class Between(val from: String, val to: String) : PlaceSpan

    /** Both ends by one place, or only one end named: "Near Höör". */
    data class Near(val place: String) : PlaceSpan
}

/** The places of [d], or null when neither end is near a named place. */
fun placeSpan(d: Description?): PlaceSpan? {
    val from = d?.start?.name
    val to = d?.end?.name
    return when {
        from != null && to != null && from != to -> PlaceSpan.Between(from, to)
        from != null -> PlaceSpan.Near(from)
        to != null -> PlaceSpan.Near(to)
        else -> null
    }
}

/** A road by number and name, as parts to put into words. */
data class RoadWords(val number: String?, val name: String?)

/** The roads of [d] worth naming, each once: the main one's number and
 * name, then the second road's number, or its name when it has none. */
fun roadWords(d: Description?): List<RoadWords> {
    val roads = d?.roads.orEmpty()
    return roads.mapIndexed { i, r ->
        if (i == 0) RoadWords(r.roadRef, r.name) else RoadWords(r.roadRef, r.name.takeIf { r.roadRef == null })
    }.filter { it.number != null || it.name != null }.distinct()
}

/** "Höör → Sjöbo" or "Near Höör"; null when there are no places. */
@Composable
fun placeText(d: Description?): String? = when (val s = placeSpan(d)) {
    is PlaceSpan.Between -> stringResource(R.string.place_between, s.from, s.to)
    is PlaceSpan.Near -> stringResource(R.string.place_near, s.place)
    null -> null
}

/** "Road 13 · Storgatan", "E22", "Kvärnbyvägen", or two roads joined:
 * "Road 11 + Road 1119". Null when no road is named. */
@Composable
fun roadText(d: Description?): String? = roadWords(d)
    .map { roadText(it.number, it.name) }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" + ")

/** One road: its number ("Road 13", "E22") and name, either or both. */
@Composable
fun roadText(number: String?, name: String?): String {
    val n = number?.let { if (isPlainRoadNumber(it)) stringResource(R.string.road_number, it) else it }
    return listOfNotNull(n, name).joinToString(" · ")
}
