package ch.overlandmap.map.ui.world

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.map.LocalTileServer
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.map.MapStyles
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.fillPattern
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature

/** Layer, source and colour constants of the Overlanding map (same as iOS `OverlandingMapView` / Flutter). */
internal object OverlandLayers {
    const val SOURCE = "om-overlanding"
    const val COUNTRIES = "om-countries"
    const val ZONES = "om-zones"
    const val BORDERS = "om-borders"
    const val POSTS = "om-border-posts"
    const val SELECTION = "om-overlanding-selection"
    const val HATCH = "om-zone-hatch"

    /** Source layers inside the country-borders archive. */
    const val SL_COUNTRY = "country"
    const val SL_ZONES = "zones"
    const val SL_BORDER = "border"
    const val SL_POST = "border_post"

    val countryColors = listOf(0 to "#252525", 1 to "#D64545", 2 to "#E7B844", 3 to "#45A866")
    const val COUNTRY_OTHER = "#9A9A9A"
    val borderColors = listOf(0 to "#D64545", 1 to "#3478C7", 2 to "#45A866", 3 to "#E7A43D")
    const val BORDER_OTHER = "#777777"
    const val POST_TEMP_CLOSED = "#8E3B3B"

    /** Which property identifies a feature of each source layer. */
    fun idProperty(selection: OverlandingSelection) = when (selection) {
        is OverlandingSelection.OfCountry -> "ADM0_A3"
        else -> "id"
    }
}

/**
 * The Overlanding map: the `country-borders` vector tiles (served by the local tile server) drawn
 * as country fills by overlanding status, hatched zones, borders and border posts by open state,
 * plus a highlight for the selection. Taps resolve to an [OverlandingSelection]. The camera is
 * restored from [initialCamera] and reported through [onCameraChanged] (debounced by the map's idle event).
 *
 * [dataRevision] changes when the tile archive is (re)installed, which rebuilds the source so tiles
 * cached as empty while the file was missing are requested again.
 */
@Composable
fun OverlandingMap(
    selection: OverlandingSelection?,
    showBorderPosts: Boolean,
    dataRevision: Int,
    initialCamera: OverlandingCamera,
    zoomRequest: GeoBounds?,
    onZoomRequestHandled: () -> Unit,
    pointZoomRequest: Coordinate?,
    onPointZoomHandled: () -> Unit,
    onCameraChanged: (OverlandingCamera) -> Unit,
    onSelect: (OverlandingSelection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val styleUrl = remember { MapStyles.globalStyleUrl(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var layersRevision by remember { mutableStateOf(-1 to -1) }

    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnCamera by rememberUpdatedState(onCameraChanged)
    val hasPendingFocus = zoomRequest != null || pointZoomRequest != null

    MapLibreMapView(
        modifier = modifier,
        styleUrl = styleUrl,
        onMapReady = { ready ->
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            // A pending deep-link focus takes precedence over the saved position.
            if (!hasPendingFocus) {
                ready.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(initialCamera.lat, initialCamera.lon))
                            .zoom(initialCamera.zoom)
                            .build()
                    )
                )
            }
            ready.addOnCameraIdleListener {
                val c = ready.cameraPosition
                c.target?.let { currentOnCamera(OverlandingCamera(it.latitude, it.longitude, c.zoom)) }
            }
            ready.addOnMapClickListener { latLng ->
                currentOnSelect(hitTest(ready, latLng))
                true
            }
            map = ready
        },
        onStyleLoaded = { _, loaded ->
            // A fresh style has none of our layers yet.
            layersRevision = -1 to -1
            style = loaded
        },
    )

    // (Re)install the layers when the style loads or the tile archive changes.
    LaunchedEffect(style, dataRevision) {
        val s = style ?: return@LaunchedEffect
        val rev = dataRevision to s.hashCode()
        if (layersRevision == rev) return@LaunchedEffect
        removeOverlandLayers(s)
        installOverlandLayers(s, showBorderPosts)
        updateSelectionLayer(s, selection)
        layersRevision = rev
    }
    LaunchedEffect(style, selection, layersRevision) {
        style?.let { updateSelectionLayer(it, selection) }
    }
    LaunchedEffect(style, showBorderPosts, layersRevision) {
        style?.getLayer(OverlandLayers.POSTS)?.setProperties(
            visibility(if (showBorderPosts) Property.VISIBLE else Property.NONE)
        )
    }
    // Focus requests wait for the style, so one arriving while the tab is still building isn't lost.
    LaunchedEffect(map, style, zoomRequest) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val bounds = zoomRequest ?: return@LaunchedEffect
        val p = bounds.padded(0.15)
        m.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(p.latMax, p.lonMax, p.latMin, p.lonMin), 32,
            ),
            400,
        )
        onZoomRequestHandled()
    }
    LaunchedEffect(map, style, pointZoomRequest) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val point = pointZoomRequest ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 12.0))
        onPointZoomHandled()
    }
}

