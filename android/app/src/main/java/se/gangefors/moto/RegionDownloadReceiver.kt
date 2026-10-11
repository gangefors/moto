// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Stop in the region download notification (ADR-0017). Not exported: only
 * the app's own explicit, immutable PendingIntent reaches it, and it acts
 * on its one action only.
 */
class RegionDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == RegionNotices.ACTION_STOP) Regions.stop(context)
    }
}
