package ch.overlandmap.map.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.map.MapStyles
import ch.overlandmap.map.map.MapboxMapView
import ch.overlandmap.map.map.enableSky
import ch.overlandmap.map.map.enableTerrain
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.Waypoint
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.vectorSource
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.launch

/** The pack's other itineraries, from the remote tracks tileset (blue lines). */
private const val TRACKS_TILES_URL = "https://overlanding.io/tiles/GOA/{z}/{x}/{y}.pbf"
private const val TRACKS_SOURCE = "tracks"
private const val STYLE_TRACKS_LAYER = "tracks"
private const val TRACKS_SOURCE_LAYER = "tracks"
private const val ITINERARY_ID = "itineraryId"

/** The itinerary's own geometry, drawn red over the blue tracks. */
private const val SELECTED_TRACKS_SOURCE = "itin-tracks"
private const val SELECTED_TRACKS_LAYER = "itin-tracks-red"

private const val STEPS_SOURCE = "itin-steps"
private const val STEPS_CIRCLES = "itin-steps-circles"
private const val STEPS_CIRCLES_SELECTED = "itin-steps-circles-sel"
private const val STEPS_NUMBERS = "itin-steps-numbers"
private const val STEPS_NUMBERS_SELECTED = "itin-steps-numbers-sel"
private const val STEP_ID = "stepId"

/** A step id no feature has, used to make a "match nothing" filter. */
private const val NO_STEP = "__none__"

private const val WAYPOINTS_SOURCE = "itin-waypoints"
private const val WAYPOINTS_LAYER = "itin-waypoints-markers"
private const val WAYPOINT_ICON = "itin-waypoint-icon"
private const val WAYPOINT_DOC_ID = "documentId"

/** What a tap on the itinerary map hit, at which map-view pixel position. */
sealed interface ItineraryMapTap {
    val position: Offset

    data class OnStep(val stepId: Int, override val position: Offset) : ItineraryMapTap
    data class OnWaypoint(val documentId: String, override val position: Offset) : ItineraryMapTap
    /** A blue line: another itinerary of the pack, identified by its slug. */
    data class OnItinerary(val itinerarySlug: String, override val position: Offset) : ItineraryMapTap
}

/**
 * Map of one local itinerary, rendered with the **Mapbox Maps SDK** (the rest
 * of the app uses MapLibre). The pack's other itineraries stay blue (the remote
 * tracks tileset filtered to [trackPackId]); this itinerary is drawn red and
 * wide from its locally stored geometry. Steps are numbered circles — white with
 * a black number, inverted for the selected one — and waypoints use a marker.
 */
