package ch.overlandmap.map.ui.world

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import ch.overlandmap.map.data.routes.TripPlannerState
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.map.LocalTileServer
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.map.MapStyles
import ch.overlandmap.map.planning.RoutePoint
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
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
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

    /** Raster climate tiles, one source and layer per month: `climate-temp-01` … `climate-precip-12`. */
    const val CLIMATE_TILES = "https://pmtiles-cloudflare.overlandmap.workers.dev"
    fun climateId(kind: ClimateKind, month: Int) =
        "om-climate-${if (kind == ClimateKind.TEMPERATURE) "temp" else "precip"}-${month.toString().padStart(2, '0')}"

    /** Source layers inside the country-borders archive. */
    const val SL_COUNTRY = "country"
    const val SL_ZONES = "zones"
    const val SL_BORDER = "border"
    const val SL_POST = "border_post"

    val countryColors = listOf(0 to "#252525", 1 to "#D64545", 2 to "#E7B844", 3 to "#45A866")
    const val COUNTRY_OTHER = "#9A9A9A"

    /** Carnet requirement of a country (`-1` access forbidden, `0` none, `1` in some situations, `2` mandatory). */
    val carnetColors = listOf(-1 to "#000000", 0 to "#9CA3AF", 1 to "#EC05F8", 2 to "#0732E2")
    const val CARNET_OTHER = "#9CA3AF"
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
 * The route network (online vector tiles) and the planned trips are drawn on top of the borders,
 * driven by [routes] and [planner]; what is tapped or edited on them is reported through
 * [routeActions]. The flags of the highlighted trip are views laid over the map.
 *
 * [dataRevision] changes when the tile archive is (re)installed, which rebuilds the source so tiles
 * cached as empty while the file was missing are requested again.
 */
