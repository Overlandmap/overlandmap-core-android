package ch.overlandmap.map.ui.home

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.map.MapStyles
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** A point drawn on the sidebar map; [color] is a `#RRGGBB` string. */
data class SidebarMarker(val id: String, val lat: Double, val lon: Double, val color: String = "#1976D2")

/** Asks the map to frame an area or a point; [token] makes asking for the same target twice work. */
data class SidebarFocus(val bounds: GeoBounds? = null, val lat: Double? = null, val lon: Double? = null, val token: Int = 0)

private const val LINES_SOURCE = "sidebar-lines"
private const val MARKERS_SOURCE = "sidebar-markers"
private const val LINES_LAYER = "sidebar-lines-layer"
private const val MARKERS_LAYER = "sidebar-markers-layer"

/**
 * The map above a sidebar article (iOS `SidebarViewer.mapPane`): the pack's tracks as thin lines,
 * the table's objects as dots, on the offline base map. A tap on a dot reports its row id.
 */
@Composable
fun SidebarMap(
    lines: List<List<Pair<Double, Double>>>,
    markers: List<SidebarMarker>,
    focus: SidebarFocus?,
    onMarkerTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val styleOptions by app.userPreferences.mapStyle.collectAsState(initial = app.userPreferences.mapStyleNow())
    val mapLanguage by app.userPreferences.mapLanguage.collectAsState(initial = app.userPreferences.mapLanguageNow())
    val styleUrl = remember(styleOptions.hillshade, styleOptions.contour, mapLanguage) {
        MapStyles.packStyleUrl(context, styleOptions.hillshade, styleOptions.contour, mapLanguage)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val onTapped by rememberUpdatedState(onMarkerTapped)

    MapLibreMapView(
        modifier = modifier,
        styleUrl = styleUrl,
        onMapReady = { ready ->
            ready.uiSettings.isRotateGesturesEnabled = false
            map = ready
            ready.addOnMapClickListener { latLng ->
                val p = ready.projection.toScreenLocation(latLng)
                val hit = ready.queryRenderedFeatures(RectF(p.x - 24f, p.y - 24f, p.x + 24f, p.y + 24f), MARKERS_LAYER)
                    .firstOrNull()?.getStringProperty("id")
                hit?.let { onTapped(it) }
                hit != null
            }
        },
        onStyleLoaded = { _, loaded -> style = loaded },
    )

    // The data layers are (re)built whenever the style loads or the data changes.
    LaunchedEffect(style, lines, markers) {
        val s = style ?: return@LaunchedEffect
        val lineCollection = FeatureCollection.fromFeatures(
            lines.filter { it.size > 1 }.map { line ->
                Feature.fromGeometry(LineString.fromLngLats(line.map { (lat, lon) -> Point.fromLngLat(lon, lat) }))
            }
        )
        val markerCollection = FeatureCollection.fromFeatures(
            markers.map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).also {
                    it.addStringProperty("id", m.id)
                    it.addStringProperty("color", m.color)
                }
            }
        )
        (s.getSourceAs<GeoJsonSource>(LINES_SOURCE))?.setGeoJson(lineCollection)
            ?: run {
                s.addSource(GeoJsonSource(LINES_SOURCE, lineCollection))
                s.addLayer(
                    LineLayer(LINES_LAYER, LINES_SOURCE).withProperties(
                        lineColor("#1E6FD9"), lineOpacity(0.8f), lineWidth(2.5f),
                        lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND),
                    )
                )
            }
        (s.getSourceAs<GeoJsonSource>(MARKERS_SOURCE))?.setGeoJson(markerCollection)
            ?: run {
                s.addSource(GeoJsonSource(MARKERS_SOURCE, markerCollection))
                s.addLayer(
                    CircleLayer(MARKERS_LAYER, MARKERS_SOURCE).withProperties(
                        circleColor(toColor(get("color"))), circleRadius(7f),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    )
                )
            }
    }

    LaunchedEffect(map, style, focus) {
        val m = map ?: return@LaunchedEffect
        if (style == null || focus == null) return@LaunchedEffect
        val b = focus.bounds
        if (b != null) {
            val p = b.padded(0.15)
            m.animateCamera(
                CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(p.latMax, p.lonMax, p.latMin, p.lonMin), 32), 400,
            )
        } else if (focus.lat != null && focus.lon != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.lat, focus.lon), 13.0), 400)
        }
    }
}
