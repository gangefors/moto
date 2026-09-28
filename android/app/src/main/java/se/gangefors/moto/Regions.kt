// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.RegionOffer
import se.gangefors.moto.core.installRegion
import se.gangefors.moto.core.parseRegionManifest
import se.gangefors.moto.core.prefetchRegionFile
import se.gangefors.moto.core.profileRegionOpen
import se.gangefors.moto.core.regionManifestFileName

/** Which region the app routes on, and where it came from. */
data class ActiveRegion(val state: RegionState, val downloaded: DownloadedRegion?)

/** A region installed from a download (ADR-0008). */
data class DownloadedRegion(val id: String, val name: String, val osmTimestamp: Long)

/** What the region download is doing. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Checking : DownloadState
    data class Offers(val offers: List<RegionOffer>) : DownloadState
    data class Downloading(val offer: RegionOffer, val done: Long) : DownloadState
    data class Installing(val offer: RegionOffer) : DownloadState
    data class Failed(val message: String, val offers: List<RegionOffer>) : DownloadState
}

/**
 * The routing region: a downloaded one when installed, else the region
 * bundled in a debug APK. Downloads run here, not in a screen, so they
 * carry on when My data is closed; an interrupted download resumes where
 * it stopped. The core checks everything before a region is used.
 */
object Regions {
    private const val DIR = "regions"
    private const val FILE = "downloaded.region"
    private const val PREFS = "regions"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null
    private var manifest: ByteArray? = null

    private val _active = MutableStateFlow(ActiveRegion(RegionState.Loading, null))
    val active: StateFlow<ActiveRegion> = _active.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private fun installed(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun partial(context: Context, offer: RegionOffer) =
        File(File(context.noBackupFilesDir, DIR), "${offer.fileName}.part")

    /** Opens the region at app start: the downloaded one, else the bundled one. */
    suspend fun load(context: Context) = lock.withLock {
        if (_active.value.state !is RegionState.Loading) return@withLock
        StartupTimes.record("region load started")
        _active.value = withContext(Dispatchers.IO) { open(context.applicationContext) }
        StartupTimes.record("region ready")
    }

    private fun open(context: Context): ActiveRegion {
        val file = installed(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (file.isFile) {
            try {
                // Read straight through first (fast), then open and check it
                // from the cache (a cold open reads it page by page: slow).
                StartupTimes.measure("region read (${file.length() / 1_000_000} MB)") { prefetchRegionFile(file.path) }
                val engine = StartupTimes.measure("region open") { Engine.open(file.path) }
                val meta = DownloadedRegion(
                    id = prefs.getString("id", null) ?: "",
                    name = prefs.getString("name", null) ?: "",
                    osmTimestamp = engine.info().osmTimestamp,
                )
                return ActiveRegion(RegionState.Ready(engine), meta)
            } catch (_: Exception) {
                // A file that no longer opens is dropped; the bundled region
                // (debug builds) takes over and the rider can download again.
                file.delete()
            }
        }
        return ActiveRegion(BundledRegion.open(context), null)
    }

    /** Fetches the list of regions this app can read. */
    fun check(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            _download.value = DownloadState.Checking
            _download.value = try {
                val bytes = fetchSmall(regionUrl(regionManifestFileName()))
                val offers = parseRegionManifest(bytes)
                manifest = bytes
                DownloadState.Offers(offers)
            } catch (e: Exception) {
                DownloadState.Failed(describe(context, e), emptyList())
            }
        }
    }

    /** Downloads and installs [offer], then switches the app to it. */
    fun start(context: Context, offer: RegionOffer) {
        val app = context.applicationContext
        val bytes = manifest ?: return
        if (job?.isActive == true) return
        val offers = (download.value as? DownloadState.Offers)?.offers
            ?: (download.value as? DownloadState.Failed)?.offers.orEmpty()
        job = scope.launch {
            val part = partial(app, offer)
            try {
                part.parentFile?.mkdirs()
                val have = part.length()
                val free = StatFs(app.filesDir.path).availableBytes
                if (!hasRoomFor(offer.gzBytes.toLong(), have, offer.regionBytes.toLong(), free)) {
                    val need = mb(offer.gzBytes.toLong() - have + offer.regionBytes.toLong())
                    error(app.getString(R.string.region_no_room, need))
                }
                fetchFile(regionUrl(offer.fileName), part, offer)
                _download.value = DownloadState.Installing(offer)
                val target = installed(app).apply { parentFile?.mkdirs() }
                installRegion(bytes, offer.id, part.path, target.path)
                part.delete()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("id", offer.id).putString("name", offer.name).apply()
                lock.withLock {
                    _active.value = ActiveRegion(
                        RegionState.Ready(Engine.open(target.path)),
                        DownloadedRegion(offer.id, offer.name, offer.osmTimestamp),
                    )
                }
                _download.value = DownloadState.Offers(offers)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = DownloadState.Offers(offers)
                throw e
            } catch (e: Exception) {
                // A file the core refused is useless to resume.
                if (e is se.gangefors.moto.core.MotoException) part.delete()
                _download.value = DownloadState.Failed(describe(app, e), offers)
            }
        }
    }

    /**
     * Debug: times the region check twice in a row on the downloaded
     * region, step by step (a fast second run means the phone keeps the
     * file cached). Lines to show; empty without a downloaded region. Call
     * off the main thread.
     */
    fun profileOpen(context: Context): List<String> {
        val file = installed(context.applicationContext)
        if (!file.isFile) return emptyList()
        return (1..2).flatMap { run ->
            val steps = profileRegionOpen(file.path)
            val total = steps.sumOf { it.ms }
            listOf("Run $run: ${"%.2f".format(java.util.Locale.ROOT, total / 1000)} s") +
                steps.map { "  ${it.name}: ${"%.2f".format(java.util.Locale.ROOT, it.ms / 1000)} s" }
        }
    }

    /** Stops a download; what has arrived is kept to resume later. */
    fun cancel() {
        job?.cancel()
    }

    /** Removes the downloaded region; the bundled one (debug builds) takes over. */
    fun remove(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            lock.withLock {
                installed(app).delete()
                _active.value = ActiveRegion(BundledRegion.open(app), null)
            }
        }
    }

