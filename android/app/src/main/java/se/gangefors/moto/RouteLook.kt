// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

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
