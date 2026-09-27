// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.graphics.PointF
import android.graphics.RectF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
 * Draws a route from the Rust core with its start and end pins. The route
 * is blue all the way; its stretches on favourite sections glow purple, and
 * on gravel it gets white dashes along its middle. Other routes to choose
 * from (a set of loops) are drawn faint underneath, and a tap on one tells
 * which (see [otherAt]).
 * The map only draws; the route comes from the core.
 */
class RouteOverlay(private val style: Style, private val density: Float) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)

    init {
        if (style.getLayer(LINE_LAYER) == null) {
            // The other routes: thin and pale, under everything else.
            style.addLayer(
                LineLayer(OTHER_CASING_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), OTHER))
                    .withProperties(
                        PropertyFactory.lineColor(CASING_COLOR),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineOpacity(0.8f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            style.addLayer(
                LineLayer(OTHER_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), OTHER))
                    .withProperties(
                        PropertyFactory.lineColor(ROUTE_COLOR),
                        PropertyFactory.lineWidth(3.5f),
                        PropertyFactory.lineOpacity(0.45f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            // Glow: a wide, soft purple band under the route's casing.
            style.addLayer(
                LineLayer(GLOW_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), FAVOURITE))
                    .withProperties(
                        PropertyFactory.lineColor(FAVOURITE_COLOR),
                        PropertyFactory.lineWidth(18f),
                        PropertyFactory.lineBlur(4f),
                        PropertyFactory.lineOpacity(0.55f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            style.addLayer(
                LineLayer(CASING_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), ROUTE))
                    .withProperties(
                        PropertyFactory.lineColor(CASING_COLOR),
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
            // Gravel: white dashes along the middle of the route, like the
            // centre line of a road. The line and its casing stay whole, so
            // the route keeps its width. Dashes are in line widths: 6 px of
            // dash, 4 px of gap.
            style.addLayer(
                LineLayer(GRAVEL_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), GRAVEL))
                    .withProperties(
                        PropertyFactory.lineColor(CASING_COLOR),
                        PropertyFactory.lineWidth(2f),
                        PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                        PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
            )
            style.addLayer(
                CircleLayer(PIN_LAYER, SOURCE)
                    .withFilter(
                        Expression.any(
                            Expression.eq(Expression.get(KIND), START),
                            Expression.eq(Expression.get(KIND), END),
                            Expression.eq(Expression.get(KIND), VIA),
                        ),
                    )
                    .withProperties(
                        PropertyFactory.circleRadius(
                            Expression.match(Expression.get(KIND), Expression.literal(9f), Expression.stop(VIA, 7f)),
                        ),
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get(KIND),
                                Expression.literal(START_COLOR),
                                Expression.stop(END, END_COLOR),
                                Expression.stop(VIA, ROUTE_COLOR),
                            ),
                        ),
                        PropertyFactory.circleStrokeColor("#ffffff"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
            )
        }
    }

    /** Shows the start pin, and the end pin and route when there are any;
     * [favourites] are the route's stretches on favourite sections,
     * [gravel] those on unpaved roads, [via] the points it passes, and
     * [others] the other routes to choose from, by their index. */
    fun show(
        start: LatLng?,
        end: LatLng?,
        route: List<LatLon>?,
        favourites: List<List<LatLon>> = emptyList(),
        gravel: List<List<LatLon>> = emptyList(),
        via: List<LatLng> = emptyList(),
        others: List<Pair<Int, List<LatLon>>> = emptyList(),
    ) {
        val features = mutableListOf<Feature>()
        fun line(points: List<LatLon>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        others.filter { it.second.size >= 2 }.forEach { (i, points) ->
            features += feature(line(points), OTHER).apply { addNumberProperty(INDEX, i) }
        }
        if (route != null && route.size >= 2) {
            features += feature(line(route), ROUTE)
            favourites.filter { it.size >= 2 }.forEach { features += feature(line(it), FAVOURITE) }
            gravel.filter { it.size >= 2 }.forEach { features += feature(line(it), GRAVEL) }
        }
        via.forEach { features += feature(Point.fromLngLat(it.longitude, it.latitude), VIA) }
        start?.let { features += feature(Point.fromLngLat(it.longitude, it.latitude), START) }
        end?.let { features += feature(Point.fromLngLat(it.longitude, it.latitude), END) }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun feature(geometry: org.maplibre.geojson.Geometry, kind: String): Feature =
        Feature.fromGeometry(geometry).apply { addStringProperty(KIND, kind) }

    /** The index of the other route drawn at [point] (within a finger's
     * width), or `null`. */
    fun otherAt(map: MapLibreMap, point: LatLng): Int? {
        val screen: PointF = map.projection.toScreenLocation(point)
        val r = HIT_RADIUS_DP * density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, OTHER_LAYER)
            .firstNotNullOfOrNull { it.getNumberProperty(INDEX)?.toInt() }
    }

    private companion object {
        const val SOURCE = "moto-route"
        const val CASING_LAYER = "moto-route-casing"
        const val LINE_LAYER = "moto-route-line"
        const val GLOW_LAYER = "moto-route-favourite-glow"
        const val GRAVEL_LAYER = "moto-route-gravel"
        const val PIN_LAYER = "moto-route-pins"
        const val OTHER_LAYER = "moto-route-other"
        const val OTHER_CASING_LAYER = "moto-route-other-casing"
        const val OTHER = "other"
        const val INDEX = "index"
        // Half a fingertip: how near a tap must be to pick another route.
        const val HIT_RADIUS_DP = 16f
        const val KIND = "kind"
        const val ROUTE = "route"
        const val FAVOURITE = "favourite"
        const val GRAVEL = "gravel"
        const val START = "start"
        const val END = "end"
        const val VIA = "via"
        const val ROUTE_COLOR = "#1a73e8"
        // Epic purple, the favourite colour of the section layer.
        const val FAVOURITE_COLOR = "#a142f4"
        // The route's white outline, also its centre dashes on gravel.
        const val CASING_COLOR = "#ffffff"
        const val START_COLOR = "#188038"
        const val END_COLOR = "#c5221f"
    }
}

private const val OUTLINE_SOURCE = "moto-region-outline"
private const val OUTLINE_LAYER = "moto-region-outline"

/**
 * Draws the ride being recorded as it grows. The line comes from the
 * recording service; the map only draws it.
 */
class RideOverlay(style: Style) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)

    init {
        if (style.getLayer(LAYER) == null) {
            style.addLayer(
                LineLayer(LAYER, SOURCE).withProperties(
                    PropertyFactory.lineColor(COLOR),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
            )
        }
    }

    /** Shows [line], or nothing when it is null or a single point. */
    fun show(line: List<LatLon>?) {
        val features = if (line != null && line.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) })))
        } else {
            emptyList()
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private companion object {
        const val SOURCE = "moto-ride"
        const val LAYER = "moto-ride-line"
        const val COLOR = "#e52b50"
    }
}
