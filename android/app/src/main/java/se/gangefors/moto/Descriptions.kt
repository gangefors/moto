// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.LatLon

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

/** What to call a saved route or loop, from where it runs. */
sealed interface PlanName {
    /** A route between two places: "Lund → Höör". */
    data class Between(val from: String, val to: String) : PlanName

    /** A loop from a place out to another: "Loop from Lund via Höör". */
    data class LoopVia(val from: String, val via: String) : PlanName

    /** A loop that stays by its start: "Loop from Lund". */
    data class LoopFrom(val from: String) : PlanName

    /** A route without places at both ends, along a road: "Along Road 13". */
    data class Along(val road: RoadWords) : PlanName
}

/**
 * A name for a route or loop ([isLoop]) described as [d]; for a loop,
 * [farthest] describes the point farthest from its start (the place it
 * goes out to). Null when there is nothing to name it by.
 */
fun planName(isLoop: Boolean, d: Description?, farthest: Description?): PlanName? {
    val from = d?.start?.name
    if (isLoop) {
        from ?: return null
        val via = farthest?.start?.name
        return if (via != null && via != from) PlanName.LoopVia(from, via) else PlanName.LoopFrom(from)
    }
    val to = d?.end?.name
    if (from != null && to != null && from != to) return PlanName.Between(from, to)
    return roadWords(d).firstOrNull()?.let { PlanName.Along(it) }
}

/** The point of [line] farthest from its first, or null for an empty line. */
fun farthestPoint(line: List<LatLon>): LatLon? {
    val start = line.firstOrNull() ?: return null
    return line.maxByOrNull { approxDistanceM(start, it) }
}

/** [name] in words. */
fun planNameText(res: android.content.res.Resources, name: PlanName): String = when (name) {
    is PlanName.Between -> res.getString(R.string.place_between, name.from, name.to)
    is PlanName.LoopVia -> res.getString(R.string.loop_name_via, name.from, name.via)
    is PlanName.LoopFrom -> res.getString(R.string.loop_name_from, name.from)
    is PlanName.Along -> res.getString(
        R.string.route_name_along,
        listOfNotNull(
            name.road.number?.let { if (isPlainRoadNumber(it)) res.getString(R.string.road_number, it) else it },
            name.road.name,
        ).joinToString(" · "),
    )
}
