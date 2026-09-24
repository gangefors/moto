// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a route to a nav app through the share sheet (PRD R9): the GPX
 * goes to one file in the cache's routes folder, and the app the rider
 * picks gets read access to that file only. Earlier exports are deleted
 * first, so at most one route lies there.
 */
object RouteShare {
    private const val FOLDER = "routes"

    /** Writes [gpx] as [fileName] (one of ours, see [routeFileName]);
     * returns the share intent. Call off the main thread. */
    fun prepare(context: Context, gpx: String, fileName: String, title: String): Intent {
        require(isRouteFileName(fileName)) { "not a route file name: $fileName" }
        val dir = File(context.cacheDir, FOLDER).apply { mkdirs() }
        dir.listFiles()?.filter { isRouteFileName(it.name) }?.forEach { it.delete() }
        val file = File(dir, fileName)
        file.writeText(gpx, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(fileName, uri)
        return Intent.createChooser(send, title)
    }
}