private fun colorMatch(property: String, stops: List<Pair<Int, String>>, other: String, extra: Pair<Int, String>? = null): Expression {
    val all = stops + listOfNotNull(extra)
    return toColor(
        match(get(property), literal(other), *all.map { stop(it.first, it.second) }.toTypedArray())
    )
}

private fun installOverlandLayers(style: Style, showBorderPosts: Boolean) {
    val tileSet = TileSet(
        "2.1.0", "${LocalTileServer.baseUrl}/tiles/country-borders/{z}/{x}/{y}.mvt",
    ).apply {
        minZoom = 0f
        // The archive's real ceiling, so the map keeps rendering its tiles when zooming further in.
        maxZoom = (LocalTileServer.countryBordersMaxZoom() ?: 8).toFloat()
    }
    style.addSource(VectorSource(OverlandLayers.SOURCE, tileSet))

    style.addLayer(
        FillLayer(OverlandLayers.COUNTRIES, OverlandLayers.SOURCE).apply {
            sourceLayer = OverlandLayers.SL_COUNTRY
            setProperties(
                fillColor(colorMatch("overlanding", OverlandLayers.countryColors, OverlandLayers.COUNTRY_OTHER)),
                fillOpacity(0.62f),
                fillOutlineColor("#F5F5F5"),
            )
        }
    )

    if (style.getImage(OverlandLayers.HATCH) == null) style.addImage(OverlandLayers.HATCH, hatchBitmap())
    // After countries and before borders, matching the geographic hierarchy.
    style.addLayer(
        FillLayer(OverlandLayers.ZONES, OverlandLayers.SOURCE).apply {
            sourceLayer = OverlandLayers.SL_ZONES
            setProperties(fillPattern(OverlandLayers.HATCH), fillOpacity(0.9f))
        }
    )

    style.addLayer(
        LineLayer(OverlandLayers.BORDERS, OverlandLayers.SOURCE).apply {
            sourceLayer = OverlandLayers.SL_BORDER
            setProperties(
                lineColor(colorMatch("is_open", OverlandLayers.borderColors, OverlandLayers.BORDER_OTHER)),
                lineWidth(interpolate(linear(), zoom(), stop(1, 1.5f), stop(7, 5.0f))),
            )
        }
    )

    style.addLayer(
        CircleLayer(OverlandLayers.POSTS, OverlandLayers.SOURCE).apply {
            sourceLayer = OverlandLayers.SL_POST
            minZoom = 3f
            setProperties(
                visibility(if (showBorderPosts) Property.VISIBLE else Property.NONE),
                circleColor(
                    colorMatch(
                        "is_open", OverlandLayers.borderColors, OverlandLayers.BORDER_OTHER,
                        extra = 4 to OverlandLayers.POST_TEMP_CLOSED,
                    )
                ),
                circleRadius(interpolate(linear(), zoom(), stop(3, 4f), stop(9, 9f))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.5f),
            )
        }
    )
}

