// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.debug.DebugTools
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
import se.gangefors.moto.core.regionFingerprint
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

/** What the app knows about its routing region. */
sealed interface RegionState {
    data object Loading : RegionState
    data class Ready(val engine: Engine) : RegionState
    /** No region is installed; the rider downloads one. */
    data object Missing : RegionState
    data class Failed(val message: String) : RegionState
}

/**
 * The routing region: the downloaded one, or none until the rider downloads
 * one (no APK carries a region, ADR-0008). Downloads run here, not in a screen, so they
 * carry on when the Map region page is closed; an interrupted download resumes where
 * it stopped. The core checks everything before a region is used.
 */
object Regions {
    private const val DIR = "regions"
    private const val FILE = "downloaded.region"
    private const val PREFS = "regions"
    /** Fingerprint of the installed file, recorded once it passed the full check. */
    private const val FINGERPRINT = "fingerprint"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null
    private var manifest: ByteArray? = null
    /** Counts installs and removals, so a stale fingerprint is never recorded. */
    @Volatile private var installs = 0

    private val _active = MutableStateFlow(ActiveRegion(RegionState.Loading, null))
    val active: StateFlow<ActiveRegion> = _active.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private fun installed(context: Context) = File(File(context.filesDir, DIR), FILE)

    private fun partial(context: Context, offer: RegionOffer) =
        File(File(context.noBackupFilesDir, DIR), "${offer.fileName}.part")

    /** Opens the downloaded region at app start, if there is one. */
    suspend fun load(context: Context) = lock.withLock {
        if (_active.value.state !is RegionState.Loading) return@withLock
        DebugTools.mark("region load started")
        _active.value = withContext(Dispatchers.IO) { open(context.applicationContext) }
        DebugTools.mark("region ready")
    }

    private fun open(context: Context): ActiveRegion {
        val file = installed(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (file.isFile) {
            try {
                val size = "${file.length() / 1_000_000} MB"
                val opened = openRegion(
                    prefs.getString(FINGERPRINT, null),
                    { fp -> DebugTools.startup("region open by fingerprint ($size)") { Engine.openFingerprinted(file.path, fp) } },
                    { DebugTools.startup("region open, full check ($size)") { Engine.open(file.path) } },
                )
                val engine = opened.region
                if (opened.needsFingerprint) recordFingerprint(context, file)
                val meta = DownloadedRegion(
                    id = prefs.getString("id", null) ?: "",
                    name = prefs.getString("name", null) ?: "",
                    osmTimestamp = engine.info().osmTimestamp,
                )
                return ActiveRegion(RegionState.Ready(engine), meta)
            } catch (_: Exception) {
                // A file that no longer opens is dropped; the rider can
                // download it again.
                prefs.edit().remove(FINGERPRINT).apply()
                file.delete()
            }
        }
        return ActiveRegion(RegionState.Missing, null)
    }

    /**
     * Records the fingerprint of [file], which just passed the full check,
     * so the next start can open it quickly. In the background: it reads
     * the whole file once. Skipped if the file was replaced or removed
     * meanwhile (an install records its own).
     */
    private fun recordFingerprint(context: Context, file: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(FINGERPRINT).apply()
        val generation = installs
        scope.launch {
            val fp = try {
                regionFingerprint(file.path)
            } catch (_: Exception) {
                return@launch
            }
            lock.withLock {
                if (installs == generation && file.isFile) prefs.edit().putString(FINGERPRINT, fp).apply()
            }
        }
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
                installs++
                val fp = installRegion(bytes, offer.id, part.path, target.path)
                part.delete()
                lock.withLock {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("id", offer.id).putString("name", offer.name)
                        .putString(FINGERPRINT, fp).apply()
                    _active.value = ActiveRegion(
                        RegionState.Ready(Engine.openFingerprinted(target.path, fp)),
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

    /** The installed downloaded region's file (it may not exist). */
    fun installedFile(context: Context): File = installed(context.applicationContext)

    /** Stops a download; what has arrived is kept to resume later. */
    fun cancel() {
        job?.cancel()
    }

    /** Removes the downloaded region; the app is left without one. */
    fun remove(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            lock.withLock {
                installs++
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(FINGERPRINT).apply()
                installed(app).delete()
                _active.value = ActiveRegion(RegionState.Missing, null)
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
