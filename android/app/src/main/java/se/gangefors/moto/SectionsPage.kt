// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.core.graphics.toColorInt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Description
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.debug.DebugTools
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.Section
import se.gangefors.moto.core.SectionRidden
import se.gangefors.moto.core.SectionStore

/**
 * The core's descriptions of saved sections, kept while the same region
 * is loaded: a section's geometry never changes, so it is described once.
 */
object SectionDescriptions {
    private val cache = DescriptionCache<Engine, Description>()

    /** The description of [s] on [engine], if found already. */
    fun cached(engine: Engine?, s: Section): Description? = cache.get(engine, s.geometry)

    /** Describes those of [sections] not yet described, and returns all
     * of theirs by line. Call off the main thread. */
    fun describeAll(engine: Engine, sections: List<Section>): Map<List<LatLon>, Description> =
        DebugTools.query("favourite names", { "${sections.size} favourites, ${it.size} described" }) {
            cache.fill(engine, sections.map { it.geometry }) { line -> runCatching { engine.describe(line) }.getOrNull() }
        }
}

/** A section's title: the rider's name for it, else where it runs
 * ("Höör → Sjöbo"), else its road, else its length. */
@Composable
fun sectionTitle(row: SectionRow): String = riderName(row.section.name)
    ?: placeText(row.description)
    ?: roadText(row.description)
    ?: stringResource(R.string.section_fallback, sectionKm(row.lengthM))

/** The line under the title: the road when the title is the places;
 * where it runs and its road when the title is the rider's name. */
@Composable
fun sectionRoadLine(row: SectionRow): String? {
    val road = roadText(row.description)
    val places = placeText(row.description)
    return if (riderName(row.section.name) != null) {
        listOfNotNull(places, road).joinToString(" · ").takeIf { it.isNotEmpty() }
    } else {
        road?.takeIf { places != null }
    }
}

/** What a section would be called without a name of the rider's: where
 * it runs, else its road; null until described. */
@Composable
fun sectionSuggestedName(description: Description?): String? = placeText(description) ?: roadText(description)

/** "Epic · 12.3 km · 35 % curvy · One-way". */
@Composable
fun sectionFacts(row: SectionRow): String = listOfNotNull(
    stringResource(ratingLabel(row.section.rating)),
    stringResource(R.string.sections_km, sectionKm(row.lengthM)),
    row.description?.let { stringResource(R.string.route_curvy, (row.curvyShare * 100).toInt()) },
    stringResource(R.string.section_one_way).takeIf { isOneWay(row.section.direction) },
).joinToString(" · ")

