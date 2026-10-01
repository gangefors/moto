// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.toColorInt
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Polygon
import org.maplibre.geojson.Point
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Rating
import se.gangefors.moto.core.RegionInfo

/**
 * Shows where routing works: a veil (grey, or black on the dark map
 * [darkMap]) over everything outside
 * the area the region's roads cover, with a thin line along its edge
 * ([coverage], from the core). A region file without that outline (format
 * 1.0) shows its bounding box as a dashed line instead. Updated in place
 * when another region takes over; drawn under the routes and sections.
 */
fun showRegionOutline(style: Style, info: RegionInfo, coverage: List<List<LatLon>>, darkMap: Boolean = false) {
    val rings = coverage.filter { it.size >= 4 }.map { ring -> ring.map { Point.fromLngLat(it.lon, it.lat) } }
    val edge: Feature
    val veil: Feature?
    if (rings.isEmpty()) {
        val (sw, ne) = info.southWest to info.northEast
        edge = Feature.fromGeometry(
            LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(sw.lon, sw.lat),
                    Point.fromLngLat(ne.lon, sw.lat),
                    Point.fromLngLat(ne.lon, ne.lat),
                    Point.fromLngLat(sw.lon, ne.lat),
                    Point.fromLngLat(sw.lon, sw.lat),
                ),
            ),
        )
        veil = null
    } else {
        edge = Feature.fromGeometry(MultiLineString.fromLngLats(rings))
        // The whole world with a hole for each covered area.
        veil = Feature.fromGeometry(Polygon.fromLngLats(listOf(WORLD) + rings))
    }
    val veilSource = style.getSourceAs<GeoJsonSource>(VEIL_SOURCE)
    val edgeSource = style.getSourceAs<GeoJsonSource>(OUTLINE_SOURCE)
    if (veilSource != null && edgeSource != null) {
        veilSource.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(veil)))
        edgeSource.setGeoJson(edge)
        style.getLayer(OUTLINE_LAYER)?.setProperties(
            PropertyFactory.lineDasharray(if (veil == null) DASHES else SOLID),
        )
        return
    }
    style.addSource(GeoJsonSource(VEIL_SOURCE, FeatureCollection.fromFeatures(listOfNotNull(veil))))
    style.addSource(GeoJsonSource(OUTLINE_SOURCE, edge))
    // Grey dims the light map; on the dark map grey would lighten it, so
    // black dims it there.
    val veilLayer = FillLayer(VEIL_LAYER, VEIL_SOURCE).withProperties(
        PropertyFactory.fillColor(if (darkMap) "#000000" else "#5f6368"),
        PropertyFactory.fillOpacity(if (darkMap) 0.45f else 0.28f),
    )
    val edgeLayer = LineLayer(OUTLINE_LAYER, OUTLINE_SOURCE).withProperties(
        PropertyFactory.lineColor("#5f6368"),
        PropertyFactory.lineOpacity(0.7f),
        PropertyFactory.lineWidth(1.5f),
        PropertyFactory.lineDasharray(if (veil == null) DASHES else SOLID),
    )
    // Under the app's own lines (routes, sections), over the base map.
    val firstOwn = style.layers.firstOrNull { it.id.startsWith("moto-") }?.id
    if (firstOwn != null) {
        style.addLayerBelow(veilLayer, firstOwn)
        style.addLayerBelow(edgeLayer, firstOwn)
    } else {
        style.addLayer(veilLayer)
        style.addLayer(edgeLayer)
    }
}

private val WORLD = listOf(
    Point.fromLngLat(-180.0, -85.0),
    Point.fromLngLat(180.0, -85.0),
    Point.fromLngLat(180.0, 85.0),
    Point.fromLngLat(-180.0, 85.0),
    Point.fromLngLat(-180.0, -85.0),
)
private val DASHES = arrayOf(4f, 3f)
private val SOLID = arrayOf(1f, 0f)

/**
 * Draws a route from the Rust core with its start and end pins. The route
 * is blue all the way; its stretches on favourite sections glow purple, and
 * on gravel it gets white dashes along its middle. Other routes to choose
 * from (a set of loops, a route's choices) are drawn faint underneath, and
 * a tap on one tells which (see [otherAt]). The fastest of a route's
 * choices, the dull option, is grey instead of blue.
 * The map only draws; the route comes from the core.
 */
