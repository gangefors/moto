// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import se.gangefors.moto.core.LatLon
import se.gangefors.moto.core.Section

/**
 * Draws the rider's saved sections, coloured by rating, with arrows along
 * one-way sections, and finds the section under a tap. The map only draws
 * and hit-tests; the sections come from the core's store.
 */
class SectionOverlay(style: Style, private val density: Float) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)

    init {
        if (style.getLayer(LINE_LAYER) == null) {
            style.addImage(ARROW_IMAGE, arrowBitmap(density))
            style.addLayer(
                LineLayer(CASING_LAYER, SOURCE).withProperties(
                    PropertyFactory.lineColor("#ffffff"),
                    PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineOpacity(0.8f),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LINE_LAYER, SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get(COLOR)),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
            )
            style.addLayer(
                SymbolLayer(ARROW_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(ONE_WAY), true))
                    .withProperties(
                        PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                        PropertyFactory.symbolSpacing(80f),
                        PropertyFactory.iconImage(ARROW_IMAGE),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    ),
            )
        }
    }

    fun show(sections: List<Section>) {
        val features = sections.filter { it.geometry.size >= 2 }.map { s ->
            Feature.fromGeometry(s.geometry.toLineString()).apply {
                addNumberProperty(ID, s.id)
                addStringProperty(COLOR, ratingColor(s.rating))
                addBooleanProperty(ONE_WAY, isOneWay(s.direction))
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** The id of the saved section drawn under [point], if any. */
    fun sectionAt(map: MapLibreMap, point: LatLng): Long? {
        val screen: PointF = map.projection.toScreenLocation(point)
        val r = HIT_RADIUS_DP * density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, LINE_LAYER)
            .firstNotNullOfOrNull { sectionIdOf(it.getNumberProperty(ID)) }
    }

    private companion object {
        const val SOURCE = "moto-sections"
        const val CASING_LAYER = "moto-sections-casing"
        const val LINE_LAYER = "moto-sections-line"
        const val ARROW_LAYER = "moto-sections-arrows"
        const val ARROW_IMAGE = "moto-section-arrow"
        const val ID = "id"
        const val COLOR = "color"
        const val ONE_WAY = "oneWay"
        const val HIT_RADIUS_DP = 12f
    }
}

/**
 * Draws a section proposed in "mark section" mode: the picked start and end
 * pins and the road between them, dashed until it is saved.
 */
class SectionDraftOverlay(style: Style) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)

    init {
        if (style.getLayer(LINE_LAYER) == null) {
            style.addLayer(
                LineLayer(LINE_LAYER, SOURCE)
                    .withFilter(Expression.eq(Expression.get(KIND), LINE))
                    .withProperties(
                        PropertyFactory.lineColor(DRAFT_COLOR),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineDasharray(arrayOf(1.5f, 1f)),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
            )
            style.addLayer(
                CircleLayer(PIN_LAYER, SOURCE)
                    .withFilter(Expression.neq(Expression.get(KIND), LINE))
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

    /** Shows the pins that are set and the proposed road, if any; all null clears it. */
    fun show(start: LatLng?, end: LatLng?, road: List<LatLon>?) {
        val features = mutableListOf<Feature>()
        if (road != null && road.size >= 2) {
            features += Feature.fromGeometry(road.toLineString()).apply { addStringProperty(KIND, LINE) }
        }
        start?.let { features += pin(it, START) }
        end?.let { features += pin(it, END) }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun pin(p: LatLng, kind: String): Feature =
        Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)).apply { addStringProperty(KIND, kind) }

    private companion object {
        const val SOURCE = "moto-section-draft"
        const val LINE_LAYER = "moto-section-draft-line"
        const val PIN_LAYER = "moto-section-draft-pins"
        const val KIND = "kind"
        const val LINE = "line"
        const val START = "start"
        const val END = "end"
        const val DRAFT_COLOR = "#d81b60"
        const val START_COLOR = "#188038"
        const val END_COLOR = "#c5221f"
    }
}

private fun List<LatLon>.toLineString(): LineString =
    LineString.fromLngLats(map { Point.fromLngLat(it.lon, it.lat) })

/** A small chevron pointing along the line (to the right), white-edged. */
private fun arrowBitmap(density: Float): Bitmap {
    val size = (14 * density).toInt().coerceAtLeast(8)
    val bitmap = createBitmap(size, size)
    val s = size.toFloat()
    val path = Path().apply {
        moveTo(s * 0.3f, s * 0.2f)
        lineTo(s * 0.7f, s * 0.5f)
        lineTo(s * 0.3f, s * 0.8f)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    Canvas(bitmap).apply {
        drawPath(path, paint.apply { color = android.graphics.Color.WHITE; strokeWidth = s * 0.28f })
        drawPath(path, paint.apply { color = android.graphics.Color.rgb(32, 33, 36); strokeWidth = s * 0.14f })
    }
    return bitmap
}
