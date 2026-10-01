// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating

/** How saved sections are drawn: line width (dp) and opacity, and the
 * opacity of their white casing and one-way arrows. */
data class SectionLook(
    val lineWidth: Float,
    val lineOpacity: Float,
    val casingOpacity: Float,
    val arrowOpacity: Float,
)

/** Full strength normally; thin, faded and without casing while a route or
 * loop is shown ([routeShown]), so the route is the one strong line and
 * the sections stay visible as context. A route drawn over a section
 * covers it completely (the route and its casing are wider). */
fun sectionLook(routeShown: Boolean, darkMap: Boolean = false): SectionLook =
    if (routeShown) {
        SectionLook(lineWidth = 3f, lineOpacity = 0.5f, casingOpacity = 0f, arrowOpacity = 0.5f)
    } else {
        SectionLook(lineWidth = 5f, lineOpacity = 1f, casingOpacity = 0.8f * outlineStrength(darkMap), arrowOpacity = 1f)
    }

/**
 * How strong the white outline of routes and sections is drawn (its
 * opacity, as a share): full on the light map, where it lifts the lines
 * off the roads; fainter on the dark map, where full white glares, but
 * still enough to keep the lines apart from the roads under them.
 */
fun outlineStrength(darkMap: Boolean): Float = if (darkMap) 0.45f else 1f

/** How the shown (selected) section is drawn: its line colour and width,
 * the dark edge round it and the white outline outside that. */
data class ShownSectionLook(
    val color: String,
    val lineWidth: Float,
    val edgeWidth: Float,
    val casingWidth: Float,
    val casingOpacity: Float,
)

/**
 * The shown section keeps its rating's colour (grey when it no longer
 * [fits] the map), drawn wider than the others with a dark edge inside
 * the white outline, so it stands out while its rating stays readable;
 * the other sections fade meanwhile (see [sectionLook]).
 */
fun shownSectionLook(rating: Rating, fits: Boolean, darkMap: Boolean = false): ShownSectionLook =
    ShownSectionLook(
        color = if (fits) ratingColor(rating) else UNMATCHED_SECTION_COLOR,
        lineWidth = 7f,
        edgeWidth = 10f,
        casingWidth = 13f,
        casingOpacity = 0.8f * outlineStrength(darkMap),
    )

/** The shown section's dark edge. */
const val SHOWN_SECTION_EDGE_COLOR = "#202124"

/**
 * The favourite stretches of a route with the colour each glows in: its
 * section's rating colour, the same as the section's own line. A stretch
 * without a rating (a core that doesn't say) glows epic purple, as all did
 * before. Stretches of fewer than two points are left out.
 */
fun favouriteGlowColors(parts: List<List<LatLon>>, ratings: List<Rating>): List<Pair<List<LatLon>, String>> =
    parts.mapIndexedNotNull { i, part ->
        if (part.size < 2) null else part to ratingColor(ratings.getOrNull(i) ?: Rating.EPIC)
    }

/** The ridden roads' dashes (ADR-0010): near-black on the light map,
 * off-white on the dark one; no single colour stands out against both
 * the route blue and both maps. */
fun riddenColor(darkMap: Boolean): String = if (darkMap) "#e8eaed" else "#202124"

/** Width of the ridden roads' line by zoom (zoom to dp). */
val RIDDEN_WIDTHS: List<Pair<Float, Float>> = listOf(8f to 1.8f, 12f to 2.4f, 16f to 3.2f)

/** Dashes and gaps in line widths: shorter when zoomed out, so they stay
 * dashes instead of merging into a line, longer from [RIDDEN_LONG_DASH_ZOOM]. */
val RIDDEN_SHORT_DASHES: Array<Float> = arrayOf(3f, 2.5f)
val RIDDEN_LONG_DASHES: Array<Float> = arrayOf(4f, 3f)
const val RIDDEN_LONG_DASH_ZOOM = 11f

/** Above this many metres across the screen the ridden roads are hidden
 * (Stefan: 70 km). */
const val RIDDEN_MAX_SPAN_M = 70_000.0

/** The sections the map leaves out of [all]: those not in [shown]. The
 * ridden roads run on under them. */
fun hiddenSectionIds(all: List<Long>, shown: List<Long>): List<Long> {
    val drawn = shown.toSet()
    return all.filterNot { it in drawn }
}
