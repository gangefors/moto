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
import se.gangefors.moto.core.RegionFile
import se.gangefors.moto.core.RegionOffer
import se.gangefors.moto.core.installRegion
import se.gangefors.moto.core.openRegions
import se.gangefors.moto.core.parseRegionManifest
import se.gangefors.moto.core.regionManifestFileName

/** The network the app routes on, and the regions on the phone. */
data class ActiveRegion(val state: RegionState, val installed: List<InstalledRegion>)

/** What the region download is doing. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Checking : DownloadState
    data class Offers(val offers: List<RegionOffer>) : DownloadState
    data class Downloading(val offer: RegionOffer, val done: Long) : DownloadState
    data class Installing(val offer: RegionOffer) : DownloadState
    data class Failed(val message: String, val offers: List<RegionOffer>) : DownloadState
}

/** What the app knows about its routing network. */
sealed interface RegionState {
    data object Loading : RegionState
    data class Ready(val engine: Engine) : RegionState
    /** No region is installed and enabled; the rider downloads or enables one. */
    data object Missing : RegionState
    data class Failed(val message: String) : RegionState
}

/**
 * The routing regions (ADR-0008, ADR-0009): the downloaded ones, one file
 * each, opened together as one network linked at their borders; none
 * until the rider downloads one (no APK carries a region). A region can
 * be disabled without removing it: it stays on the phone but isn't opened.
 * Downloads run here, not in a screen, so they carry on when the Map region page is
 * closed; an interrupted download resumes where it stopped. The core checks everything
 * before a region is used.
 */
object Regions {
    private const val DIR = "regions"
    /** The single region file of app versions before ADR-0009. */
    private const val LEGACY_FILE = "downloaded.region"
    private const val PREFS = "regions"
    private const val INSTALLED = "installed"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null
    private var manifest: ByteArray? = null

    private val _active = MutableStateFlow(ActiveRegion(RegionState.Loading, emptyList()))
    val active: StateFlow<ActiveRegion> = _active.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    // The regions of an Update all still waiting their turn, by id.
    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    val waiting: StateFlow<Set<String>> = _waiting.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Region [id]'s file; the id is checked, so it can only name a file here. */
    private fun file(context: Context, id: String) = File(dir(context), regionFileName(id))

    private fun partial(context: Context, offer: RegionOffer) =
        File(File(context.noBackupFilesDir, DIR), "${offer.fileName}.part")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // The engine of the network in use, released once another replaces it.
    private var opened: Engine? = null

    /** Makes [next] the network in use; the engine it replaces is released. */
    private fun use(next: ActiveRegion) {
        val previous = opened
        opened = (next.state as? RegionState.Ready)?.engine
        _active.value = next
        if (previous != null && previous !== opened) NativeRelease.later(previous)
    }

