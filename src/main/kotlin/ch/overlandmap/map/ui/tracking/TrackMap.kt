package ch.overlandmap.map.ui.tracking

import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.ui.home.MapStyleMenu
import ch.overlandmap.map.map.addUserLocationLayer
import ch.overlandmap.map.map.updateUserLocation
import kotlinx.coroutines.launch
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
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.map.MapStyles
import ch.overlandmap.map.tracking.RecordedTrack
import ch.overlandmap.map.ui.home.SidebarFocus
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
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val TRACKS_SOURCE = "recorded-tracks"
private const val WAYPOINTS_SOURCE = "recorded-waypoints"
private const val TRACKS_LAYER = "recorded-tracks-layer"
private const val WAYPOINTS_LAYER = "recorded-waypoints-layer"

/**
 * The tracks on the offline base map, each in its own colour, with their waypoints as pins.
 * A tap on a pin reports the waypoint id (iOS `TrackListMapScreen` / `RecordingViewer` maps).
 */
@Composable
fun TrackMap(
    tracks: List<RecordedTrack>,
    focus: SidebarFocus?,
    onWaypointTapped: (trackId: String, waypointId: String) -> Unit,
    modifier: Modifier = Modifier,
    onTrackTapped: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val styleOptions by app.userPreferences.mapStyle.collectAsState(initial = app.userPreferences.mapStyleNow())
    val mapLanguage by app.userPreferences.mapLanguage.collectAsState(initial = app.userPreferences.mapLanguageNow())
    val scope = rememberCoroutineScope()
    val hasMapboxToken = !app.mapboxTokenManager.cachedTokenNow().isNullOrBlank()
    val styleUrl = remember(styleOptions, mapLanguage, hasMapboxToken) {
        MapStyles.resolve(context, styleOptions, hasMapboxToken, mapLanguage)
    }
    val location by rememberUserLocation()
    var zoomWhenLocated by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val onTapped by rememberUpdatedState(onWaypointTapped)
    val onLineTapped by rememberUpdatedState(onTrackTapped)

    Box(modifier) {
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = styleUrl,
            onMapReady = { ready ->
                map = ready
                ready.addOnMapClickListener { latLng ->
                    val p = ready.projection.toScreenLocation(latLng)
                    val hit = ready.queryRenderedFeatures(RectF(p.x - 28f, p.y - 28f, p.x + 28f, p.y + 28f), WAYPOINTS_LAYER)
                        .firstOrNull()
                    val trackId = hit?.getStringProperty("trackId")
                    val waypointId = hit?.getStringProperty("id")
                    if (trackId != null && waypointId != null) onTapped(trackId, waypointId)
                    if (trackId != null) true else {
                        val line = ready.queryRenderedFeatures(RectF(p.x - 12f, p.y - 12f, p.x + 12f, p.y + 12f), TRACKS_LAYER).firstOrNull()
                        line?.getStringProperty("trackId")?.let(onLineTapped)
                        line != null
                    }
                }
            },
            onStyleLoaded = { _, loaded -> style = loaded },
        )

        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapStyleMenu(
                options = styleOptions,
                hasMapboxToken = hasMapboxToken,
                onChange = { options -> scope.launch { app.userPreferences.setMapStyle(options) } },
                waypointFilter = emptySet(),
                onWaypointFilterChange = {},
                showWaypointFilter = false,
            )
            SmallFloatingActionButton(onClick = { zoomWhenLocated = true }) {
                Icon(Icons.Filled.MyLocation, stringResource(R.string.use_current_location))
            }
        }
    }

    LaunchedEffect(style, location) {
        style?.let { addUserLocationLayer(it); updateUserLocation(it, location) }
    }
    LaunchedEffect(map, style, location, zoomWhenLocated) {
        val position = location ?: return@LaunchedEffect
        val ready = map ?: return@LaunchedEffect
        if (style != null && zoomWhenLocated) {
            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.latitude, position.longitude), 14.0), 400)
            zoomWhenLocated = false
        }
    }

    LaunchedEffect(style, tracks) {
        val s = style ?: return@LaunchedEffect
        val lines = FeatureCollection.fromFeatures(
            tracks.filter { it.points.size > 1 }.map { t ->
                Feature.fromGeometry(
                    LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) })
                ).also { it.addStringProperty("color", t.colorHex.toMapColor()); it.addStringProperty("trackId", t.id) }
            }
        )
        val pins = FeatureCollection.fromFeatures(
            tracks.flatMap { t ->
                t.waypoints.map { w ->
                    Feature.fromGeometry(Point.fromLngLat(w.longitude, w.latitude)).also {
                        it.addStringProperty("label", w.label)
                        it.addStringProperty("icon", "imported-${w.symbol}")
                        it.addStringProperty("id", w.id)
                        it.addStringProperty("trackId", t.id)
                        it.addStringProperty("color", t.colorHex.toMapColor())
                    }
                }
            }
        )
        // Render the same type icons in map pins and object sheets.
        tracks.flatMap { it.waypoints }.map { it.symbol }.distinct().forEach { symbol ->
            s.addImage("imported-$symbol", importedWaypointBitmap(context, symbol))
        }
        s.getSourceAs<GeoJsonSource>(TRACKS_SOURCE)?.setGeoJson(lines) ?: run {
            s.addSource(GeoJsonSource(TRACKS_SOURCE, lines))
            s.addLayer(
                LineLayer(TRACKS_LAYER, TRACKS_SOURCE).withProperties(
                    lineColor(toColor(get("color"))), lineWidth(4f),
                    lineJoin(Property.LINE_JOIN_ROUND), lineCap(Property.LINE_CAP_ROUND),
                )
            )
        }
        s.getSourceAs<GeoJsonSource>(WAYPOINTS_SOURCE)?.setGeoJson(pins) ?: run {
            s.addSource(GeoJsonSource(WAYPOINTS_SOURCE, pins))
            s.addLayer(
                CircleLayer(WAYPOINTS_LAYER, WAYPOINTS_SOURCE).withProperties(
                    circleColor(toColor(get("color"))), circleRadius(6f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                ).apply { minZoom = 6f }
            )
        }
    }

    LaunchedEffect(style) {
        val s = style ?: return@LaunchedEffect
        // Layers are installed by the data effect above.
        if (s.getSource(WAYPOINTS_SOURCE) != null && s.getLayer("recorded-waypoint-icons") == null) {
            s.addLayer(org.maplibre.android.style.layers.SymbolLayer("recorded-waypoint-icons", WAYPOINTS_SOURCE).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.iconImage(get("icon")),
                org.maplibre.android.style.layers.PropertyFactory.iconSize(0.5f),
                org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
            ).apply { minZoom = 6f })
            s.addLayer(org.maplibre.android.style.layers.SymbolLayer("recorded-waypoint-labels", WAYPOINTS_SOURCE).withProperties(
                org.maplibre.android.style.layers.PropertyFactory.textField(get("label")),
                org.maplibre.android.style.layers.PropertyFactory.textSize(12f),
                org.maplibre.android.style.layers.PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                org.maplibre.android.style.layers.PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
                org.maplibre.android.style.layers.PropertyFactory.textHaloColor("#FFFFFF"),
                org.maplibre.android.style.layers.PropertyFactory.textHaloWidth(1.5f),
            ).apply { minZoom = 6f })
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
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.lat, focus.lon), 14.0), 400)
        }
    }
}

/** `#RRGGBB` as MapLibre expects it; anything unparseable falls back to the default track colour. */
internal fun String.toMapColor(): String =
    if (Regex("^#[0-9A-Fa-f]{6}$").matches(this)) this else RecordedTrack.DEFAULT_COLOR
