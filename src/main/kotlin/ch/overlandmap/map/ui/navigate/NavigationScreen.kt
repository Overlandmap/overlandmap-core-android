package ch.overlandmap.map.ui.navigate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.map.MapStyles
import ch.overlandmap.map.map.MapboxMapView
import ch.overlandmap.map.navigation.AutoCenterZone
import ch.overlandmap.map.navigation.NavigationLayout
import ch.overlandmap.map.navigation.NavigationMode
import ch.overlandmap.map.navigation.beginNavigation
import ch.overlandmap.map.navigation.coordinate
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.tracking.rememberWithLocationPermission
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.layers.properties.generated.IconPitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.ProjectionName
import com.mapbox.maps.extension.style.projection.generated.projection
import com.mapbox.maps.extension.style.projection.generated.setProjection
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.animation.flyTo
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.OnRotateListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.gestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OTHER_TRACKS_SOURCE = "nav-other-tracks"
private const val OTHER_TRACKS_LAYER = "nav-other-tracks-line"
private const val TRACKS_SOURCE = "nav-tracks"
private const val TRACKS_LAYER = "nav-tracks-line"
private const val ROAD_SOURCE = "nav-road"
private const val ROAD_LAYER = "nav-road-line"
private const val GUIDE_SOURCE = "nav-guide"
private const val GUIDE_LINE_LAYER = "nav-guide-line"
private const val GUIDE_DOT_LAYER = "nav-guide-dot"
private const val STEPS_SOURCE = "nav-steps"
private const val STEPS_CIRCLES = "nav-steps-circles"
private const val STEPS_TARGET_CIRCLES = "nav-steps-target"
private const val STEPS_NUMBERS = "nav-steps-numbers"
private const val STEPS_TARGET_NUMBERS = "nav-steps-target-numbers"
private const val USER_SOURCE = "nav-user"
private const val USER_LAYER = "nav-user-dot"
private const val USER_ARROW_IMAGE = "nav-user-arrow"
private const val USER_DISC_IMAGE = "nav-user-disc"
private const val NUMBER = "number"
private const val NO_STEP = "__none__"

/** The itinerary's own tracks are purple; the pack's other tracks blue and thin. */
private const val ITINERARY_TRACK_COLOR = "#AF52DE"
private const val OTHER_TRACK_COLOR = "#0000FF"
private const val ROAD_ROUTE_COLOR = "#007AFF"

/** The zoom of the navigation: where the camera flies to the user, and what the +/- change. */
private const val DEFAULT_NAV_ZOOM = 15.0
private const val MIN_NAV_ZOOM = 2.0
private const val MAX_NAV_ZOOM = 20.0
private const val TARGET_ZOOM = 14.0
private const val FLY_DURATION_MS = 800L
private const val PAN_DURATION_MS = 600L

/** Debug aid: draws a line from the user to the point the needle aims at, with a red dot on it. */
private const val SHOWS_NEEDLE_GUIDE = false

private val TOP_BAR_HEIGHT = 56.dp

/**
 * The navigation tab (iOS `NavigationScreen`): the map fills the whole screen, a HUD covers half
 * of it (the top half in portrait, the left half in landscape) without taking touches, and the
 * user is kept at the centre of the other half. It also resumes a navigation saved before the app
 * was closed, and leaves the tab ([onStopped]) when there is nothing to navigate any more.
 */
