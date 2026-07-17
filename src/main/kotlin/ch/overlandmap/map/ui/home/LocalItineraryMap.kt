package ch.overlandmap.map.ui.home

import android.content.Context
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.map.MapStyles
import ch.overlandmap.map.map.boundsOf
import ch.overlandmap.map.map.ensureTracksLayer
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.Waypoint
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** The itinerary's own geometry, drawn red over the style's blue tracks. */
private const val SELECTED_TRACKS_SOURCE = "itin-tracks"
private const val SELECTED_TRACKS_LAYER = "itin-tracks-red"

private const val STEPS_SOURCE = "itin-steps"
private const val STEPS_CIRCLES = "itin-steps-circles"
private const val STEPS_CIRCLES_SELECTED = "itin-steps-circles-sel"
private const val STEPS_NUMBERS = "itin-steps-numbers"
private const val STEPS_NUMBERS_SELECTED = "itin-steps-numbers-sel"
private const val STEP_ID = "stepId"

private const val WAYPOINTS_SOURCE = "itin-waypoints"
private const val WAYPOINTS_LAYER = "itin-waypoints-markers"
private const val WAYPOINT_ICON = "itin-waypoint-icon"
private const val WAYPOINT_DOC_ID = "documentId"

/** The style's own tracks layer; its tile features carry the itinerary slug. */
private const val STYLE_TRACKS_LAYER = "tracks"
private const val ITINERARY_ID = "itineraryId"

/** What a tap on the itinerary map hit, at which map-view pixel position. */
sealed interface ItineraryMapTap {
    val position: Offset

    data class OnStep(val stepId: Int, override val position: Offset) : ItineraryMapTap
    data class OnWaypoint(val documentId: String, override val position: Offset) : ItineraryMapTap
    /** A blue line: another itinerary of the pack, identified by its slug. */
    data class OnItinerary(val itinerarySlug: String, override val position: Offset) : ItineraryMapTap
}

/**
 * Map of one local itinerary. The pack's other itineraries stay blue (the
 * global style's tracks layer filtered to [trackPackId]); this itinerary is
 * drawn red and wide from its locally stored geometry, so it shows even
 * offline. Steps are numbered circles — white with a black number, inverted
 * for the selected one — and waypoints use the default marker icon.
 */
@Composable
fun LocalItineraryMap(
    trackPackId: String,
    tracks: List<Track>,
    steps: List<ItineraryStep>,
    waypoints: List<Waypoint>,
    selectedStepId: Int?,
    onTapped: (ItineraryMapTap) -> Unit,
    onMapReady: (MapLibreMap) -> Unit = {},
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
    val styleUrl = remember(styleOptions, hasMapboxToken) {
        MapStyles.resolve(context, styleOptions, hasMapboxToken)
    }
    // Fit the camera to the itinerary only on first load, not on style switches.
    var fitted by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = styleUrl,
            onMapReady = onMapReady,
            onStyleLoaded = { map, style ->
                ensureTracksLayer(style)
                style.getLayerAs<LineLayer>(STYLE_TRACKS_LAYER)?.apply {
                    setFilter(eq(get("trackPackId"), literal(trackPackId)))
                    // Unselected itineraries: thin blue, overriding the style's
                    // zoom-interpolated width. Round joins/caps to smooth corners.
                    setProperties(
                        lineColor("#0000FF"),
                        lineWidth(2f),
                        lineJoin(LINE_JOIN_ROUND),
                        lineCap(LINE_CAP_ROUND),
                    )
                }
                addItineraryLayer(style, tracks)
                addWaypointLayer(context, style, waypoints)
                addStepLayers(style, steps)
                installTapHandler(map, onTapped)
                if (!fitted) {
                    fitToContent(map, tracks, steps)
                    fitted = true
                }
                loadedStyle = style
            },
        )
        MapStyleMenu(
            options = styleOptions,
            hasMapboxToken = hasMapboxToken,
            onChange = { scope.launch { app.userPreferences.setMapStyle(it) } },
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
        )
    }

    // Swapping the paired layers' filters moves the inverted circle.
    LaunchedEffect(selectedStepId, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val selected = if (selectedStepId == null) literal(false)
        else eq(get(STEP_ID), literal(selectedStepId.toString()))
        val others = if (selectedStepId == null) literal(true)
        else neq(get(STEP_ID), literal(selectedStepId.toString()))
        style.getLayerAs<CircleLayer>(STEPS_CIRCLES)?.setFilter(others)
        style.getLayerAs<SymbolLayer>(STEPS_NUMBERS)?.setFilter(others)
        style.getLayerAs<CircleLayer>(STEPS_CIRCLES_SELECTED)?.setFilter(selected)
        style.getLayerAs<SymbolLayer>(STEPS_NUMBERS_SELECTED)?.setFilter(selected)
    }
}

