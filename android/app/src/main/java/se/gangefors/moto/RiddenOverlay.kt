// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

package se.gangefors.moto

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import se.gangefors.moto.core.LatLon

/**
 * The roads the rider's rides have been on (ADR-0010): a thin dashed line,
 * near-black on the light map and off-white on the dark one, on top of the
 * route but below its pins. The core leaves out the stretches under
 * favourite sections. Nothing on it reacts to taps.
 */
class RiddenOverlay(style: Style, darkMap: Boolean) {
    private val source = style.getSourceAs(SOURCE) ?: GeoJsonSource(SOURCE).also(style::addSource)
    private val layer: LineLayer = style.getLayerAs(LAYER) ?: LineLayer(LAYER, SOURCE).withProperties(
        PropertyFactory.lineColor(riddenColor(darkMap)),
        PropertyFactory.lineWidth(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                *RIDDEN_WIDTHS.map { (z, w) -> Expression.stop(z, w) }.toTypedArray(),
            ),
        ),
        // Dashes in line widths, set afresh for each zoom (riddenDashes).
        PropertyFactory.lineDasharray(dashesByZoom(::riddenDashes)),
        PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ).also { l ->
        // Over the route's lines, under its arrows and pins.
        if (style.getLayer(ROUTE_ARROWS) != null) style.addLayerBelow(l, ROUTE_ARROWS) else style.addLayer(l)
    }

    /** Shows [lines] (none hides the layer's content); not below [minZoom]. */
    fun show(lines: List<List<LatLon>>, minZoom: Float) {
        layer.minZoom = minZoom
        val features = lines.filter { it.size >= 2 }.map { line ->
            Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lon, it.lat) }))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private companion object {
        const val SOURCE = "moto-ridden"
        const val LAYER = "moto-ridden-line"
        const val ROUTE_ARROWS = "moto-route-arrows"
    }
}

/** A dash array that changes at each of [DASH_ZOOMS]: [dashes] of that
 * zoom, from it up to the next one (the lowest also below it). */
fun dashesByZoom(dashes: (Int) -> Array<Float>): Expression = Expression.step(
    Expression.zoom(),
    Expression.literal(dashes(DASH_ZOOMS.first)),
    *DASH_ZOOMS.drop(1)
        .map { z -> Expression.stop(z.toFloat(), Expression.literal(dashes(z))) }
        .toTypedArray(),
)