@Composable
fun NavigationScreen(onStopped: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val coordinator = app.navigationCoordinator
    val state by coordinator.state.collectAsState()
    val saved by app.savedNavigationStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    val language = currentLanguage()
    val withPermission = rememberWithLocationPermission()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // ── Resume, load ────────────────────────────────────────────────────────

    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val resume = saved
        if (!coordinator.isNavigating && resume != null) {
            restoring = true
            withPermission {
                scope.launch {
                    val started = app.beginNavigation(resume.itineraryId, resume.isReverse, language, resume.targetIndex)
                    restoring = false
                    if (!started) {
                        // The itinerary is gone (pack deleted or updated, database reset): the saved state can
                        // never be restored, and left in place it would keep an empty Nav tab alive.
                        app.savedNavigationStore.clear()
                        onStopped()
                    }
                }
            }
        }
    }

    val itineraryId = state.itineraryId ?: saved?.itineraryId
    val content by produceState<NavigationMapContent?>(null, itineraryId) {
        value = null
        value = itineraryId?.let { app.loadNavigationContent(it) }
    }
    val isLoading = restoring || (itineraryId != null && content == null)
    val title = state.itineraryTitle?.takeIf { it.isNotEmpty() }
        ?: content?.itinerary?.name(language)
        ?: stringResource(R.string.nav)

    // ── Layout ──────────────────────────────────────────────────────────────

    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val safeTop = safe.calculateTopPadding()
    val safeLeft = safe.calculateLeftPadding(layoutDirection)
    val safeRight = safe.calculateRightPadding(layoutDirection)
    val mapWidthDp = with(density) { mapSize.width.toDp() }.value.toDouble()
    val mapHeightDp = with(density) { mapSize.height.toDp() }.value.toDouble()
    // The part of the screen below the top bar, inside the safe area, is what the HUD is laid out in.
    val regionTop = safeTop + TOP_BAR_HEIGHT
    val regionWidth = mapWidthDp - safeLeft.value - safeRight.value
    val regionHeight = mapHeightDp - regionTop.value
    val landscape = NavigationLayout.isLandscape(regionWidth, regionHeight)
    val mapW = mapSize.width.toDouble()
    val mapH = mapSize.height.toDouble()
    val hudEdge = NavigationLayout.HUD_EDGE_PADDING.dp
    val hudTop = NavigationLayout.HUD_TOP_PADDING.dp
    val portraitHudHeight = NavigationLayout.hudHeight(regionHeight).dp

    // ── Camera ──────────────────────────────────────────────────────────────

    var autoCenter by remember { mutableStateOf(true) }
    var navZoom by remember { mutableDoubleStateOf(DEFAULT_NAV_ZOOM) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var cameraBearing by remember { mutableDoubleStateOf(0.0) }
    var hasFitted by remember { mutableStateOf(false) }
    val clock = remember { LongArray(1) } // when the camera last re-centred

    // Where the HUD ends, in map pixels: the visible area starts there, however big the HUD is.
    val hudBottomPx = with(density) { (regionTop + hudTop + portraitHudHeight).toPx() }.toDouble()
    val hudRightPx = with(density) { (safeLeft + hudEdge + NavigationLayout.landscapeHudWidth(regionWidth).dp).toPx() }.toDouble()
    fun visibleArea() = AutoCenterZone.visibleRect(mapW, mapH, landscape, hudBottomPx, hudRightPx)

    fun viewportPadding(): EdgeInsets {
        val visible = visibleArea()
        return EdgeInsets(visible.top, visible.left, 0.0, 0.0)
    }

    /** Flies so that [coordinate] is at the centre of the visible half (the padding makes it so). */
    fun flyTo(coordinate: Coordinate, zoom: Double) {
        val view = mapView ?: return
        view.camera.flyTo(
            CameraOptions.Builder()
                .center(Point.fromLngLat(coordinate.longitude, coordinate.latitude))
                .zoom(zoom)
                .padding(viewportPadding())
                .build(),
            MapAnimationOptions.Builder().duration(FLY_DURATION_MS).build(),
        )
    }

    /** Pans (an ease, not a fly: a fly curve zooms out and back in) so the user is at the centre of the visible half. */
    fun centerOnUser(user: Coordinate) {
        val view = mapView ?: return
        clock[0] = System.currentTimeMillis()
        view.camera.easeTo(
            CameraOptions.Builder()
                .center(Point.fromLngLat(user.longitude, user.latitude))
                .zoom(navZoom)
                .padding(viewportPadding())
                .build(),
            MapAnimationOptions.Builder().duration(PAN_DURATION_MS).build(),
        )
    }

    /** Whether the user is drawn outside the stable area; unknown (map not ready) counts as no. */
    fun userNeedsRecentering(user: Coordinate): Boolean {
        val view = mapView ?: return false
        if (loadedStyle == null || mapW <= 0 || mapH <= 0) return false
        // The previous re-centre is still animating: the user isn't at rest yet.
        if (System.currentTimeMillis() - clock[0] < AutoCenterZone.RECENTER_COOLDOWN_MS) return false
        val p = view.mapboxMap.pixelForCoordinate(Point.fromLngLat(user.longitude, user.latitude))
        return AutoCenterZone.needsRecentering(p.x, p.y, mapW, mapH, landscape, hudBottomPx, hudRightPx)
    }

    // A new navigation while the screen is up (tab kept alive, or a restart right after a stop).
    LaunchedEffect(state.itineraryId) {
        if (state.itineraryId != null) {
            autoCenter = true
            hasFitted = false
        }
    }

    // The viewport padding follows the layout; turning the device brings the user back to the middle straight away.
    LaunchedEffect(mapView, loadedStyle, mapSize, landscape, hudBottomPx, hudRightPx) {
        val view = mapView ?: return@LaunchedEffect
        if (loadedStyle == null || mapW <= 0) return@LaunchedEffect
        view.mapboxMap.setCamera(CameraOptions.Builder().padding(viewportPadding()).build())
        val here = state.currentLocation
        if (autoCenter && here != null) centerOnUser(here.coordinate)
    }

    // No fix yet: show the whole itinerary in the visible half.
    LaunchedEffect(mapView, loadedStyle, content, mapSize) {
        val view = mapView ?: return@LaunchedEffect
        val bounds = content?.bounds ?: return@LaunchedEffect
        if (loadedStyle == null || mapW <= 0 || state.currentLocation != null) return@LaunchedEffect
        val visible = visibleArea()
        val camera = view.mapboxMap.cameraForCoordinateBounds(
            CoordinateBounds(Point.fromLngLat(bounds.lonMin, bounds.latMin), Point.fromLngLat(bounds.lonMax, bounds.latMax)),
            EdgeInsets(visible.top + 40.0, visible.left + 40.0, 40.0, 40.0),
            null, null,
        )
        view.mapboxMap.setCamera(camera)
    }

    // On each fix: the first one puts the user in place; after that the camera stays still until they leave the stable area.
    LaunchedEffect(state.currentLocation, mapView, loadedStyle, autoCenter) {
        val here = state.currentLocation ?: return@LaunchedEffect
        if (!autoCenter || mapView == null || loadedStyle == null || mapW <= 0) return@LaunchedEffect
        val user = here.coordinate
        if (!hasFitted) {
            hasFitted = true
            centerOnUser(user)
        } else if (userNeedsRecentering(user)) {
            centerOnUser(user)
        }
    }

    // ── Map content ─────────────────────────────────────────────────────────

    LaunchedEffect(loadedStyle, content) {
        val style = loadedStyle ?: return@LaunchedEffect
        fun lines(tracks: List<List<Coordinate>>) = FeatureCollection.fromFeatures(
            tracks.map { Feature.fromGeometry(LineString.fromLngLats(it.map { c -> Point.fromLngLat(c.longitude, c.latitude) })) },
        )
        style.getSourceAs<GeoJsonSource>(OTHER_TRACKS_SOURCE)?.featureCollection(lines(content?.otherTracks.orEmpty()))
        style.getSourceAs<GeoJsonSource>(TRACKS_SOURCE)?.featureCollection(lines(content?.itineraryTracks.orEmpty()))
        style.getSourceAs<GeoJsonSource>(STEPS_SOURCE)?.featureCollection(
            FeatureCollection.fromFeatures(
                content?.markers.orEmpty().map { m ->
                    Feature.fromGeometry(Point.fromLngLat(m.coordinate.longitude, m.coordinate.latitude))
                        .also { it.addStringProperty(NUMBER, m.number.toString()) }
                },
            ),
        )
    }
    val targetNumber = state.currentTargetIndex + 1
    LaunchedEffect(loadedStyle, targetNumber, state.isNavigating) {
        val style = loadedStyle ?: return@LaunchedEffect
        val id = if (state.isNavigating) targetNumber.toString() else NO_STEP
        val target = Expression.eq(Expression.get(NUMBER), Expression.literal(id))
        val others = Expression.neq(Expression.get(NUMBER), Expression.literal(id))
        style.getLayerAs<CircleLayer>(STEPS_CIRCLES)?.filter(others)
        style.getLayerAs<SymbolLayer>(STEPS_NUMBERS)?.filter(others)
        style.getLayerAs<CircleLayer>(STEPS_TARGET_CIRCLES)?.filter(target)
        style.getLayerAs<SymbolLayer>(STEPS_TARGET_NUMBERS)?.filter(target)
    }
    LaunchedEffect(loadedStyle, state.roadRoute) {
        val style = loadedStyle ?: return@LaunchedEffect
        val line = state.roadRoute?.coordinates?.takeIf { it.size > 1 }?.let { coords ->
            Feature.fromGeometry(LineString.fromLngLats(coords.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        style.getSourceAs<GeoJsonSource>(ROAD_SOURCE)?.featureCollection(FeatureCollection.fromFeatures(listOfNotNull(line)))
    }

    // The user dot: nothing before the first fix; the arrow shows the same heading as the dial's ring.
    val deviceHeading = state.deviceHeading(cameraBearing)
    LaunchedEffect(loadedStyle, state.currentLocation, deviceHeading, cameraBearing) {
        val style = loadedStyle ?: return@LaunchedEffect
        val here = state.currentLocation
        val features = if (here == null) emptyList() else listOf(
            Feature.fromGeometry(Point.fromLngLat(here.longitude, here.latitude)).also {
                it.addStringProperty("icon", if (deviceHeading != null) USER_ARROW_IMAGE else USER_DISC_IMAGE)
                it.addNumberProperty("rotation", (((deviceHeading ?: 0.0) - cameraBearing) % 360 + 360) % 360)
            },
        )
        style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.featureCollection(FeatureCollection.fromFeatures(features))
    }

    if (SHOWS_NEEDLE_GUIDE) {
        LaunchedEffect(loadedStyle, state.currentLocation, state.needleTargetPoint, state.mode) {
            val style = loadedStyle ?: return@LaunchedEffect
            val here = state.currentLocation
            val to = state.needleTargetPoint
            val features = if (state.mode == NavigationMode.ITINERARY && here != null && to != null) {
                listOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(listOf(Point.fromLngLat(here.longitude, here.latitude), Point.fromLngLat(to.longitude, to.latitude))),
                    ),
                    Feature.fromGeometry(Point.fromLngLat(to.longitude, to.latitude)),
                )
            } else emptyList()
            style.getSourceAs<GeoJsonSource>(GUIDE_SOURCE)?.featureCollection(FeatureCollection.fromFeatures(features))
        }
    }

    // Navigation ended elsewhere (the notification, the final step): leave the tab.
    LaunchedEffect(state.isNavigating, saved) {
        if (!state.isNavigating && saved == null && !restoring) {
            delay(300)
            if (!coordinator.isNavigating && app.savedNavigationStore.state.value == null) onStopped()
        }
    }

    // ── Style ───────────────────────────────────────────────────────────────

    val styleOptions by app.userPreferences.mapStyle.collectAsState(initial = app.userPreferences.mapStyleNow())
    val mapLanguage by app.userPreferences.mapLanguage.collectAsState(initial = app.userPreferences.mapLanguageNow())
    val hasMapboxToken = app.mapboxTokenManager.cachedTokenNow() != null
    val styleUrl = remember(styleOptions, hasMapboxToken, mapLanguage) {
        MapStyles.resolve(context, styleOptions, hasMapboxToken, mapLanguage)
    }
    val density1 = density.density
    val userArrow = remember(density1) { userDotBitmap(density1, withArrow = true) }
    val userDisc = remember(density1) { userDotBitmap(density1, withArrow = false) }

    fun zoomBy(delta: Double) {
        navZoom = (navZoom + delta).coerceIn(MIN_NAV_ZOOM, MAX_NAV_ZOOM)
        val here = state.currentLocation
        if (autoCenter && here != null) {
            centerOnUser(here.coordinate)
        } else {
            mapView?.camera?.easeTo(
                CameraOptions.Builder().zoom(((mapView?.mapboxMap?.cameraState?.zoom ?: navZoom) + delta).coerceIn(MIN_NAV_ZOOM, MAX_NAV_ZOOM)).build(),
                MapAnimationOptions.Builder().duration(250L).build(),
            )
        }
    }

    val targetCoordinate: Coordinate? = state.currentTargetStep?.coordinate() ?: state.currentRoadStep?.maneuver?.location

    // ── Layout ──────────────────────────────────────────────────────────────

    Box(Modifier.fillMaxSize().onSizeChanged { mapSize = it }) {
        MapboxMapView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = styleUrl,
            showZoomControls = false,
            onMapReady = { view ->
                mapView = view
                // No tilt; rotating is allowed and shows in the user arrow.
                view.gestures.pitchEnabled = false
                // Any camera-moving gesture (not a single tap) stops the following; programmatic moves don't fire these.
                view.gestures.addOnMoveListener(object : OnMoveListener {
                    override fun onMoveBegin(detector: MoveGestureDetector) { autoCenter = false }
                    override fun onMove(detector: MoveGestureDetector): Boolean = false
                    override fun onMoveEnd(detector: MoveGestureDetector) {}
                })
                view.gestures.addOnScaleListener(object : OnScaleListener {
                    override fun onScaleBegin(detector: StandardScaleGestureDetector) { autoCenter = false }
                    override fun onScale(detector: StandardScaleGestureDetector) {}
                    override fun onScaleEnd(detector: StandardScaleGestureDetector) {}
                })
                view.gestures.addOnRotateListener(object : OnRotateListener {
                    override fun onRotateBegin(detector: RotateGestureDetector) { autoCenter = false }
                    override fun onRotate(detector: RotateGestureDetector) {}
                    override fun onRotateEnd(detector: RotateGestureDetector) {}
                })
                view.mapboxMap.subscribeCameraChanged {
                    val bearing = view.mapboxMap.cameraState.bearing
                    if (kotlin.math.abs(bearing - cameraBearing) >= 0.5) cameraBearing = bearing
                }
            },
            onStyleLoaded = { _, style ->
                // Flat Mercator: the user dot and markers must not drift from their position.
                style.setProjection(projection(ProjectionName.MERCATOR))
                style.addImage(USER_ARROW_IMAGE, userArrow)
                style.addImage(USER_DISC_IMAGE, userDisc)
                addNavigationLayers(style, density1)
                loadedStyle = style
            },
        )

        // The top bar: stop, the itinerary's name, zoom to the target, previous and next step.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(top = safeTop, start = safeLeft, end = safeRight)
                .height(TOP_BAR_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                coordinator.stopNavigation("stop button")
                app.savedNavigationStore.clear()
                onStopped()
            }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop_navigation), tint = Color(0xFFD32F2F))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { targetCoordinate?.let { flyTo(it, TARGET_ZOOM) } }, enabled = targetCoordinate != null) {
                Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.show_on_map))
            }
            IconButton(onClick = coordinator::selectPreviousStep, enabled = state.canSelectPreviousStep) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_step))
            }
            IconButton(onClick = coordinator::selectNextStep, enabled = state.canSelectNextStep) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_step))
            }
        }

        // The HUD: the top of the screen in portrait, the left half in landscape.
        if (mapSize != IntSize.Zero && state.isNavigating) {
            NavigationOverlay(
                state = state,
                cameraBearing = cameraBearing,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = safeLeft + hudEdge, top = regionTop + hudTop)
                    .then(
                        if (landscape) {
                            Modifier
                                .width(NavigationLayout.landscapeHudWidth(regionWidth).dp)
                                .height((regionHeight - 2 * NavigationLayout.HUD_TOP_PADDING).dp)
                        } else {
                            Modifier.width((regionWidth - 2 * NavigationLayout.HUD_EDGE_PADDING).dp).height(portraitHudHeight)
                        },
                    ),
            )
        }

        // "Reverse direction": offered while moving away from the target.
        AnimatedVisibility(
            visible = state.mode == NavigationMode.ITINERARY && state.isMovingAwayFromTarget,
            enter = scaleIn(tween(250), initialScale = 0.9f) + fadeIn(tween(250)),
            exit = scaleOut(tween(250), targetScale = 0.9f) + fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .then(
                    if (landscape) Modifier.padding(start = safeLeft + (regionWidth / 2).dp, top = regionTop + hudTop + 4.dp).width((regionWidth / 2).dp)
                    else Modifier.padding(top = regionTop + hudTop + portraitHudHeight + 12.dp).fillMaxWidth(),
                ),
        ) {
            Box(contentAlignment = Alignment.TopCenter) { ReverseButton(onClick = coordinator::reverseDirection) }
        }

        // The floating controls: zoom, and auto-centre.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = safeRight + 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
            ) {
                IconButton(onClick = { zoomBy(1.0) }, modifier = Modifier.width(44.dp).height(42.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zoom_in), tint = Color.White)
                }
                HorizontalDivider(Modifier.width(32.dp).align(Alignment.CenterHorizontally), color = Color.White.copy(alpha = 0.3f))
                IconButton(onClick = { zoomBy(-1.0) }, modifier = Modifier.width(44.dp).height(42.dp)) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.zoom_out), tint = Color.White)
                }
            }
            val blue = Color(0xFF2979FF)
            Box(
                Modifier
                    .shadow(6.dp, CircleShape)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                    .border(1.5.dp, if (autoCenter) blue.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.25f), CircleShape)
                    .clip(CircleShape)
                    .clickable {
                        autoCenter = true
                        mapView?.mapboxMap?.cameraState?.zoom?.let { navZoom = it.coerceIn(MIN_NAV_ZOOM, MAX_NAV_ZOOM) }
                        state.currentLocation?.let { centerOnUser(it.coordinate) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (autoCenter) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                    contentDescription = stringResource(if (autoCenter) R.string.auto_centering else R.string.recenter_map),
                    tint = if (autoCenter) blue else Color.White,
                )
            }
        }

        // "Step reached": centred on the screen in portrait, on the map half in landscape.
        val reached = state.recentlyReachedStepName
        var lastReached by remember { mutableStateOf("") }
        LaunchedEffect(reached) { if (reached != null) lastReached = reached }
        AnimatedVisibility(
            visible = reached != null,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.85f) + fadeIn(),
            exit = scaleOut(targetScale = 0.85f) + fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .then(if (landscape) Modifier.padding(start = safeLeft + (regionWidth / 2).dp).fillMaxWidth() else Modifier),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ReachedBanner(reached ?: lastReached) }
        }

        // Loading the itinerary, or computing the road route.
        if (isLoading || state.isCalculatingRoadRoute) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                Text(stringResource(R.string.calculating_route), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReverseButton(onClick: () -> Unit) {
    Row(
        Modifier
            .shadow(6.dp, CircleShape)
            .background(Color(0xFFFF9500), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.UTurnLeft, contentDescription = null, tint = Color.White)
        Text(stringResource(R.string.reverse_navigation), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReachedBanner(name: String) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .widthIn(max = 320.dp)
            .shadow(16.dp, shape)
            .background(Color.Black.copy(alpha = 0.88f), shape)
            .border(2.dp, Color(0xFF34C759).copy(alpha = 0.8f), shape)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(48.dp))
        Text(
            name,
            color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * The layers, bottom to top: the pack's other tracks, the itinerary's, the road route, the step
 * markers (numbers never hidden by collisions), the debug needle guide, and last the user dot, so
 * nothing ever covers it.
 */
private fun addNavigationLayers(style: Style, density: Float) {
    fun empty() = FeatureCollection.fromFeatures(emptyList<Feature>())
    for (source in listOf(OTHER_TRACKS_SOURCE, TRACKS_SOURCE, ROAD_SOURCE, STEPS_SOURCE, GUIDE_SOURCE, USER_SOURCE)) {
        style.addSource(geoJsonSource(source) { featureCollection(empty()) })
    }

    style.addLayer(
        lineLayer(OTHER_TRACKS_LAYER, OTHER_TRACKS_SOURCE) {
            lineColor(OTHER_TRACK_COLOR); lineWidth(2.0); lineJoin(LineJoin.ROUND); lineCap(LineCap.ROUND)
        },
    )
    style.addLayer(
        lineLayer(TRACKS_LAYER, TRACKS_SOURCE) {
            lineColor(ITINERARY_TRACK_COLOR); lineWidth(4.0); lineJoin(LineJoin.ROUND); lineCap(LineCap.ROUND)
        },
    )
    style.addLayer(
        lineLayer(ROAD_LAYER, ROAD_SOURCE) {
            lineColor(ROAD_ROUTE_COLOR); lineWidth(6.0); lineJoin(LineJoin.ROUND); lineCap(LineCap.ROUND)
        },
    )

    fun circles(id: String, fill: String, radius: Double) =
        circleLayer(id, STEPS_SOURCE) {
            circleColor(fill); circleRadius(radius); circleStrokeColor("#000000"); circleStrokeWidth(1.8)
        }

    fun numbers(id: String) =
        symbolLayer(id, STEPS_SOURCE) {
            textField("{$NUMBER}")
            textFont(listOf("Roboto Medium"))
            textSize(9.0)
            textColor("#000000")
            // Markers always show, even when they collide with each other or with the user dot.
            textAllowOverlap(true)
            textIgnorePlacement(true)
        }
    style.addLayer(circles(STEPS_CIRCLES, "#FFFFFF", 9.0))
    style.addLayer(circles(STEPS_TARGET_CIRCLES, "#FFC107", 13.0))
    style.addLayer(numbers(STEPS_NUMBERS))
    style.addLayer(numbers(STEPS_TARGET_NUMBERS))
    val none = Expression.eq(Expression.get(NUMBER), Expression.literal(NO_STEP))
    style.getLayerAs<CircleLayer>(STEPS_TARGET_CIRCLES)?.filter(none)
    style.getLayerAs<SymbolLayer>(STEPS_TARGET_NUMBERS)?.filter(none)

    if (SHOWS_NEEDLE_GUIDE) {
        style.addLayer(lineLayer(GUIDE_LINE_LAYER, GUIDE_SOURCE) { lineColor("#FF0000"); lineWidth(2.0) })
        style.addLayer(circleLayer(GUIDE_DOT_LAYER, GUIDE_SOURCE) { circleColor("#FF0000"); circleRadius(5.0) })
    }

    // The user dot is an image (disc, shadow, arrow) drawn by us rather than the SDK's puck, which
    // sits beneath marker views. Rotated about its centre; not queryable, so markers under it stay tappable.
    style.addLayer(
        symbolLayer(USER_LAYER, USER_SOURCE) {
            iconImage(Expression.get("icon"))
            iconRotate(Expression.get("rotation"))
            iconRotationAlignment(IconRotationAlignment.VIEWPORT)
            iconPitchAlignment(IconPitchAlignment.VIEWPORT)
            iconAllowOverlap(true)
            iconIgnorePlacement(true)
            // The bitmaps are drawn at the screen's density; the map treats an image pixel as a dp.
            iconSize(1.0 / density)
        },
    )
}

/**
 * The user marker: an 18 dp blue disc with a 3 dp white border and a shadow, and, when
 * [withArrow], a 16 x 14 dp blue triangle with a white 1.5 dp outline just outside it, pointing
 * up. The image is square and the disc is at its centre, so rotating it turns the arrow round the disc.
 */
private fun userDotBitmap(density: Float, withArrow: Boolean): Bitmap {
    fun dp(v: Float) = v * density
    val size = dp(64f).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val blue = 0xFF007AFF.toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // The white border (with the shadow), then the blue disc.
    paint.color = 0xFFFFFFFF.toInt()
    paint.setShadowLayer(dp(3f), 0f, dp(1.5f), 0x66000000)
    canvas.drawCircle(cx, cy, dp(12f), paint)
    paint.clearShadowLayer()
    paint.color = blue
    canvas.drawCircle(cx, cy, dp(9f), paint)

    if (withArrow) {
        val arrow = Path().apply {
            moveTo(cx, cy - dp(26f))
            lineTo(cx - dp(8f), cy - dp(12f))
            lineTo(cx + dp(8f), cy - dp(12f))
            close()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f) // half of it is covered by the fill: a 1.5 dp outline
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
        paint.color = blue
        canvas.drawPath(arrow, paint)
    }
    return bitmap
}
