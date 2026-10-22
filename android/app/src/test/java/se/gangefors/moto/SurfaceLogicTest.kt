// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.CorrectionAt
import se.gangefors.moto.core.Direction
import se.gangefors.moto.core.FavouriteSurface
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.MapSurface
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.RestoreReport
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionSource
import se.gangefors.moto.core.SectionStatus
import se.gangefors.moto.core.Surface
import se.gangefors.moto.core.SurfaceCorrection
import se.gangefors.moto.core.WaySpan

class SurfaceLogicTest {
    private val line = listOf(LatLon(55.7, 13.2), LatLon(55.71, 13.2))
    private val ways = listOf(WaySpan(42, 0u, 3u))

    private fun correction(surface: Surface) =
        SurfaceCorrection(7, surface, SectionStatus.OK, 0, 0, ways, line)

    private fun section(status: SectionStatus = SectionStatus.OK, surface: Surface? = null) =
        Section(3, "local", "", Rating.GOOD, Direction.FORWARD, SectionSource.MAP, status, 0, 0, ways, line, surface)

    @Test
    fun aNewCorrectionStartsWithNoSurfacePicked() {
        assertNull(firstSurfaceChoice(null))
        assertEquals(Surface.GRAVEL, firstSurfaceChoice(correction(Surface.GRAVEL)))
        assertEquals(Surface.PAVED, firstSurfaceChoice(correction(Surface.PAVED)))
        assertEquals(listOf(Surface.PAVED, Surface.GRAVEL), SURFACE_CHOICES)
    }

    @Test
    fun theRoadCardsLastRow() {
        assertEquals(SurfaceRow.Correct, surfaceRow(null))
        val c = correction(Surface.GRAVEL)
        assertEquals(
            SurfaceRow.Corrected(Surface.GRAVEL, SurfaceEdit.Standalone(c)),
            surfaceRow(CorrectionAt(Surface.GRAVEL, c, null)),
        )
        assertEquals(
            SurfaceRow.Corrected(Surface.PAVED, SurfaceEdit.Favourite(3)),
            surfaceRow(CorrectionAt(Surface.PAVED, null, 3)),
        )
        assertEquals(R.string.surface_gravel, surfaceChoiceName(Surface.GRAVEL))
        assertEquals(R.string.surface_paved, surfaceChoiceName(Surface.PAVED))
    }

    @Test
    fun theFavouriteSheetsSurfaceRow() {
        assertEquals(listOf(FavouriteSurface.MAP, FavouriteSurface.PAVED, FavouriteSurface.GRAVEL), FAVOURITE_SURFACES)
        assertEquals(FavouriteSurface.MAP, favouriteSurfaceOf(section()))
        assertEquals(FavouriteSurface.GRAVEL, favouriteSurfaceOf(section(surface = Surface.GRAVEL)))
        assertEquals(FavouriteSurface.PAVED, favouriteSurfaceOf(section(surface = Surface.PAVED)))
        assertEquals(R.string.surface_map, favouriteSurfaceName(FavouriteSurface.MAP))
        assertEquals(R.string.surface_paved, mapSurfaceText(MapSurface.PAVED))
        assertEquals(R.string.surface_gravel, mapSurfaceText(MapSurface.GRAVEL))
        assertEquals(R.string.surface_unknown, mapSurfaceText(MapSurface.UNTAGGED))
        // The sheet's choice starts on Map.
        assertEquals(FavouriteSurface.MAP, SectionChoice(Rating.GOOD, false).surface)
    }

    @Test
    fun changingAFavouritesEndsStartsFromItsOwnLine() {
        assertEquals(ways to line, reshapeStart(section()))
        assertNull(reshapeStart(section(SectionStatus.UNMATCHED)))
        assertNull(reshapeStart(section(SectionStatus.NEEDS_REMATCH)))
        val choice = SectionChoice(Rating.EPIC, true, "x", surface = FavouriteSurface.GRAVEL)
        assertEquals(section() to choice, reshapeBack(Reshape(section(), choice)))
    }