@Composable
fun OverlandingMap(
    selection: OverlandingSelection?,
    showBorderPosts: Boolean,
    scheme: OverlandMapScheme,
    climateKind: ClimateKind,
    climateMonth: Int,
    dataRevision: Int,
    initialCamera: OverlandingCamera,
    zoomRequest: GeoBounds?,
    onZoomRequestHandled: () -> Unit,
    pointZoomRequest: Coordinate?,
    onPointZoomHandled: () -> Unit,
    onCameraChanged: (OverlandingCamera) -> Unit,
    onSelect: (OverlandingSelection?) -> Unit,
    routes: OverlandingRoutes,
    planner: TripPlannerState,
    routeActions: OverlandingRouteActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val styleUrl = remember { MapStyles.globalStyleUrl(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapRef = remember { object { var value: MapLibreMap? = null } }
    var style by remember { mutableStateOf<Style?>(null) }
    var layersRevision by remember { mutableStateOf(-1 to -1) }
    // Read only by the flags overlay, so a camera move redraws the flags and nothing else.
    val cameraTick = remember { mutableIntStateOf(0) }

    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnCamera by rememberUpdatedState(onCameraChanged)
    val currentRoutes by rememberUpdatedState(routes)
    val currentPlanner by rememberUpdatedState(planner)
    val currentActions by rememberUpdatedState(routeActions)
    val currentShowPosts by rememberUpdatedState(showBorderPosts)
    val hasPendingFocus = zoomRequest != null || pointZoomRequest != null
    // A flag inserted by a drag from the trip line follows the finger before the trip is updated.
    var lineDragFlag by remember { mutableStateOf<Pair<String, LatLng>?>(null) }
    val lineDrag = remember {
        TripLineDrag(
            map = { mapRef.value },
            density = density,
            routes = { currentRoutes },
            planner = { currentPlanner },
            actions = { currentActions },
            onFlagMoved = { id, at -> lineDragFlag = at?.let { id to it } },
        )
    }

    Box(modifier) {
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
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
                ready.addOnCameraMoveListener { cameraTick.intValue++ }
                ready.addOnCameraIdleListener {
                    cameraTick.intValue++
                    OverlandingRouteLayers.logRenderedDots(ready)
                    val c = ready.cameraPosition
                    c.target?.let { currentOnCamera(OverlandingCamera(it.latitude, it.longitude, c.zoom)) }
                }
                ready.addOnMapClickListener { latLng ->
                    handleTap(ready, latLng, density, currentRoutes, currentPlanner, currentActions, currentOnSelect)
                    true
                }
                ready.addOnMapLongClickListener { latLng ->
                    handleLongPress(ready, latLng, density, currentRoutes, currentPlanner, currentActions)
                    true
                }
                mapRef.value = ready
                map = ready
            },
            onTouch = lineDrag::onTouch,
            onStyleLoaded = { _, loaded ->
                // A fresh style has none of our layers yet.
                layersRevision = -1 to -1
                style = loaded
            },
        )

        if (routes.highlightedTripId != null) {
            val trip = planner.trips.firstOrNull { it.id == routes.highlightedTripId }
            val points = if (trip?.isVisible == false) emptyList() else trip?.waypoints.orEmpty()
            val flags = points.mapIndexed { index, point ->
                PlanFlagItem(
                    id = point.id,
                    coordinate = lineDragFlag?.takeIf { it.first == point.id }?.second
                        ?: LatLng(point.coordinate.latitude, point.coordinate.longitude),
                    index = index,
                    color = flagColor(index, points.size),
                )
            }
            PlanFlagsOverlay(
                map = map,
                cameraTick = cameraTick,
                flags = flags,
                editable = routes.planning,
                onLongPress = { currentActions.onLongPressFlag(it.id) },
                onDragChange = { flag, translation ->
                    map?.let { m ->
                        flagDropTarget(m, flag, translation, density)?.let { currentActions.onPreviewFlag(flag.id, it) }
                    }
                },
                onDrop = { flag, translation ->
                    val m = map
                    val tap = m?.let { flagDropTarget(it, flag, translation, density) }
                    if (tap != null) currentActions.onMoveFlag(flag.id, tap) else currentActions.onCancelPreview()
                },
            )
        }
    }

    // (Re)install the layers when the style loads or the tile archive changes.
    LaunchedEffect(style, dataRevision) {
        val s = style ?: return@LaunchedEffect
        val rev = dataRevision to s.hashCode()
        if (layersRevision == rev) return@LaunchedEffect
        removeOverlandLayers(s)
        installOverlandLayers(s, currentShowPosts, currentRoutes, currentPlanner)
        updateSelectionLayer(s, selection)
        layersRevision = rev
    }
    LaunchedEffect(style, selection, layersRevision) {
        style?.let { updateSelectionLayer(it, selection) }
    }
    // The colour scheme decides which layers are shown and how the countries are filled.
    LaunchedEffect(style, scheme, showBorderPosts, climateKind, climateMonth, layersRevision) {
        style?.let { applyScheme(it, scheme, showBorderPosts, climateKind, climateMonth) }
    }
    LaunchedEffect(style, layersRevision, routes.showRoutes, routes.showTrips, routes.planning) {
        style?.let { OverlandingRouteLayers.updateVisibility(it, routes) }
    }
    LaunchedEffect(style, layersRevision, routes.selectedSegmentId) {
        style?.let { OverlandingRouteLayers.updateSelection(it, routes.selectedSegmentId) }
    }
    LaunchedEffect(style, layersRevision, routes.highlightedSegmentIds) {
        style?.let { OverlandingRouteLayers.updateLegHighlight(it, routes.highlightedSegmentIds) }
    }
    LaunchedEffect(style, layersRevision, planner.trips, planner.displays, planner.previewDisplay, routes.highlightedTripId) {
        style?.let { OverlandingRouteLayers.updateTrips(it, routes, planner) }
        // Once the map has drawn the update: report the dots it shows (diagnostic).
        kotlinx.coroutines.delay(1500)
        map?.let { OverlandingRouteLayers.logRenderedDots(it) }
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

private fun installOverlandLayers(
    style: Style,
    showBorderPosts: Boolean,
    routes: OverlandingRoutes,
    planner: TripPlannerState,
) {
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

    // Routes: above countries and borders, below the border posts.
    OverlandingRouteLayers.installRoutes(style, routes)

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

    // Trips stay above every other overlay.
    OverlandingRouteLayers.installTrips(style, routes)
    OverlandingRouteLayers.installLegHighlight(style, routes)
    OverlandingRouteLayers.updateTrips(style, routes, planner)
    installClimateLayers(style)
}

/**
 * One raster source and (hidden) layer per month for the temperature and the precipitation, under
 * the base map's water so the sea and the labels stay readable (Flutter `_addClimateSources/Layers`).
 */
private fun installClimateLayers(style: Style) {
    val below = if (style.getLayer("water") != null) "water" else OverlandLayers.COUNTRIES
    for (kind in ClimateKind.entries) {
        val path = if (kind == ClimateKind.TEMPERATURE) "temp" else "pr"
        for (month in 1..12) {
            val id = OverlandLayers.climateId(kind, month)
            val tiles = TileSet("2.1.0", "${OverlandLayers.CLIMATE_TILES}/$path/${month.toString().padStart(2, '0')}/{z}/{x}/{y}.webp")
            style.addSource(RasterSource(id, tiles, 512))
            style.addLayerBelow(
                RasterLayer(id, id).withProperties(visibility(Property.NONE), rasterOpacity(1f)),
                below,
            )
        }
    }
}

private fun removeClimateLayers(style: Style) {
    for (kind in ClimateKind.entries) for (month in 1..12) {
        val id = OverlandLayers.climateId(kind, month)
        style.removeLayer(id)
        style.removeSource(id)
    }
}

/**
 * Applies the colour scheme: overlanding fills the countries by status and shows the zones, the
 * borders and (when asked) the border posts; carnet fills them by carnet requirement with the
 * borders only; climate hides all of that and shows the raster of the chosen month.
 */
private fun applyScheme(style: Style, scheme: OverlandMapScheme, showBorderPosts: Boolean, kind: ClimateKind, month: Int) {
    fun show(visible: Boolean) = visibility(if (visible) Property.VISIBLE else Property.NONE)
    style.getLayer(OverlandLayers.COUNTRIES)?.setProperties(
        fillColor(
            if (scheme == OverlandMapScheme.CARNET) colorMatch("carnet", OverlandLayers.carnetColors, OverlandLayers.CARNET_OTHER)
            else colorMatch("overlanding", OverlandLayers.countryColors, OverlandLayers.COUNTRY_OTHER)
        ),
        fillOpacity(if (scheme == OverlandMapScheme.CARNET) 0.8f else 0.62f),
        show(scheme != OverlandMapScheme.CLIMATE),
    )
    style.getLayer(OverlandLayers.ZONES)?.setProperties(show(scheme == OverlandMapScheme.OVERLANDING))
    style.getLayer(OverlandLayers.BORDERS)?.setProperties(show(scheme != OverlandMapScheme.CLIMATE))
    style.getLayer(OverlandLayers.POSTS)?.setProperties(show(scheme == OverlandMapScheme.OVERLANDING && showBorderPosts))
    for (k in ClimateKind.entries) for (m in 1..12) {
        style.getLayer(OverlandLayers.climateId(k, m))
            ?.setProperties(show(scheme == OverlandMapScheme.CLIMATE && k == kind && m == month))
    }
}

private fun removeOverlandLayers(style: Style) {
    OverlandingRouteLayers.remove(style)
    listOf(
        OverlandLayers.SELECTION, OverlandLayers.POSTS, OverlandLayers.BORDERS,
        OverlandLayers.ZONES, OverlandLayers.COUNTRIES,
    ).forEach { style.removeLayer(it) }
    removeClimateLayers(style)
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
    // Above the border posts, below the trips.
    if (style.getLayer(RouteLayerIds.TILE_HALO) != null) style.addLayerBelow(layer, RouteLayerIds.TILE_HALO)
    else style.addLayer(layer)
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

/** What a tap in normal mode landed on, besides the trips. */
private sealed interface MapHit {
    data object None : MapHit
    data class Pick(val selection: OverlandingSelection) : MapHit
    data class Segment(val segment: RouteSegmentSelection) : MapHit
}

/**
 * What a tap landed on, after the trips (see [tripHit]). A 36 px box around the finger is queried
 * and, when several things are under it, the first of these wins: border posts, borders, route
 * segments (when shown), zones, countries. A miss clears the selection.
 */
private fun hitTest(map: MapLibreMap, latLng: LatLng, density: Float, showRoutes: Boolean): MapHit {
    if (map.style?.getLayer(OverlandLayers.COUNTRIES) == null) return MapHit.None
    val point = map.projection.toScreenLocation(latLng)
    val box = RectF(point.x - 18f, point.y - 18f, point.x + 18f, point.y + 18f)
    fun hits(layer: String) = map.queryRenderedFeatures(box, layer)

    hits(OverlandLayers.POSTS).firstNotNullOfOrNull { it.string("id", "documentId") }
        ?.let { return MapHit.Pick(OverlandingSelection.OfBorderPost(it)) }
    hits(OverlandLayers.BORDERS).firstNotNullOfOrNull { it.string("id", "documentId") }
        ?.let { return MapHit.Pick(OverlandingSelection.OfBorder(it)) }
    if (showRoutes) {
        OverlandingRouteLayers.queryRoutes(map, point, 18f * density)
            .firstNotNullOfOrNull { OverlandingRouteLayers.selection(it) }
            ?.let { return MapHit.Segment(it) }
    }
    hits(OverlandLayers.ZONES).firstNotNullOfOrNull { it.string("id", "documentId") }
        ?.let { return MapHit.Pick(OverlandingSelection.OfZone(it)) }
    hits(OverlandLayers.COUNTRIES).firstNotNullOfOrNull { it.string("ADM0_A3", "id", "documentId") }
        ?.let { return MapHit.Pick(OverlandingSelection.OfCountry(it)) }
    return MapHit.None
}

/** The trip drawn under [point], if any: its own line first, then the route tiles it highlights. Trips come before everything else. */
private fun tripHit(
    map: MapLibreMap,
    point: PointF,
    density: Float,
    actions: OverlandingRouteActions,
): String? {
    val style = map.style ?: return null
    val half = 18f * density
    val box = RectF(point.x - half, point.y - half, point.x + half, point.y + half)
    fun layers(vararg ids: String) = ids.filter { style.getLayer(it) != null }.toTypedArray()

    val own = layers(RouteLayerIds.TRIP_LINE, RouteLayerIds.TRIP_POINTS)
    if (own.isNotEmpty()) {
        map.queryRenderedFeatures(box, *own).firstNotNullOfOrNull { it.getStringProperty("id") }
            ?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    val tiles = layers(RouteLayerIds.TILE_LINE, RouteLayerIds.TILE_POINTS)
    if (tiles.isNotEmpty()) {
        map.queryRenderedFeatures(box, *tiles).firstNotNullOfOrNull { it.getStringProperty("id") }
            ?.let { segmentId -> actions.tripForSegment(segmentId) }
            ?.let { return it }
    }
    return null
}

/** A tap. In planning mode it places a flag; otherwise a trip wins over what lies under it. */
private fun handleTap(
    map: MapLibreMap,
    latLng: LatLng,
    density: Float,
    routes: OverlandingRoutes,
    planner: TripPlannerState,
    actions: OverlandingRouteActions,
    onSelect: (OverlandingSelection?) -> Unit,
) {
    // A tap while the keyboard is up only dismisses it.
    if (actions.onMapTap()) return
    val point = map.projection.toScreenLocation(latLng)
    if (routes.planning) {
        planTapAt(map, latLng, point, density, planner, actions)
        return
    }
    if (routes.showTrips && planner.trips.any { it.isVisible && it.segmentIds.isNotEmpty() }) {
        tripHit(map, point, density, actions)?.let { tripId ->
            onSelect(null)
            actions.onSelectTrip(tripId)
            return
        }
    }
    when (val hit = hitTest(map, latLng, density, routes.showRoutes)) {
        is MapHit.Segment -> {
            onSelect(null)
            actions.onSelectSegment(hit.segment)
        }
        is MapHit.Pick -> onSelect(hit.selection)
        MapHit.None -> onSelect(null)
    }
}

/**
 * A long press. In planning mode it acts as a tap; otherwise on a route it starts a new trip with
 * its first flag there.
 */
private fun handleLongPress(
    map: MapLibreMap,
    latLng: LatLng,
    density: Float,
    routes: OverlandingRoutes,
    planner: TripPlannerState,
    actions: OverlandingRouteActions,
) {
    if (!routes.planning && !routes.showRoutes) return
    val point = map.projection.toScreenLocation(latLng)
    if (routes.planning) {
        planTapAt(map, latLng, point, density, planner, actions)
        return
    }
    val id = OverlandingRouteLayers.nearestSegment(map, point, density) ?: return
    actions.onStartTrip(PlanTap(id, RoutePoint(latLng.longitude, latLng.latitude)))
}

/** A tap or long press in planning mode: on the trip's line it inserts a flag, elsewhere it appends one. */
private fun planTapAt(
    map: MapLibreMap,
    latLng: LatLng,
    point: PointF,
    density: Float,
    planner: TripPlannerState,
    actions: OverlandingRouteActions,
) {
    val onTrip = planner.activeTrip?.let { OverlandingRouteLayers.tripSegmentAt(map, point, density, it) }
    val id = onTrip ?: OverlandingRouteLayers.nearestSegment(map, point, density) ?: return
    actions.onPlanTap(PlanTap(id, RoutePoint(latLng.longitude, latLng.latitude), onTrip = onTrip != null))
}

/** The segment under a dragged flag and the place it would be dropped at. */
private fun flagDropTarget(map: MapLibreMap, flag: PlanFlagItem, translation: Offset, density: Float): PlanTap? {
    val start = map.projection.toScreenLocation(flag.coordinate)
    val end = PointF(start.x + translation.x, start.y + translation.y)
    val dropped = map.projection.fromScreenLocation(end)
    val id = OverlandingRouteLayers.nearestSegment(map, end, density) ?: return null
    return PlanTap(id, RoutePoint(dropped.longitude, dropped.latitude))
}
