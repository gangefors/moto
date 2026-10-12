// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.io.IOException
import se.gangefors.moto.core.MotoException

/**
 * Pure logic for the region download queue that runs in the background
 * (ADR-0017): which networks a download may use, the queue kept in the
 * regions preferences (read defensively: it is stored input), the partial
 * files, the retry rules, what each row shows and what the one
 * notification says. Kept out of the Android classes so it can be
 * unit-tested.
 */

/** Which networks a region download may use, strictest first (ADR-0017). */
enum class NetworkPolicy { UNMETERED, HOME, ANY }

/** The phone's default network now, as far as a download cares. */
data class NetworkNow(val connected: Boolean, val metered: Boolean, val roaming: Boolean) {
    companion object {
        val OFFLINE = NetworkNow(connected = false, metered = false, roaming = false)
    }
}

/** The setting ([mobileData]: HOME, else UNMETERED) or the once-only
 * grant [once], whichever is wider. */
fun downloadPolicy(mobileData: Boolean, once: NetworkPolicy?): NetworkPolicy {
    val setting = if (mobileData) NetworkPolicy.HOME else NetworkPolicy.UNMETERED
    return if (once != null && once > setting) once else setting
}

/** UNMETERED: connected, not metered, not roaming. HOME: connected, not
 * roaming. ANY: connected. */
fun allows(policy: NetworkPolicy, now: NetworkNow): Boolean = now.connected && when (policy) {
    NetworkPolicy.UNMETERED -> !now.metered && !now.roaming
    NetworkPolicy.HOME -> !now.roaming
    NetworkPolicy.ANY -> true
}

/** The question when a download starts on a network the policy doesn't
 * allow; [grant] is what "Download now" allows for this queue. */
data class MobileAsk(val roaming: Boolean, val grant: NetworkPolicy)

/** Null when [now] is allowed or offline; roaming → (true, ANY); else
 * (false, HOME). */
fun mobileDataAsk(policy: NetworkPolicy, now: NetworkNow): MobileAsk? = when {
    !now.connected || allows(policy, now) -> null
    now.roaming -> MobileAsk(roaming = true, grant = NetworkPolicy.ANY)
    else -> MobileAsk(roaming = false, grant = NetworkPolicy.HOME)
}

/** The most regions the queue holds. */
const val MAX_QUEUE = 32

/** The download queue kept in the regions preferences. */
data class DownloadQueue(
    /** In order; the head downloads. */
    val ids: List<String> = emptyList(),
    /** Of [ids], those installed switched off (a restore, ADR-0016). */
    val off: Set<String> = emptySet(),
    /** Installed in this batch. */
    val done: Int = 0,
    /** The once-only network grant ("Download now"). */
    val once: NetworkPolicy? = null,
    /** Why the queue stopped; null while it runs or waits. */
    val failed: String? = null,
    /** Attempts in a row without new bytes. */
    val retries: Int = 0,
) {
    val total: Int get() = done + ids.size
    val active: Boolean get() = ids.isNotEmpty()
    /** "Map region [index] of [total]". */
    val index: Int get() = done + 1
}

/** Split on ',', keep [isRegionId] ids, distinct, the first [MAX_QUEUE];
 * null or blank reads as empty. */
fun parseQueueIds(stored: String?): List<String> =
    stored.orEmpty().split(',').filter(::isRegionId).distinct().take(MAX_QUEUE)

fun queueIdsString(ids: List<String>): String = ids.joinToString(",")

/** The once-only grant as stored: "", "HOME" or "ANY". */
fun onceString(once: NetworkPolicy?): String = when (once) {
    NetworkPolicy.HOME -> "HOME"
    NetworkPolicy.ANY -> "ANY"
    else -> ""
}

/** The stored grant; anything unknown reads as none. */
fun parseOnce(stored: String?): NetworkPolicy? = when (stored) {
    "HOME" -> NetworkPolicy.HOME
    "ANY" -> NetworkPolicy.ANY
    else -> null
}

/** The largest batch count kept (a stored value beyond reads as this). */
private const val MAX_DONE = 999

/**
 * The queue from its stored values, each checked again: ids through
 * [parseQueueIds], [off] narrowed to them, counts clamped, an unknown grant
 * read as none, and no failure without a queue.
 */
