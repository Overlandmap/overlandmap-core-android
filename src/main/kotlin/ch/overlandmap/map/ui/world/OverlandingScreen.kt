package ch.overlandmap.map.ui.world

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import ch.overlandmap.map.ui.rememberUnitFormatter
import ch.overlandmap.map.model.CountryCarnet
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.data.GeneralAssetState
import ch.overlandmap.map.model.ZoneType
import ch.overlandmap.map.planning.BlockedCountryReason
import ch.overlandmap.map.planning.RouteMode
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.VerticalSplit
import java.text.DateFormat
import java.util.Date

/**
 * The Overlanding tab (iOS `OverlandingScreen`): the country-borders map on top and, below it,
 * a detail pane that goes from the home screen to a country, a border and a border post while the
 * map stays visible. Free feature; works offline once its two assets are downloaded.
 *
 * The map also draws the route network and the planned trips; the toolbar's route button switches
 * to planning mode, where taps on the map place flags and the pane becomes the trip planner.
 */
@Composable
fun OverlandingScreen(
    onOpenSearch: () -> Unit,
    viewModel: OverlandingViewModel = viewModel { OverlandingViewModel(overlandApp()) },
) {
    val data by viewModel.data.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val panePath by viewModel.panePath.collectAsState()
    val showPosts by viewModel.showBorderPosts.collectAsState()
    val legendExpanded by viewModel.legendExpanded.collectAsState()
    val scheme by viewModel.scheme.collectAsState()
    val climateKind by viewModel.climateKind.collectAsState()
    val climateMonth by viewModel.climateMonth.collectAsState()
    val zoomRequest by viewModel.zoomRequest.collectAsState()
    val pointZoomRequest by viewModel.pointZoomRequest.collectAsState()
    val tilesRevision by viewModel.tilesRevision.collectAsState()
    val showRoutes by viewModel.showRoutes.collectAsState()
    val showTrips by viewModel.showTrips.collectAsState()
    val planning by viewModel.planning.collectAsState()
    val routeSelection by viewModel.routeSelection.collectAsState()
    val tripDetailId by viewModel.tripDetailId.collectAsState()
    val highlightedLeg by viewModel.highlightedLeg.collectAsState()
    val planningBoxExpanded by viewModel.planningBoxExpanded.collectAsState()
    val plannerState by viewModel.plannerState.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var flagToDelete by remember { mutableStateOf<String?>(null) }
    var confirmDeleteTrip by remember { mutableStateOf(false) }

    val viewingLeg by viewModel.viewingLeg.collectAsState()
    BackHandler(enabled = viewingLeg || planning || tripDetailId != null || routeSelection != null || panePath.isNotEmpty()) {
        when {
            viewingLeg -> viewModel.closeLeg()
            planning -> viewModel.stopPlanning()
            tripDetailId != null || routeSelection != null -> viewModel.clearRouteDetails()
            else -> viewModel.popPane()
        }
    }

    // The default name of a new trip is numbered and localized by the screen's own context.
    val tripName = rememberUpdatedState { number: Int -> context.getString(R.string.plan_default_name, number) }
    val routes = OverlandingRoutes(
        showRoutes = showRoutes,
        showTrips = showTrips,
        planning = planning,
        selectedSegmentId = routeSelection?.id,
        highlightedSegmentIds = if (planning) highlightedLeg?.segmentIds.orEmpty() else emptyList(),
        highlightedTripId = if (planning) plannerState.activeId else tripDetailId,
    )
    val routeActions = remember(viewModel) {
        OverlandingRouteActions(
            onSelectSegment = viewModel::selectSegment,
            onSelectTrip = viewModel::selectTrip,
            tripForSegment = viewModel.planner::tripIdForSegment,
            onPlanTap = { viewModel.planTap(it) { n -> tripName.value(n) } },
            onInsertFlag = { viewModel.insertFlag(it) { n -> tripName.value(n) } },
            onMapTap = {
                if (viewModel.textEditing.value) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    true
                } else {
                    false
                }
            },
            onStartTrip = { viewModel.startTrip(it) { n -> tripName.value(n) } },
            onMoveFlag = { id, tap -> viewModel.moveFlag(id, tap) { n -> tripName.value(n) } },
            onLongPressFlag = { flagToDelete = it },
            onPreviewFlag = viewModel::previewFlag,
            onCancelPreview = viewModel.planner::cancelPreview,
        )
    }

    VerticalSplit(
        splitKey = "overlanding",
        initialTopFraction = 0.55f,
        top = {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                OverlandingMap(
                    selection = selection,
                    showBorderPosts = showPosts,
                    scheme = scheme,
                    climateKind = climateKind,
                    climateMonth = climateMonth,
                    dataRevision = tilesRevision,
                    initialCamera = viewModel.initialCamera,
                    zoomRequest = zoomRequest,
                    onZoomRequestHandled = viewModel::zoomHandled,
                    pointZoomRequest = pointZoomRequest,
                    onPointZoomHandled = viewModel::pointZoomHandled,
                    onCameraChanged = viewModel::saveCamera,
                    onSelect = viewModel::select,
                    routes = routes,
                    planner = plannerState,
                    routeActions = routeActions,
                    modifier = Modifier.fillMaxSize(),
                )
                SchemeSwitch(
                    scheme = scheme,
                    onScheme = viewModel::setScheme,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
                // Below the scheme switch, and scrollable when the map pane is short.
                val legendModifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, end = 8.dp, top = LegendTop)
                    .heightIn(max = (maxHeight - LegendTop - 8.dp).coerceAtLeast(96.dp))
                if (planning) {
                    PlanningOptionsBox(
                        restrictions = plannerState.restrictions,
                        defaultKmPerDay = plannerState.defaultKmPerDay,
                        expanded = planningBoxExpanded,
                        onExpandedChange = viewModel::setPlanningBoxExpanded,
                        onChange = viewModel.planner::setRestrictions,
                        onDefaultKmPerDayChange = viewModel.planner::setDefaultKmPerDay,
                        onTextEditingChange = viewModel::setTextEditing,
                        modifier = legendModifier,
                    )
                } else {
                    OverlandingLegend(
                        expanded = legendExpanded,
                        onExpandedChange = viewModel::setLegendExpanded,
                        scheme = scheme,
                        showBorderPosts = showPosts,
                        onShowBorderPosts = viewModel::setShowBorderPosts,
                        climateKind = climateKind,
                        onClimateKind = viewModel::setClimateKind,
                        climateMonth = climateMonth,
                        onClimateMonth = viewModel::setClimateMonth,
                        showRoutes = showRoutes,
                        onShowRoutes = viewModel::setShowRoutes,
                        showTrips = showTrips,
                        onShowTrips = viewModel::setShowTrips,
                        modifier = legendModifier,
                    )
                }
                MapToolbar(
                    canGoBack = panePath.isNotEmpty(),
                    backTitle = panePath.lastOrNull()?.backTitle,
                    zoomTarget = viewModel.zoomTarget(data),
                    shareUrl = viewModel.shareUrl(data),
                    planning = planning,
                    canUndo = plannerState.canUndo,
                    onBack = { viewModel.popPane() },
                    onSearch = onOpenSearch,
                    onZoom = { target ->
                        when (target) {
                            is OverlandingViewModel.ZoomTarget.Bounds -> viewModel.zoomTo(target.bounds)
                            is OverlandingViewModel.ZoomTarget.Point -> viewModel.zoomToPoint(target.point)
                        }
                    },
                    onShare = { url ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                null,
                            )
                        )
                    },
                    onUndo = viewModel.planner::undo,
                    onTogglePlanning = { if (planning) viewModel.stopPlanning() else viewModel.startPlanning() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(start = 8.dp, end = 8.dp, top = LegendTop),
                )
            }
        },
        bottom = {
            DetailPane(
                viewModel,
                onNewTrip = { viewModel.planner.newTrip(tripName.value(plannerState.trips.size + 1)) },
                onDeleteTrip = { confirmDeleteTrip = true },
            )
        },
    )

    flagToDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { flagToDelete = null },
            title = { Text(stringResource(R.string.plan_delete_waypoint)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.planner.removeWaypoint(id)
                    flagToDelete = null
                }) { Text(stringResource(R.string.plan_delete_waypoint), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { flagToDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDeleteTrip) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTrip = false },
            title = { Text(stringResource(R.string.plan_delete_trip)) },
            confirmButton = {
                TextButton(onClick = {
                    plannerState.activeId?.let(viewModel.planner::deleteTrip)
                    confirmDeleteTrip = false
                }) { Text(stringResource(R.string.plan_delete_trip), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTrip = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    plannerState.blocked?.let { reason ->
        AlertDialog(
            onDismissRequest = viewModel.planner::dismissBlocked,
            title = {
                Text(
                    stringResource(
                        when (reason) {
                            BlockedCountryReason.FORBIDDEN -> R.string.plan_blocked_forbidden
                            BlockedCountryReason.UNSAFE -> R.string.plan_blocked_unsafe
                            BlockedCountryReason.RESTRICTED -> R.string.plan_blocked_restricted
                        }
                    )
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.planner.confirmBlocked() }) { Text(stringResource(R.string.plan_allow)) } },
            dismissButton = { TextButton(onClick = viewModel.planner::dismissBlocked) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Distance from the top of the map pane to the legend, which sits under the toolbar. */
private val LegendTop get() = SCHEME_SWITCH_HEIGHT + 12.dp

@Composable
private fun MapToolbar(
    canGoBack: Boolean,
    backTitle: String?,
    zoomTarget: OverlandingViewModel.ZoomTarget?,
    shareUrl: String?,
    planning: Boolean,
    canUndo: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onZoom: (OverlandingViewModel.ZoomTarget) -> Unit,
    onShare: (String) -> Unit,
    onUndo: () -> Unit,
    onTogglePlanning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (planning) {
            ToolbarButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.plan_undo), onUndo, enabled = canUndo)
        } else {
            if (canGoBack) {
                ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, backTitle ?: stringResource(R.string.close), onBack)
            }
            ToolbarButton(Icons.Filled.Search, stringResource(R.string.search), onSearch)
        }
        ToolbarButton(
            if (planning) Icons.Filled.Close else Icons.Filled.Route,
            stringResource(if (planning) R.string.plan_done else R.string.plan_plan_trip),
            onTogglePlanning,
            tint = if (planning) Color(0xFFD32F2F) else null,
        )
        if (zoomTarget != null) {
            ToolbarButton(Icons.Filled.ZoomIn, stringResource(R.string.zoom), onClick = { onZoom(zoomTarget) })
        }
        if (shareUrl != null) {
            ToolbarButton(Icons.Filled.Share, stringResource(R.string.share_link), onClick = { onShare(shareUrl) })
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    Surface(shape = CircleShape, shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(
                icon,
                contentDescription = description,
                tint = tint ?: LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }
}

// ── Colour scheme ───────────────────────────────────────────────────────────

private val SCHEME_SWITCH_HEIGHT = 36.dp

/** The Overlanding / Carnet / Climate switch over the top of the map (Flutter `SegmentedButton<CountryColorScheme>`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemeSwitch(scheme: OverlandMapScheme, onScheme: (OverlandMapScheme) -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf(
        OverlandMapScheme.OVERLANDING to R.string.overlanding,
        OverlandMapScheme.CARNET to R.string.carnet,
        OverlandMapScheme.CLIMATE to R.string.climate,
    )
    SingleChoiceSegmentedButtonRow(modifier.height(SCHEME_SWITCH_HEIGHT)) {
        entries.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = scheme == value,
                onClick = { onScheme(value) },
                shape = SegmentedButtonDefaults.itemShape(index, entries.size, RoundedCornerShape(8.dp)),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFF2196F3),
                    activeContentColor = Color.White,
                    activeBorderColor = Color(0xFF3F51B5),
                    inactiveContainerColor = Color.White,
                    inactiveContentColor = Color.Black,
                    inactiveBorderColor = Color(0xFF3F51B5),
                ),
            ) {
                Text(stringResource(label), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// ── Legend ──────────────────────────────────────────────────────────────────

internal val LegendAccent = Color(0xFF3478C7)

/** The frame of the map's legend and planning box: a bordered card that scrolls when too tall. */
@Composable
internal fun LegendCard(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 176.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .then(if (expanded) Modifier.width(width) else Modifier)
            .border(1.dp, LegendAccent.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun OverlandingLegend(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    scheme: OverlandMapScheme,
    showBorderPosts: Boolean,
    onShowBorderPosts: (Boolean) -> Unit,
    climateKind: ClimateKind,
    onClimateKind: (ClimateKind) -> Unit,
    climateMonth: Int,
    onClimateMonth: (Int) -> Unit,
    showRoutes: Boolean,
    onShowRoutes: (Boolean) -> Unit,
    showTrips: Boolean,
    onShowTrips: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LegendCard(expanded = expanded, modifier = modifier, width = if (scheme == OverlandMapScheme.CLIMATE) 248.dp else 176.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onExpandedChange(!expanded) },
        ) {
            Text(stringResource(R.string.legend), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = LegendAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            when (scheme) {
                OverlandMapScheme.OVERLANDING -> OverlandingLegendBody(showBorderPosts, onShowBorderPosts)
                OverlandMapScheme.CARNET -> {
                    LegendSwatch(CountryCarnet.NOT_REQUIRED.label(), Color(0xFF9CA3AF))
                    LegendSwatch(CountryCarnet.REQUIRED_IN_SOME_SITUATIONS.label(), Color(0xFFEC05F8))
                    LegendSwatch(CountryCarnet.MANDATORY.label(), Color(0xFF0732E2))
                    LegendSwatch(CountryCarnet.ACCESS_FORBIDDEN.label(), Color(0xFF000000))
                }
                OverlandMapScheme.CLIMATE -> ClimateLegendBody(climateKind, onClimateKind, climateMonth, onClimateMonth)
            }
            // The routes and the trips are drawn whatever the colour scheme.
            RoutesLegendRows(showRoutes, onShowRoutes, showTrips, onShowTrips)
        }
    }
}

@Composable
private fun RoutesLegendRows(
    showRoutes: Boolean,
    onShowRoutes: (Boolean) -> Unit,
    showTrips: Boolean,
    onShowTrips: (Boolean) -> Unit,
) {
    LegendSwitch(stringResource(R.string.plan_routes), showRoutes, onShowRoutes)
    if (showRoutes) {
        RouteSample(RouteMode.LAND, stringResource(R.string.route_land))
        RouteSample(RouteMode.FERRY, stringResource(R.string.route_ferry))
        RouteSample(RouteMode.SHIPPING, stringResource(R.string.route_shipping))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 28.dp, height = 5.dp).clip(CircleShape).background(Color.Red))
        Text(stringResource(R.string.plan_trips), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = showTrips, onCheckedChange = onShowTrips, modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun OverlandingLegendBody(showBorderPosts: Boolean, onShowBorderPosts: (Boolean) -> Unit) {
    LegendSwatch(stringResource(R.string.open), Color(0xFF45A866))
    LegendSwatch(stringResource(R.string.restricted), Color(0xFFE7B844))
    LegendSwatch(stringResource(R.string.overlanding_unsafe), Color(0xFFD64545))
    LegendSwatch(stringResource(R.string.overlanding_forbidden), Color(0xFF252525))
    Text(
        stringResource(R.string.overland_borders_heading),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 2.dp),
    )
    LegendLine(stringResource(R.string.border_open), Color(0xFF45A866))
    LegendLine(stringResource(R.string.border_bilateral), Color(0xFF3478C7))
    LegendLine(stringResource(R.string.restricted), Color(0xFFE7A43D))
    LegendLine(stringResource(R.string.border_closed), Color(0xFFD64545))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.border_posts), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = showBorderPosts, onCheckedChange = onShowBorderPosts, modifier = Modifier.height(28.dp))
    }
}

private val TEMPERATURE_GRADIENT = listOf(
    Color(16, 39, 69), Color(111, 159, 205), Color(189, 222, 236), Color(243, 246, 212),
    Color(254, 227, 149), Color(250, 153, 87), Color(215, 48, 39), Color(115, 27, 21),
)
private val PRECIPITATION_GRADIENT = listOf(
    Color(255, 255, 204), Color(199, 233, 180), Color(65, 182, 196), Color(37, 52, 148),
    Color(197, 27, 125), Color(247, 247, 247), Color(128, 0, 38),
)

/** Temperature or precipitation, the month, and the colour scale of the raster on the map. */
@Composable
private fun ClimateLegendBody(
    kind: ClimateKind,
    onKind: (ClimateKind) -> Unit,
    month: Int,
    onMonth: (Int) -> Unit,
) {
    val format = rememberUnitFormatter().format
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClimateChip(stringResource(R.string.temperature), kind == ClimateKind.TEMPERATURE, Color(0xFFFF8C00)) { onKind(ClimateKind.TEMPERATURE) }
        ClimateChip(stringResource(R.string.precipitation), kind == ClimateKind.PRECIPITATION, Color(0xFF8A2BE2)) { onKind(ClimateKind.PRECIPITATION) }
    }
    val months = remember { java.text.DateFormatSymbols.getInstance(java.util.Locale.getDefault()).shortMonths }
    for (row in 0..1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 6) {
                val m = row * 6 + i + 1
                val selected = m == month
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Color(0xFF4CAF50) else Color(0xFFEEEEEE))
                        .border(1.dp, if (selected) Color(0xFF4CAF50) else Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                        .clickable { onMonth(m) }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        months[m - 1].trimEnd('.'),
                        fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        color = if (selected) Color.White else Color(0xDD000000),
                    )
                }
            }
        }
    }
    val temperature = kind == ClimateKind.TEMPERATURE
    Text(
        stringResource(if (temperature) R.string.temperature_scale else R.string.precipitation_scale),
        fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
    )
    Box(
        Modifier
            .width(120.dp).height(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(if (temperature) TEMPERATURE_GRADIENT else PRECIPITATION_GRADIENT)),
    )
    val labels = when {
        temperature && format.temperatureCelsius -> listOf("-45°C", "0°C", "45°C")
        temperature -> listOf("-50°F", "32°F", "110°F")
        format.distanceMetric -> listOf("0", if (java.util.Locale.getDefault().language == "ru") "400 мм" else "400 mm", if (java.util.Locale.getDefault().language == "ru") "2000 мм" else "2000 mm")
        else -> listOf("0", "15 in", "80 in")
    }
    Row(Modifier.width(if (temperature) 130.dp else 140.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, fontSize = 9.sp) }
    }
}

