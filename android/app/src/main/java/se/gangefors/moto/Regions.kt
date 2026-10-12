// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.debug.DebugTools
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 * Who runs the download queue (ADR-0017): the network its connections
 * use (null: the phone's default), how it waits for an allowed network,
 * and where the bytes it moved are reported.
 */
internal interface RegionHost {
    val network: Network?
    /** Returns once the queue's network policy allows the network. */
    suspend fun awaitNetwork(app: Context)
    /** [bytes] moved so far in this run. */
    fun transferred(bytes: Long) = Unit
}

/** How one run of the queue ended: done, failed, to be retried after a
 * wait, or to wait for an allowed network (the in-app host). */
internal enum class RunOutcome { DONE, FAILED, RETRY, WAIT }

/**
 * The routing regions (ADR-0008, ADR-0009): the downloaded ones, one file
 * each, opened together as one network linked at their borders; none
 * until the rider downloads one (no APK carries a region). A region can
 * be disabled without removing it: it stays on the phone but isn't opened.
 *
 * Downloads (ADR-0017) form a queue kept in the preferences, so it outlives
 * the screens and the app's process: one region at a time, each from a
 * freshly fetched signed manifest, resumed from its partial file after a
 * lost connection or a kill, on the networks the rider allows. The core
 * checks everything before a region is installed or used.
 */
object Regions {
    private const val DIR = "regions"
    /** The single region file of app versions before ADR-0009. */
    private const val LEGACY_FILE = "downloaded.region"
    private const val PREFS = "regions"
    private const val INSTALLED = "installed"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // The download queue in the preferences (ADR-0017), read back checked.
    private const val Q_IDS = "queue"
    private const val Q_OFF = "queue.off"
    private const val Q_DONE = "queue.done"
    private const val Q_RETRIES = "queue.retries"
    private const val Q_ONCE = "queue.once"
    private const val Q_FAILED = "queue.failed"
    private const val Q_NAME = "qname."
    private const val PART_SHA = "partsha."
    /** "Download over mobile data"; not in backups. */
    private const val MOBILE_DATA = "mobile_data"
    /** The notification permission was asked for once (ADR-0017). */
    private const val NOTIF_ASKED = "notif_asked"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var checkJob: Job? = null
    private var changeJob: Job? = null
    @Volatile private var runner: Job? = null
    /** A run has the queue in hand and will take any region queued now;
     * set and cleared under [queueLock] with the queue's last check. */
    @Volatile private var runActive = false
    /** The offers of the last manifest read. */
    @Volatile private var lastOffers: List<RegionOffer> = emptyList()
    /** The regions installed in this batch, for the ready notice. */
    private val batchNames = mutableListOf<String>()
    /** [load] has opened the network in this process: only then is it
     * reopened after an install; before that an install lists the regions
     * on the phone instead (a job run with the app closed never opens it). */
    @Volatile private var loaded = false
    /** Guards the queue's read-change-commit. */
    private val queueLock = Any()

    /** Whether MainActivity is started (the app is open), see [setInFront]. */
    @Volatile private var inFront = false

    /** The last batch ended without a notice (app open, or nothing installed). */
    @Volatile private var quietEnd = false

    /** The last queue notice posted, to set once more as the job ends. */
    @Volatile private var lastShown: NoticeState? = null
    @Volatile private var queueRead = false
    @Volatile private var watching = false

    private val _active = MutableStateFlow(ActiveRegion(RegionState.Loading, emptyList()))
    val active: StateFlow<ActiveRegion> = _active.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _queue = MutableStateFlow(DownloadQueue())
    /** The download queue, as kept on the phone. */
    val queue: StateFlow<DownloadQueue> = _queue.asStateFlow()

    private val _installing = MutableStateFlow<String?>(null)

    /** The region being installed: from the core's check until the network
     * has been opened again with it (or that failed), or, before the app has
     * opened its regions, until it is listed; null otherwise. Not persisted. */
    val installing: StateFlow<String?> = _installing.asStateFlow()

    private val _running = MutableStateFlow<RunPhase?>(null)
    /** What the runner is doing to the queue's head; null while it isn't running. */
    val running: StateFlow<RunPhase?> = _running.asStateFlow()

    private val _policy = MutableStateFlow(NetworkPolicy.UNMETERED)
    /** The networks the queue may use now. */
    val policy: StateFlow<NetworkPolicy> = _policy.asStateFlow()

    private val _mobileData = MutableStateFlow(false)
    /** The "Download over mobile data" setting. */
    val mobileData: StateFlow<Boolean> = _mobileData.asStateFlow()

    private val _network = MutableStateFlow(NetworkNow.OFFLINE)
    /** The phone's default network. */
    val network: StateFlow<NetworkNow> = _network.asStateFlow()

    private val _ask = MutableStateFlow<MobileAsk?>(null)
    /** The mobile-data question, while it waits for an answer. */
    val ask: StateFlow<MobileAsk?> = _ask.asStateFlow()

    private val _pageRequests = MutableStateFlow(false)
    /** A notice was tapped: the Map region page should open. */
    val pageRequests: StateFlow<Boolean> = _pageRequests.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Region [id]'s file; the id is checked, so it can only name a file here. */
    private fun file(context: Context, id: String) = File(dir(context), regionFileName(id))

    /** The folder of partial downloads, in no-backup storage. */
    private fun partDir(context: Context) = File(context.noBackupFilesDir, DIR)

    /** Region [id]'s partial download; the id is checked. */
    private fun partial(context: Context, id: String) = File(partDir(context), partialFileName(id))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Stored values are read defensively: a wrong type reads as missing.
    private fun SharedPreferences.str(key: String): String? = runCatching { getString(key, null) }.getOrNull()
    private fun SharedPreferences.int(key: String): Int = runCatching { getInt(key, 0) }.getOrDefault(0)
    private fun SharedPreferences.bool(key: String): Boolean = runCatching { getBoolean(key, false) }.getOrDefault(false)
    private fun SharedPreferences.strings(key: String): Set<String>? = runCatching { getStringSet(key, null) }.getOrNull()

    // The engine of the network in use, released once another replaces it.
    private var opened: Engine? = null

    /** Makes [next] the network in use; the engine it replaces is released. */
    private fun use(next: ActiveRegion) {
        val previous = opened
        opened = (next.state as? RegionState.Ready)?.engine
        _active.value = next
        if (previous != null && previous !== opened) NativeRelease.later(previous)
    }

    /** Lists the regions on the phone while the network is loading (see
     * withPhoneList). The list is read inside the update, so a race never
     * leaves an older list in place of a newer one. Not on the main thread. */
    private fun listOnPhone(app: Context) = _active.update { withPhoneList(it, installedList(app)) }

    /** Opens the enabled regions at app start. */
    suspend fun load(context: Context) {
        // Before the lock, so the list shows even while an install holds it.
        withContext(Dispatchers.IO) { listOnPhone(context.applicationContext) }
        loadLocked(context)
    }

    private suspend fun loadLocked(context: Context) = lock.withLock {
        if (_active.value.state !is RegionState.Loading) return@withLock
        DebugTools.mark("region load started")
        use(
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                migrate(app)
                open(app)
            },
        )
        loaded = true
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

    /** Fetches the list of regions this app can read (on any network: it is small). */
    fun check(context: Context) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            // A download's progress is never replaced by the lookup.
            fun busy() = _download.value.let { it is DownloadState.Downloading || it is DownloadState.Installing }
            if (!busy()) _download.value = DownloadState.Checking
            val next = try {
                val bytes = fetchSmall(regionUrl(regionManifestFileName()), null)
                val offers = parseRegionManifest(bytes)
                lastOffers = offers
                DownloadState.Offers(offers)
            } catch (e: Exception) {
                DownloadState.Failed(describe(context, e), emptyList())
            }
            if (!busy()) _download.value = next
        }
    }

    /** The ids of the regions on the phone, on or off (for a backup). */
    fun installedRegionIds(context: Context): Set<String> = installedIds(context.applicationContext)

    /** The ids of the regions on the phone that are switched off (for a backup). */
    fun disabledRegionIds(context: Context): Set<String> {
        val app = context.applicationContext
        val p = prefs(app)
        return installedIds(app).filter { p.getBoolean("off.$it", false) }.toSet()
    }

    // ---- The download queue (ADR-0017) ----

    /** Reads the queue and the setting once per process, and starts watching the network. */
    private fun init(app: Context) {
        if (!queueRead) {
            synchronized(queueLock) {
                if (!queueRead) {
                    val p = prefs(app)
                    _queue.value = readQueue(
                        p.str(Q_IDS), p.strings(Q_OFF), p.int(Q_DONE), p.str(Q_ONCE), p.str(Q_FAILED), p.int(Q_RETRIES),
                    )
                    _mobileData.value = p.bool(MOBILE_DATA)
                    _policy.value = downloadPolicy(_mobileData.value, _queue.value.once)
                    queueRead = true
                }
            }
        }
        watchNetwork(app)
    }

    /**
     * MainActivity started or stopped (ADR-0017). Opening the app takes a
     * ready notice away, and a leftover progress one once the queue is
     * empty, never a failed one or the notice of a running queue.
     */
    fun setInFront(context: Context, front: Boolean) {
        inFront = front
        if (!front) return
        val app = context.applicationContext
        init(app)
        synchronized(queueLock) {
            if (clearsInFront(RegionNotices.shownKind(app), _queue.value.active)) RegionNotices.show(app, null)
        }
    }

    /** Follows the phone's default network (ACCESS_NETWORK_STATE only). */
    private fun watchNetwork(app: Context) {
        if (watching) return
        synchronized(queueLock) {
            if (watching) return
            watching = true
        }
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        _network.value = cm.activeNetwork?.let { n -> cm.getNetworkCapabilities(n)?.let { networkNow(cm, n, it) } } ?: NetworkNow.OFFLINE
        try {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        _network.value = networkNow(cm, network, caps)
                    }

                    override fun onLost(network: Network) {
                        _network.value = NetworkNow.OFFLINE
                    }
                },
            )
        } catch (e: RuntimeException) {
            // Too many callbacks or a platform refusal: rows then show the
            // network as it was; the job's own constraint still holds.
            DebugTools.mark("network watch failed: $e")
        }
    }

    private fun networkNow(cm: ConnectivityManager, network: Network, caps: NetworkCapabilities) = NetworkNow(
        connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        roaming = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            // Android 8.x has no roaming capability yet.
            @Suppress("DEPRECATION")
            cm.getNetworkInfo(network)?.isRoaming == true
        },
    )

    /** Writes [q] into [edit]; the names and partial checksums of regions no
     * longer queued go with it. */
    private fun putQueue(p: SharedPreferences, edit: SharedPreferences.Editor, q: DownloadQueue) {
        edit.putString(Q_IDS, queueIdsString(q.ids))
            .putStringSet(Q_OFF, HashSet(q.off))
            .putInt(Q_DONE, q.done)
            .putInt(Q_RETRIES, q.retries)
            .putString(Q_ONCE, onceString(q.once))
        if (q.failed != null) edit.putString(Q_FAILED, q.failed) else edit.remove(Q_FAILED)
        for (key in p.all.keys) {
            val prefix = when {
                key.startsWith(Q_NAME) -> Q_NAME
                key.startsWith(PART_SHA) -> PART_SHA
                else -> continue
            }
            if (key.removePrefix(prefix) !in q.ids) edit.remove(key)
        }
    }

    /** Changes the queue and commits it at once (it must survive a kill),
     * with [extra] in the same write. */
    @android.annotation.SuppressLint("ApplySharedPref")
    private fun updateQueue(
        app: Context,
        extra: (SharedPreferences.Editor) -> Unit = {},
        change: (DownloadQueue) -> DownloadQueue,
    ): DownloadQueue = synchronized(queueLock) {
        val p = prefs(app)
        val next = change(_queue.value)
        val edit = p.edit()
        extra(edit)
        putQueue(p, edit, next)
        edit.commit()
        _queue.value = next
        _policy.value = downloadPolicy(_mobileData.value, next.once)
        next
    }

    /** The name to show for queued region [id]. */
    private fun nameOf(app: Context, id: String): String =
        lastOffers.firstOrNull { it.id == id }?.name ?: prefs(app).str(Q_NAME + id) ?: id

    /** Bytes of region [id] already downloaded, for its row. */
    fun partBytes(context: Context, id: String): Long =
        if (isRegionId(id)) partial(context.applicationContext, id).length() else 0

    /**
     * Queues [offers] (Download, Update, Update all): one region downloads
     * at a time, those added while one runs wait their turn. Those in [off]
     * are installed switched off (a restore, ADR-0016).
     */
    fun enqueue(context: Context, offers: List<RegionOffer>, off: Set<String> = emptySet()) {
        val app = context.applicationContext
        val valid = offers.filter { isRegionId(it.id) }
        enqueueIds(app, valid.map { it.id }, off, valid.associate { it.id to it.name })
    }

    private fun enqueueIds(app: Context, ids: List<String>, off: Set<String>, names: Map<String, String>) {
        init(app)
        // Atomic with the run's last look at the queue: either the run
        // takes the new regions, or a new one is started for them.
        val (q, taken) = synchronized(queueLock) {
            // A new batch: the ready notice names only its regions.
            if (!_queue.value.active) synchronized(batchNames) { batchNames.clear() }
            val q = updateQueue(app, { e -> names.forEach { (id, name) -> e.putString(Q_NAME + id, name) } }) {
                enqueue(it, ids, off)
            }
            q to runActive
        }
        if (!q.active) return
        _ask.value = mobileDataAsk(_policy.value, _network.value)
        if (taken) return
        schedule(app)
    }

    /**
     * After a restore: queues those of [ids] that aren't on the phone, one
     * after another, as Update all does; those in [off] are installed
     * switched off. Ids no longer offered are skipped when their turn comes.
     */
    fun downloadMissing(context: Context, ids: Collection<String>, off: Set<String> = emptySet()) {
        val app = context.applicationContext
        val want = missingRegions(ids.toList(), installedIds(app))
        if (want.isEmpty()) return
        val names = lastOffers.filter { it.id in want }.associate { it.id to it.name }
        enqueueIds(app, want, off, names)
    }

    /**
     * Stops the queue: the download and the regions waiting, with what has
     * arrived deleted at once (the rider, 2026-10-11); no result notice.
     */
    fun stop(context: Context) {
        val app = context.applicationContext
        init(app)
        val stopping = runner
        cancelHost(app)
        updateQueue(app) { DownloadQueue() }
        _ask.value = null
        _running.value = null
        synchronized(batchNames) { batchNames.clear() }
        post(app, null)
        if (lastOffers.isNotEmpty()) _download.value = DownloadState.Offers(lastOffers)
        restartAfter(app, stopping, clean = true)
    }

    /**
     * Once the cancelled run [stopping] has ended (an install under way
     * finishes first: it can't be cut short), deletes the partials of
     * regions no longer queued ([clean]) and starts a run for regions
     * queued meanwhile: while it was ending they were left to it, as it
     * still counted as running.
     */
    private fun restartAfter(app: Context, stopping: Job?, clean: Boolean = false) {
        scope.launch {
            stopping?.cancelAndJoin()
            if (clean) sweep(app)
            if (needsRun(_queue.value)) schedule(app)
        }
    }

    /** Try again after a failure: carries on from the region that failed. */
    fun retry(context: Context) {
        val app = context.applicationContext
        init(app)
        val q = updateQueue(app) { it.copy(failed = null, retries = 0) }
        if (!q.active) return
        _ask.value = mobileDataAsk(_policy.value, _network.value)
        schedule(app)
    }

    /** "Download now": this queue may use [grant] networks. */
    fun allowOnce(context: Context, grant: NetworkPolicy) {
        val app = context.applicationContext
        init(app)
        _ask.value = null
        val q = updateQueue(app) { it.copy(once = grant) }
        if (needsRun(q)) schedule(app)
    }

    /** "Wait for Wi-Fi": the queue waits for an allowed network. */
    fun dismissAsk() {
        _ask.value = null
    }

    /** Asks the mobile-data question again ("Download now" on a waiting row). */
    fun askAgain() {
        _ask.value = mobileDataAsk(_policy.value, _network.value)
    }

    /** The "Download over mobile data" setting, written at once: the job is
     * scheduled with it right after. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun setMobileData(context: Context, on: Boolean) {
        val app = context.applicationContext
        init(app)
        prefs(app).edit().putBoolean(MOBILE_DATA, on).commit()
        _mobileData.value = on
        _policy.value = downloadPolicy(on, _queue.value.once)
        _ask.value?.let { _ask.value = mobileDataAsk(_policy.value, _network.value) }
        val q = _queue.value
        if (needsRun(q)) schedule(app)
    }

    /**
     * At app start, after [load]: follows the network, deletes stray
     * partial and temporary files, and carries on with a queue that was
     * interrupted (a kill, a phone restart), without a tap.
     */
    fun resume(context: Context) {
        val app = context.applicationContext
        init(app)
        scope.launch { sweep(app) }
        val q = _queue.value
        if (needsRun(q) && !hostPending(app)) schedule(app)
    }

    /** Whether the notification permission was asked for on a download (once). */
    fun notificationsAsked(context: Context): Boolean = prefs(context.applicationContext).bool(NOTIF_ASKED)

    fun markNotificationsAsked(context: Context) {
        prefs(context.applicationContext).edit().putBoolean(NOTIF_ASKED, true).apply()
    }

    /** A notice was tapped: open the Map region page. */
    fun requestPage() {
        _pageRequests.value = true
    }

    /** The Map region page was opened for the request. */
    fun pageOpened() {
        _pageRequests.value = false
    }

    /**
     * Deletes every partial file but those of queued regions, and a
     * temporary file an install that was killed left behind (under the
     * lock, so never one an install is writing).
     */
    private suspend fun sweep(app: Context) {
        val parts = partDir(app)
        partialsToDelete(parts.list()?.toList().orEmpty(), _queue.value.ids).forEach { File(parts, it).delete() }
        lock.withLock {
            dir(app).listFiles { f -> f.name.endsWith(".region.tmp") }?.forEach { it.delete() }
        }
    }

    // ---- Hosts ----

    /** The one region download job. */
    private const val JOB_ID = 17

    /**
     * Starts the queue on this phone's host: the user-initiated job on
     * Android 14 and newer (scheduling it again replaces a running one,
     * which goes on from its partial), the in-app host on older versions
     * or when the job can't be scheduled.
     */
    private fun schedule(app: Context) {
        val q = _queue.value
        if (!allows(_policy.value, _network.value)) postWaiting(app, q)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && scheduleJob(app)) return
        startInApp(app)
    }

    /** Schedules the job; false when Android refused it (it then runs in the app). */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleJob(app: Context): Boolean = try {
        val b = JobInfo.Builder(JOB_ID, ComponentName(app, RegionDownloadJob::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(networkRequest(_policy.value))
            .setBackoffCriteria(BACKOFF_START_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        remainingBytes(app)?.let { b.setEstimatedNetworkBytes(it, 0) }
        val ok = app.getSystemService(JobScheduler::class.java).schedule(b.build()) == JobScheduler.RESULT_SUCCESS
        if (!ok) DebugTools.mark("region job not scheduled")
        ok
    } catch (e: RuntimeException) {
        // No permission, or the app isn't visible.
        DebugTools.mark("region job not scheduled: $e")
        false
    }

    /** The networks [policy] allows, as the job's constraint: validated
     * ones only; a VPN counts as the network under it. */
    private fun networkRequest(policy: NetworkPolicy): NetworkRequest {
        val b = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // A network that really reaches the internet (not a captive portal).
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        when (policy) {
            NetworkPolicy.UNMETERED -> b.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            NetworkPolicy.HOME -> b.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            NetworkPolicy.ANY -> Unit
        }
        return b.build()
    }

    /** Bytes still to download for the queue, when every queued offer's size is known. */
    private fun remainingBytes(app: Context): Long? {
        val offers = _queue.value.ids.map { id -> lastOffers.firstOrNull { it.id == id } ?: return null }
        return offers.sumOf { (it.gzBytes.toLong() - partBytes(app, it.id)).coerceAtLeast(0) }
    }

    /** Whether a host already has the queue in hand. */
    private fun hostPending(app: Context): Boolean =
        runner?.isActive == true ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                app.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null)

    /** Stops the host that runs the queue. */
    private fun cancelHost(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
        runner?.cancel()
    }

    /** The job's parameters while it runs the queue. */
    @Volatile private var jobParams: JobParameters? = null

    /** The notice a job shows as it starts. */
    internal fun startingNotice(app: Context): NoticeState {
        init(app)
        val q = _queue.value
        val name = q.ids.firstOrNull()?.let { nameOf(app, it) } ?: ""
        return NoticeState.Starting(q.index, q.total, name).also { lastShown = it }
    }

    /** The job started: runs the queue in it (a run already going is stopped first). */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    internal fun runJob(app: Context, params: JobParameters, service: JobService) = synchronized(queueLock) {
        val previous = runner
        jobParams = params
        runner = scope.launch {
            previous?.cancelAndJoin()
            val outcome = runQueue(app, JobHost(params, service))
            if (jobParams === params) jobParams = null
            // A batch that ended without a notice: Android removes the job's
            // last progress notice as the job ends (DETACH would keep it).
            val last = lastShown
            if (outcome == RunOutcome.DONE && quietEnd && last != null) {
                try {
                    service.setNotification(
                        params,
                        RegionNotices.NOTICE_ID,
                        RegionNotices.build(app, last),
                        JobService.JOB_END_NOTIFICATION_POLICY_REMOVE,
                    )
                } catch (e: RuntimeException) {
                    DebugTools.mark("job notification not removed: ${e.javaClass.simpleName}")
                }
            }
            service.jobFinished(params, outcome == RunOutcome.RETRY || outcome == RunOutcome.WAIT)
            // A region queued while the run failed cleared the failure but was
            // left to this run, which had already stopped: start one for it.
            // (The run no longer counts as running, so later ones start their own.)
            if (outcome == RunOutcome.FAILED && needsRun(_queue.value)) schedule(app)
        }
    }

    /**
     * Android stopped the job: the network went, or the rider stopped the
     * app in the active-apps panel (the queue ends). True asks Android to
     * run it again; the partial is kept.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    internal fun jobStopped(app: Context, params: JobParameters): Boolean {
        init(app)
        synchronized(queueLock) {
            if (jobParams === params) {
                runner?.cancel()
                jobParams = null
            }
        }
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            stop(app)
            return false
        }
        val q = _queue.value
        if (!q.active || q.failed != null) return false
        // Replaced by a new schedule: the new job shows its own notice.
        if (params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP) postWaiting(app, q)
        return true
    }

    /** The job as the runner's host: its network, and the bytes it moved. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class JobHost(private val params: JobParameters, private val service: JobService) : RegionHost {
        override val network: Network? = params.network

        // Android starts the job only on a network its constraint allows.
        override suspend fun awaitNetwork(app: Context) = Unit

        override fun transferred(bytes: Long) {
            service.updateTransferredNetworkBytes(params, bytes, 0)
        }
    }

    /**
     * The in-app host (Android 8 to 13, and the fallback): runs the queue in
     * the app's process, waits for an allowed network itself and retries
     * after a wait.
     */
    private fun startInApp(app: Context) = synchronized(queueLock) {
        if (inAppLooping != 0) return@synchronized
        val loop = ++inAppLoops
        inAppLooping = loop
        runner = scope.launch {
            try {
                while (true) {
                    val retries = _queue.value.retries
                    when (runQueue(app, InAppHost)) {
                        RunOutcome.RETRY -> delay(backoffMs(retries))
                        // The next run waits for an allowed network first.
                        RunOutcome.WAIT -> Unit
                        else -> {
                            // Ends only when nothing was queued as the run
                            // finished (atomic with startInApp's check).
                            val again = synchronized(queueLock) {
                                val q = _queue.value
                                needsRun(q).also { if (!it && inAppLooping == loop) inAppLooping = 0 }
                            }
                            if (!again) break
                        }
                    }
                }
            } finally {
                synchronized(queueLock) { if (inAppLooping == loop) inAppLooping = 0 }
            }
        }
    }

    /** The in-app host's loop that runs (0: none), and the last one
     * started; guarded by [queueLock]. */
    private var inAppLooping = 0
    private var inAppLoops = 0

    private object InAppHost : RegionHost {
        override val network: Network? = null

        override suspend fun awaitNetwork(app: Context) {
            if (allows(_policy.value, _network.value)) return
            _running.value = null
            postWaiting(app, _queue.value)
            combine(_policy, _network) { p, n -> allows(p, n) }.first { it }
        }
    }

    // ---- The runner ----

    /**
     * Runs the queue once on [host]: a fresh signed manifest, then each
     * queued region in turn, checked and installed by the core; until the
     * queue is empty, a region fails, or an attempt is to be retried.
     */
    internal suspend fun runQueue(app: Context, host: RegionHost): RunOutcome {
        init(app)
        synchronized(queueLock) { runActive = true }
        quietEnd = false
        try {
            return runQueueOnce(app, host)
        } finally {
            synchronized(queueLock) { runActive = false }
        }
    }

    @android.annotation.SuppressLint("ApplySharedPref") // The partial's checksum must be on disk before its bytes.
    private suspend fun runQueueOnce(app: Context, host: RegionHost): RunOutcome {
        var head: String? = null
        var before = -1L
        try {
            sweep(app)
            host.awaitNetwork(app)
            _queue.value.ids.firstOrNull()?.let { setPhase(app, RunPhase.STARTING, it) }
            val bytes = fetchSmall(regionUrl(regionManifestFileName()), host.network)
            val offers = parseRegionManifest(bytes)
            lastOffers = offers
            var moved = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val id = _queue.value.ids.firstOrNull()
                if (id == null) {
                    // Done, unless a region was queued meanwhile. Under the
                    // lock a region queued from here on starts a new batch,
                    // so this batch's names and its ready notice are settled
                    // here first (a new batch's waiting notice comes after).
                    val goOn = synchronized(queueLock) {
                        val active = updateQueue(app) { if (it.active) it else DownloadQueue() }.active
                        if (!active) {
                            runActive = false
                            val names = synchronized(batchNames) { batchNames.toList().also { batchNames.clear() } }
                            _running.value = null
                            _download.value = DownloadState.Offers(offers)
                            val ready = readyNotice(names, inFront)
                            quietEnd = ready == null
                            post(app, ready)
                        }
                        active
                    }
                    if (goOn) continue
                    break
                }
                head = id
                before = -1L
                val offer = offers.firstOrNull { it.id == id }
                if (offer == null || offerAction(installedList(app), id, offer.osmTimestamp, offer.gzSha256) == OfferAction.INSTALLED) {
                    // Not offered any more, or already up to date: skipped.
                    updateQueue(app) { dropped(it, id) }
                    partial(app, id).delete()
                    continue
                }
                host.awaitNetwork(app)
                setPhase(app, RunPhase.STARTING, id)
                val part = partial(app, id).apply { parentFile?.mkdirs() }
                val total = offer.gzBytes.toLong()
                val p = prefs(app)
                // A partial of a file published since is never appended to.
                if (!keepPartial(p.str(PART_SHA + id), offer.gzSha256, part.length(), total)) part.delete()
                p.edit().putString(PART_SHA + id, offer.gzSha256).commit()
                before = part.length()
                val free = StatFs(app.filesDir.path).availableBytes
                if (!hasRoomFor(total, before, offer.regionBytes.toLong(), free)) {
                    val need = mb(total - before + offer.regionBytes.toLong())
                    throw NoRoom(app.getString(R.string.region_no_room, need))
                }
                moved += fetchFile(app, regionUrl(offer.fileName), part, offer, host, moved)
                install(app, bytes, offer, part)
            }
            return RunOutcome.DONE
        } catch (e: Exception) {
            // Stopped (by the rider, or Android for the network), also when a
            // blocked read only ends after the stop: nothing is written; the
            // partial stays for the next run.
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                _running.value = null
                if (lastOffers.isNotEmpty()) _download.value = DownloadState.Offers(lastOffers)
                throw e as? CancellationException ?: CancellationException("stopped")
            }
            return failed(app, e, head, before)
        }
    }

    /** Acts on a failed attempt: retried, or the queue stops with the reason. */
    @android.annotation.SuppressLint("ApplySharedPref")
    private fun failed(app: Context, e: Exception, head: String?, before: Long): RunOutcome {
        val f = failureOf(e)
        DebugTools.mark("region download: $f ($e)")
        if (waitsForNetwork(f)) {
            // The partial and the retry count stay as they are. The row shows
            // "Waiting for Wi-Fi" (or a network) with what has arrived, from
            // the queue and the partial, not the transfer that ended.
            _running.value = null
            if (lastOffers.isNotEmpty()) _download.value = DownloadState.Offers(lastOffers)
            return RunOutcome.WAIT
        }
        if (head != null && deletesPartial(f)) {
            partial(app, head).delete()
            prefs(app).edit().remove(PART_SHA + head).commit()
        }
        val gotBytes = head != null && before >= 0 && partial(app, head).length() > before
        val retries = if (gotBytes) 0 else _queue.value.retries
        val q = _queue.value
        val id = q.ids.firstOrNull()
        if (shouldRetry(f, retries) && q.active) {
            val next = updateQueue(app) { it.copy(retries = retries + 1) }
            if (id != null) {
                _running.value = RunPhase.RETRYING
                post(app, NoticeState.Retrying(next.index, next.total, nameOf(app, id)))
            }
            return RunOutcome.RETRY
        }
        val message = describe(app, e)
        updateQueue(app) { if (it.active) it.copy(failed = message, retries = 0) else it }
        _running.value = null
        _download.value = DownloadState.Offers(lastOffers)
        if (id != null) post(app, NoticeState.Failed(nameOf(app, id), message))
        return RunOutcome.FAILED
    }

    /** The runner is at [phase] with region [id]; the notice follows. */
    private fun setPhase(app: Context, phase: RunPhase, id: String) {
        _running.value = phase
        val q = _queue.value
        val name = nameOf(app, id)
        post(
            app,
            when (phase) {
                RunPhase.INSTALLING -> NoticeState.Installing(q.index, q.total, name)
                RunPhase.RETRYING -> NoticeState.Retrying(q.index, q.total, name)
                else -> NoticeState.Starting(q.index, q.total, name)
            },
        )
    }

    /**
     * Installs the downloaded [offer] (the core checks its SHA-256 against
     * the signed manifest, unpacks it and checks its structure, then renames
     * it into place), switched on or off by [switchedOnAfterInstall]. Kill
     * safe: one synchronous write records the install and takes it off the
     * queue, and only then is the partial deleted.
     */
    private suspend fun install(app: Context, bytes: ByteArray, offer: RegionOffer, part: File) {
        val id = offer.id
        setPhase(app, RunPhase.INSTALLING, id)
        _download.value = DownloadState.Installing(offer)
        val target = file(app, id).apply { parentFile?.mkdirs() }
        withContext(NonCancellable) {
            lock.withLock {
                // Removed from the queue (its bin, or Stop) while it downloaded.
                if (!isHead(_queue.value, id)) {
                    part.delete()
                    return@withLock
                }
                _installing.value = id
                try {
                    // The switch as the phone has it, read before the file is replaced.
                    val on = switchedOnAfterInstall(installedList(app), id, _queue.value.off)
                    val fp = installRegion(bytes, id, part.path, target.path)
                    updateQueue(
                        app,
                        { edit ->
                            edit.putStringSet(INSTALLED, HashSet(installedIds(app) + id))
                                .putString("name.$id", offer.name)
                                .putString("fp.$id", fp)
                                .putLong("ts.$id", offer.osmTimestamp)
                                .putString("sha.$id", offer.gzSha256)
                            // In the same write, before the reopen, so a region
                            // that was off is never opened.
                            if (on) edit.remove("off.$id") else edit.putBoolean("off.$id", true)
                        },
                    ) {
                        // Counted for the ready notice only if it was still queued
                        // (not stopped meanwhile).
                        if (id in it.ids) synchronized(batchNames) { batchNames += offer.name }
                        installed(it, id)
                    }
                    part.delete()
                    if (loaded) reopen(app) else listOnPhone(app)
                } finally {
                    _installing.value = null
                }
            }
        }
        _download.value = DownloadState.Offers(lastOffers)
    }

    /** The installed regions' files, for the debug tools. */
    fun installedFiles(context: Context): List<File> =
        installedList(context.applicationContext).map { file(context.applicationContext, it.id) }

    /** Removes region [id] from the phone; the network opens without it. A
     * queued region is dropped from the queue with its partial. */
    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        init(app)
        if (id in _queue.value.ids) {
            val q = updateQueue(app) { dropped(it, id) }
            if (isRegionId(id)) partial(app, id).delete()
            if (!q.active) {
                val stopping = runner
                cancelHost(app)
                _running.value = null
                post(app, null)
                restartAfter(app, stopping)
            }
        }
        change(app) { drop(it, id) }
    }

    /**
     * Enables or disables region [id]: a disabled region stays on the phone
     * but isn't used for routing; enabling it needs no download.
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean) = change(context) {
        prefs(it).edit().putBoolean("off.$id", !enabled).apply()
    }

    private fun change(context: Context, what: (Context) -> Unit) {
        val app = context.applicationContext
        if (changeJob?.isActive == true) return
        changeJob = scope.launch {
            lock.withLock {
                _active.value = _active.value.copy(state = RegionState.Loading)
                what(app)
                reopen(app)
            }
        }
    }

    private fun describe(context: Context, e: Exception): String = when (e) {
        is NoRoom -> e.message.orEmpty()
        is IOException -> context.getString(R.string.region_network, e.message ?: e.toString())
        else -> e.message ?: e.toString()
    }

    // ---- Notices ----

    /**
     * Shows [s] in the queue's one notification; null takes it away. Under
     * the queue's lock, so a late progress notice from a stopped run can't
     * come after Stop took the notification away (an ongoing one can't be
     * swiped away on Android 13 and older).
     */
    private fun post(app: Context, s: NoticeState?) = synchronized(queueLock) {
        if (s != null && s !is NoticeState.Ready && s !is NoticeState.Failed && !_queue.value.active) return@synchronized
        if (s != null) lastShown = s
        RegionNotices.show(app, s)
    }

    /** The waiting notice, for the queue's head. */
    private fun postWaiting(app: Context, q: DownloadQueue) {
        val id = q.ids.firstOrNull() ?: return
        if (q.failed != null) return
        post(app, NoticeState.Waiting(q.index, q.total, nameOf(app, id), wifi = _policy.value == NetworkPolicy.UNMETERED))
    }

    // ---- Transfers ----

    /** Opens [url] over HTTPS on [network] (null: the default), following
     * redirects only to HTTPS. */
    private fun connect(url: String, range: String?, network: Network?): HttpsURLConnection {
        var target = URL(url)
        repeat(MAX_REDIRECTS) {
            if (target.protocol != "https") throw Refused(DownloadFailure.REFUSED_ADDRESS, "refused a non-HTTPS address")
            val c = (network?.openConnection(target) ?: target.openConnection()) as? HttpsURLConnection
                ?: throw Refused(DownloadFailure.REFUSED_ADDRESS, "refused a non-HTTPS address")
            c.instanceFollowRedirects = false
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location")
                    ?: throw Refused(DownloadFailure.REFUSED_ADDRESS, "redirect without a location")
                c.disconnect()
                target = URL(target, next)
            } else {
                return c
            }
        }
        throw Refused(DownloadFailure.REFUSED_ADDRESS, "too many redirects")
    }

    private fun fetchSmall(url: String, network: Network?): ByteArray {
        val c = connect(url, null, network)
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw HttpStatus(c.responseCode)
            return c.inputStream.use { readCapped(it, MAX_MANIFEST_BYTES) }
                ?: throw Refused(DownloadFailure.OTHER, "the region list is too large")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [offer] into [part], resumed where it stopped and never past
     * its listed size; [movedBefore] bytes were moved earlier in this run.
     * Returns the bytes moved now.
     */
    private suspend fun fetchFile(app: Context, url: String, part: File, offer: RegionOffer, host: RegionHost, movedBefore: Long): Long {
        val total = offer.gzBytes.toLong()
        var have = part.length()
        if (have > total) {
            part.delete()
            have = 0
        }
        if (have == total) return 0
        var c = connect(url, resumeRange(have, total), host.network)
        if (c.responseCode == 206 && !contentRangeMatches(c.getHeaderField("Content-Range"), have, total)) {
            // Not the rest of this file: start it afresh.
            c.disconnect()
            part.delete()
            have = 0
            c = connect(url, null, host.network)
        }
        var moved = 0L
        try {
            when (resumeAction(c.responseCode, have)) {
                ResumeAction.APPEND -> Unit
                ResumeAction.RESTART -> have = 0
                ResumeAction.FAIL -> throw HttpStatus(c.responseCode)
            }
            _running.value = RunPhase.DOWNLOADING
            _download.value = DownloadState.Downloading(offer, have)
            val q = _queue.value
            fun show() = post(app, NoticeState.Downloading(q.index, q.total, offer.name, have, total))
            show()
            c.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastShown = have
                    var lastPosted = SystemClock.elapsedRealtime()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        // Roaming, or mobile data switched off: stop on the default network.
                        if (!transferAllowed(host.network != null, _policy.value, _network.value)) throw NotAllowed()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (have + n > total) throw Refused(DownloadFailure.SIZE_MISMATCH, "the download is larger than listed")
                        out.write(buf, 0, n)
                        have += n
                        moved += n
                        if (have - lastShown >= PROGRESS_STEP) {
                            lastShown = have
                            _download.value = DownloadState.Downloading(offer, have)
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPosted >= NOTICE_STEP_MS) {
                            lastPosted = now
                            host.transferred(movedBefore + moved)
                            show()
                        }
                    }
                }
            }
            host.transferred(movedBefore + moved)
            if (have != total) throw IOException("the download stopped early")
        } finally {
            c.disconnect()
        }
        return moved
    }

    private const val MAX_REDIRECTS = 5
    private const val PROGRESS_STEP = 1L shl 20
    /** The notice and the job's byte count are updated at most this often. */
    private const val NOTICE_STEP_MS = 1_000L
}
