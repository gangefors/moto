// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileNotFoundException
import se.gangefors.moto.core.Engine
import se.gangefors.moto.core.verifyRegionFile

/** What the app knows about its routing region. */
sealed interface RegionState {
    data object Loading : RegionState
    data class Ready(val engine: Engine) : RegionState
    /** This build carries no region file. */
    data object Missing : RegionState
    data class Failed(val message: String) : RegionState
}

/**
 * The region file bundled in the APK (testing only; production downloads
 * regions, ADR-0005). A memory map needs a real file, so the asset is copied
 * into app storage once per app install or update, verified (checksums and
 * structure), and moved into place. Call off the main thread.
 */
object BundledRegion {
    private const val ASSET = "regions/m0.region"
    private const val DIR = "regions"
    private const val FILE = "m0.region"

    private var opened: RegionState.Ready? = null

    /** Installs the region if needed and opens it; later calls reuse the open engine. */
    @Synchronized
    fun open(context: Context): RegionState = opened ?: load(context).also {
        if (it is RegionState.Ready) opened = it
    }

    private fun load(context: Context): RegionState = try {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val installed = File(dir, FILE)
        val stamp = File(dir, "$FILE.stamp")
        val version = appVersionStamp(context)
        if (!installed.isFile || stamp.takeIf { it.isFile }?.readText() != version) {
            install(context, installed)
            stamp.writeText(version)
        }
        RegionState.Ready(Engine.open(installed.path))
    } catch (_: FileNotFoundException) {
        RegionState.Missing
    } catch (e: Exception) {
        RegionState.Failed(e.message ?: e.toString())
    }

    /** Copies the asset next to [target], verifies it, then renames it over [target]. */
    private fun install(context: Context, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 16) }
            }
            verifyRegionFile(tmp.path)
            if (!tmp.renameTo(target)) error("could not move the region file into place")
        } finally {
            tmp.delete()
        }
    }

    /** Changes whenever the APK is installed or updated, and so may carry a new region. */
    private fun appVersionStamp(context: Context): String {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        return info.lastUpdateTime.toString()
    }
}
