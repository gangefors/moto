// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.FavouriteSurface
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.NewSection
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionDraft
import se.gangefors.moto.core.SectionSource
import se.gangefors.moto.core.SectionUpdate
import se.gangefors.moto.core.Surface
import se.gangefors.moto.core.Tag
import se.gangefors.moto.core.WaySpan

/**
 * What the favourite sheet is about (ADR-0020): a new favourite (the
 * marked draft, and the quick-tag it came from, if any) or a saved one
 * (the section, and the line its ends were moved to, if any). All that
 * differs between the two is derived from it by the functions below.
 */
sealed interface SheetSubject {
    data class New(val draft: SectionDraft, val tag: Tag?) : SheetSubject
    data class Saved(val section: Section, val newLine: SectionDraft?) : SheetSubject
}

/** The sheet's subject, or none when no sheet is open. A saved
 * favourite's edit wins over a draft; [pending] is used for the favourite
 * being edited only. */
fun sheetSubject(
    savingDraft: Boolean,
    draft: SectionDraft?,
    reviewTag: Tag?,
    editing: Section?,
    pending: PendingEdit?,
): SheetSubject? = when {
    editing != null -> SheetSubject.Saved(editing, pendingFor(pending, editing.id)?.line)
    savingDraft && draft != null -> SheetSubject.New(draft, reviewTag)
    else -> null
}

/** The sheet's title (a string resource taking the length in km). */
fun sheetTitleRes(subject: SheetSubject): Int = when (subject) {
    is SheetSubject.New -> R.string.section_new_title
    is SheetSubject.Saved -> R.string.section_edit_title
}

/** The length in metres the title shows: the draft, a saved favourite's
 * moved line, or its saved line. */
fun sheetLengthM(subject: SheetSubject): Double = when (subject) {
    is SheetSubject.New -> subject.draft.distanceM
    is SheetSubject.Saved -> subject.newLine?.distanceM ?: lengthM(subject.section.geometry)
}

/** Only a saved favourite has anything to delete. */
fun canDelete(subject: SheetSubject): Boolean = subject is SheetSubject.Saved

/** The line Save would store: the draft, the moved line, else the saved one. */
fun sheetLine(subject: SheetSubject): List<LatLon> = when (subject) {
    is SheetSubject.New -> subject.draft.geometry
    is SheetSubject.Saved -> subject.newLine?.geometry ?: subject.section.geometry
}

/** The ways to ask the map about for "On the map: …": those of the line
 * Save would store; a saved favourite not moved and not fitting the map
 * has none. */
fun sheetMapWays(subject: SheetSubject): List<WaySpan>? = when (subject) {
    is SheetSubject.New -> subject.draft.ways
    is SheetSubject.Saved -> subject.newLine?.ways ?: subject.section.ways.takeIf { fitsTheMap(subject.section.status) }
}

/**
 * The choices the sheet opens with. [kept] are those kept across "Change
 * start and end" (the new favourite's, or the pending edit's), which win;
 * else a new favourite starts on the defaults and a saved one on its saved
 * values.
 */
fun sheetFirstChoice(subject: SheetSubject, kept: SectionChoice?): SectionChoice = kept ?: when (subject) {
    is SheetSubject.New -> SectionChoice(rating = Rating.GOOD, oneWay = false)
    is SheetSubject.Saved -> SectionChoice(
        subject.section.rating,
        isOneWay(subject.section.direction),
        riderName(subject.section.name) ?: "",
        surface = favouriteSurfaceOf(subject.section),
    )
}

/** The write Save makes. */
sealed interface SheetWrite {
    data class Add(val section: NewSection) : SheetWrite
    data class Update(val id: Long, val update: SectionUpdate) : SheetWrite
}

/** What Save stores for [choice]: a new favourite (named [autoName] when
 * the rider typed none; turned round only when one-way) or the changes
 * to a saved one. */
fun sheetWrite(subject: SheetSubject, choice: SectionChoice, autoName: String): SheetWrite = when (subject) {
    is SheetSubject.New -> SheetWrite.Add(
        NewSection(
            name = sectionNameToStore(choice.name).ifEmpty { autoName },
            rating = choice.rating,
            direction = directionOf(choice.oneWay),
            source = if (subject.tag != null) SectionSource.TAG else SectionSource.MAP,
            ways = subject.draft.ways,
            geometry = subject.draft.geometry,
            surface = when (choice.surface) {
                FavouriteSurface.MAP -> null
                FavouriteSurface.PAVED -> Surface.PAVED
                FavouriteSurface.GRAVEL -> Surface.GRAVEL
            },
            reverse = choice.oneWay && choice.reverse,
        ),
    )
    is SheetSubject.Saved -> SheetWrite.Update(
        subject.section.id,
        SectionUpdate(
            name = nameUpdate(subject.section.name, choice.name),
            rating = choice.rating,
            direction = directionOf(choice.oneWay),
            reverse = choice.reverse,
            line = subject.newLine,
            surface = choice.surface,
        ),
    )
}

/** The draft line to draw while a new favourite's sheet is open: turned
 * round and with arrows as the sheet's [preview] (one-way, turn round)
 * says; as it is, without arrows, when no sheet is open. */
fun draftPreviewLine(
    line: List<LatLon>,
    sheetOpen: Boolean,
    preview: Pair<Boolean, Boolean>?,
): Pair<List<LatLon>, Boolean> =
    if (!sheetOpen || preview == null) {
        line to false
    } else {
        val (oneWay, turn) = preview
        (if (oneWay && turn) line.asReversed() else line) to oneWay
    }