private fun addItineraryLayer(style: Style, tracks: List<Track>) {
    val lines = tracks.mapNotNull { track ->
        val points = track.coordinates().map { Point.fromLngLat(it.lon, it.lat) }
        if (points.size < 2) null
        else Feature.fromGeometry(LineString.fromLngLats(points))
    }
    if (lines.isEmpty()) return
    style.addSource(GeoJsonSource(SELECTED_TRACKS_SOURCE, FeatureCollection.fromFeatures(lines)))
    style.addLayer(
        LineLayer(SELECTED_TRACKS_LAYER, SELECTED_TRACKS_SOURCE).withProperties(
            lineColor("#FF0000"),
            lineWidth(4f),
            lineJoin(LINE_JOIN_ROUND),
            lineCap(LINE_CAP_ROUND),
        )
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
    style.addSource(GeoJsonSource(STEPS_SOURCE, FeatureCollection.fromFeatures(features)))

    fun circles(id: String, fill: String, stroke: String): Layer =
        CircleLayer(id, STEPS_SOURCE).withProperties(
            circleColor(fill),
            circleRadius(11f),
            circleStrokeColor(stroke),
            circleStrokeWidth(2f),
        )

    fun numbers(id: String, color: String): Layer =
        SymbolLayer(id, STEPS_SOURCE).withProperties(
            textField("{$STEP_ID}"),
            // The spec's default fonts aren't on the style's glyph server; a
            // 404ed glyph fetch leaves the whole source's tile pending, which
            // blanks the circle layers too.
            textFont(arrayOf("Roboto Regular")),
            textSize(11f),
            textColor(color),
            textAllowOverlap(true),
        )

    style.addLayer(circles(STEPS_CIRCLES, "#FFFFFF", "#000000"))
    style.addLayer(circles(STEPS_CIRCLES_SELECTED, "#000000", "#FFFFFF"))
    style.addLayer(numbers(STEPS_NUMBERS, "#000000"))
    style.addLayer(numbers(STEPS_NUMBERS_SELECTED, "#FFFFFF"))
    style.getLayerAs<CircleLayer>(STEPS_CIRCLES_SELECTED)?.setFilter(literal(false))
    style.getLayerAs<SymbolLayer>(STEPS_NUMBERS_SELECTED)?.setFilter(literal(false))
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
    val marker = BitmapUtils.getBitmapFromDrawable(
        ContextCompat.getDrawable(
            context,
            org.maplibre.android.R.drawable.maplibre_marker_icon_default,
        )
    ) ?: return
    style.addImage(WAYPOINT_ICON, marker)
    style.addSource(GeoJsonSource(WAYPOINTS_SOURCE, FeatureCollection.fromFeatures(features)))
    style.addLayer(
        SymbolLayer(WAYPOINTS_LAYER, WAYPOINTS_SOURCE).withProperties(
            iconImage(WAYPOINT_ICON),
            iconAnchor(ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true),
        )
    )
}

/**
 * Resolves a tap to the object under it — steps first (smallest targets),
 * then waypoints, then the pack's blue itinerary lines.
 */
private fun installTapHandler(map: MapLibreMap, onTapped: (ItineraryMapTap) -> Unit) {
    fun hitBox(point: android.graphics.PointF, radius: Float) =
        RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)

    map.addOnMapClickListener { latLng ->
        val point = map.projection.toScreenLocation(latLng)
        val position = Offset(point.x, point.y)

        val stepId = map.queryRenderedFeatures(
            hitBox(point, 20f), STEPS_CIRCLES, STEPS_CIRCLES_SELECTED,
        ).firstNotNullOfOrNull { it.getStringProperty(STEP_ID)?.toIntOrNull() }
        if (stepId != null) {
            onTapped(ItineraryMapTap.OnStep(stepId, position))
            return@addOnMapClickListener true
        }

        val waypointId = map.queryRenderedFeatures(hitBox(point, 24f), WAYPOINTS_LAYER)
            .firstNotNullOfOrNull { it.getStringProperty(WAYPOINT_DOC_ID) }
        if (waypointId != null) {
            onTapped(ItineraryMapTap.OnWaypoint(waypointId, position))
            return@addOnMapClickListener true
        }

        val slug = map.queryRenderedFeatures(hitBox(point, 16f), STYLE_TRACKS_LAYER)
            .firstNotNullOfOrNull { it.getStringProperty(ITINERARY_ID) }
        if (slug != null) {
            onTapped(ItineraryMapTap.OnItinerary(slug, position))
            return@addOnMapClickListener true
        }
        false
    }
}

private fun fitToContent(map: MapLibreMap, tracks: List<Track>, steps: List<ItineraryStep>) {
    var latMin = Double.MAX_VALUE
    var latMax = -Double.MAX_VALUE
    var lonMin = Double.MAX_VALUE
    var lonMax = -Double.MAX_VALUE
    fun extend(lat: Double, lon: Double) {
        latMin = minOf(latMin, lat); latMax = maxOf(latMax, lat)
        lonMin = minOf(lonMin, lon); lonMax = maxOf(lonMax, lon)
    }
    tracks.forEach { track -> track.coordinates().forEach { extend(it.lat, it.lon) } }
    steps.forEach { step ->
        if (step.lat != null && step.lon != null) extend(step.lat, step.lon)
    }
    if (latMin > latMax) return
    map.moveCamera(
        CameraUpdateFactory.newLatLngBounds(boundsOf(latMin, latMax, lonMin, lonMax), 60)
    )
}
