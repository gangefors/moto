// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Draws a tapped point and where the Rust core snapped it: a small dot at the
 * tap, a larger marker on the road and a thin line between them. The map only
 * draws; snapping happens in the core.
 */
class SnapMarker(style: Style) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)

    init {
        if (style.getLayer(LINE_LAYER) == null) {
            style.addLayer(
                LineLayer(LINE_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), LINK))
                    .withProperties(
                        PropertyFactory.lineColor(COLOR),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                    ),
            )
            style.addLayer(
                CircleLayer(TAP_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), TAP))
                    .withProperties(
                        PropertyFactory.circleRadius(4f),
                        PropertyFactory.circleColor(COLOR),
                    ),
            )
            style.addLayer(
                CircleLayer(SNAP_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), SNAPPED))
                    .withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(COLOR),
                        PropertyFactory.circleStrokeColor("#ffffff"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
            )
        }
    }

    /** Shows the tap, and the snapped road point if there is one. */
    fun show(tap: LatLng, snapped: LatLng?) {
        val tapPoint = Point.fromLngLat(tap.longitude, tap.latitude)
        val features = mutableListOf(feature(tapPoint, TAP))
        if (snapped != null) {
            val road = Point.fromLngLat(snapped.longitude, snapped.latitude)
            features += feature(LineString.fromLngLats(listOf(tapPoint, road)), LINK)
            features += feature(road, SNAPPED)
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Removes the marker. */
    fun clear() {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun feature(geometry: org.maplibre.geojson.Geometry, kind: String): Feature =
        Feature.fromGeometry(geometry).apply { addStringProperty(KIND, kind) }

    private companion object {
        const val SOURCE = "moto-snap"
        const val LINE_LAYER = "moto-snap-link"
        const val TAP_LAYER = "moto-snap-tap"
        const val SNAP_LAYER = "moto-snap-road"
        const val KIND = "kind"
        const val TAP = "tap"
        const val LINK = "link"
        const val SNAPPED = "snapped"
        const val COLOR = "#d81b60"
    }
}