class RouteOverlay(private val style: Style, private val density: Float, private val darkMap: Boolean = false) {
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
                        PropertyFactory.lineOpacity(0.8f * outlineStrength(darkMap)),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            style.addLayer(
                LineLayer(OTHER_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), OTHER))
                    .withProperties(
                        PropertyFactory.lineColor(routeColor()),
                        PropertyFactory.lineWidth(3.5f),
                        PropertyFactory.lineOpacity(0.45f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            // Glow: a wide, soft band under the route's casing, in the
            // colour of the section's rating.
            style.addLayer(
                LineLayer(GLOW_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), FAVOURITE))
                    .withProperties(
                        PropertyFactory.lineColor(Expression.get(GLOW_COLOR)),
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
                        PropertyFactory.lineOpacity(outlineStrength(darkMap)),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            style.addLayer(
                LineLayer(LINE_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), ROUTE))
                    .withProperties(
                        PropertyFactory.lineColor(routeColor()),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
            )
            // Gravel: white dashes along the middle of the route, like the
            // centre line of a road. The line and its casing stay whole, so
            // the route keeps its width. Dashes as long as the ridden roads'
            // at every zoom (dashArray).
            style.addLayer(
                LineLayer(GRAVEL_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), GRAVEL))
                    .withProperties(
                        PropertyFactory.lineColor(CASING_COLOR),
                        PropertyFactory.lineWidth(GRAVEL_DASH_WIDTH),
                        PropertyFactory.lineDasharray(dashesByZoom(::gravelDashes)),
                        PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
            )
            // A one-way section shown as the route: its direction, with the
            // same arrows as on the sections.
            style.addImage(ARROW_IMAGE, arrowBitmap(density))
            style.addLayer(
                SymbolLayer(ARROW_LAYER, SOURCE)
                    .withFilter(
                        Expression.all(
                            Expression.eq(Expression.get(KIND), ROUTE),
                            Expression.toBool(Expression.get(ARROWS)),
                        ),
                    )
                    .withProperties(
                        PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                        PropertyFactory.symbolSpacing(60f),
                        PropertyFactory.iconImage(ARROW_IMAGE),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
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
     * [favourites] are the route's stretches on favourite sections
     * (glowing in the colour of [favouriteRatings], one per stretch),
     * [gravel] those on unpaved roads, [via] the points it passes, and
     * [others] the other routes to choose from, by their index. The route
     * is grey when [dull] (the fastest choice), as is the other route of
     * index [dullOther]. With [arrows] the route shows its direction (a
 * one-way section shown on its own). */
    fun show(
        start: LatLng?,
        end: LatLng?,
        route: List<LatLon>?,
        favourites: List<List<LatLon>> = emptyList(),
        gravel: List<List<LatLon>> = emptyList(),
        via: List<LatLng> = emptyList(),
        others: List<Pair<Int, List<LatLon>>> = emptyList(),
        dull: Boolean = false,
        dullOther: Int? = null,
        arrows: Boolean = false,
        favouriteRatings: List<Rating> = emptyList(),
    ) {
        val features = mutableListOf<Feature>()
        fun line(points: List<LatLon>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })
        others.filter { it.second.size >= 2 }.forEach { (i, points) ->
            features += feature(line(points), OTHER).apply {
                addNumberProperty(INDEX, i)
                addBooleanProperty(DULL, i == dullOther)
            }
        }
        if (route != null && route.size >= 2) {
            features += feature(line(route), ROUTE).apply {
                addBooleanProperty(DULL, dull)
                addBooleanProperty(ARROWS, arrows)
            }
            favouriteGlowColors(favourites, favouriteRatings, darkMap).forEach { (points, color) ->
                features += feature(line(points), FAVOURITE).apply { addStringProperty(GLOW_COLOR, color) }
            }
            gravel.filter { it.size >= 2 }.forEach { features += feature(line(it), GRAVEL) }
        }
        via.forEachIndexed { i, p ->
            features += feature(Point.fromLngLat(p.longitude, p.latitude), VIA).apply { addNumberProperty(INDEX, i) }
        }
        start?.let { features += feature(Point.fromLngLat(it.longitude, it.latitude), START) }
        end?.let { features += feature(Point.fromLngLat(it.longitude, it.latitude), END) }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun feature(geometry: org.maplibre.geojson.Geometry, kind: String): Feature =
        Feature.fromGeometry(geometry).apply { addStringProperty(KIND, kind) }

    /** The index of the via point drawn at [point] (within a finger's
     * width), or `null`. */
    fun viaAt(map: MapLibreMap, point: LatLng): Int? {
        val screen: PointF = map.projection.toScreenLocation(point)
        val r = HIT_RADIUS_DP * density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, PIN_LAYER)
            .filter { it.getStringProperty(KIND) == VIA }
            .firstNotNullOfOrNull { it.getNumberProperty(INDEX)?.toInt() }
    }

    /** Blue, or grey for the dull option (a feature's [DULL] property). */
    private fun routeColor(): Expression = Expression.switchCase(
        Expression.toBool(Expression.get(DULL)),
        Expression.color(DULL_COLOR.toColorInt()),
        Expression.color(ROUTE_COLOR.toColorInt()),
    )

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
        const val ARROW_LAYER = "moto-route-arrows"
        const val ARROW_IMAGE = "moto-route-arrow"
        const val ARROWS = "arrows"
        const val OTHER_LAYER = "moto-route-other"
        const val OTHER_CASING_LAYER = "moto-route-other-casing"
        const val OTHER = "other"
        const val INDEX = "index"
        const val DULL = "dull"
        // The fastest route: a road grey, not the blue of the fun ones.
        const val DULL_COLOR = "#5f6368"
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
        const val GLOW_COLOR = "glowColor"
        // The route's white outline, also its centre dashes on gravel.
        const val CASING_COLOR = "#ffffff"
        const val START_COLOR = "#188038"
        const val END_COLOR = "#c5221f"
    }
}

private const val OUTLINE_SOURCE = "region-outline"
private const val OUTLINE_LAYER = "region-outline"
private const val VEIL_SOURCE = "region-veil"
private const val VEIL_LAYER = "region-veil"

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