@Composable
private fun LegendSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.height(28.dp))
    }
}

/** A short sample of how a kind of route is drawn on the map. */
@Composable
private fun RouteSample(mode: RouteMode, label: String) {
    val blue = Color(0xFF2563EB)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(width = 28.dp, height = 8.dp)) {
            val y = size.height / 2
            val from = Offset(1.dp.toPx(), y)
            val to = Offset(size.width - 1.dp.toPx(), y)
            when (mode) {
                RouteMode.LAND -> {
                    drawLine(Color.Black, from, to, strokeWidth = 7.dp.toPx() * 0.7f, cap = StrokeCap.Round)
                    drawLine(Color.White, from, to, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
                RouteMode.FERRY -> drawLine(
                    blue, from, to, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Butt,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx())),
                )
                RouteMode.SHIPPING -> drawLine(
                    blue, from, to, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.6.dp.toPx(), 6.dp.toPx())),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ClimateChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.White else Color.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) color else Color(0xFFE0E0E0))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LegendLine(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 28.dp, height = 3.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Detail pane ─────────────────────────────────────────────────────────────

@Composable
private fun DetailPane(vm: OverlandingViewModel, onNewTrip: () -> Unit, onDeleteTrip: () -> Unit) {
    val data by vm.data.collectAsState()
    val selection by vm.selection.collectAsState()
    val panePath by vm.panePath.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val templates by vm.advisoryTemplates.collectAsState()
    val lang = currentLanguage()
    val unavailable = stringResource(R.string.overland_data_unavailable)
    val borderFallback = stringResource(R.string.border)

    fun tab(key: String) = tabs[key] ?: 0

    val planning by vm.planning.collectAsState()
    val viewingLeg by vm.viewingLeg.collectAsState()
    val routeSelection by vm.routeSelection.collectAsState()
    val tripDetailId by vm.tripDetailId.collectAsState()
    val highlightedLeg by vm.highlightedLeg.collectAsState()

    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        if (viewingLeg) LegBackBar(onClick = vm::closeLeg)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        val top = panePath.lastOrNull()
        when {
            planning && !viewingLeg -> TripPlannerPanel(
                vm = vm, data = data, lang = lang,
                selectedLegId = highlightedLeg?.id, onSelectLeg = vm::selectLeg,
                onNewTrip = onNewTrip,
                onDeleteTrip = onDeleteTrip,
            )
            tripDetailId != null && !viewingLeg -> key(tripDetailId) {
                TripDetail(
                    tripId = tripDetailId.orEmpty(), vm = vm, data = data, lang = lang,
                    onClose = vm::clearRouteDetails,
                    onEdit = vm::startPlanning,
                    onOpenBorderPost = vm::openBorderPostOfTrip,
                )
            }
            routeSelection != null -> key(routeSelection?.id) {
                RouteSegmentDetail(segment = routeSelection!!, data = data, lang = lang)
            }
            top is PaneDestination.Border -> {
                val border = data.border(top.borderId)
                if (border == null) UnavailableSelection(unavailable)
                else BorderDetail(
                    border, data, lang, tab("border:${border.documentId}"),
                    { vm.setTab("border:${border.documentId}", it) },
                    onOpenPost = { post ->
                        vm.openPost(post.documentId, border.documentId, data.borderTitle(border, lang, borderFallback))
                    },
                    onZoomPost = vm::zoomToPoint,
                )
            }
            top is PaneDestination.BorderPostOf -> {
                val post = data.post(top.postId)
                if (post == null) UnavailableSelection(unavailable)
                else BorderPostDetail(
                    post, data.border(top.borderId ?: post.borderId), data, lang,
                    tab("post:${post.documentId}"), { vm.setTab("post:${post.documentId}", it) },
                )
            }
            else -> when (val s = selection) {
                null -> OverlandHome(vm, data)
                is OverlandingSelection.OfCountry -> {
                    val country = data.country(s.id)
                    if (country == null) UnavailableSelection(unavailable)
                    else CountryDetail(
                        country, data, lang, templates,
                        tab("country:${country.documentId}"), { vm.setTab("country:${country.documentId}", it) },
                        onOpenBorder = { border -> vm.openBorder(border.documentId, country.name(lang)) },
                    )
                }
                is OverlandingSelection.OfBorder -> {
                    val border = data.border(s.id)
                    if (border == null) UnavailableSelection(unavailable)
                    else BorderDetail(
                        border, data, lang, tab("border:${border.documentId}"),
                        { vm.setTab("border:${border.documentId}", it) },
                        onOpenPost = { post ->
                            vm.openPost(post.documentId, border.documentId, data.borderTitle(border, lang, borderFallback))
                        },
                        onZoomPost = vm::zoomToPoint,
                    )
                }
                is OverlandingSelection.OfBorderPost -> {
                    val post = data.post(s.id)
                    if (post == null) UnavailableSelection(unavailable)
                    else BorderPostDetail(
                        post, data.border(post.borderId), data, lang,
                        tab("post:${post.documentId}"), { vm.setTab("post:${post.documentId}", it) },
                    )
                }
                is OverlandingSelection.OfZone -> {
                    val zone = data.zone(s.id)
                    if (zone == null) UnavailableSelection(unavailable)
                    else ZoneDetail(zone, data.country(zone.country), lang)
                }
            }
        }
        }
      }
    }
}

