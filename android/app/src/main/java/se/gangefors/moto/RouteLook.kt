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
