// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.CorrectionAt
import se.gangefors.moto.core.FavouriteSurface
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.MapSurface
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionStatus
import se.gangefors.moto.core.Surface
import se.gangefors.moto.core.SurfaceCorrection
import se.gangefors.moto.core.WaySpan

/**
 * Surface corrections (ADR-0018), the app's side: pure logic, so it can
 * be unit tested. The core stores them and routes by them; the map never
 * draws them.
 */

/** What "mark" mode marks: a favourite section, or a surface correction. */
enum class MarkKind { FAVOURITE, SURFACE }

/** The surfaces a correction offers, in their order: Paved | Gravel. */
val SURFACE_CHOICES: List<Surface> = listOf(Surface.PAVED, Surface.GRAVEL)

/** The surfaces a favourite's edit sheet offers: Map | Paved | Gravel. */
val FAVOURITE_SURFACES: List<FavouriteSurface> = listOf(FavouriteSurface.MAP, FavouriteSurface.PAVED, FavouriteSurface.GRAVEL)

/** The choice a correction's marking starts on: an existing correction's
 * own surface when it is edited, else none, so the rider always picks. */
fun firstSurfaceChoice(existing: SurfaceCorrection?): Surface? = existing?.surface?.takeIf { it in SURFACE_CHOICES }

/** Where a correction row's pencil leads. */
sealed interface SurfaceEdit {
    /** The correction's own marking. */
    data class Standalone(val correction: SurfaceCorrection) : SurfaceEdit

    /** The edit sheet of the favourite whose own surface it is. */
    data class Favourite(val sectionId: Long) : SurfaceEdit
}

/** The last row of the road card: the Correct surface button, or "Your
 * correction: Gravel" with its pencil and bin. */
sealed interface SurfaceRow {
    data object Correct : SurfaceRow

    data class Corrected(val surface: Surface, val edit: SurfaceEdit) : SurfaceRow
}

fun surfaceRow(correction: CorrectionAt?): SurfaceRow {
    if (correction == null) return SurfaceRow.Correct
    val standalone = correction.correction
    val favourite = correction.favouriteId
    return when {
        standalone != null -> SurfaceRow.Corrected(correction.surface, SurfaceEdit.Standalone(standalone))
        favourite != null -> SurfaceRow.Corrected(correction.surface, SurfaceEdit.Favourite(favourite))
        else -> SurfaceRow.Correct
    }
}

/** The name of a correction's surface. */
fun surfaceChoiceName(surface: Surface): Int = if (surface == Surface.GRAVEL) R.string.surface_gravel else R.string.surface_paved

/** The name of a choice in a favourite's Surface row. */
fun favouriteSurfaceName(s: FavouriteSurface): Int = when (s) {
    FavouriteSurface.MAP -> R.string.surface_map
    FavouriteSurface.PAVED -> R.string.surface_paved
    FavouriteSurface.GRAVEL -> R.string.surface_gravel
}

/** A favourite's Surface row starts on its own surface, or on Map. */
fun favouriteSurfaceOf(section: Section): FavouriteSurface = when (section.surface) {
    Surface.PAVED -> FavouriteSurface.PAVED
    Surface.GRAVEL -> FavouriteSurface.GRAVEL
    else -> FavouriteSurface.MAP
}

/** What the map has along a favourite, for "On the map: …". */
fun mapSurfaceText(s: MapSurface): Int = when (s) {
    MapSurface.PAVED -> R.string.surface_paved
    MapSurface.GRAVEL -> R.string.surface_gravel
    MapSurface.UNTAGGED -> R.string.surface_unknown
}

/** The line a favourite's "Change start and end" starts from: its own
 * ways and line when it fits the map, else none (it is marked afresh
 * between its ends). */
fun reshapeStart(section: Section): Pair<List<WaySpan>, List<LatLon>>? =
    if (section.status == SectionStatus.OK && section.geometry.size >= 2) section.ways to section.geometry else null

/** A favourite's start and end being changed: the favourite, and the
 * choices made in its edit sheet so far, kept for when it reopens. */
data class Reshape(val section: Section, val choice: SectionChoice)

/** X or Back while changing a favourite's start and end: the edit sheet
 * again, with the old line and the choices kept. */
fun reshapeBack(r: Reshape): Pair<Section, SectionChoice> = r.section to r.choice
