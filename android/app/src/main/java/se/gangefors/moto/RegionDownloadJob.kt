// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi

/**
 * The region download queue as a user-initiated data transfer job (Android
 * 14 and newer, ADR-0017): Android runs it on an allowed network, holds a
 * wake lock for it, stops it when the network goes and starts it again when
 * it is back, and reschedules it when the app's process dies. Not exported;
 * only the system binds it (BIND_JOB_SERVICE).
 */
@RequiresApi(34)
class RegionDownloadJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        // First: a user-initiated job must show its notification at once.
        setNotification(
            params,
            RegionNotices.NOTICE_ID,
            RegionNotices.build(app, Regions.startingNotice(app)),
            JOB_END_NOTIFICATION_POLICY_DETACH,
        )
        Regions.runJob(app, params, this)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = Regions.jobStopped(applicationContext, params)
}