fun readQueue(ids: String?, off: Set<String>?, done: Int, once: String?, failed: String?, retries: Int): DownloadQueue {
    val list = parseQueueIds(ids)
    return DownloadQueue(
        ids = list,
        off = off.orEmpty().filter { it in list }.toSet(),
        done = if (list.isEmpty()) 0 else done.coerceIn(0, MAX_DONE),
        once = if (list.isEmpty()) null else parseOnce(once),
        failed = failed?.takeIf { it.isNotBlank() && list.isNotEmpty() },
        retries = retries.coerceIn(0, MAX_RETRIES),
    )
}

/**
 * Appends the valid ids not yet queued (up to [MAX_QUEUE]); off gains those
 * of them in [off]; failed and retries are cleared; an empty queue starts
 * with done = 0.
 */
fun enqueue(q: DownloadQueue, ids: List<String>, off: Set<String>): DownloadQueue {
    val added = ids.filter { isRegionId(it) && it !in q.ids }.distinct().take(MAX_QUEUE - q.ids.size)
    return q.copy(
        ids = q.ids + added,
        off = q.off + added.filter { it in off },
        done = if (q.active) q.done else 0,
        once = if (q.active) q.once else null,
        failed = null,
        retries = 0,
    )
}

/** The head [id] was installed: removed, done + 1, removed from off,
 * retries 0. */
fun installed(q: DownloadQueue, id: String): DownloadQueue =
    if (id !in q.ids) q else q.copy(ids = q.ids - id, off = q.off - id, done = q.done + 1, retries = 0)

/** Whether the queue needs a run: regions are queued and it hasn't
 * stopped on a failure (that waits for Try again). */
fun needsRun(q: DownloadQueue): Boolean = q.active && q.failed == null

/** Whether [id] is still the queue's head: a region removed or stopped
 * while it downloaded is not installed. */
fun isHead(q: DownloadQueue, id: String): Boolean = q.ids.firstOrNull() == id

/** [id] is not offered any more or already up to date: removed, done
 * unchanged (the total shrinks). */
fun dropped(q: DownloadQueue, id: String): DownloadQueue =
    if (id !in q.ids) q else q.copy(ids = q.ids - id, off = q.off - id, retries = 0)

/** The partial download of region [id]: named only by the checked id. */
fun partialFileName(id: String): String {
    require(isRegionId(id)) { "bad region id" }
    return "$id.part"
}

/** Every name in the partial folder except the partial of a queued id. */
fun partialsToDelete(names: List<String>, queued: List<String>): List<String> {
    val keep = queued.filter(::isRegionId).map(::partialFileName).toSet()
    return names.filter { it !in keep }
}

/** Resume only the same file: [recordedSha] == [offerSha] and [have] <=
 * [total] ([have] 0 is always fine: there is nothing to append to). */
fun keepPartial(recordedSha: String?, offerSha: String, have: Long, total: Long): Boolean =
    have == 0L || (recordedSha == offerSha && have in 0..total)

/** Why one region's download stopped. */
enum class DownloadFailure { NETWORK, HTTP_TRANSIENT, HTTP_OTHER, REFUSED_ADDRESS, SIZE_MISMATCH, CORE_REFUSED, NO_ROOM, NOT_ALLOWED, OTHER }

/** The server answered [code]. */
class HttpStatus(val code: Int) : IOException("HTTP $code")

/** A download refused on our side (an address, a size), as [kind]. */
class Refused(val kind: DownloadFailure, message: String) : IOException(message)

/** The phone moved to a network the policy doesn't allow (roaming, or
 * mobile data switched off) during a transfer on the default network. */
class NotAllowed : IOException("the network is not allowed now")

/**
 * Whether a transfer may go on: one bound to the job's network always may
 * ([bound]: Android stops the job when its constraint no longer holds);
 * one on the phone's default network only while [policy] allows [now].
 */
fun transferAllowed(bound: Boolean, policy: NetworkPolicy, now: NetworkNow): Boolean =
    bound || allows(policy, now)

/** Too little free space for the region; [message] says how much is needed. */
class NoRoom(message: String) : Exception(message)

/** 408, 429, 500–599 → HTTP_TRANSIENT; 416 → SIZE_MISMATCH; else HTTP_OTHER. */
fun httpFailure(code: Int): DownloadFailure = when (code) {
    408, 429, in 500..599 -> DownloadFailure.HTTP_TRANSIENT
    416 -> DownloadFailure.SIZE_MISMATCH
    else -> DownloadFailure.HTTP_OTHER
}