    private fun describe(context: Context, e: Exception): String = when (e) {
        is IOException -> context.getString(R.string.region_network, e.message ?: e.toString())
        else -> e.message ?: e.toString()
    }

    /** Opens [url] over HTTPS, following redirects only to HTTPS. */
    private fun connect(url: String, range: String?): HttpsURLConnection {
        var target = URL(url)
        repeat(MAX_REDIRECTS) {
            if (target.protocol != "https") throw IOException("refused a non-HTTPS address")
            val c = target.openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location") ?: throw IOException("redirect without a location")
                c.disconnect()
                target = URL(target, next)
            } else {
                return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun fetchSmall(url: String): ByteArray {
        val c = connect(url, null)
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { readCapped(it, MAX_MANIFEST_BYTES) }
                ?: throw IOException("the region list is too large")
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetchFile(url: String, part: File, offer: RegionOffer) {
        val total = offer.gzBytes.toLong()
        var have = part.length()
        if (have > total) {
            part.delete()
            have = 0
        }
        if (have == total) return
        val c = connect(url, resumeRange(have, total))
        try {
            when (resumeAction(c.responseCode, have)) {
                ResumeAction.APPEND -> Unit
                ResumeAction.RESTART -> have = 0
                ResumeAction.FAIL -> throw IOException("HTTP ${c.responseCode}")
            }
            _download.value = DownloadState.Downloading(offer, have)
            c.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastShown = have
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (have + n > total) throw IOException("the download is larger than listed")
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastShown >= PROGRESS_STEP) {
                            lastShown = have
                            _download.value = DownloadState.Downloading(offer, have)
                        }
                    }
                }
            }
            if (have != total) throw IOException("the download stopped early")
        } finally {
            c.disconnect()
        }
    }

    private const val MAX_REDIRECTS = 5
    private const val PROGRESS_STEP = 1L shl 20
}
