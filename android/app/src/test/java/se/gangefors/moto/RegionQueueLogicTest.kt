// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.gangefors.moto.core.MotoException

class RegionQueueLogicTest {
    private val offline = NetworkNow.OFFLINE
    private val wifi = NetworkNow(connected = true, metered = false, roaming = false)
    private val mobile = NetworkNow(connected = true, metered = true, roaming = false)
    private val roaming = NetworkNow(connected = true, metered = true, roaming = true)

    @Test
    fun policyIsTheWiderOfSettingAndGrant() {
        assertEquals(NetworkPolicy.UNMETERED, downloadPolicy(false, null))
        assertEquals(NetworkPolicy.HOME, downloadPolicy(true, null))
        assertEquals(NetworkPolicy.HOME, downloadPolicy(false, NetworkPolicy.HOME))
        assertEquals(NetworkPolicy.ANY, downloadPolicy(true, NetworkPolicy.ANY))
        assertEquals(NetworkPolicy.ANY, downloadPolicy(false, NetworkPolicy.ANY))
        // A narrower grant never narrows the setting.
        assertEquals(NetworkPolicy.HOME, downloadPolicy(true, NetworkPolicy.UNMETERED))
    }

    @Test
    fun allowsEachNetworkByPolicy() {
        val table = mapOf(
            NetworkPolicy.UNMETERED to listOf(false, true, false, false),
            NetworkPolicy.HOME to listOf(false, true, true, false),
            NetworkPolicy.ANY to listOf(false, true, true, true),
        )
        for ((policy, expected) in table) {
            listOf(offline, wifi, mobile, roaming).zip(expected).forEach { (now, want) ->
                assertEquals("$policy $now", want, allows(policy, now))
            }
        }
        // A Wi-Fi hotspot abroad is unmetered but roaming: never by default.
        assertFalse(allows(NetworkPolicy.UNMETERED, NetworkNow(connected = true, metered = false, roaming = true)))
    }

    @Test
    fun asksOnlyOnANetworkThePolicyRefuses() {
        assertEquals(MobileAsk(false, NetworkPolicy.HOME), mobileDataAsk(NetworkPolicy.UNMETERED, mobile))
        assertEquals(MobileAsk(true, NetworkPolicy.ANY), mobileDataAsk(NetworkPolicy.UNMETERED, roaming))
        assertEquals(MobileAsk(true, NetworkPolicy.ANY), mobileDataAsk(NetworkPolicy.HOME, roaming))
        assertNull(mobileDataAsk(NetworkPolicy.HOME, mobile))
        for (p in NetworkPolicy.entries) {
            assertNull(mobileDataAsk(p, offline))
            assertNull(mobileDataAsk(p, wifi))
        }
        assertNull(mobileDataAsk(NetworkPolicy.ANY, roaming))
    }

    @Test
    fun parsesStoredQueueIdsDefensively() {
        assertEquals(emptyList<String>(), parseQueueIds(null))
        assertEquals(emptyList<String>(), parseQueueIds(""))
        assertEquals(emptyList<String>(), parseQueueIds(",,"))
        assertEquals(listOf("gamma", "alpha", "beta"), parseQueueIds("gamma,alpha,beta"))
        assertEquals(listOf("alpha", "gamma"), parseQueueIds("alpha,,Beta,../x,alpha,gamma"))
        assertEquals(listOf("alpha"), parseQueueIds("alpha," + "a".repeat(33)))
        assertEquals(listOf("a".repeat(32)), parseQueueIds("a".repeat(32)))
        for (bad in listOf(" alpha", "alpha/x", "alpha\u0000", "ALPHA", "al.pha", "\n")) {
            assertEquals(bad, emptyList<String>(), parseQueueIds(bad))
        }
        val many = (1..40).map { "r$it" }
        assertEquals(many.take(MAX_QUEUE), parseQueueIds(queueIdsString(many)))
        val ids = listOf("alpha", "beta", "gamma")
        assertEquals(ids, parseQueueIds(queueIdsString(ids)))
    }

