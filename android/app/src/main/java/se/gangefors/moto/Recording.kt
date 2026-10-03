// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.SectionStore
import se.gangefors.moto.core.Track
import se.gangefors.moto.core.TrackPoint

/**
 * Ride recording as the map screen sees it (PRD R4). [RecordingService]
 * records; this object carries its state to the UI within the process.
 */
object Recording {
    sealed interface State {
        data object Idle : State
        data class Active(
            /** The ride being recorded; none while riding to a route's
             * start, before recording starts (ADR-0011). */
            val trackId: Long?,
            val startedAtMs: Long,
            val distanceM: Double,
            /** The line ridden so far, thinned; refreshed every few seconds. */
            val line: List<LatLon>,
            val waitingForGps: Boolean,
            /** The latest fix, for quick-tags. */
            val lastFix: TrackPoint?,
            /** The route being ridden, if any (ADR-0011). */
            val following: Following? = null,
            /** Paused while the rider plans: no fixes are kept. */
            val paused: Boolean = false,
            /** Time paused so far, ms, the current pause included. */
            val pausedMs: Long = 0,
        ) : State
        /** The last ride just ended; [batteryPerHour] in percent, if known. */
        data class Finished(val track: Track, val batteryPerHour: Double?) : State
        data class Failed(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    internal fun set(state: State) {
        mutableState.value = state
    }

    /** The track being recorded in this process, if any. */
    val activeTrackId: Long? get() = (mutableState.value as? State.Active)?.trackId

    /** A route to ride, handed to [RecordingService] in-process (never
     * through an intent): taken by the service when it starts or is told
     * to follow. */
    @Volatile
    private var requested: RideRoute? = null

    fun request(route: RideRoute) {
        requested = route
    }

    fun takeRequest(): RideRoute? = requested.also { requested = null }

    /** A ride that was following a route when Android stopped the app,
     * left open so the rider can carry on ([recover]). */
    private val _interrupted = MutableStateFlow<Long?>(null)
    val interrupted: StateFlow<Long?> = _interrupted

    /** The rider answered: [interrupted] is no longer waiting. */
    fun answered() {
        _interrupted.value = null
    }

    /** Where the crash-safe fix buffers live. */
    fun bufferDir(context: Context): File = File(context.filesDir, "recording").apply { mkdirs() }

    /**
     * After the app died mid-ride: hands the fixes left in buffers to the
     * core and finishes tracks that were never ended, except the one being
     * recorded now. A ride that was following a route is left open for
     * the rider to carry on ([interrupted]), unless [finishFollowed] (a new
     * recording starts) or it is [keep] (carrying on now). Call off the
     * main thread.
     */
    @Synchronized
    fun recover(context: Context, store: SectionStore, finishFollowed: Boolean = false, keep: Long? = null) {
        val active = activeTrackId
        val files = bufferDir(context).listFiles().orEmpty()
        for (file in files) {
            val id = PointBuffer.trackIdOf(file.name)
            if (id == null) {
                file.delete() // not ours
                continue
            }
            if (id == active || id == keep) continue
            val buffer = PointBuffer(file)
            try {
                for (batch in batches(buffer.read())) store.appendTrackPoints(id, batch)
            } catch (e: Exception) {
                // The track is gone or already finished: nothing to add to.
                Log.w(TAG, "could not recover fixes of track $id: ${e.message}")
            }
            buffer.delete()
        }
        try {
            val open = store.listTracks().filter { it.endedAt == null && it.id != active && it.id != keep }
            // The newest ride that followed a route waits for the rider;
            // every other open ride is finished as before.
            val waiting = if (finishFollowed) {
                null
            } else {
                open.sortedByDescending { it.startedAt }
                    .firstOrNull { runCatching { store.followedRoute(it.id) }.getOrNull() != null }
            }
            open.filter { it.id != waiting?.id }
                .forEach { store.finishTrack(it.id)?.let { t -> finishedUnnamed += t.id } }
            _interrupted.value = waiting?.id
            RideChanges.changed()
        } catch (e: Exception) {
            Log.w(TAG, "could not finish interrupted tracks: ${e.message}")
        }
    }

    /** Rides [recover] finished, waiting to be named once a map is open
     * (the map screen does it, as for a ride finished normally). */
    private val finishedUnnamed = mutableSetOf<Long>()

    /** The rides [recover] finished since the last call, to name. */
    @Synchronized
    fun takeFinished(): List<Long> = finishedUnnamed.toList().also { finishedUnnamed.clear() }

    private const val TAG = "moto"
}