/**
 * Menu → Sections: every saved section as a row the rider recognises
 * (where it runs, its road, rating, length, how curvy), with the totals
 * at the top, chips to show only some ratings or only the sections that
 * need attention after a map update, and a choice of order. Tapping a
 * row shows the section on the map ([onShow]); its ⋮ menu has
 * [rowActions] and Delete (a second tap confirms). [onDelete] deletes.
 * The chips show [filter] and change it through [onFilter], so the page
 * can open again as the rider left it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionsList(
    sections: List<Section>,
    store: SectionStore?,
    engine: Engine?,
    here: LatLon?,
    onShow: (Section) -> Unit,
    onDelete: (List<Long>) -> Unit,
    filter: SectionFilter = SectionFilter(),
    onFilter: (SectionFilter) -> Unit = {},
    rowActions: @Composable (section: Section, close: () -> Unit) -> Unit = { _, _ -> },
) {
    // By line, not by id: a new section can get a deleted one's id.
    var described by remember(engine) { mutableStateOf<Map<List<LatLon>, Description>>(emptyMap()) }
    LaunchedEffect(engine, sections) {
        val e = engine ?: return@LaunchedEffect
        described = withContext(Dispatchers.Default) { SectionDescriptions.describeAll(e, sections) }
    }
    // How often each was ridden, counted from the rides off the main thread.
    var ridden by remember(store) { mutableStateOf<Map<Long, SectionRidden>>(emptyMap()) }
    LaunchedEffect(store, sections) {
        val st = store ?: return@LaunchedEffect
        ridden = withContext(Dispatchers.IO) {
            runCatching { st.ridden() }.getOrDefault(emptyList()).associateBy { it.sectionId }
        }
    }
    // The order last chosen, kept between visits.
    val context = LocalContext.current
    var sort by rememberSaveable { mutableStateOf(RoutePrefs.sectionsSort(context)) }
    val ratings = filter.ratings
    val attention = filter.attention
    val all = remember(sections, described, ridden) {
        sections.map { SectionRow(it, lengthM(it.geometry), described[it.geometry], ridden[it.id]) }
    }
    val summary = summarize(all)
    // Nothing left to attend to: the filter is off too.
    val onlyAttention = attention && summary.attention > 0
    val shown = sortSections(filterSections(all, SectionFilter(ratings, onlyAttention)), sort, here)

    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxWidth().scrollHints(listState),
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
    ) {
        if (sections.isEmpty()) {
            item(key = "empty") { IconText(stringResource(R.string.sections_empty), Modifier.padding(end = 12.dp, top = 8.dp)) }
            return@LazyColumn
        }
        item(key = "summary") {
            Text(
                listOf(
                    pluralStringResource(R.plurals.sections_count, summary.count, summary.count),
                    stringResource(R.string.sections_km, summary.totalKm),
                    stringResource(R.string.sections_epic_count, summary.epic),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "filters") {
            FlowRow(
                Modifier.padding(top = 8.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RATINGS.reversed().forEach { r ->
                    FilterChip(
                        selected = r in ratings,
                        onClick = { onFilter(filter.copy(ratings = toggled(ratings, r))) },
                        label = { OneLine(stringResource(ratingLabel(r))) },
                        leadingIcon = { RatingDot(r) },
                    )
                }
                if (summary.attention > 0) {
                    FilterChip(
                        selected = onlyAttention,
                        onClick = { onFilter(filter.copy(attention = !attention)) },
                        label = { OneLine(stringResource(R.string.sections_attention, summary.attention)) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.error),
                    )
                }
                SortButton(sort, canNearest = here != null) {
                    sort = it
                    RoutePrefs.setSectionsSort(context, it)
                }
            }
        }
        if (onlyAttention) {
            item(key = "attention-delete") {
                // All of them at once, confirmed by a second tap.
                var confirming by remember { mutableStateOf(false) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.section_attention_note),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    DeleteButton(
                        confirming = confirming,
                        onArm = { confirming = true },
                        onDelete = {
                            confirming = false
                            onDelete(shown.map { it.section.id })
                        },
                    )
                }
            }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(top = 8.dp, end = 12.dp)) }
        if (shown.isEmpty()) {
            item(key = "none") { Text(stringResource(R.string.sections_none_match), Modifier.padding(top = 16.dp)) }
        }
        items(shown, key = { it.section.id }) { row ->
            SectionRowItem(row, onShow = { onShow(row.section) }, onDelete = { onDelete(listOf(row.section.id)) }, rowActions)
        }
    }
}

/** The order to list in, as a button that opens the choices. */
@Composable
private fun SortButton(sort: SectionSort, canNearest: Boolean, onSort: (SectionSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            OneLine(stringResource(R.string.sections_sort, stringResource(sortLabel(sort))))
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SectionSort.entries.filter { it != SectionSort.NEAREST || canNearest }.forEach { s ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortLabel(s))) },
                    onClick = {
                        open = false
                        onSort(s)
                    },
                    trailingIcon = if (s == sort) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun sortLabel(s: SectionSort): Int = when (s) {
    SectionSort.RATING -> R.string.sort_rating
    SectionSort.LENGTH -> R.string.sort_length
    SectionSort.CURVY -> R.string.sort_curvy
    SectionSort.NEAREST -> R.string.sort_nearest
    SectionSort.LONGEST_UNRIDDEN -> R.string.sort_longest_unridden
}

/** "Ridden 4 times · last 12 Aug" or "Not ridden yet"; null until counted. */
@Composable
fun riddenText(row: SectionRow): String? {
    val r = row.ridden ?: return null
    val last = r.lastAt ?: return stringResource(R.string.section_not_ridden)
    val times = r.times.toInt()
    val day = rideDay(last, System.currentTimeMillis() / 1000, java.time.ZoneId.systemDefault(), androidx.compose.ui.platform.LocalLocale.current.platformLocale)
    return pluralStringResource(R.plurals.section_ridden, times, times, day)
}

