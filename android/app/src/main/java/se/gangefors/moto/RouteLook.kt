// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/**
 * How a route marks its stretches on favourite sections. The route itself
 * stays blue all the way, so no saved section (amber, orange, purple) can
 * be taken for it: a thin purple stripe along its middle, or a purple glow
 * around it.
 */
enum class FavouriteMark(val key: String) {
    STRIPE("stripe"),
    GLOW("glow"),
}

val DEFAULT_FAVOURITE_MARK = FavouriteMark.STRIPE

/** A stored mark, or the default when it isn't one (preferences are read
 * back as untrusted input). */
fun favouriteMarkOf(stored: String?): FavouriteMark =
    FavouriteMark.entries.firstOrNull { it.key == stored } ?: DEFAULT_FAVOURITE_MARK

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
fun sectionLook(routeShown: Boolean): SectionLook =
    if (routeShown) {
        SectionLook(lineWidth = 3f, lineOpacity = 0.5f, casingOpacity = 0f, arrowOpacity = 0.5f)
    } else {
        SectionLook(lineWidth = 5f, lineOpacity = 1f, casingOpacity = 0.8f, arrowOpacity = 1f)
    }
