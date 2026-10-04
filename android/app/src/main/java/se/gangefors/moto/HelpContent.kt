// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/** The topics of the how-to page, in page order. */
enum class HelpTopicId { MAP, PLAN, FAVOURITES, RIDE, RIDE_ROUTE, LIBRARY, SETTINGS }

/**
 * A button in a topic's key: its icon (a key of [BUTTON_ICONS]), its
 * name, what it does, and the topic that tells more, if any.
 */
data class HelpKey(val icon: String, val name: Int, val what: Int, val see: HelpTopicId? = null)

/**
 * A setting described in the settings topic: its name and what it does,
 * the same strings as its heading and (i) text on the Ride settings page.
 */
data class HelpSetting(val name: Int, val text: Int)

/** A group of settings, as the Ride settings page groups them. */
data class HelpGroup(val title: Int, val settings: List<HelpSetting>)

/**
 * A topic: its heading (with the icon after it, if any), its text, then
 * its key of buttons or its groups of settings, and after them, with
 * [figuresText] as their lead, a key of the icons on its [figures], and
 * with [summaryText] as theirs, of the summary row's chips ([summary]).
 */
data class HelpTopic(
    val id: HelpTopicId,
    val title: Int,
    val text: Int,
    val keys: List<HelpKey> = emptyList(),
    val groups: List<HelpGroup> = emptyList(),
    val titleIcon: String? = null,
    val figuresText: Int? = null,
    val figures: List<HelpKey> = emptyList(),
    val summaryText: Int? = null,
    val summary: List<HelpKey> = emptyList(),
)

/**
 * The buttons on the map, in the order they sit there: the left column
 * top to bottom, then the right.
 */
val MAP_BUTTON_ORDER = listOf("menu", "tag", "settings", "flag", "mark", "loop", "record", "location")

