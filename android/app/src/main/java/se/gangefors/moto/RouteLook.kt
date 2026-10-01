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

/** Zooms the map's dashed lines (ridden roads, gravel on the route) are
 * set for, one by one: each zoom gets its own dash and gap, so they keep
 * their look as the map zooms. */
val DASH_ZOOMS: IntRange = 8..16

/** Width of the ridden roads' line at [zoom], dp, as the map draws it
 * from [RIDDEN_WIDTHS]. */
fun riddenWidth(zoom: Float): Float {
    val (z0, w0) = RIDDEN_WIDTHS.first()
    if (zoom <= z0) return w0
    for ((a, b) in RIDDEN_WIDTHS.zipWithNext()) {
        if (zoom <= b.first) return a.second + (b.second - a.second) * (zoom - a.first) / (b.first - a.first)
    }
    return RIDDEN_WIDTHS.last().second
}

/** A dashed line's dash and gap on screen at [zoom], dp: short when
 * zoomed out, so they stay dashes instead of merging into a line, and a
 * little longer each zoom in (5.5 and 4.5 dp at zoom 8, 12.7 and 9.3 at
 * zoom 16). */
fun dashDp(zoom: Int): Pair<Float, Float> = (5.5f + 0.9f * (zoom - 8)) to (4.5f + 0.6f * (zoom - 8))

/** The map's dash array at [zoom] for a line [width] dp wide: [dashDp]
 * in line widths, which is how the map measures dashes. */
fun dashArray(zoom: Int, width: Float): Array<Float> {
    val (dash, gap) = dashDp(zoom)
    return arrayOf(dash / width, gap / width)
}

/** The ridden roads' dash array at [zoom]. */
fun riddenDashes(zoom: Int): Array<Float> = dashArray(zoom, riddenWidth(zoom.toFloat()))

/** Width of the white gravel dashes along routes and sections, dp. */
const val GRAVEL_DASH_WIDTH = 2f

/** The gravel dashes' dash array at [zoom]: as long as the ridden roads'. */
fun gravelDashes(zoom: Int): Array<Float> = dashArray(zoom, GRAVEL_DASH_WIDTH)

/** Above this many metres across the screen the ridden roads are hidden
 * (Stefan: 70 km). */
const val RIDDEN_MAX_SPAN_M = 70_000.0

/** The sections the map leaves out of [all]: those not in [shown]. The
 * ridden roads run on under them. */
fun hiddenSectionIds(all: List<Long>, shown: List<Long>): List<Long> {
    val drawn = shown.toSet()
    return all.filterNot { it in drawn }
}
