package ch.overlandmap.map.ui.tracking

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
    val onTapped by rememberUpdatedState(onWaypointTapped)

    MapLibreMapView(
        modifier = modifier,
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
                trackId != null
            }
        },
        onStyleLoaded = { _, loaded -> style = loaded },
    )

    LaunchedEffect(style, tracks) {
        val s = style ?: return@LaunchedEffect
        val lines = FeatureCollection.fromFeatures(
            tracks.filter { it.points.size > 1 }.map { t ->
                Feature.fromGeometry(
                    LineString.fromLngLats(t.points.map { Point.fromLngLat(it.longitude, it.latitude) })
                ).also { it.addStringProperty("color", t.colorHex.toMapColor()) }
            }
        )
        val pins = FeatureCollection.fromFeatures(
            tracks.flatMap { t ->
                t.waypoints.map { w ->
                    Feature.fromGeometry(Point.fromLngLat(w.longitude, w.latitude)).also {
                        it.addStringProperty("id", w.id)
                        it.addStringProperty("trackId", t.id)
                        it.addStringProperty("color", t.colorHex.toMapColor())
                    }
                }
            }
        )
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
                    circleColor(toColor(get("color"))), circleRadius(8f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
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
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.lat, focus.lon), 14.0), 400)
        }
    }
}

/** `#RRGGBB` as MapLibre expects it; anything unparseable falls back to the default track colour. */
internal fun String.toMapColor(): String =
    if (Regex("^#[0-9A-Fa-f]{6}$").matches(this)) this else RecordedTrack.DEFAULT_COLOR
