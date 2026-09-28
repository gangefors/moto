// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

/**
 * Pure logic for downloading regions (ADR-0008), kept out of the Android
 * classes so it can be unit-tested. The checks that matter for security
 * (manifest, size, SHA-256, unpacking, structure) are in the core.
 */

/** Where the region files are published: the `regions` release. */
const val REGIONS_BASE_URL = "https://github.com/gangefors/moto/releases/download/regions/"

/** Largest manifest read; the core's own limit. */
const val MAX_MANIFEST_BYTES = 64 * 1024

/** Room kept free on the phone beyond what a download needs. */
const val REGION_SPACE_MARGIN = 64L * 1024 * 1024

/**
 * The URL of a file in the regions release. [name] comes from the core
 * (the manifest's name for this app's format, or a region's file name
 * built from its checked id); anything else is refused.
 */
fun regionUrl(name: String): String {
    require(name.matches(Regex("[a-z0-9-]+(-v[0-9]+)?\\.(json|region\\.gz)"))) { "bad region file name" }
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

/** Whether an offered region is newer data than the installed one. */
fun isUpdate(installedOsmTimestamp: Long?, offeredOsmTimestamp: Long): Boolean =
    installedOsmTimestamp != null && offeredOsmTimestamp > installedOsmTimestamp

/** Megabytes (10⁶ bytes), one decimal, for labels. */
fun mb(bytes: Long): Double = Math.round(bytes / 100_000.0) / 10.0

/** An opened region, and whether its fingerprint still needs recording. */
data class OpenedRegion<T>(val region: T, val needsFingerprint: Boolean)

/**
 * Opens an installed region (ADR-0005): by its recorded [fingerprint]
 * when there is one (fast), else, or when the file no longer matches it,
 * with the full check; after a full check the caller records the file's
 * fingerprint anew. A file that fails the full check throws.
 */
fun <T> openRegion(
    fingerprint: String?,
    openFingerprinted: (String) -> T,
    openFull: () -> T,
): OpenedRegion<T> {
    if (fingerprint != null) {
        try {
            return OpenedRegion(openFingerprinted(fingerprint), needsFingerprint = false)
        } catch (_: Exception) {
            // Changed or unreadable: the full check decides.
        }
    }
    return OpenedRegion(openFull(), needsFingerprint = true)
}
