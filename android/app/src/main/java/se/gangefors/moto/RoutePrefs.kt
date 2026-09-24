// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import androidx.core.content.edit

/**
 * The route settings the rider last chose, in app-private preferences
 * (never backed up; see the data extraction rules).
 */
object RoutePrefs {
    private const val FILE = "route"
    private const val BUDGET = "budget_percent"
    private const val GRAVEL = "allow_gravel"
    private const val LOOP = "loop_length"

    /** The extra-time budget in percent, or the default. */
    fun budgetPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // A value of another type throws; it counts as unset.
        return budgetPercentOf(runCatching { prefs.getInt(BUDGET, DEFAULT_BUDGET_PERCENT) }.getOrNull())
    }

    fun setBudgetPercent(context: Context, percent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putInt(BUDGET, percent) }
    }

    /** Whether routes may use gravel (unpaved) roads freely; avoided by
     * default. */
    fun allowGravel(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(GRAVEL, false) }
            .getOrDefault(false)

    fun setAllowGravel(context: Context, allow: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(GRAVEL, allow) }
    }

    /** The round-trip length last picked, or the default. */
    fun loopChoice(context: Context): LoopChoice =
        loopChoiceOf(runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LOOP, null) }.getOrNull())

    fun setLoopChoice(context: Context, choice: LoopChoice) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(LOOP, choice.key) }
    }
}