    /** Opens the enabled regions at app start. */
    suspend fun load(context: Context) = lock.withLock {
        if (_active.value.state !is RegionState.Loading) return@withLock
        DebugTools.mark("region load started")
        use(
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                migrate(app)
                open(app)
            },
        )
        DebugTools.mark("region ready")
    }

    /**
     * Moves the single region of app versions before ADR-0009 to its own
     * file, named by its id; a file without a valid id is dropped (the
     * rider can download it again).
     */
    private fun migrate(context: Context) {
        val legacy = File(dir(context), LEGACY_FILE)
        if (!legacy.isFile) return
        val p = prefs(context)
        val id = p.getString("id", null)
        if (id != null && isRegionId(id) && legacy.renameTo(file(context, id))) {
            p.edit()
                .putStringSet(INSTALLED, installedIds(context) + id)
                .putString("name.$id", p.getString("name", null) ?: id)
                .remove("id").remove("name").remove("fingerprint")
                .apply()
        } else {
            legacy.delete()
            p.edit().remove("id").remove("name").remove("fingerprint").apply()
        }
    }

    private fun installedIds(context: Context): Set<String> =
        prefs(context).getStringSet(INSTALLED, emptySet()).orEmpty().filter(::isRegionId).toSet()

    /** The regions on the phone, by id; ids whose file is gone are dropped. */
    private fun installedList(context: Context): List<InstalledRegion> {
        val p = prefs(context)
        return installedIds(context).mapNotNull { id ->
            val f = file(context, id)
            if (!f.isFile) return@mapNotNull null
            InstalledRegion(
                id = id,
                name = p.getString("name.$id", null) ?: id,
                osmTimestamp = p.getLong("ts.$id", 0),
                bytes = f.length(),
                enabled = !p.getBoolean("off.$id", false),
                gzSha256 = p.getString("sha.$id", null),
            )
        }.sortedBy { it.name }
    }

    /**
     * Opens the enabled regions as one network (the core checks each by
     * its recorded fingerprint, or in full when it has none or no longer
     * matches, and hands back each file's fingerprint to keep). A region
     * that no longer opens is dropped; the rider can download it again.
     */
    private fun open(context: Context): ActiveRegion {
        val p = prefs(context)
        var installed = installedList(context)
        val enabled = enabledRegions(installed)
        if (enabled.isEmpty()) return ActiveRegion(RegionState.Missing, installed)
        val files = enabled.map { RegionFile(file(context, it.id).path, p.getString("fp.${it.id}", null)) }
        val size = "${enabled.size} region(s), ${installedBytes(enabled) / 1_000_000} MB"
        return try {
            val opened = DebugTools.startup("regions open ($size)") { openRegions(files) }
            val infos = opened.engine.regionInfos()
            val edit = p.edit()
            enabled.forEachIndexed { i, r ->
                // For the debug report: by checksum or checked in full.
                val how = openCheck(p.getString("fp.${r.id}", null), opened.fingerprints.getOrNull(i))
                DebugTools.mark("  ${r.id} ${r.bytes / 1_000_000} MB: ${how.name.lowercase().replace('_', ' ')}")
                opened.fingerprints.getOrNull(i)?.let { edit.putString("fp.${r.id}", it) }
                infos.getOrNull(i)?.let { edit.putLong("ts.${r.id}", it.osmTimestamp) }
            }
            edit.apply()
            installed = installedList(context)
            ActiveRegion(RegionState.Ready(opened.engine), installed)
        } catch (e: Exception) {
            // Find the file that no longer opens (the core names it) and
            // drop it; the others open next time.
            val bad = enabled.firstOrNull { e.message?.contains(file(context, it.id).path) == true }
            if (bad != null) {
                drop(context, bad.id)
                open(context)
            } else {
                ActiveRegion(RegionState.Failed(e.message ?: e.toString()), installed)
            }
        }
    }

    /** Forgets region [id] and deletes its file. */
    private fun drop(context: Context, id: String) {
        prefs(context).edit()
            .putStringSet(INSTALLED, installedIds(context) - id)
            .remove("name.$id").remove("fp.$id").remove("ts.$id").remove("off.$id").remove("sha.$id")
            .apply()
        file(context, id).delete()
    }

    /** Opens the network again after a change, the app switching to it. */
    private fun reopen(context: Context) {
        use(open(context))
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

    /** Downloads and installs [offer] (enabled), then opens the network with it. */
    fun start(context: Context, offer: RegionOffer) = start(context, listOf(offer))

    /**
     * Downloads and installs [queue] one after another (Update all), each
     * opened with the network as it is installed. A failure stops the rest;
     * cancelling stops the one downloading and those still waiting.
     */
    fun start(context: Context, queue: List<RegionOffer>) {
        val app = context.applicationContext
        val bytes = manifest ?: return
        if (job?.isActive == true) return
        val list = queue.filter { isRegionId(it.id) }
        if (list.isEmpty()) return
        val offers = (download.value as? DownloadState.Offers)?.offers
            ?: (download.value as? DownloadState.Failed)?.offers.orEmpty()
        job = scope.launch {
            try {
                list.forEachIndexed { i, offer ->
                    _waiting.value = list.drop(i + 1).map { it.id }.toSet()
                    if (!install(app, bytes, offer, offers)) return@launch
                }
                _download.value = DownloadState.Offers(offers)
            } finally {
                _waiting.value = emptySet()
            }
        }
    }

    /** Downloads and installs [offer]; false (and the download failed) when it didn't. */
    private suspend fun install(app: Context, bytes: ByteArray, offer: RegionOffer, offers: List<RegionOffer>): Boolean {
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
            val target = file(app, offer.id).apply { parentFile?.mkdirs() }
            lock.withLock {
                val fp = installRegion(bytes, offer.id, part.path, target.path)
                part.delete()
                prefs(app).edit()
                    .putStringSet(INSTALLED, installedIds(app) + offer.id)
                    .putString("name.${offer.id}", offer.name)
                    .putString("fp.${offer.id}", fp)
                    .putLong("ts.${offer.id}", offer.osmTimestamp)
                    .putString("sha.${offer.id}", offer.gzSha256)
                    .remove("off.${offer.id}")
                    .apply()
                reopen(app)
            }
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            _download.value = DownloadState.Offers(offers)
            throw e
        } catch (e: Exception) {
            // A file the core refused is useless to resume.
            if (e is se.gangefors.moto.core.MotoException) part.delete()
            _download.value = DownloadState.Failed(describe(app, e), offers)
            return false
        }
    }

    /** The installed regions' files, for the debug tools. */
    fun installedFiles(context: Context): List<File> =
        installedList(context.applicationContext).map { file(context.applicationContext, it.id) }

    /** Stops a download; what has arrived is kept to resume later. */
    fun cancel() {
        job?.cancel()
    }

    /** Removes region [id] from the phone; the network opens without it. */
    fun remove(context: Context, id: String) = change(context) { drop(it, id) }

    /**
     * Enables or disables region [id]: a disabled region stays on the phone
     * but isn't used for routing; enabling it needs no download.
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean) = change(context) {
        prefs(it).edit().putBoolean("off.$id", !enabled).apply()
    }

    private fun change(context: Context, what: (Context) -> Unit) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            lock.withLock {
                _active.value = _active.value.copy(state = RegionState.Loading)
                what(app)
                reopen(app)
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