private fun removeOverlandLayers(style: Style) {
    listOf(
        OverlandLayers.SELECTION, OverlandLayers.POSTS, OverlandLayers.BORDERS,
        OverlandLayers.ZONES, OverlandLayers.COUNTRIES,
    ).forEach { style.removeLayer(it) }
    style.removeSource(OverlandLayers.SOURCE)
}

/** Draws (or clears) the highlight of the selected object, on top of everything else. */
private fun updateSelectionLayer(style: Style, selection: OverlandingSelection?) {
    style.removeLayer(OverlandLayers.SELECTION)
    if (selection == null || style.getSource(OverlandLayers.SOURCE) == null) return
    val filter = eq(get(OverlandLayers.idProperty(selection)), literal(selection.id))
    val layer: Layer = when (selection) {
        is OverlandingSelection.OfCountry, is OverlandingSelection.OfZone ->
            FillLayer(OverlandLayers.SELECTION, OverlandLayers.SOURCE).apply {
                sourceLayer = if (selection is OverlandingSelection.OfCountry) OverlandLayers.SL_COUNTRY
                else OverlandLayers.SL_ZONES
                setFilter(filter)
                setProperties(fillColor("#FFFFFF"), fillOpacity(0.38f), fillOutlineColor("#111111"))
            }
        is OverlandingSelection.OfBorder ->
            LineLayer(OverlandLayers.SELECTION, OverlandLayers.SOURCE).apply {
                sourceLayer = OverlandLayers.SL_BORDER
                setFilter(filter)
                setProperties(lineColor("#FFFFFF"), lineWidth(6f), lineOpacity(0.9f))
            }
        is OverlandingSelection.OfBorderPost ->
            CircleLayer(OverlandLayers.SELECTION, OverlandLayers.SOURCE).apply {
                sourceLayer = OverlandLayers.SL_POST
                setFilter(filter)
                setProperties(
                    circleColor("#FFFFFF"), circleRadius(12f),
                    circleStrokeColor("#111111"), circleStrokeWidth(3f),
                )
            }
    }
    style.addLayer(layer)
}

/** Diagonal black hatching, 16 px, for the zones fill pattern. */
private fun hatchBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    for (offset in -16..16 step 8) {
        canvas.drawLine(offset.toFloat(), 16f, (offset + 16).toFloat(), 0f, paint)
    }
    return bitmap
}

private fun Feature.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    properties()?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
}

/**
 * What a tap landed on. A 36 px box around the finger is queried, top-most layer first
 * (posts, borders, zones, countries); a miss clears the selection.
 */
private fun hitTest(map: MapLibreMap, latLng: LatLng): OverlandingSelection? {
    if (map.style?.getLayer(OverlandLayers.COUNTRIES) == null) return null
    val point = map.projection.toScreenLocation(latLng)
    val box = RectF(point.x - 18f, point.y - 18f, point.x + 18f, point.y + 18f)
    fun hits(layer: String) = map.queryRenderedFeatures(box, layer)

    hits(OverlandLayers.POSTS).firstNotNullOfOrNull { it.string("id", "documentId") }
        ?.let { return OverlandingSelection.OfBorderPost(it) }
    hits(OverlandLayers.BORDERS).firstNotNullOfOrNull { it.string("id", "documentId") }
        ?.let { return OverlandingSelection.OfBorder(it) }
    hits(OverlandLayers.ZONES).firstNotNullOfOrNull { it.string("id", "documentId") }
        ?.let { return OverlandingSelection.OfZone(it) }
    hits(OverlandLayers.COUNTRIES).firstNotNullOfOrNull { it.string("ADM0_A3", "id", "documentId") }
        ?.let { return OverlandingSelection.OfCountry(it) }
    return null
}
