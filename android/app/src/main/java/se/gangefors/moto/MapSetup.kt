// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.content.res.Configuration
import android.content.ComponentCallbacks2
import android.content.res.Resources
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.widgets.CompassView
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import se.gangefors.moto.core.LatLon

/** The map layers the screen draws into, created once per style. */
internal class Overlays(
    val sections: SectionOverlay,
    val ride: RideOverlay,
    val follow: FollowOverlay,
    val draft: SectionDraftOverlay,
    val route: RouteOverlay,
    val ridden: RiddenOverlay,
    val snap: SnapMarker,
)

/** What to say while the region is not ready to use. */
internal fun regionStatus(res: Resources, region: RegionState): String = when (region) {
    RegionState.Loading -> res.getString(R.string.region_loading)
    RegionState.Missing -> res.getString(R.string.region_missing)
    is RegionState.Failed -> res.getString(R.string.region_failed, region.message)
    is RegionState.Ready -> res.getString(R.string.map_hint)
}

/** A short message for an error from the core. */
internal fun coreErrorMessage(res: Resources, e: Throwable): String = when (classify(e)) {
    CoreProblem.OUTSIDE_REGION -> res.getString(R.string.region_outside)
    CoreProblem.NO_ROUTE -> res.getString(R.string.route_none)
    CoreProblem.NO_ROAD_NEARBY -> e.message ?: e.toString()
    CoreProblem.OTHER -> res.getString(R.string.snap_error, e.message ?: e.toString())
}

internal fun LatLng.toLatLon() = LatLon(latitude, longitude)

/** MapLibre's compass in this map, once it has made one. */
internal fun MapView.findCompass(): CompassView? {
    fun find(v: android.view.View): CompassView? = when (v) {
        is CompassView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
        else -> null
    }
    return find(this)
}

/** System-bar and cutout insets in pixels. */
internal data class SafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Widest the messages and route card get (tablets, landscape). */
internal val TOP_BOX_MAX_WIDTH: Dp = 640.dp

/** MapLibre's default control margin. */
internal val CONTROL_MARGIN: Dp = 4.dp

/** The scale bar's space above the navigation bar or the sheet. */
internal val SCALE_BOTTOM: Dp = 10.dp

/** MapLibre's default attribution offset from the left, which keeps it clear of the logo. */
internal val ATTRIBUTION_OFFSET: Dp = 92.dp

/** The scale bar starts this far from the left: past the attribution. */
internal val SCALE_START: Dp = ATTRIBUTION_OFFSET + 32.dp

/** Moves the compass, logo and attribution inside the safe area; px
 * arguments. The compass is placed from the top right corner. */
internal fun applyControlMargins(
    map: MapLibreMap,
    insets: SafeInsets,
    margin: Int,
    attributionOffset: Int,
    compassRight: Int,
    compassTop: Int,
) {
    map.uiSettings.apply {
        compassGravity = Gravity.TOP or Gravity.END
        setCompassMargins(0, insets.top + compassTop, insets.right + compassRight, 0)
        setLogoMargins(insets.left + margin, 0, 0, insets.bottom + margin)
        setAttributionMargins(insets.left + attributionOffset, 0, 0, insets.bottom + margin)
    }
}

/** A [MapView] that follows the composition's lifecycle, as MapLibre requires. */
@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // When the system needs memory back while the app is in the
        // background (music and other apps in front), the map drops its
        // tile cache; tiles load again on return.
        val trim = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (dropsMapCaches(level)) mapView.onLowMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = mapView.onLowMemory()
        }
        context.registerComponentCallbacks(trim)
        onDispose {
            context.unregisterComponentCallbacks(trim)
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

internal fun initialCamera(res: Resources): CameraPosition =
    CameraPosition.Builder()
        .target(
            LatLng(
                res.getString(R.string.map_initial_lat).toDouble(),
                res.getString(R.string.map_initial_lon).toDouble(),
            ),
        )
        .zoom(res.getInteger(R.integer.map_initial_zoom).toDouble())
        .build()

/** OpenFreeMap's light or dark map style (config.xml). */
internal fun mapStyleUrl(resources: android.content.res.Resources, dark: Boolean): String =
    resources.getString(if (dark) R.string.map_style_url_dark else R.string.map_style_url)