    @Test
    fun readsTheStoredQueueDefensively() {
        val q = readQueue("alpha,beta", setOf("beta", "zeta", "../x"), 2, "ANY", "it broke", 3)
        assertEquals(DownloadQueue(listOf("alpha", "beta"), setOf("beta"), 2, NetworkPolicy.ANY, "it broke", 3), q)
        // Out-of-range numbers are clamped; an unknown grant is none.
        val bad = readQueue("alpha", null, -4, "EVERYTHING", "", 99)
        assertEquals(0, bad.done)
        assertNull(bad.once)
        assertNull(bad.failed)
        assertEquals(MAX_RETRIES, bad.retries)
        assertEquals(999, readQueue("alpha", null, Int.MAX_VALUE, null, null, 0).done)
        // No queue: nothing else is kept.
        assertEquals(DownloadQueue(), readQueue(null, setOf("alpha"), 4, "HOME", "x", 0))
        assertEquals(DownloadQueue(), readQueue("..,/", null, 0, null, null, 0))
        assertEquals(NetworkPolicy.HOME, parseOnce(onceString(NetworkPolicy.HOME)))
        assertEquals(NetworkPolicy.ANY, parseOnce(onceString(NetworkPolicy.ANY)))
        assertNull(parseOnce(onceString(null)))
        assertNull(parseOnce(null))
        assertNull(parseOnce("home"))
    }

    @Test
    fun enqueueAppendsInOrderOnce() {
        val q = enqueue(DownloadQueue(), listOf("alpha", "Bad", "beta", "alpha"), setOf("beta", "gamma"))
        assertEquals(listOf("alpha", "beta"), q.ids)
        assertEquals(setOf("beta"), q.off)
        assertEquals(0, q.done)
        assertEquals(2, q.total)
        assertEquals(1, q.index)
        assertTrue(q.active)
        // Off is kept only for ids appended in this call.
        val r = enqueue(q.copy(done = 1, failed = "x", retries = 2, once = NetworkPolicy.HOME), listOf("alpha", "gamma"), setOf("alpha", "gamma"))
        assertEquals(listOf("alpha", "beta", "gamma"), r.ids)
        assertEquals(setOf("beta", "gamma"), r.off)
        assertEquals(1, r.done)
        assertEquals(4, r.total)
        assertNull(r.failed)
        assertEquals(0, r.retries)
        assertEquals(NetworkPolicy.HOME, r.once)
        // An empty queue starts a new batch.
        val fresh = enqueue(DownloadQueue(done = 3, once = NetworkPolicy.ANY), listOf("delta"), emptySet())
        assertEquals(0, fresh.done)
        assertNull(fresh.once)
        assertEquals(1, fresh.total)
        // The cap holds.
        val full = enqueue(DownloadQueue(), (1..40).map { "r$it" }, emptySet())
        assertEquals(MAX_QUEUE, full.ids.size)
        assertEquals(full, enqueue(full, listOf("extra"), emptySet()).copy(failed = null))
        assertFalse(DownloadQueue().active)
    }

    @Test
    fun installedAndDroppedMoveTheCount() {
        val q = DownloadQueue(listOf("alpha", "beta", "gamma"), setOf("alpha", "gamma"), done = 0, retries = 2)
        val a = installed(q, "alpha")
        assertEquals(listOf("beta", "gamma"), a.ids)
        assertEquals(setOf("gamma"), a.off)
        assertEquals(2, a.index)
        assertEquals(3, a.total)
        assertEquals(0, a.retries)
        val b = dropped(a, "beta")
        assertEquals(listOf("gamma"), b.ids)
        assertEquals(2, b.index)
        assertEquals(2, b.total)
        val c = installed(b, "gamma")
        assertFalse(c.active)
        assertEquals(2, c.done)
        assertEquals(emptySet<String>(), c.off)
        // An id not queued changes nothing.
        assertEquals(q, installed(q, "zeta"))
        assertEquals(q, dropped(q, "zeta"))
    }