/** A dot in the rating's map colour. */
@Composable
fun RatingDot(r: Rating, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(12.dp)
            .background(Color(android.graphics.Color.parseColor(ratingColor(r))), CircleShape),
    )
}

/** One section: rating dot, title, road, facts; tap to show it; ⋮ for more. */
@Composable
private fun SectionRowItem(
    row: SectionRow,
    onShow: () -> Unit,
    onDelete: () -> Unit,
    rowActions: @Composable (section: Section, close: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var armed by remember(row.section.id) { mutableStateOf(false) }
    fun closeMenu() {
        menu = false
        armed = false
    }
    val title = sectionTitle(row)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.library_show_on_map), onClick = onShow)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingDot(row.section.rating, Modifier.padding(end = 0.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(title)
            sectionRoadLine(row)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(sectionFacts(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            riddenText(row)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (needsAttention(row.section)) {
                Text(
                    stringResource(R.string.section_attention_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (offTheMap(row.section)) {
                Text(
                    stringResource(R.string.section_off_map_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.library_more, title))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { closeMenu() }) {
                rowActions(row.section) { closeMenu() }
                val deleted = stringResource(R.string.deleted)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(if (armed) R.string.delete_confirm else R.string.delete),
                            color = DELETE_COLOR,
                        )
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = DELETE_COLOR) },
                    onClick = {
                        if (armed) {
                            closeMenu()
                            onDelete()
                            Toasts.show(deleted)
                        } else {
                            armed = true
                        }
                    },
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(end = 12.dp))
}

/**
 * A saved section shown on the map (from the Sections page): where it
 * runs, its road and facts, a pencil to change its rating or direction
 * ([onEdit]), [actions] and the cross; below, Loop through it ([onLoop])
 * and Route through it ([onRide]) when the rider's position is known.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShownSectionCard(
    section: Section,
    engine: Engine?,
    onLoop: (() -> Unit)?,
    onRide: (() -> Unit)?,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val row = rememberSectionRow(section, engine)
    MapCard(
        title = sectionTitle(row),
        supporting = listOfNotNull(sectionRoadLine(row), sectionFacts(row)).joinToString("\n"),
        onClose = onClose,
        closeDescription = stringResource(R.string.section_hide),
        modifier = modifier,
        actions = {
            actions()
            IconButton(onClick = onEdit) {
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.section_edit))
            }
        },
    ) {
        if (needsAttention(section)) {
            Text(
                stringResource(R.string.section_attention_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (offTheMap(section)) {
            Text(
                stringResource(R.string.section_off_map_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onLoop != null || onRide != null) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                onLoop?.let { IconTextButton(R.drawable.ic_loop, stringResource(R.string.section_loop_through), it) }
                onRide?.let { IconTextButton(R.drawable.ic_directions, stringResource(R.string.section_ride_from_here), it) }
            }
        }
    }
}

/** [section] as a row, described (where it runs, its road) once the
 * engine has done so. */
@Composable
private fun rememberSectionRow(section: Section, engine: Engine?): SectionRow {
    var description by remember(section.geometry, engine) { mutableStateOf(SectionDescriptions.cached(engine, section)) }
    LaunchedEffect(section.geometry, engine) {
        val e = engine ?: return@LaunchedEffect
        if (description == null) {
            description = withContext(Dispatchers.Default) { SectionDescriptions.describeAll(e, listOf(section))[section.geometry] }
        }
    }
    return SectionRow(section, lengthM(section.geometry), description)
}

/**
 * A favourite tapped while something else is open (a plan, a saved route
 * or ride): only its facts, like the road card, with its rating behind a
 * star in the rating's colour; the cross closes it and nothing else
 * changes (Stefan, 2026-10-02).
 */
@Composable
fun FavouriteInfoCard(section: Section, engine: Engine?, darkMap: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val row = rememberSectionRow(section, engine)
    MapCard(
        title = sectionTitle(row),
        supporting = sectionRoadLine(row),
        onClose = onClose,
        closeDescription = stringResource(R.string.favourite_info_close),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_star),
                contentDescription = null,
                tint = Color(ratingColor(section.rating, darkMap).toColorInt()),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(sectionFacts(row), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