/** What [e] means for the download. */
fun failureOf(e: Throwable): DownloadFailure = when (e) {
    is HttpStatus -> httpFailure(e.code)
    is Refused -> e.kind
    is NoRoom -> DownloadFailure.NO_ROOM
    is NotAllowed -> DownloadFailure.NOT_ALLOWED
    is MotoException -> DownloadFailure.CORE_REFUSED
    is IOException -> DownloadFailure.NETWORK
    else -> DownloadFailure.OTHER
}

/** NOT_ALLOWED: not a failure; the queue waits for an allowed network
 * and goes on from the partial, without counting an attempt. */
fun waitsForNetwork(f: DownloadFailure): Boolean = f == DownloadFailure.NOT_ALLOWED

/** Attempts in a row without new bytes before a region fails. */
const val MAX_RETRIES = 5

/** NETWORK or HTTP_TRANSIENT, and [retries] < [MAX_RETRIES]. */
fun shouldRetry(f: DownloadFailure, retries: Int): Boolean =
    (f == DownloadFailure.NETWORK || f == DownloadFailure.HTTP_TRANSIENT) && retries < MAX_RETRIES

/** SIZE_MISMATCH and CORE_REFUSED: the partial is useless. */
fun deletesPartial(f: DownloadFailure): Boolean =
    f == DownloadFailure.SIZE_MISMATCH || f == DownloadFailure.CORE_REFUSED

/** The first wait before a retry. */
const val BACKOFF_START_MS = 30_000L

/** 30 s × 2^[retries] (the in-app host's wait; the job uses JobScheduler's
 * backoff with the same start). */
fun backoffMs(retries: Int): Long = BACKOFF_START_MS shl retries.coerceIn(0, MAX_RETRIES)

/** What the runner is doing to the queue's head now. */
enum class RunPhase { STARTING, DOWNLOADING, INSTALLING, RETRYING }

/** What an offer's row shows. */
enum class RowPhase { NONE, QUEUED, STARTING, DOWNLOADING, INSTALLING, RETRYING, WAITING_WIFI, WAITING_NETWORK, FAILED }

/**
 * Not queued → NONE; queued, not the head → QUEUED; the head: failed →
 * FAILED; running → its phase; else allowed now → STARTING, UNMETERED →
 * WAITING_WIFI, else WAITING_NETWORK.
 */
fun rowPhase(offerId: String, q: DownloadQueue, running: RunPhase?, policy: NetworkPolicy, now: NetworkNow, installing: String? = null): RowPhase = when {
    offerId !in q.ids -> RowPhase.NONE
    q.ids.first() != offerId -> RowPhase.QUEUED
    q.failed != null -> RowPhase.FAILED
    running != null -> when (running) {
        RunPhase.STARTING -> RowPhase.STARTING
        RunPhase.DOWNLOADING -> RowPhase.DOWNLOADING
        RunPhase.INSTALLING -> RowPhase.INSTALLING
        RunPhase.RETRYING -> RowPhase.RETRYING
    }
    allows(policy, now) -> RowPhase.STARTING
    policy == NetworkPolicy.UNMETERED -> RowPhase.WAITING_WIFI
    else -> RowPhase.WAITING_NETWORK
}

/** A string resource with its format arguments. */
data class Txt(val id: Int, val args: List<Any> = emptyList())

/** The two notification channels. */
enum class NoticeChannel { PROGRESS, RESULT }

/** What the queue's notification shows. */
sealed interface NoticeState {
    data class Starting(val index: Int, val total: Int, val name: String) : NoticeState
    data class Downloading(val index: Int, val total: Int, val name: String, val done: Long, val size: Long) : NoticeState
    data class Installing(val index: Int, val total: Int, val name: String) : NoticeState
    data class Waiting(val index: Int, val total: Int, val name: String, val wifi: Boolean) : NoticeState
    data class Retrying(val index: Int, val total: Int, val name: String) : NoticeState
    data class Ready(val names: List<String>) : NoticeState
    data class Failed(val name: String, val message: String) : NoticeState
}

/** The kind of a queue notification; carried in its extras (ADR-0017). */
enum class NoticeKind { STARTING, DOWNLOADING, INSTALLING, WAITING, RETRYING, READY, FAILED }

/** The kind of [s]. */
fun noticeKind(s: NoticeState): NoticeKind = when (s) {
    is NoticeState.Starting -> NoticeKind.STARTING
    is NoticeState.Downloading -> NoticeKind.DOWNLOADING
    is NoticeState.Installing -> NoticeKind.INSTALLING
    is NoticeState.Waiting -> NoticeKind.WAITING
    is NoticeState.Retrying -> NoticeKind.RETRYING
    is NoticeState.Ready -> NoticeKind.READY
    is NoticeState.Failed -> NoticeKind.FAILED
}