    @Test
    fun aRunIsNeededOnlyForAQueueThatHasNotFailed() {
        assertFalse(needsRun(DownloadQueue()))
        val q = enqueue(DownloadQueue(), listOf("alpha"), emptySet())
        assertTrue(needsRun(q))
        assertFalse(needsRun(q.copy(failed = "no room")))
        // Queued again after a failure: Try again isn't needed.
        assertTrue(needsRun(enqueue(q.copy(failed = "no room"), listOf("beta"), emptySet())))
        // The last region removed while a run ended: nothing to start.
        assertFalse(needsRun(dropped(q, "alpha")))
        // Another region queued meanwhile: a run is started for it.
        assertTrue(needsRun(enqueue(dropped(q, "alpha"), listOf("beta"), emptySet())))
    }

    @Test
    fun onlyTheHeadIsInstalled() {
        val q = DownloadQueue(listOf("alpha", "beta"))
        assertTrue(isHead(q, "alpha"))
        assertFalse(isHead(q, "beta"))
        // Removed while it downloaded, or the queue stopped.
        assertFalse(isHead(dropped(q, "alpha"), "alpha"))
        assertFalse(isHead(DownloadQueue(), "alpha"))
    }

    @Test
    fun partialsOnlyForQueuedRegions() {
        assertEquals("alpha.part", partialFileName("alpha"))
        for (bad in listOf("", "../x", "Alpha", "a".repeat(33), "a/b")) {
            assertThrows(bad, IllegalArgumentException::class.java) { partialFileName(bad) }
        }
        val names = listOf("alpha.part", "beta.part", "alpha-v3.region.gz.part", "x.tmp", "..")
        assertEquals(listOf("beta.part", "alpha-v3.region.gz.part", "x.tmp", ".."), partialsToDelete(names, listOf("alpha")))
        assertEquals(names, partialsToDelete(names, emptyList()))
        assertEquals(names, partialsToDelete(names, listOf("../alpha")))
    }

    @Test
    fun keepsOnlyAPartialOfTheSameFile() {
        assertTrue(keepPartial(null, "s1", 0, 100))
        assertFalse(keepPartial(null, "s1", 40, 100))
        assertFalse(keepPartial("s0", "s1", 40, 100))
        assertFalse(keepPartial("s1", "s1", 101, 100))
        assertTrue(keepPartial("s1", "s1", 40, 100))
        assertTrue(keepPartial("s1", "s1", 100, 100))
    }

    @Test
    fun failuresAreRetriedOrNot() {
        assertEquals(DownloadFailure.HTTP_OTHER, httpFailure(200))
        assertEquals(DownloadFailure.HTTP_OTHER, httpFailure(404))
        assertEquals(DownloadFailure.HTTP_TRANSIENT, httpFailure(408))
        assertEquals(DownloadFailure.SIZE_MISMATCH, httpFailure(416))
        assertEquals(DownloadFailure.HTTP_TRANSIENT, httpFailure(429))
        assertEquals(DownloadFailure.HTTP_TRANSIENT, httpFailure(500))
        assertEquals(DownloadFailure.HTTP_TRANSIENT, httpFailure(503))
        assertEquals(DownloadFailure.HTTP_OTHER, httpFailure(600))
        val retried = setOf(DownloadFailure.NETWORK, DownloadFailure.HTTP_TRANSIENT)
        val deleting = setOf(DownloadFailure.SIZE_MISMATCH, DownloadFailure.CORE_REFUSED)
        for (f in DownloadFailure.entries) {
            assertEquals(f.name, f in retried, shouldRetry(f, 0))
            assertEquals(f.name, f in retried, shouldRetry(f, MAX_RETRIES - 1))
            assertFalse(f.name, shouldRetry(f, MAX_RETRIES))
            assertEquals(f.name, f in deleting, deletesPartial(f))
        }
        assertEquals(30_000L, backoffMs(0))
        assertEquals(60_000L, backoffMs(1))
        assertEquals(480_000L, backoffMs(4))
        // Stored counts can't overflow the wait.
        assertEquals(backoffMs(MAX_RETRIES), backoffMs(Int.MAX_VALUE))
        assertEquals(30_000L, backoffMs(-3))
    }

