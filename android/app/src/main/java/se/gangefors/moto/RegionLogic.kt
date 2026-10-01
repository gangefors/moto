// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import se.gangefors.moto.core.RegionOffer

/**
 * Pure logic for downloading regions (ADR-0008), kept out of the Android
 * classes so it can be unit-tested. The checks that matter for security
 * (manifest, size, SHA-256, unpacking, structure) are in the core.
 */

/** Where the region files are published: the `regions` release. */
const val REGIONS_BASE_URL = "https://github.com/gangefors/moto/releases/download/regions/"

/** Largest signed manifest read: the core's limit, plus the 64-byte signature. */
const val MAX_MANIFEST_BYTES = 64 * 1024 + 64

/** Room kept free on the phone beyond what a download needs. */
const val REGION_SPACE_MARGIN = 64L * 1024 * 1024

/**
 * The URL of a file in the regions release. [name] comes from the core
 * (the manifest's name for this app's format, or a region's file name
 * built from its checked id); anything else is refused.
 */
fun regionUrl(name: String): String {
    require(name.matches(Regex("[a-z0-9-]+(-v[0-9]+)?\\.(manifest|region\\.gz)"))) { "bad region file name" }
    return REGIONS_BASE_URL + name
}

/** The Range header to resume a download [have] bytes in, or null to start afresh. */
fun resumeRange(have: Long, total: Long): String? =
    if (have in 1 until total) "bytes=$have-" else null

/**
 * What to do with the partial file after the server answered [code] to a
 * request that asked to resume at [have] bytes (or not, when [have] is 0):
 * append to it, start it again, or give up.
 */
enum class ResumeAction { APPEND, RESTART, FAIL }

fun resumeAction(code: Int, have: Long): ResumeAction = when {
    code == 206 && have > 0 -> ResumeAction.APPEND
    code == 200 -> ResumeAction.RESTART
    else -> ResumeAction.FAIL
}

/** Whether there is room for the rest of the download and the installed region. */
fun hasRoomFor(gzBytes: Long, have: Long, regionBytes: Long, free: Long): Boolean =
    free - REGION_SPACE_MARGIN >= (gzBytes - have).coerceAtLeast(0) + regionBytes

/**
 * Whether an offered region replaces the installed one: newer map data,
 * or the same map data built again (a new builder: other speeds, tolls,
 * curvature) — a download whose SHA-256 differs from the one installed.
 * A region installed before the app kept that ([installedGzSha256] null)
 * is offered its same-day file once, as it can't tell.
 */
fun isUpdate(installedOsmTimestamp: Long?, offeredOsmTimestamp: Long, installedGzSha256: String?, offeredGzSha256: String): Boolean =
    installedOsmTimestamp != null &&
        (offeredOsmTimestamp > installedOsmTimestamp ||
            (offeredOsmTimestamp == installedOsmTimestamp && installedGzSha256 != offeredGzSha256))

/** Whether an update ([isUpdate]) is the same map data built again, so
 * its row can say so (the date alone would look like nothing new). */
fun isRebuild(installed: List<InstalledRegion>, offerId: String, offerOsmTimestamp: Long): Boolean =
    installed.firstOrNull { it.id == offerId }?.osmTimestamp == offerOsmTimestamp

/** What an offered region's row offers. */
enum class OfferAction { DOWNLOAD, UPDATE, INSTALLED }

/** [OfferAction] for an offer ([offerId], [offerOsmTimestamp],
 * [offerGzSha256]) with the regions [installed] on the phone (ADR-0009:
 * several side by side). */
fun offerAction(installed: List<InstalledRegion>, offerId: String, offerOsmTimestamp: Long, offerGzSha256: String): OfferAction {
    val mine = installed.firstOrNull { it.id == offerId } ?: return OfferAction.DOWNLOAD
    return if (isUpdate(mine.osmTimestamp, offerOsmTimestamp, mine.gzSha256, offerGzSha256)) OfferAction.UPDATE else OfferAction.INSTALLED
}

/** The offers that update a region on the phone ([OfferAction.UPDATE]),
 * in the order offered: what "Update all" fetches. */
fun offersToUpdate(installed: List<InstalledRegion>, offers: List<RegionOffer>): List<RegionOffer> =
    offers.filter { offerAction(installed, it.id, it.osmTimestamp, it.gzSha256) == OfferAction.UPDATE }

/**
 * A region installed from a download (ADR-0008, ADR-0009): its id and
 * name from the manifest, the day its map data is from (0 while unknown),
 * its file size, whether it is used (a disabled region stays on the
 * phone but isn't opened), and the SHA-256 of the download it came from
 * (null for regions installed before the app kept it).
 */
data class InstalledRegion(
    val id: String,
    val name: String,
    val osmTimestamp: Long,
    val bytes: Long,
    val enabled: Boolean,
    val gzSha256: String? = null,
)

/** A region id the core accepts in a manifest: `[a-z0-9-]`, 1–32 long. It
 * names the region's file, so nothing else is ever used as one. */
fun isRegionId(id: String): Boolean = id.matches(Regex("[a-z0-9-]{1,32}"))

/** The file name of region [id] in the app's region folder. */
fun regionFileName(id: String): String {
    require(isRegionId(id)) { "bad region id" }
    return "$id.region"
}

/** The regions that are used, in a stable order (by id), for opening. */
fun enabledRegions(installed: List<InstalledRegion>): List<InstalledRegion> =
    installed.filter { it.enabled }.sortedBy { it.id }

/** Bytes of all installed regions, enabled or not. */
fun installedBytes(installed: List<InstalledRegion>): Long = installed.sumOf { it.bytes }

/** How far a download is, 0 to 1 ([total] 0 or less reads as none). */
fun downloadShare(done: Long, total: Long): Float =
    if (total <= 0) 0f else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

/** Megabytes (10⁶ bytes), one decimal, for labels. */
fun mb(bytes: Long): Double = Math.round(bytes / 100_000.0) / 10.0

/** Map data this much older than the newest in use counts as from an older month. */
const val REGION_STALE_S = 20L * 24 * 3600

/**
 * The enabled regions whose map data is from an older month than the
 * newest enabled one (ADR-0009): a road rebuilt at the border since can
 * leave a crossing closed until both sides are updated. Regions whose
 * date is unknown are left out.
 */
fun olderNeighbours(installed: List<InstalledRegion>): List<InstalledRegion> {
    val used = enabledRegions(installed).filter { it.osmTimestamp > 0 }
    val newest = used.maxOfOrNull { it.osmTimestamp } ?: return emptyList()
    return used.filter { newest - it.osmTimestamp > REGION_STALE_S }
}

/** How the core proved a region file sound when it opened. */
enum class OpenCheck {
    /** Its checksum matched the one recorded: no full check. */
    CHECKSUM,
    /** No checksum was recorded, so it was checked in full. */
    FULL_NO_CHECKSUM,
    /** The recorded checksum differed, so it was checked in full. */
    FULL_CHECKSUM_DIFFERED,
}

/** How a region opened, from the checksum recorded before ([recorded])
 * and the one the core handed back ([returned]): the core returns the
 * file's own checksum, so they match exactly when it skipped the full
 * check (ADR-0005). */
fun openCheck(recorded: String?, returned: String?): OpenCheck = when {
    recorded == null -> OpenCheck.FULL_NO_CHECKSUM
    recorded == returned -> OpenCheck.CHECKSUM
    else -> OpenCheck.FULL_CHECKSUM_DIFFERED
}