/** The way back from a leg's pane to the planner or the trip. */
@Composable
private fun LegBackBar(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = LegendAccent)
        Text(stringResource(R.string.plan_back_to_trip), color = LegendAccent, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}

// ── Home ────────────────────────────────────────────────────────────────────

@Composable
private fun OverlandHome(vm: OverlandingViewModel, data: OverlandData) {
    val searching by vm.searching.collectAsState()
    val query by vm.query.collectAsState()
    val datasetState by vm.datasetState.collectAsState()
    val version by vm.datasetVersion.collectAsState()
    var showDisclaimer by remember { mutableStateOf(false) }
    val lang = currentLanguage()

    val zoneLabels = mapOf(
        ZoneType.CLOSED to stringResource(R.string.zone_closed),
        ZoneType.GUIDE_ESCORT_NEEDED to stringResource(R.string.zone_guide_escort),
        ZoneType.PERMIT_NEEDED to stringResource(R.string.zone_permit),
        ZoneType.SPECIAL to stringResource(R.string.zone_special),
        ZoneType.UNKNOWN to stringResource(R.string.unknown),
    )
    val postFallback = stringResource(R.string.search_kind_border_post)

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (searching) {
                TextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { vm.setSearching(false) }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    stringResource(R.string.overland_db),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.setSearching(true) }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
            }
        }

        if (searching) {
            val results = remember(query, data, lang) {
                OverlandSearch.search(query, data, lang, { zoneLabels[it.zoneType].orEmpty() }, postFallback)
            }
            SearchResults(results, query) { result ->
                vm.select(result.selection)
                vm.setSearching(false)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (data.countries.isEmpty()) DatasetStatus(datasetState, onRetry = { vm.retryDownload() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    version?.let { v ->
                        val date = v.dateMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                        Text(if (date != null) "v.${v.version} ($date)" else "v.${v.version}")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDisclaimer = true }) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFFF9500))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.disclaimer), color = Color(0xFFFF9500))
                    }
                }
                ContinentProgress()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = Color(0xFF3478C7), modifier = Modifier.size(32.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.getting_started), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.start_by_clicking_map), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer)) },
            text = { Text(stringResource(R.string.database_disclaimer)) },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun DatasetStatus(state: GeneralAssetState, onRetry: () -> Unit) {
    when (state) {
        is GeneralAssetState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.overland_data_downloading))
            val fraction = state.fraction
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is GeneralAssetState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.overland_data_downloading))
        }
    }
}