    @Test
    fun classifiesErrors() {
        assertEquals(DownloadFailure.HTTP_OTHER, failureOf(HttpStatus(404)))
        assertEquals(DownloadFailure.HTTP_TRANSIENT, failureOf(HttpStatus(503)))
        assertEquals(DownloadFailure.REFUSED_ADDRESS, failureOf(Refused(DownloadFailure.REFUSED_ADDRESS, "refused a non-HTTPS address")))
        assertEquals(DownloadFailure.SIZE_MISMATCH, failureOf(Refused(DownloadFailure.SIZE_MISMATCH, "the download is larger than listed")))
        assertEquals(DownloadFailure.NO_ROOM, failureOf(NoRoom("not enough free space")))
        assertEquals(DownloadFailure.CORE_REFUSED, failureOf(MotoException.Region("bad checksum")))
        assertEquals(DownloadFailure.NETWORK, failureOf(IOException("timeout")))
        assertEquals(DownloadFailure.NETWORK, failureOf(java.net.SocketTimeoutException()))
        assertEquals(DownloadFailure.OTHER, failureOf(IllegalStateException("x")))
        assertEquals(DownloadFailure.NOT_ALLOWED, failureOf(NotAllowed()))
    }

    @Test
    fun aTransferStopsWhenTheNetworkIsNoLongerAllowed() {
        // On the phone's default network (the in-app host): only while allowed.
        assertTrue(transferAllowed(false, NetworkPolicy.UNMETERED, wifi))
        assertFalse(transferAllowed(false, NetworkPolicy.UNMETERED, mobile))
        assertFalse(transferAllowed(false, NetworkPolicy.HOME, roaming))
        assertFalse(transferAllowed(false, NetworkPolicy.HOME, offline))
        assertTrue(transferAllowed(false, NetworkPolicy.HOME, mobile))
        // Bound to the job's network: Android stops the job itself.
        assertTrue(transferAllowed(true, NetworkPolicy.UNMETERED, roaming))
        // Not a failure: waits without counting a retry or losing the partial.
        val f = failureOf(NotAllowed())
        assertTrue(waitsForNetwork(f))
        assertFalse(shouldRetry(f, 0))
        assertFalse(deletesPartial(f))
        for (other in DownloadFailure.entries - DownloadFailure.NOT_ALLOWED) assertFalse(other.name, waitsForNetwork(other))
    }

    @Test
    fun rowPhaseForEveryBranch() {
        val q = DownloadQueue(listOf("alpha", "beta"))
        val u = NetworkPolicy.UNMETERED
        assertEquals(RowPhase.NONE, rowPhase("gamma", q, RunPhase.DOWNLOADING, u, wifi))
        assertEquals(RowPhase.QUEUED, rowPhase("beta", q, RunPhase.DOWNLOADING, u, wifi))
        assertEquals(RowPhase.QUEUED, rowPhase("beta", q, null, u, offline))
        assertEquals(RowPhase.FAILED, rowPhase("alpha", q.copy(failed = "x"), null, u, wifi))
        assertEquals(RowPhase.FAILED, rowPhase("alpha", q.copy(failed = "x"), RunPhase.STARTING, u, wifi))
        assertEquals(RowPhase.STARTING, rowPhase("alpha", q, RunPhase.STARTING, u, mobile))
        assertEquals(RowPhase.DOWNLOADING, rowPhase("alpha", q, RunPhase.DOWNLOADING, u, wifi))
        assertEquals(RowPhase.INSTALLING, rowPhase("alpha", q, RunPhase.INSTALLING, u, offline))
        assertEquals(RowPhase.RETRYING, rowPhase("alpha", q, RunPhase.RETRYING, u, wifi))
        assertEquals(RowPhase.STARTING, rowPhase("alpha", q, null, u, wifi))
        assertEquals(RowPhase.WAITING_WIFI, rowPhase("alpha", q, null, u, mobile))
        assertEquals(RowPhase.WAITING_WIFI, rowPhase("alpha", q, null, u, offline))
        assertEquals(RowPhase.STARTING, rowPhase("alpha", q, null, NetworkPolicy.HOME, mobile))
        assertEquals(RowPhase.WAITING_NETWORK, rowPhase("alpha", q, null, NetworkPolicy.HOME, roaming))
        assertEquals(RowPhase.WAITING_NETWORK, rowPhase("alpha", q, null, NetworkPolicy.HOME, offline))
        assertEquals(RowPhase.WAITING_NETWORK, rowPhase("alpha", q, null, NetworkPolicy.ANY, offline))
        assertEquals(RowPhase.NONE, rowPhase("alpha", DownloadQueue(), null, u, wifi))
    }

