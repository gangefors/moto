// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.RegionInfo

/**
 * Draws the region's bounding box as a faint dashed outline, so it is clear
 * where the loaded roads end. Drawn once per style.
 */
fun showRegionOutline(style: Style, info: RegionInfo) {
    if (style.getSource(OUTLINE_SOURCE) != null) return
    val (sw, ne) = info.southWest to info.northEast
    val ring = listOf(
        Point.fromLngLat(sw.lon, sw.lat),
        Point.fromLngLat(ne.lon, sw.lat),
        Point.fromLngLat(ne.lon, ne.lat),
        Point.fromLngLat(sw.lon, ne.lat),
        Point.fromLngLat(sw.lon, sw.lat),
    )
    style.addSource(GeoJsonSource(OUTLINE_SOURCE, Feature.fromGeometry(LineString.fromLngLats(ring))))
    style.addLayer(
        LineLayer(OUTLINE_LAYER, OUTLINE_SOURCE).withProperties(
            PropertyFactory.lineColor("#5f6368"),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineWidth(1.5f),
            PropertyFactory.lineDasharray(arrayOf(4f, 3f)),
        ),
    )
}

/**
 * Draws a route from the Rust core with its start and end pins. The map
 * only draws; the route comes from the core.
 */
class RouteOverlay(style: Style) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)

    init {
        if (style.getLayer(LINE_LAYER) == null) {
            style.addLayer(
                LineLayer(CASING_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), ROUTE))
                    .withProperties(
                        PropertyFactory.lineColor("#ffffff"),
                        PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            style.addLayer(
                LineLayer(LINE_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), ROUTE))
                    .withProperties(
                        PropertyFactory.lineColor(ROUTE_COLOR),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            style.addLayer(
                CircleLayer(PIN_LAYER, SOURCE)
                    .withFilter(Expression.neq(Expression.get(KIND), ROUTE))
                    .withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get(KIND),
                                Expression.literal(START_COLOR),
                                Expression.stop(END, END_COLOR),
                            ),
                        ),
                        PropertyFactory.circleStrokeColor("#ffffff"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
            )
        }
    }

    /** Shows the start pin, and the end pin and route when there are any. */
    fun show(start: LatLng?, end: LatLng?, route: List<LatLon>?) {
        val features = mutableListOf<Feature>()
        if (route != null && route.size >= 2) {
            val line = LineString.fromLngLats(route.map { Point.fromLngLat(it.lon, it.lat) })
            features += feature(line, ROUTE)
        }
        start?.let { features += feature(Point.fromLngLat(it.longitude, it.latitude), START) }
        end?.let { features += feature(Point.fromLngLat(it.longitude, it.latitude), END) }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun feature(geometry: org.maplibre.geojson.Geometry, kind: String): Feature =
        Feature.fromGeometry(geometry).apply { addStringProperty(KIND, kind) }

    private companion object {
        const val SOURCE = "moto-route"
        const val CASING_LAYER = "moto-route-casing"
        const val LINE_LAYER = "moto-route-line"
        const val PIN_LAYER = "moto-route-pins"
        const val KIND = "kind"
        const val ROUTE = "route"
        const val START = "start"
        const val END = "end"
        const val ROUTE_COLOR = "#1a73e8"
        const val START_COLOR = "#188038"
        const val END_COLOR = "#c5221f"
    }
}

private const val OUTLINE_SOURCE = "moto-region-outline"
private const val OUTLINE_LAYER = "moto-region-outline"