    @Test
    fun aPendingEditBelongsToItsFavouriteOnly() {
        val draft = se.gangefors.moto.core.SectionDraft(ways, line, 1000.0)
        val p = PendingEdit(3, SectionChoice(Rating.EPIC, true), draft)
        assertEquals(p, pendingFor(p, 3))
        assertNull(pendingFor(p, 4))
        assertNull(pendingFor(p, null))
        assertNull(pendingFor(null, 3))
        // Left out of the layer: the one being reshaped, and the one whose
        // sheet holds a new line.
        assertEquals(setOf(5L), favouritesDrawnElsewhere(5, null, p))
        assertEquals(setOf(3L), favouritesDrawnElsewhere(null, 3, p))
        assertEquals(emptySet<Long>(), favouritesDrawnElsewhere(null, 4, p))
        assertEquals(emptySet<Long>(), favouritesDrawnElsewhere(null, 3, p.copy(line = null)))
    }

    @Test
    fun thePlanSheetStepsAsideWhileMarking() {
        assertTrue(planSheetShown(planning = true, marking = false))
        assertFalse(planSheetShown(planning = true, marking = true))
        assertFalse(planSheetShown(planning = false, marking = false))
        assertFalse(planSheetShown(planning = false, marking = true))
    }

    @Test
    fun backWhileChangingAFavouritesEndsGoesBackToItsSheet() {
        val none = BooleanArray(10)
        fun step(marking: Boolean, reshaping: Boolean) = backStep(
            marking, none[0], none[1], none[2], none[3], none[4], none[5], none[6], none[7], none[8], none[9],
            reshaping = reshaping,
        )
        assertEquals(BackStep.RESHAPE, step(marking = true, reshaping = true))
        assertEquals(BackStep.MARK, step(marking = true, reshaping = false))
        assertNull(step(marking = false, reshaping = true))
    }

    @Test
    fun restoredCorrectionsAreCountedLikeFavourites() {
        val r = RestoreReport(
            favouritesAdded = 1u,
            favouritesSkipped = 0u,
            favouritesReplaced = 0u,
            ridesAdded = 0u,
            ridesSkipped = 0u,
            routesAdded = 0u,
            routesSkipped = 0u,
            tagsAdded = 0u,
            tagsSkipped = 0u,
            tagsOnFavourites = 0u,
            surfaceAdded = 3u,
            surfaceSkipped = 2u,
            settings = null,
            regions = emptyList(),
            regionsDisabled = emptyList(),
        )
        val c = restoreCounts(r)
        assertEquals(3, c.surfaceCorrections)
        assertEquals(4, c.added)
        assertEquals(2, c.alreadyHere)
    }

    /** The app's English strings, as written. */
    private fun string(name: String): String {
        val xml = File("src/main/res/values/strings.xml").readText()
        val m = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
        return requireNotNull(m) { name }.groupValues[1]
    }

    @Test
    fun theHowToShowsTheGravelIconInline() {
        val parts = helpParts(string("help_map"), BUTTON_ICONS.keys)
        assertTrue(parts.toString(), HelpPart.Icon("gravel") in parts)
        assertTrue(string("help_sections").contains("Map, Paved or Gravel"))
        val sections = helpParts(string("help_sections"), BUTTON_ICONS.keys)
        assertTrue(sections.toString(), HelpPart.Icon("edit") in sections)
    }

    @Test
    fun theNewStringsSayFavouriteSectionsNotSectionAlone() {
        for (name in listOf("surface_proposed", "surface_saved", "surface_corrected", "section_reshape", "section_proposed", "backup_text")) {
            val text = string(name).lowercase()
            val alone = Regex("""\bsections?\b""").findAll(text).count()
            val favourite = Regex("""favourite sections?\b""").findAll(text).count()
            assertEquals(name, favourite, alone)
        }
    }
}