val HELP_TOPICS = listOf(
    HelpTopic(
        HelpTopicId.MAP, R.string.help_map_title, R.string.help_map,
        keys = listOf(
            HelpKey("menu", R.string.help_key_menu, R.string.help_key_menu_what),
            HelpKey("tag", R.string.help_key_tag, R.string.help_key_tag_what, HelpTopicId.RIDE),
            HelpKey("settings", R.string.help_key_settings, R.string.help_key_settings_what, HelpTopicId.SETTINGS),
            HelpKey("flag", R.string.help_key_review, R.string.help_key_review_map, HelpTopicId.RIDE),
            HelpKey("mark", R.string.help_key_mark, R.string.help_key_mark_map, HelpTopicId.FAVOURITES),
            HelpKey("loop", R.string.help_key_loop, R.string.help_key_loop_what, HelpTopicId.PLAN),
            HelpKey("record", R.string.help_key_record, R.string.help_key_record_map, HelpTopicId.RIDE),
            HelpKey("location", R.string.help_key_location, R.string.help_key_location_what),
        ),
        figuresText = R.string.help_map_lines,
        figures = listOf(HelpKey("ridden", R.string.help_line_ridden, R.string.help_line_ridden_what)),
    ),
    HelpTopic(
        HelpTopicId.PLAN, R.string.help_plan_title, R.string.help_plan,
        keys = listOf(
            HelpKey("loop", R.string.help_key_loop_here, R.string.help_key_loop_here_what),
            HelpKey("directions", R.string.help_key_route_here, R.string.help_key_route_here_what),
            HelpKey("share", R.string.help_key_share, R.string.help_key_share_what),
            HelpKey("save", R.string.help_key_save, R.string.help_key_save_what),
            HelpKey("ridden_button", R.string.help_key_ridden_button, R.string.help_key_ridden_button_what),
        ),
        figuresText = R.string.help_plan_figures,
        figures = listOf(
            HelpKey("time", R.string.help_figure_time, R.string.help_figure_time_what),
            HelpKey("sections", R.string.help_figure_favourites, R.string.help_figure_favourites_what),
            HelpKey("curvy", R.string.help_figure_curvy, R.string.help_figure_curvy_what),
            HelpKey("unridden", R.string.help_figure_unridden, R.string.help_figure_unridden_what),
            HelpKey("gravel", R.string.help_figure_gravel, R.string.help_figure_gravel_what),
            HelpKey("toll", R.string.help_figure_toll, R.string.help_figure_toll_what),
        ),
        summaryText = R.string.help_plan_summary,
        summary = listOf(
            HelpKey("clock", R.string.help_summary_clock, R.string.help_summary_clock_what),
            HelpKey("pin", R.string.help_summary_pin, R.string.help_summary_pin_what),
            HelpKey("compass", R.string.help_summary_compass, R.string.help_summary_compass_what),
            HelpKey("gravel", R.string.help_summary_gravel, R.string.help_summary_gravel_what),
            HelpKey("star_off", R.string.help_summary_star_off, R.string.help_summary_star_off_what),
            HelpKey("unridden", R.string.help_summary_unridden, R.string.help_summary_unridden_what),
            HelpKey("motorway", R.string.help_summary_roads, R.string.help_summary_roads_what),
        ),
    ),
    HelpTopic(
        HelpTopicId.FAVOURITES, R.string.help_sections_title, R.string.help_sections,
        keys = listOf(
            HelpKey("mark", R.string.help_key_mark, R.string.help_key_mark_what),
            HelpKey("sections", R.string.help_key_sections, R.string.help_key_sections_what),
        ),
    ),
    HelpTopic(
        HelpTopicId.RIDE, R.string.help_ride_title, R.string.help_ride,
        keys = listOf(
            HelpKey("record", R.string.help_key_record, R.string.help_key_record_what),
            HelpKey("tag", R.string.help_key_tag, R.string.help_key_tag_what),
            HelpKey("flag", R.string.help_key_review, R.string.help_key_review_what),
        ),
    ),
    HelpTopic(
        HelpTopicId.RIDE_ROUTE, R.string.help_riding_title, R.string.help_riding,
        titleIcon = "navigation",
        keys = listOf(
            HelpKey("navigation", R.string.help_key_ride, R.string.help_key_ride_what),
            HelpKey("location", R.string.help_key_recentre, R.string.help_key_recentre_what),
            HelpKey("compass", R.string.help_key_ride_compass, R.string.help_key_ride_compass_what),
            HelpKey("zoom_in", R.string.help_key_zoom, R.string.help_key_zoom_what),
            HelpKey("close", R.string.help_key_stop_following, R.string.help_key_stop_following_what),
            HelpKey("stop", R.string.help_key_stop_ride, R.string.help_key_stop_ride_what),
        ),
    ),
    // The same line as the Routes & rides page.
    HelpTopic(
        HelpTopicId.LIBRARY, R.string.help_library_title, R.string.library_hint,
        keys = listOf(
            HelpKey("directions", R.string.library_route_term, R.string.library_route_what),
            HelpKey("ride", R.string.library_ride_term, R.string.library_ride_what),
        ),
    ),
    HelpTopic(
        HelpTopicId.SETTINGS, R.string.help_settings_title, R.string.help_settings,
        titleIcon = "settings",
        groups = listOf(
            HelpGroup(
                R.string.settings_group_routing,
                listOf(
                    HelpSetting(R.string.settings_loop_length, R.string.settings_loop_length_hint),
                    HelpSetting(R.string.settings_loop_direction, R.string.settings_loop_direction_hint),
                    HelpSetting(R.string.routing_gravel, R.string.routing_gravel_hint),
                    HelpSetting(R.string.favourites_label, R.string.favourites_hint),
                    HelpSetting(R.string.unridden_label, R.string.unridden_hint),
                    HelpSetting(R.string.avoid_heading, R.string.avoid_hint),
                ),
            ),
            HelpGroup(R.string.settings_group_map, listOf(HelpSetting(R.string.locate_zooms, R.string.locate_zooms_hint))),
            HelpGroup(
                R.string.settings_group_recording,
                listOf(HelpSetting(R.string.settings_keep_screen_on, R.string.settings_keep_screen_on_hint)),
            ),
            HelpGroup(
                R.string.settings_group_riding,
                listOf(
                    HelpSetting(R.string.settings_turn_map, R.string.settings_turn_map_hint),
                    HelpSetting(R.string.settings_off_alert, R.string.settings_off_alert_hint),
                ),
            ),
        ),
    ),
)