/** The kind named [name] exactly; anything else (null, empty, other case, unknown) is null. */
fun noticeKindOf(name: String?): NoticeKind? = NoticeKind.entries.firstOrNull { it.name == name }

/** The ready notice for [names], or null when there is nothing to say or the app is open. */
fun readyNotice(names: List<String>, inFront: Boolean): NoticeState.Ready? =
    if (names.isEmpty() || inFront) null else NoticeState.Ready(names)

/**
 * Whether opening the app takes away a notice of [kind]: a ready one, and a
 * progress-type one only once the queue is empty (a leftover); never a
 * failed one, nor one of unknown kind.
 */
fun clearsInFront(kind: NoticeKind?, queueActive: Boolean): Boolean = when (kind) {
    NoticeKind.READY -> true
    NoticeKind.STARTING, NoticeKind.DOWNLOADING, NoticeKind.INSTALLING,
    NoticeKind.WAITING, NoticeKind.RETRYING -> !queueActive
    NoticeKind.FAILED, null -> false
}

/**
 * The notification: [title], [text], its bar ([progress] 0–1, null for an
 * indeterminate one, unless there is none: ![bar]), its channel, and
 * whether it is ongoing with a Stop action (the progress channel's).
 */
data class Notice(
    val title: Txt,
    val text: Txt,
    val bar: Boolean,
    val progress: Float?,
    val channel: NoticeChannel,
) {
    val ongoing: Boolean get() = channel == NoticeChannel.PROGRESS
}

/** "Sweden", "Sweden and Norway", "Sweden, Norway and Denmark". */
fun namesInWords(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** The notification for [s]: one region names it in the title, several
 * count it ("map region 2 of 5") and name it in the text. */
fun notice(s: NoticeState): Notice {
    fun title(total: Int, index: Int, name: String) =
        if (total <= 1) Txt(R.string.region_notice_one, listOf(name)) else Txt(R.string.region_notice_many, listOf(index, total))
    val p = NoticeChannel.PROGRESS
    return when (s) {
        is NoticeState.Starting -> Notice(
            title(s.total, s.index, s.name),
            if (s.total <= 1) Txt(R.string.region_starting) else Txt(R.string.region_notice_starting, listOf(s.name)),
            bar = true, progress = null, channel = p,
        )
        is NoticeState.Downloading -> Notice(
            title(s.total, s.index, s.name),
            if (s.total <= 1) {
                Txt(R.string.region_progress, listOf(mb(s.done), mb(s.size)))
            } else {
                Txt(R.string.region_with_progress, listOf(s.name, mb(s.done), mb(s.size)))
            },
            bar = true, progress = downloadShare(s.done, s.size), channel = p,
        )
        is NoticeState.Installing -> Notice(
            title(s.total, s.index, s.name),
            if (s.total <= 1) Txt(R.string.region_installing_short) else Txt(R.string.region_notice_installing, listOf(s.name)),
            bar = true, progress = null, channel = p,
        )
        is NoticeState.Waiting -> Notice(
            Txt(if (s.wifi) R.string.region_wait_wifi else R.string.region_wait_network),
            if (s.total <= 1) {
                Txt(R.string.region_notice_wait_one, listOf(s.name))
            } else {
                Txt(R.string.region_notice_wait_many, listOf(s.index, s.total, s.name))
            },
            bar = false, progress = null, channel = p,
        )
        is NoticeState.Retrying -> Notice(
            title(s.total, s.index, s.name),
            if (s.total <= 1) Txt(R.string.region_retrying) else Txt(R.string.region_notice_retrying, listOf(s.name)),
            bar = false, progress = null, channel = p,
        )
        is NoticeState.Ready -> if (s.names.size == 1) {
            Notice(
                Txt(R.string.region_notice_ready_one, listOf(s.names[0])),
                Txt(R.string.region_notice_ready_text_one),
                bar = false, progress = null, channel = NoticeChannel.RESULT,
            )
        } else {
            Notice(
                Txt(R.string.region_notice_ready_many, listOf(s.names.size)),
                Txt(R.string.region_notice_ready_text, listOf(namesInWords(s.names))),
                bar = false, progress = null, channel = NoticeChannel.RESULT,
            )
        }
        is NoticeState.Failed -> Notice(
            Txt(R.string.region_notice_failed),
            Txt(R.string.region_notice_failed_text, listOf(s.name, s.message)),
            bar = false, progress = null, channel = NoticeChannel.RESULT,
        )
    }
}
