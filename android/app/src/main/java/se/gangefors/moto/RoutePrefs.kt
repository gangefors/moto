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

    /** The extra-time budget in percent, or the default. */
    fun budgetPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return budgetPercentOf(if (prefs.contains(BUDGET)) prefs.getInt(BUDGET, DEFAULT_BUDGET_PERCENT) else null)
    }

    fun setBudgetPercent(context: Context, percent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putInt(BUDGET, percent) }
    }
}