@Composable
private fun ContinentProgress() {
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9500)
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ContinentRow(stringResource(R.string.asia), "✔", green, stringResource(R.string.complete))
            ContinentRow(stringResource(R.string.africa), "⏳", orange, stringResource(R.string.coming_soon))
            ContinentRow(stringResource(R.string.americas), "⏳", orange, stringResource(R.string.coming_soon))
            ContinentRow(stringResource(R.string.europe), "✔", green, stringResource(R.string.not_planned))
        }
    }
}

@Composable
private fun ContinentRow(name: String, icon: String, color: Color, result: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, modifier = Modifier.widthIn(min = 105.dp))
        Text(icon, color = color, modifier = Modifier.width(24.dp))
        Spacer(Modifier.weight(1f))
        Text(result)
    }
}

@Composable
private fun SearchResults(results: List<OverlandSearchResult>, query: String, onPick: (OverlandSearchResult) -> Unit) {
    if (query.isBlank() || results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (query.isNotBlank()) Text(query, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { result ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPick(result) }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                    val flag = flagEmoji(result.isoA2)
                    when {
                        flag.isNotEmpty() -> Text(flag, style = MaterialTheme.typography.titleLarge)
                        result.kind == OverlandResultKind.BORDER ->
                            Icon(Icons.Filled.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        else -> Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column {
                    Text(result.title)
                    Text(
                        result.subtitle ?: when (result.kind) {
                            OverlandResultKind.COUNTRY -> stringResource(R.string.search_kind_country)
                            OverlandResultKind.BORDER -> stringResource(R.string.search_kind_border)
                            OverlandResultKind.BORDER_POST -> stringResource(R.string.search_kind_border_post)
                            OverlandResultKind.ZONE -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