    @Test
    fun noticeTextsForOneAndMany() {
        val p = NoticeChannel.PROGRESS
        val mib = 1_000_000L
        // Downloading.
        val d1 = notice(NoticeState.Downloading(1, 1, "Norway", 45_200_000, 180_400_000))
        assertEquals(Txt(R.string.region_notice_one, listOf("Norway")), d1.title)
        assertEquals(Txt(R.string.region_progress, listOf(45.2, 180.4)), d1.text)
        assertTrue(d1.bar)
        assertEquals(45.2f / 180.4f, d1.progress!!, 0.001f)
        assertEquals(p, d1.channel)
        assertTrue(d1.ongoing)
        val dn = notice(NoticeState.Downloading(2, 5, "Norway", 45_200_000, 180_400_000))
        assertEquals(Txt(R.string.region_notice_many, listOf(2, 5)), dn.title)
        assertEquals(Txt(R.string.region_with_progress, listOf("Norway", 45.2, 180.4)), dn.text)
        assertEquals(0f, notice(NoticeState.Downloading(1, 1, "N", 0, 0)).progress!!, 0f)
        assertEquals(1f, notice(NoticeState.Downloading(1, 1, "N", 2 * mib, mib)).progress!!, 0f)
        // Starting and installing: an indeterminate bar.
        val s1 = notice(NoticeState.Starting(1, 1, "Norway"))
        assertEquals(Txt(R.string.region_notice_one, listOf("Norway")), s1.title)
        assertEquals(Txt(R.string.region_starting), s1.text)
        assertTrue(s1.bar)
        assertNull(s1.progress)
        assertEquals(Txt(R.string.region_notice_starting, listOf("Norway")), notice(NoticeState.Starting(2, 5, "Norway")).text)
        val i1 = notice(NoticeState.Installing(1, 1, "Norway"))
        assertEquals(Txt(R.string.region_installing_short), i1.text)
        assertTrue(i1.bar)
        assertNull(i1.progress)
        val iN = notice(NoticeState.Installing(2, 5, "Norway"))
        assertEquals(Txt(R.string.region_notice_many, listOf(2, 5)), iN.title)
        assertEquals(Txt(R.string.region_notice_installing, listOf("Norway")), iN.text)
        // Waiting: no bar, still ongoing with Stop.
        val w1 = notice(NoticeState.Waiting(1, 1, "Norway", wifi = true))
        assertEquals(Txt(R.string.region_wait_wifi), w1.title)
        assertEquals(Txt(R.string.region_notice_wait_one, listOf("Norway")), w1.text)
        assertFalse(w1.bar)
        assertTrue(w1.ongoing)
        val wn = notice(NoticeState.Waiting(2, 5, "Norway", wifi = false))
        assertEquals(Txt(R.string.region_wait_network), wn.title)
        assertEquals(Txt(R.string.region_notice_wait_many, listOf(2, 5, "Norway")), wn.text)
        // Retrying.
        val r1 = notice(NoticeState.Retrying(1, 1, "Norway"))
        assertEquals(Txt(R.string.region_notice_one, listOf("Norway")), r1.title)
        assertEquals(Txt(R.string.region_retrying), r1.text)
        assertFalse(r1.bar)
        assertEquals(p, r1.channel)
        assertEquals(Txt(R.string.region_notice_retrying, listOf("Norway")), notice(NoticeState.Retrying(2, 5, "Norway")).text)
        // Ready.
        val ready1 = notice(NoticeState.Ready(listOf("Norway")))
        assertEquals(Txt(R.string.region_notice_ready_one, listOf("Norway")), ready1.title)
        assertEquals(Txt(R.string.region_notice_ready_text_one), ready1.text)
        assertEquals(NoticeChannel.RESULT, ready1.channel)
        assertFalse(ready1.ongoing)
        val ready3 = notice(NoticeState.Ready(listOf("Sweden", "Norway", "Denmark")))
        assertEquals(Txt(R.string.region_notice_ready_many, listOf(3)), ready3.title)
        assertEquals(Txt(R.string.region_notice_ready_text, listOf("Sweden, Norway and Denmark")), ready3.text)
        assertEquals("Sweden and Norway", namesInWords(listOf("Sweden", "Norway")))
        assertEquals("", namesInWords(emptyList()))
        // Failed.
        val f = notice(NoticeState.Failed("Norway", "not enough free space (412.0 MB needed)"))
        assertEquals(Txt(R.string.region_notice_failed), f.title)
        assertEquals(Txt(R.string.region_notice_failed_text, listOf("Norway", "not enough free space (412.0 MB needed)")), f.text)
        assertEquals(NoticeChannel.RESULT, f.channel)
        assertFalse(f.bar)
    }