@Composable
fun MapboxItineraryMap(
    trackPackId: String,
    tracks: List<Track>,
    steps: List<ItineraryStep>,
    waypoints: List<Waypoint>,
    selectedStepId: Int?,
    onTapped: (ItineraryMapTap) -> Unit,
    onMapReady: (MapView) -> Unit = {},
    initialCameraZoom: Double? = null,
    initialCameraLat: Double? = null,
    initialCameraLon: Double? = null,
    onCameraChanged: (zoom: Double, lat: Double, lon: Double) -> Unit = { _, _, _ -> },
    is3D: Boolean = false,
    onSet3D: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val styleOptions by app.userPreferences.mapStyle.collectAsState(
        initial = app.userPreferences.mapStyleNow(),
    )
    val hasMapboxToken = app.mapboxTokenManager.cachedTokenNow() != null
    val showZoom by app.userPreferences.debugShowZoom.collectAsState(
        initial = app.userPreferences.debugShowZoomNow(),
    )
    val styleUrl = remember(styleOptions, hasMapboxToken) {
        MapStyles.resolve(context, styleOptions, hasMapboxToken)
    }
    var fitted by remember { mutableStateOf(false) }
    var mapZoom by remember { mutableStateOf<Double?>(null) }
    var localMapView by remember { mutableStateOf<MapView?>(null) }
    // Captured once: a fresh map instance (e.g. leaving full screen) after the
    // caller clears the restore only fits to content.
    val startCamera = remember {
        if (initialCameraZoom != null && initialCameraLat != null && initialCameraLon != null) {
            Triple(initialCameraZoom, initialCameraLat, initialCameraLon)
        } else {
            null
        }
    }

    Box(modifier = modifier) {
        MapboxMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = styleUrl,
            onMapReady = { mapView ->
                localMapView = mapView
                onMapReady(mapView)
                installTapHandler(mapView, onTapped)
                mapZoom = mapView.mapboxMap.cameraState.zoom
                mapView.mapboxMap.subscribeCameraChanged {
                    val camera = mapView.mapboxMap.cameraState
                    mapZoom = camera.zoom
                    onCameraChanged(camera.zoom, camera.center.latitude(), camera.center.longitude())
                }
            },
            onStyleLoaded = { map, style ->
                addTracksLayer(style, trackPackId)
                addItineraryLayer(style, tracks)
                addWaypointLayer(context, style, waypoints)
                addStepLayers(style, steps)
                if (!fitted) {
                    // Restored at cold start: the saved camera; otherwise the
                    // itinerary's extent.
                    if (startCamera != null) {
                        val (zoom, lat, lon) = startCamera
                        map.setCamera(
                            CameraOptions.Builder()
                                .center(Point.fromLngLat(lon, lat))
                                .zoom(zoom)
                                .build()
                        )
                    } else {
                        fitToContent(map, tracks, steps)
                    }
                    fitted = true
                }
                loadedStyle = style
            },
        )
        Column(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
            MapStyleMenu(
                options = styleOptions,
                hasMapboxToken = hasMapboxToken,
                onChange = { scope.launch { app.userPreferences.setMapStyle(it) } },
            )
            Spacer(Modifier.height(8.dp))
            // 3D terrain toggle. Off: locked to north, no tilt. On: terrain
            // added, all rotations enabled, tilted to 60°.
            Surface(
                onClick = { onSet3D(!is3D) },
                shape = CircleShape,
                color = if (is3D) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = if (is3D) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                shadowElevation = 3.dp,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("3D", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        // Debug: current map zoom level (toggled in Settings → Debug).
        mapZoom?.takeIf { showZoom }?.let { zoom ->
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            ) {
                Text(
                    "z %.1f".format(zoom),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    // 3D on: add terrain, allow rotate/pitch, tilt to 60°. 3D off (the default):
    // lock the map north-up and flat, disabling the rotate and pitch gestures.
    LaunchedEffect(is3D, localMapView, loadedStyle) {
        val mv = localMapView ?: return@LaunchedEffect
        if (is3D) {
            loadedStyle?.let { enableTerrain(it); enableSky(it) }
            mv.gestures.pitchEnabled = true
            mv.gestures.rotateEnabled = true
            mv.camera.easeTo(
                CameraOptions.Builder().pitch(60.0).build(),
                MapAnimationOptions.Builder().duration(500L).build(),
            )
        } else {
            mv.gestures.pitchEnabled = false
            mv.gestures.rotateEnabled = false
            mv.camera.easeTo(
                CameraOptions.Builder().pitch(0.0).bearing(0.0).build(),
                MapAnimationOptions.Builder().duration(500L).build(),
            )
        }
    }

    // Swapping the paired layers' filters moves the inverted circle.
    LaunchedEffect(selectedStepId, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        // A Mapbox filter must reference a feature (a `["==", ["get", …], …]`
        // shape works; a two-constant `["==", 1, 0]` is rejected), so "no
        // selection" is expressed as matching a step id that never exists.
        val id = selectedStepId?.toString() ?: NO_STEP
        val selected = Expression.eq(Expression.get(STEP_ID), Expression.literal(id))
        val others = Expression.neq(Expression.get(STEP_ID), Expression.literal(id))
        style.getLayerAs<CircleLayer>(STEPS_CIRCLES)?.filter(others)
        style.getLayerAs<SymbolLayer>(STEPS_NUMBERS)?.filter(others)
        style.getLayerAs<CircleLayer>(STEPS_CIRCLES_SELECTED)?.filter(selected)
        style.getLayerAs<SymbolLayer>(STEPS_NUMBERS_SELECTED)?.filter(selected)
    }
}

/** The pack's blue tracks: remote vector tileset, filtered to this pack. */
private fun addTracksLayer(style: Style, trackPackId: String) {
    if (!style.styleLayerExists(STYLE_TRACKS_LAYER)) {
        if (!style.styleSourceExists(TRACKS_SOURCE)) {
            style.addSource(
                vectorSource(TRACKS_SOURCE) {
                    tiles(listOf(TRACKS_TILES_URL))
                    minzoom(0)
                    maxzoom(7)
                }
            )
        }
        style.addLayer(
            lineLayer(STYLE_TRACKS_LAYER, TRACKS_SOURCE) {
                sourceLayer(TRACKS_SOURCE_LAYER)
            }
        )
    }
    style.getLayerAs<LineLayer>(STYLE_TRACKS_LAYER)?.apply {
        filter(Expression.eq(Expression.get("trackPackId"), Expression.literal(trackPackId)))
        lineColor("#0000FF")
        lineWidth(2.0)
        lineJoin(LineJoin.ROUND)
        lineCap(LineCap.ROUND)
    }
}

private fun addItineraryLayer(style: Style, tracks: List<Track>) {
    val lines = tracks.mapNotNull { track ->
        val points = track.coordinates().map { Point.fromLngLat(it.lon, it.lat) }
        if (points.size < 2) null else Feature.fromGeometry(LineString.fromLngLats(points))
    }
    if (lines.isEmpty()) return
    style.addSource(
        geoJsonSource(SELECTED_TRACKS_SOURCE) {
            featureCollection(FeatureCollection.fromFeatures(lines))
        }
    )
    style.addLayer(
        lineLayer(SELECTED_TRACKS_LAYER, SELECTED_TRACKS_SOURCE) {
            lineColor("#FF0000")
            lineWidth(4.0)
            lineJoin(LineJoin.ROUND)
            lineCap(LineCap.ROUND)
        }
    )
}

private fun addStepLayers(style: Style, steps: List<ItineraryStep>) {
    val features = steps.mapNotNull { step ->
        val lat = step.lat ?: return@mapNotNull null
        val lon = step.lon ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(lon, lat)).also {
            it.addStringProperty(STEP_ID, step.stepId.toString())
        }
    }
    if (features.isEmpty()) return
    style.addSource(
        geoJsonSource(STEPS_SOURCE) { featureCollection(FeatureCollection.fromFeatures(features)) }
    )

    fun circles(id: String, fill: String, stroke: String) =
        circleLayer(id, STEPS_SOURCE) {
            circleColor(fill)
            circleRadius(11.0)
            circleStrokeColor(stroke)
            circleStrokeWidth(2.0)
        }

    fun numbers(id: String, color: String) =
        symbolLayer(id, STEPS_SOURCE) {
            textField("{$STEP_ID}")
            textFont(listOf("Roboto Regular"))
            textSize(11.0)
            textColor(color)
            textAllowOverlap(true)
        }

    style.addLayer(circles(STEPS_CIRCLES, "#FFFFFF", "#000000"))
    style.addLayer(circles(STEPS_CIRCLES_SELECTED, "#000000", "#FFFFFF"))
    style.addLayer(numbers(STEPS_NUMBERS, "#000000"))
    style.addLayer(numbers(STEPS_NUMBERS_SELECTED, "#FFFFFF"))
    // Hide the "selected" layers until a step is chosen: filter to a step id
    // that never exists (see the note in the selection effect).
    val hideAll = Expression.eq(Expression.get(STEP_ID), Expression.literal(NO_STEP))
    style.getLayerAs<CircleLayer>(STEPS_CIRCLES_SELECTED)?.filter(hideAll)
    style.getLayerAs<SymbolLayer>(STEPS_NUMBERS_SELECTED)?.filter(hideAll)
}

private fun addWaypointLayer(context: Context, style: Style, waypoints: List<Waypoint>) {
    val features = waypoints.mapNotNull { waypoint ->
        val lat = waypoint.lat ?: return@mapNotNull null
        val lon = waypoint.lon ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(lon, lat)).also {
            it.addStringProperty(WAYPOINT_DOC_ID, waypoint.documentId)
        }
    }
    if (features.isEmpty()) return
    val marker = ContextCompat.getDrawable(
        context,
        org.maplibre.android.R.drawable.maplibre_marker_icon_default,
    )?.toBitmap() ?: return
    style.addImage(WAYPOINT_ICON, marker)
    style.addSource(
        geoJsonSource(WAYPOINTS_SOURCE) { featureCollection(FeatureCollection.fromFeatures(features)) }
    )
    style.addLayer(
        symbolLayer(WAYPOINTS_LAYER, WAYPOINTS_SOURCE) {
            iconImage(WAYPOINT_ICON)
            iconAnchor(IconAnchor.BOTTOM)
            iconAllowOverlap(true)
        }
    )
}

/**
 * Resolves a tap to the object under it — steps first (smallest targets), then
 * waypoints, then the pack's blue itinerary lines. Mapbox's
 * `queryRenderedFeatures` is asynchronous, so the fallbacks nest in callbacks.
 */
private fun installTapHandler(mapView: MapView, onTapped: (ItineraryMapTap) -> Unit) {
    val map = mapView.mapboxMap
    mapView.gestures.addOnMapClickListener { point ->
        val screen = map.pixelForCoordinate(point)
        val position = Offset(screen.x.toFloat(), screen.y.toFloat())
        fun box(radius: Double) = RenderedQueryGeometry(
            ScreenBox(
                ScreenCoordinate(screen.x - radius, screen.y - radius),
                ScreenCoordinate(screen.x + radius, screen.y + radius),
            )
        )
        map.queryRenderedFeatures(
            box(20.0),
            RenderedQueryOptions(listOf(STEPS_CIRCLES, STEPS_CIRCLES_SELECTED), null),
        ) { stepResult ->
            val stepId = stepResult.value?.firstNotNullOfOrNull {
                it.queriedFeature.feature.getStringProperty(STEP_ID)?.toIntOrNull()
            }
            if (stepId != null) {
                onTapped(ItineraryMapTap.OnStep(stepId, position))
                return@queryRenderedFeatures
            }
            map.queryRenderedFeatures(
                box(24.0),
                RenderedQueryOptions(listOf(WAYPOINTS_LAYER), null),
            ) { wpResult ->
                val wpId = wpResult.value?.firstNotNullOfOrNull {
                    it.queriedFeature.feature.getStringProperty(WAYPOINT_DOC_ID)
                }
                if (wpId != null) {
                    onTapped(ItineraryMapTap.OnWaypoint(wpId, position))
                    return@queryRenderedFeatures
                }
                map.queryRenderedFeatures(
                    box(16.0),
                    RenderedQueryOptions(listOf(STYLE_TRACKS_LAYER), null),
                ) { trackResult ->
                    val slug = trackResult.value?.firstNotNullOfOrNull {
                        it.queriedFeature.feature.getStringProperty(ITINERARY_ID)
                    }
                    if (slug != null) onTapped(ItineraryMapTap.OnItinerary(slug, position))
                }
            }
        }
        true
    }
}

private fun fitToContent(map: MapboxMap, tracks: List<Track>, steps: List<ItineraryStep>) {
    var latMin = Double.MAX_VALUE
    var latMax = -Double.MAX_VALUE
    var lonMin = Double.MAX_VALUE
    var lonMax = -Double.MAX_VALUE
    fun extend(lat: Double, lon: Double) {
        latMin = minOf(latMin, lat); latMax = maxOf(latMax, lat)
        lonMin = minOf(lonMin, lon); lonMax = maxOf(lonMax, lon)
    }
    tracks.forEach { track -> track.coordinates().forEach { extend(it.lat, it.lon) } }
    steps.forEach { step -> if (step.lat != null && step.lon != null) extend(step.lat, step.lon) }
    if (latMin > latMax) return
    val camera = map.cameraForCoordinateBounds(
        CoordinateBounds(
            Point.fromLngLat(lonMin, latMin),
            Point.fromLngLat(lonMax, latMax),
        ),
        EdgeInsets(60.0, 60.0, 60.0, 60.0),
        null,
        null,
    )
    map.setCamera(camera)
}
