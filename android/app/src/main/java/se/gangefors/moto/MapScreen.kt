// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The single map screen (ADR-0002): OpenFreeMap tiles (ADR-0003), attribution
 * visible, and the rider's GPS position. The map only picks, draws and
 * hit-tests; routing and snapping belong to the Rust core.
 */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> hasLocation = granted.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // Load the style once the map is ready.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = true
            m.cameraPosition = initialCamera(resources)
            m.setStyle(resources.getString(R.string.map_style_url)) { s ->
                map = m
                style = s
            }
        }
    }

    // The map draws edge to edge, but its controls must never sit under the
    // status bar, navigation bar or a display cutout.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val insets = SafeInsets(
        left = safe.getLeft(density, layoutDirection),
        top = safe.getTop(density),
        right = safe.getRight(density, layoutDirection),
        bottom = safe.getBottom(density),
    )
    LaunchedEffect(map, insets) {
        val m = map ?: return@LaunchedEffect
        with(density) {
            applyControlMargins(m, insets, CONTROL_MARGIN.roundToPx(), ATTRIBUTION_OFFSET.roundToPx())
        }
    }

    // Status bar icons follow the brightness of the map behind them.
    StatusBarIconsFollowMap(mapView, map, WindowInsets.statusBars.getTop(density))

    // Show the GPS position as soon as both the style and the permission are there.
    LaunchedEffect(map, style, hasLocation) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (hasLocation) enableLocation(context, m, s)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Theme-coloured scrim keeps the navigation bar icons readable over any
        // part of the map; the icons follow the same theme (MainActivity). The
        // status bar has no scrim: its icons are chosen to suit the map style.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .background(if (isSystemInDarkTheme()) DARK_SCRIM else LIGHT_SCRIM),
        )
        if (hasLocation) {
            FloatingActionButton(
                onClick = { map?.locationComponent?.cameraMode = CameraMode.TRACKING },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = stringResource(R.string.my_location),
                )
            }
        }
    }
}

/**
 * Samples the map pixels behind the status bar and switches the status bar
 * icons between light and dark to contrast with them: whenever the map
 * becomes idle, and at most every [SAMPLE_INTERVAL_MS] while the camera moves.
 */
@Composable
private fun StatusBarIconsFollowMap(mapView: MapView, map: MapLibreMap?, statusBarHeight: Int) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(mapView, map, statusBarHeight) {
        if (map == null || statusBarHeight <= 0) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val handler = Handler(Looper.getMainLooper())
        val sample = createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT)
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        var lastSampleAt = 0L

        fun update() {
            val surface = mapView.findSurfaceView() ?: return
            if (surface.width <= 0 || !surface.holder.surface.isValid) return
            lastSampleAt = SystemClock.uptimeMillis()
            // PixelCopy scales the status bar strip down into the small bitmap.
            val strip = Rect(0, 0, surface.width, statusBarHeight.coerceAtMost(surface.height))
            PixelCopy.request(surface, strip, sample, { result ->
                if (result != PixelCopy.SUCCESS) return@request
                sample.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                controller.isAppearanceLightStatusBars =
                    wantsDarkIcons(averageLuma(pixels), controller.isAppearanceLightStatusBars)
            }, handler)
        }

        val onIdle = MapView.OnDidBecomeIdleListener { update() }
        val onMove = MapLibreMap.OnCameraMoveListener {
            if (SystemClock.uptimeMillis() - lastSampleAt >= SAMPLE_INTERVAL_MS) update()
        }
        mapView.addOnDidBecomeIdleListener(onIdle)
        map.addOnCameraMoveListener(onMove)
        update()
        onDispose {
            mapView.removeOnDidBecomeIdleListener(onIdle)
            map.removeOnCameraMoveListener(onMove)
            handler.removeCallbacksAndMessages(null)
        }
    }
}

/** The SurfaceView MapLibre renders into, if it uses one. */
private fun View.findSurfaceView(): SurfaceView? = when (this) {
    is SurfaceView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findSurfaceView() }
    else -> null
}

/** Size of the downscaled status bar sample, in pixels. */
private const val SAMPLE_WIDTH = 64
private const val SAMPLE_HEIGHT = 8

/** Minimum time between samples while the camera moves. */
private const val SAMPLE_INTERVAL_MS = 500L

/** System-bar and cutout insets in pixels. */
private data class SafeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Translucent scrims behind the navigation bar, per system theme. */
private val LIGHT_SCRIM = Color.White.copy(alpha = 0.7f)
private val DARK_SCRIM = Color.Black.copy(alpha = 0.7f)

/** MapLibre's default control margin. */
private val CONTROL_MARGIN: Dp = 4.dp

/** MapLibre's default attribution offset from the left, which keeps it clear of the logo. */
private val ATTRIBUTION_OFFSET: Dp = 92.dp

/** Moves the compass, logo and attribution inside the safe area; px arguments. */
private fun applyControlMargins(map: MapLibreMap, insets: SafeInsets, margin: Int, attributionOffset: Int) {
    map.uiSettings.apply {
        setCompassMargins(0, insets.top + margin, insets.right + margin, 0)
        setLogoMargins(insets.left + margin, 0, 0, insets.bottom + margin)
        setAttributionMargins(insets.left + attributionOffset, 0, 0, insets.bottom + margin)
    }
}

/** A [MapView] that follows the composition's lifecycle, as MapLibre requires. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
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
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun initialCamera(res: Resources): CameraPosition =
    CameraPosition.Builder()
        .target(
            LatLng(
                res.getString(R.string.map_initial_lat).toDouble(),
                res.getString(R.string.map_initial_lon).toDouble(),
            ),
        )
        .zoom(res.getInteger(R.integer.map_initial_zoom).toDouble())
        .build()

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@SuppressLint("MissingPermission") // Only called after hasLocationPermission().
private fun enableLocation(context: Context, map: MapLibreMap, style: Style) {
    map.locationComponent.apply {
        if (!isLocationComponentActivated) {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
        }
        isLocationComponentEnabled = true
        renderMode = RenderMode.COMPASS
        cameraMode = CameraMode.TRACKING
    }
}
