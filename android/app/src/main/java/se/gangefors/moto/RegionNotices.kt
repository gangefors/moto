// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The one notification of the region download queue (ADR-0017): progress,
 * waiting, retrying, then ready or failed, all under one id so there is
 * only ever one. Progress and waiting use a quiet channel, the result a
 * default one. Tapping it opens the Map region page; Stop goes to a
 * receiver that is not exported, through explicit, immutable intents.
 */
object RegionNotices {
    /** The recording uses 1 and 2. */
    const val NOTICE_ID = 3
    private const val CHANNEL_PROGRESS = "region_download"
    private const val CHANNEL_RESULT = "region_result"

    /** The notification's Stop (to [RegionDownloadReceiver]). */
    const val ACTION_STOP = "se.gangefors.moto.action.STOP_DOWNLOADS"

    /** Opens the Map region page ([MainActivity]); carries no data. */
    const val ACTION_OPEN = "se.gangefors.moto.action.OPEN_REGIONS"

    private fun channels(app: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, app.getString(R.string.region_channel_progress), NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (nm.getNotificationChannel(CHANNEL_RESULT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, app.getString(R.string.region_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun text(app: Context, t: Txt): String = app.getString(t.id, *t.args.toTypedArray())

    /** The notification for [s]. */
    fun build(context: Context, s: NoticeState): Notification {
        val app = context.applicationContext
        channels(app, app.getSystemService(NotificationManager::class.java))
        val n = notice(s)
        val open = PendingIntent.getActivity(
            app,
            NOTICE_ID,
            Intent(app, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = text(app, n.text)
        val b = NotificationCompat.Builder(app, if (n.channel == NoticeChannel.PROGRESS) CHANNEL_PROGRESS else CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(text(app, n.title))
            .setContentText(text)
            .setContentIntent(open)
        if (n.ongoing) {
            val stop = PendingIntent.getBroadcast(
                app,
                NOTICE_ID,
                Intent(app, RegionDownloadReceiver::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, app.getString(R.string.region_notice_stop), stop)
        } else {
            b.setAutoCancel(true)
                .setCategory(if (s is NoticeState.Failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        }
        if (n.bar) {
            val p = n.progress
            b.setProgress(PROGRESS_MAX, ((p ?: 0f) * PROGRESS_MAX).toInt(), p == null)
        } else {
            // A long text wraps instead of being cut.
            b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        return b.build()
    }

    /** Whether the app may show notifications (asked on the first download, Android 13+). */
    fun allowed(context: Context): Boolean {
        val app = context.applicationContext
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    /** Shows [s] (silently nothing when notifications are off); null takes it away. */
    fun show(context: Context, s: NoticeState?) {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        if (s == null) {
            nm.cancel(NOTICE_ID)
            return
        }
        if (!allowed(app)) return
        nm.notify(NOTICE_ID, build(app, s))
    }

    private const val PROGRESS_MAX = 1000
}
