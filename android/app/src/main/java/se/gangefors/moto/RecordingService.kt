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
import se.gangefors.moto.core.FollowFix
import se.gangefors.moto.core.FollowPhase
import se.gangefors.moto.core.FollowState
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.RouteFollower
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.TrackPoint
import se.gangefors.moto.core.defaultRouteOptions
import se.gangefors.moto.debug.DebugTools

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
 * track ([Recording.recover]), or, for a ride following a route, asks the
 * rider whether to carry on into it. Not exported: only this app can
 * start it.
 *
 * Riding a route (ADR-0011): each fix also goes to the core's
 * [RouteFollower]; the state reaches the map screen with the recording's,
 * an alert sounds when the rider leaves the route, and the recording
 * stops by itself [FINISH_COUNTDOWN_MS] after the end.
 */
class RecordingService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var session: Session? = null

    /** Set on the main thread when a start is accepted, so a second tap
     * can't start a second recording before the first one is set up. */
    private var started = false

    /** One recording, or a ride along a route that isn't recorded yet
     * ([trackId] null: Ride records from the route's start, ADR-0011);
     * touched only on [thread]. */
    private inner class Session(val store: SectionStore) {
        var trackId: Long? = null
        var buffer: PointBuffer? = null
        /** The recording was started by reaching the route's start, not by
         * the rider: it is dropped if they turn out to ride the route the
         * wrong way. */
        var autoStarted = false
        var startedAtMs = System.currentTimeMillis()
        var startedElapsed = SystemClock.elapsedRealtime()
        var batteryAtStart = batteryPercent()
        var progress = RideProgress()
        val pending = ArrayList<TrackPoint>()
        var oldestPendingAt = 0L
        var lastPublishAt = 0L
        var gotFix = false
        var lastFix: TrackPoint? = null
        var follow: Follow? = null
    }

    /** A route being ridden in a [Session]. */
    private class Follow(val route: RideRoute, val follower: RouteFollower) {
        var state: FollowState = follower.state()
        var back: List<LatLon>? = null
        var backM: Double? = null
        var turnRound = false
        var askedAt: Long? = null
        var alertedAt: Long? = null
        var stopsAt: Long? = null
        var fixes = 0L
        var updateNs = 0L
        var maxUpdateNs = 0L
        var offTimes = 0
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
            ACTION_START -> start(resume = null, record = true)
            ACTION_RIDE -> start(resume = null, record = false)
            ACTION_RESUME -> start(resume = intent.getLongExtra(EXTRA_TRACK, -1).takeIf { it >= 0 }, record = true)
            ACTION_FOLLOW -> handler.post { Recording.takeRequest()?.let { r -> session?.let { follow(it, r) } } }
            ACTION_UNFOLLOW -> handler.post { session?.let { unfollow(it) } }
            ACTION_STOP -> handler.post { stop() }
            else -> stopSelf() // e.g. a restart with no intent
        }
        return START_NOT_STICKY
    }

    private fun start(resume: Long?, record: Boolean) {
        if (started) {
            handler.post {
                val s = session ?: return@post
                if (record) {
                    // Record while riding to a route: recording starts now.
                    startTrack(s, auto = false)
                    publish(s, force = true)
                } else {
                    // Ride while recording: the same ride follows the route.
                    Recording.takeRequest()?.let { r -> follow(s, r) }
                }
            }
            return
        }
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
        handler.post { begin(resume, record) }
    }

    @SuppressLint("MissingPermission") // Checked in start().
    private fun begin(resume: Long?, record: Boolean) {
        val store = (SavedSections.open(applicationContext) as? StoreState.Ready)?.store
        if (store == null) {
            fail(getString(R.string.recording_no_store))
            return
        }
        try {
            // Under the recovery lock, so a recovery started by the map screen
            // can't finish this track before it shows as active.
            val s = synchronized(Recording) {
                // Save what an earlier, interrupted ride left behind first;
                // a new recording finishes one that waited to carry on.
                Recording.recover(applicationContext, store, finishFollowed = resume == null, keep = resume)
                Recording.answered()
                val reopened = resume?.let { id -> store.getTrack(id)?.takeIf { it.endedAt == null } }
                Session(store).also {
                    session = it
                    if (reopened != null) {
                        // Carrying on: the fixes from now on are a new segment.
                        store.breakTrack(reopened.id)
                        it.trackId = reopened.id
                        it.buffer = PointBuffer(File(Recording.bufferDir(applicationContext), PointBuffer.fileName(reopened.id)))
                    } else if (record) {
                        startTrack(it, auto = false)
                    }
                    val route = if (reopened != null) {
                        runCatching { store.followedRoute(reopened.id)?.toRideRoute() }.getOrNull()
                    } else {
                        Recording.takeRequest()
                    }
                    if (route != null) follow(it, route)
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
        s.lastFix = fix
        // Following first: reaching the route's start may start recording,
        // with this fix as its first.
        val followed = s.follow?.let { onFollowFix(s, it, fix) } == true
        if (s.trackId != null) {
            try {
                s.buffer?.append(fix)
            } catch (e: Exception) {
                Log.w(TAG, "buffer write failed: ${e.message}")
            }
            if (s.pending.isEmpty()) s.oldestPendingAt = SystemClock.elapsedRealtime()
            s.pending += fix
            s.progress.add(fix.position)
            if (policy.due(s.pending.size, SystemClock.elapsedRealtime() - s.oldestPendingAt)) flush(s)
        }
        publish(s, force = followed)
    }

    /** Starts following [route] on the ride [s] records; kept in the store
     * until the ride ends, so it survives Android stopping the app. */
    private fun follow(s: Session, route: RideRoute) {
        try {
            val follower = RouteFollower(route.line, route.favouriteParts, route.favouriteRatings, route.durationS)
            s.trackId?.let { s.store.setFollowedRoute(it, route.toFollowed()) }
            s.follow?.let { endFollow(s, it) }
            s.follow = Follow(route, follower)
            publish(s, force = true)
        } catch (e: Exception) {
            Log.w(TAG, "could not follow the route: ${e.message}")
            Recording.set(Recording.State.Failed(e.message ?: e.toString()))
        }
    }

    /** Stops following, keeps recording (the card's X); with nothing
     * recorded yet the ride just ends. */
    private fun unfollow(s: Session) {
        val f = s.follow ?: return
        val id = s.trackId
        if (id == null) {
            stop()
            return
        }
        endFollow(s, f)
        s.follow = null
        runCatching { s.store.clearFollowedRoute(id) }
        publish(s, force = true)
    }

    /** Starts recording on [s] (the rider tapped Record, or with [auto]
     * the ride reached the route's start). */
    private fun startTrack(s: Session, auto: Boolean) {
        if (s.trackId != null) return
        try {
            val track = s.store.startTrack()
            s.trackId = track.id
            s.buffer = PointBuffer(File(Recording.bufferDir(applicationContext), PointBuffer.fileName(track.id)))
            s.autoStarted = auto
            s.progress = RideProgress()
            s.startedAtMs = System.currentTimeMillis()
            s.startedElapsed = SystemClock.elapsedRealtime()
            s.batteryAtStart = batteryPercent()
            s.follow?.let { s.store.setFollowedRoute(track.id, it.route.toFollowed()) }
        } catch (e: Exception) {
            Log.w(TAG, "could not start recording: ${e.message}")
        }
    }

    /** Drops a recording the ride started by itself: the rider turned out
     * to ride the route the wrong way, on their way to its start. */
    private fun discardTrack(s: Session) {
        val id = s.trackId ?: return
        s.pending.clear()
        s.buffer?.delete()
        s.buffer = null
        s.trackId = null
        s.autoStarted = false
        s.progress = RideProgress()
        runCatching { s.store.deleteTrack(id) }
        RideChanges.changed()
    }

    private fun endFollow(s: Session, f: Follow) {
        cancelAlert()
        if (f.fixes > 0) {
            DebugTools.mark(
                "follow: ${f.fixes} fixes, ${f.updateNs / f.fixes / 1000} µs mean, " +
                    "${f.maxUpdateNs / 1000} µs max, off the route ${f.offTimes} times",
            )
        }
        if (s.follow === f) f.stopsAt = null
    }

    /** One fix for the route being ridden; true when the state changed. */
    private fun onFollowFix(s: Session, f: Follow, fix: TrackPoint): Boolean {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val next = try {
            f.follower.update(FollowFix(fix.position, fix.timeMs, fix.accuracyM, fix.speedMps, fix.bearingDeg))
        } catch (e: Exception) {
            Log.w(TAG, "follow update failed: ${e.message}")
            return false
        }
        val ns = SystemClock.elapsedRealtimeNanos() - t0
        f.fixes++
        f.updateNs += ns
        f.maxUpdateNs = maxOf(f.maxUpdateNs, ns)
        val before = f.state.phase
        val wasStarted = f.state.started
        f.state = next
        val now = System.currentTimeMillis()
        // Ride records from the route's start; a recording it started is
        // dropped if the rider turns out to ride the route backwards.
        if (next.started && !wasStarted && s.trackId == null) startTrack(s, auto = true)
        if (next.phase == FollowPhase.JOINING && next.wrongWay && s.autoStarted) discardTrack(s)
        when {
            next.phase == FollowPhase.OFF_ROUTE && before != FollowPhase.OFF_ROUTE -> {
                f.offTimes++
                if (RoutePrefs.offRouteAlert(applicationContext)) alert(f, sound = alertDue(f.alertedAt, now))
                f.alertedAt = now
            }
            next.phase == FollowPhase.ON_ROUTE && before == FollowPhase.OFF_ROUTE -> cancelAlert()
            next.phase == FollowPhase.FINISHED && f.stopsAt == null -> {
                cancelAlert()
                f.stopsAt = now + FINISH_COUNTDOWN_MS
                handler.postDelayed({ if (session === s && s.follow === f && f.stopsAt != null) stop() }, FINISH_COUNTDOWN_MS)
            }
        }
        if (next.phase == FollowPhase.ON_ROUTE || next.phase == FollowPhase.FINISHED) {
            f.back = null
            f.backM = null
            f.turnRound = false
        } else if (next.phase == FollowPhase.JOINING && next.offM?.let { it <= JOIN_LINE_M } == true) {
            f.back = null
            f.backM = null
        }
        if (rejoinDue(next, f.askedAt, now)) {
            f.askedAt = now
            askWayBack(f, fix)
            if (next.phase == FollowPhase.OFF_ROUTE && RoutePrefs.offRouteAlert(applicationContext)) alert(f, sound = false)
        }
        return true
    }

    /** The way back to the route from [fix], if a map is open. */
    private fun askWayBack(f: Follow, fix: TrackPoint) {
        val engine = (Regions.active.value.state as? RegionState.Ready)?.engine ?: return
        val opts = routeOptions(
            defaultRouteOptions(),
            ROUTE_EXTRA_PERCENT,
            RoutePrefs.gravel(applicationContext),
            RoutePrefs.avoid(applicationContext),
        )
        val back = try {
            DebugTools.query("way back", { "${sectionKm(it.route.distanceM)} km, turn round ${it.turnRound}" }) {
                engine.rejoin(fix.position, fix.bearingDeg, f.follower, opts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "no way back: ${e.message}")
            return
        }
        f.back = back.route.geometry
        f.backM = back.route.distanceM
        f.turnRound = back.turnRound
    }

    /** The off-route alert: a notification of its own, with the channel's
     * sound (no vibration: the phone is in a holder); [sound] false
     * updates it silently. */
    private fun alert(f: Follow, sound: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, getString(R.string.ride_off_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(getString(R.string.ride_off_title))
            .setContentText(offText(f))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!sound)
            .setOnlyAlertOnce(!sound)
            .build()
        nm.notify(ALERT_ID, n)
    }

    private fun offText(f: Follow): String {
        val m = f.backM ?: return getString(R.string.ride_off_follow)
        return getString(if (f.turnRound) R.string.ride_off_turn_round else R.string.ride_off_back_in, rideKm(m))
    }

    private fun cancelAlert() {
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    /** Hands the pending fixes to the core, then empties the buffer file. */
    private fun flush(s: Session) {
        val id = s.trackId ?: return
        if (s.pending.isEmpty()) return
        try {
            for (batch in batches(s.pending)) s.store.appendTrackPoints(id, batch)
            s.pending.clear()
            s.buffer?.clear()
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
        s.follow?.let { endFollow(s, it) }
        session = null
        val id = s.trackId
        if (id == null) {
            // A ride to a route that never recorded: nothing to save.
            Recording.set(Recording.State.Idle)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        try {
            if (s.pending.isNotEmpty()) {
                // The core could not take the last fixes: leave the track open
                // with its buffer, for the next start to save and finish.
                s.buffer?.close()
                Recording.set(Recording.State.Failed(getString(R.string.recording_saved_later)))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            val track = s.store.finishTrack(id)
            RideChanges.changed()
            s.buffer?.delete()
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
        val following = s.follow?.let {
            Following(it.route, it.state, it.back, it.backM, it.turnRound, it.stopsAt)
        }
        Recording.set(
            Recording.State.Active(
                trackId = s.trackId,
                startedAtMs = s.startedAtMs,
                distanceM = s.progress.distanceM,
                line = s.progress.line,
                waitingForGps = !s.gotFix,
                lastFix = s.lastFix,
                following = following,
            ),
        )
        val text = if (following != null && s.trackId == null) {
            getString(R.string.ride_not_recording, following.route.name)
        } else if (following != null && s.gotFix && following.state.phase != FollowPhase.JOINING) {
            val arrive = android.text.format.DateFormat.getTimeFormat(this)
                .format(java.util.Date(System.currentTimeMillis() + (following.state.leftS * 1000).toLong()))
            getString(R.string.ride_progress, following.route.name, rideKm(following.state.leftM), arrive)
        } else if (s.gotFix) {
            getString(R.string.recording_progress, sectionKm(s.progress.distanceM), formatDuration(now - s.startedElapsed))
        } else {
            getString(R.string.recording_waiting)
        }
        val title = getString(if (s.trackId == null) R.string.ride_title else R.string.recording_title)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, title))
    }

    private fun notification(text: String, title: String = getString(R.string.recording_title)): Notification {
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
            .setContentTitle(title)
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
        private const val ACTION_RESUME = "se.gangefors.moto.action.RESUME_RECORDING"
        private const val ACTION_RIDE = "se.gangefors.moto.action.RIDE_ROUTE"
        private const val ACTION_FOLLOW = "se.gangefors.moto.action.FOLLOW_ROUTE"
        private const val ACTION_UNFOLLOW = "se.gangefors.moto.action.UNFOLLOW_ROUTE"
        private const val EXTRA_TRACK = "track"
        private const val CHANNEL = "recording"
        private const val ALERT_CHANNEL = "off_route"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val FIX_INTERVAL_MS = 1_000L
        private const val PUBLISH_INTERVAL_MS = 3_000L
        private const val TAG = "moto"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START),
            )
        }

        /** Rides [route] (ADR-0011): recording starts at its start, or
         * the ride being recorded follows it. */
        fun ride(context: Context, route: RideRoute) {
            Recording.request(route)
            if (Recording.state.value is Recording.State.Active) {
                context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_FOLLOW))
            } else {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RecordingService::class.java).setAction(ACTION_RIDE),
                )
            }
        }

        /** Carries on ride [trackId], left open when Android stopped the
         * app, following its route again. */
        fun resume(context: Context, trackId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_TRACK, trackId),
            )
        }

        /** Stops following the route; the ride records on. */
        fun unfollow(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_UNFOLLOW))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