    @Test
    fun readyNoticeIsOnlyForAnAwayAppWithNames() {
        assertNull(readyNotice(emptyList(), inFront = true))
        assertNull(readyNotice(emptyList(), inFront = false))
        assertNull(readyNotice(listOf("Sweden"), inFront = true))
        assertEquals(NoticeState.Ready(listOf("Sweden", "Norway")), readyNotice(listOf("Sweden", "Norway"), inFront = false))
    }

    @Test
    fun clearsInFrontTable() {
        for (kind in NoticeKind.entries) {
            val active = kind == NoticeKind.READY
            val empty = kind != NoticeKind.FAILED
            assertEquals("$kind active", active, clearsInFront(kind, queueActive = true))
            assertEquals("$kind empty", empty, clearsInFront(kind, queueActive = false))
        }
        assertFalse(clearsInFront(null, queueActive = true))
        assertFalse(clearsInFront(null, queueActive = false))
    }

    @Test
    fun noticeKindMapsEverySubtype() {
        assertEquals(NoticeKind.STARTING, noticeKind(NoticeState.Starting(1, 2, "a")))
        assertEquals(NoticeKind.DOWNLOADING, noticeKind(NoticeState.Downloading(1, 2, "a", 1, 2)))
        assertEquals(NoticeKind.INSTALLING, noticeKind(NoticeState.Installing(1, 2, "a")))
        assertEquals(NoticeKind.WAITING, noticeKind(NoticeState.Waiting(1, 2, "a", wifi = true)))
        assertEquals(NoticeKind.RETRYING, noticeKind(NoticeState.Retrying(1, 2, "a")))
        assertEquals(NoticeKind.READY, noticeKind(NoticeState.Ready(listOf("a"))))
        assertEquals(NoticeKind.FAILED, noticeKind(NoticeState.Failed("a", "m")))
    }

    @Test
    fun noticeKindOfRoundTripsAndRejectsTheRest() {
        for (kind in NoticeKind.entries) assertEquals(kind, noticeKindOf(kind.name))
        for (bad in listOf(null, "", "ready", "READY ", "UNKNOWN")) assertNull(bad, noticeKindOf(bad))
    }
}
