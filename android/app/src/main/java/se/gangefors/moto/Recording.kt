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
            val trackId: Long,
            val startedAtMs: Long,
            val distanceM: Double,
            /** The line ridden so far, thinned; refreshed every few seconds. */
            val line: List<LatLon>,
            val waitingForGps: Boolean,
            /** The latest fix, for quick-tags. */
            val lastFix: TrackPoint?,
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

    /** Where the crash-safe fix buffers live. */
    fun bufferDir(context: Context): File = File(context.filesDir, "recording").apply { mkdirs() }

    /**
     * After the app died mid-ride: hands the fixes left in buffers to the
     * core and finishes tracks that were never ended, except the one being
     * recorded now. Call off the main thread.
     */
    @Synchronized
    fun recover(context: Context, store: SectionStore) {
        val active = activeTrackId
        val files = bufferDir(context).listFiles().orEmpty()
        for (file in files) {
            val id = PointBuffer.trackIdOf(file.name)
            if (id == null) {
                file.delete() // not ours
                continue
            }
            if (id == active) continue
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
            store.listTracks()
                .filter { it.endedAt == null && it.id != active }
                .forEach { store.finishTrack(it.id) }
        } catch (e: Exception) {
            Log.w(TAG, "could not finish interrupted tracks: ${e.message}")
        }
    }

    private const val TAG = "moto"
}
