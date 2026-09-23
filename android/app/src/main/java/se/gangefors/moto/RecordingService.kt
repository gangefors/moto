// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.TrackPoint

/**
 * Records a ride (PRD R4) as a foreground service of type location with a
 * visible notification. It only runs after the rider starts it from the
 * app, so the app's foreground ("while in use") location permission is
 * enough; there is no background location permission. Fixes go to a
 * crash-safe buffer file and reach the core's store in batches.
 *
 * If the system kills the process mid-ride, the service is not restarted
 * (Android does not let a location service start itself from the
 * background); the next app start saves what was buffered and ends the
 * track ([Recording.recover]). Not exported: only this app can start it.
 */
class RecordingService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var session: Session? = null

    /** Set on the main thread when a start is accepted, so a second tap
     * can't start a second recording before the first one is set up. */
    private var started = false

    /** One recording, touched only on [thread]. */
    private inner class Session(val store: SectionStore, val trackId: Long, val buffer: PointBuffer) {
        val startedAtMs = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        val batteryAtStart = batteryPercent()
        val progress = RideProgress()
        val pending = ArrayList<TrackPoint>()
        var oldestPendingAt = 0L
        var lastPublishAt = 0L
        var gotFix = false
    }

    private val policy = FlushPolicy()

    private val listener = LocationListener { location -> onLocation(location) }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("moto-recording").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> handler.post { stop() }
            else -> stopSelf() // e.g. a restart with no intent
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (started) return
        if (!hasLocationPermission()) {
            Recording.set(Recording.State.Failed(getString(R.string.recording_no_permission)))
            stopSelf()
            return
        }
        started = true
        // Must be called promptly after startForegroundService.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.recording_waiting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        handler.post { begin() }
    }

    @SuppressLint("MissingPermission") // Checked in start().
    private fun begin() {
        val store = (SavedSections.open(applicationContext) as? StoreState.Ready)?.store
        if (store == null) {
            fail(getString(R.string.recording_no_store))
            return
        }
        try {
            // Under the recovery lock, so a recovery started by the map screen
            // can't finish this track before it shows as active.
            val s = synchronized(Recording) {
                // Save what an earlier, interrupted ride left behind first.
                Recording.recover(applicationContext, store)
                val track = store.startTrack()
                val file = File(Recording.bufferDir(applicationContext), PointBuffer.fileName(track.id))
                Session(store, track.id, PointBuffer(file)).also {
                    session = it
                    publish(it, force = true)
                }
            }
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0f, listener, thread.looper)
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    private fun onLocation(location: Location) {
        val s = session ?: return
        val fix = checkedFix(
            timeMs = location.time,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
        ) ?: return
        s.gotFix = true
        try {
            s.buffer.append(fix)
        } catch (e: Exception) {
            Log.w(TAG, "buffer write failed: ${e.message}")
        }
        if (s.pending.isEmpty()) s.oldestPendingAt = SystemClock.elapsedRealtime()
        s.pending += fix
        s.progress.add(fix.position)
        if (policy.due(s.pending.size, SystemClock.elapsedRealtime() - s.oldestPendingAt)) flush(s)
        publish(s, force = false)
    }

    /** Hands the pending fixes to the core, then empties the buffer file. */
    private fun flush(s: Session) {
        if (s.pending.isEmpty()) return
        try {
            for (batch in batches(s.pending)) s.store.appendTrackPoints(s.trackId, batch)
            s.pending.clear()
            s.buffer.clear()
        } catch (e: Exception) {
            // Kept in memory and in the buffer file; tried again next time.
            Log.w(TAG, "could not store fixes: ${e.message}")
        }
    }

    private fun stop() {
        val s = session
        if (s == null) {
            stopSelf()
            return
        }
        getSystemService(LocationManager::class.java).removeUpdates(listener)
        flush(s)
        session = null
        try {
            if (s.pending.isNotEmpty()) {
                // The core could not take the last fixes: leave the track open
                // with its buffer, for the next start to save and finish.
                s.buffer.close()
                Recording.set(Recording.State.Failed(getString(R.string.recording_saved_later)))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            val track = s.store.finishTrack(s.trackId)
            s.buffer.delete()
            val duration = SystemClock.elapsedRealtime() - s.startedElapsed
            Recording.set(
                if (track != null) {
                    Recording.State.Finished(track, batteryPerHour(s.batteryAtStart, batteryPercent(), duration))
                } else {
                    Recording.State.Idle
                },
            )
        } catch (e: Exception) {
            Recording.set(Recording.State.Failed(e.message ?: e.toString()))
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Recording.set(Recording.State.Failed(message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Updates the UI state (the line at most every few seconds) and the notification. */
    private fun publish(s: Session, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - s.lastPublishAt < PUBLISH_INTERVAL_MS) return
        s.lastPublishAt = now
        Recording.set(
            Recording.State.Active(
                trackId = s.trackId,
                startedAtMs = s.startedAtMs,
                distanceM = s.progress.distanceM,
                line = s.progress.line,
                waitingForGps = !s.gotFix,
            ),
        )
        val text = if (s.gotFix) {
            getString(R.string.recording_progress, sectionKm(s.progress.distanceM), formatDuration(now - s.startedElapsed))
        } else {
            getString(R.string.recording_waiting)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(getString(R.string.recording_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.recording_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryPercent(): Int? =
        getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    override fun onDestroy() {
        // Stopped by the system or the app: end the ride cleanly.
        handler.post {
            if (session != null) stop()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "se.gangefors.moto.action.START_RECORDING"
        private const val ACTION_STOP = "se.gangefors.moto.action.STOP_RECORDING"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val FIX_INTERVAL_MS = 1_000L
        private const val PUBLISH_INTERVAL_MS = 3_000L
        private const val TAG = "moto"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
