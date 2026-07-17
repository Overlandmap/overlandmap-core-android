package ch.overlandmap.map.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.AirportShuttle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.AppConfig
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryDifficulty
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.MapObjectPopup
import ch.overlandmap.map.ui.MapPopupKind
import ch.overlandmap.map.ui.MapPopupState
import ch.overlandmap.map.ui.VerticalSplit
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.markup.Markup
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.markup.rememberMarkupLinkHandler
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.shop.CommentsTab
import ch.overlandmap.map.ui.shop.zoomToItinerary
import ch.overlandmap.map.ui.zoomToPopupObject
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import ch.overlandmap.map.model.ItineraryStep

private val GREEN = Color(0xFF2E7D32)
private val BLUE = Color(0xFF1976D2)
private val ORANGE = Color(0xFFF57C00)
private val RED = Color(0xFFD32F2F)

/**
 * Local itinerary viewer: the itinerary's map on top (red line, numbered
 * steps, waypoints), and below it four tabs — description (titled with the
 * itinerary slug), steps (one at a time with prev/next arrows synced with the
 * map selection), photos, comments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryScreen(
    itineraryId: String,
    onBack: () -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenPack: (packId: String) -> Unit,
    onOpenSidebar: (sidebarId: String) -> Unit,
    initialStepId: Int? = null,
    viewModel: ItineraryViewModel = viewModel(key = itineraryId) {
        ItineraryViewModel(overlandApp(), itineraryId)
    },
) {
    val state by viewModel.state.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val selectedStepIndex by viewModel.selectedStepIndex.collectAsState()
    val useMiles by viewModel.useMiles.collectAsState()
    val useFeet by viewModel.useFeet.collectAsState()
    val lang = currentLanguage()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var popup by remember { mutableStateOf<MapPopupState?>(null) }
    var openWaypoint by remember { mutableStateOf<Waypoint?>(null) }
    var showAddWaypoint by remember { mutableStateOf(false) }

    // Opened through a step link (?step=N): select that step once loaded.
    LaunchedEffect(initialStepId, state.steps) {
        if (initialStepId == null || state.steps.isEmpty()) return@LaunchedEffect
        val index = state.steps.indexOfFirst { it.stepId == initialStepId }
        if (index >= 0) {
            viewModel.selectStep(index)
            tab = 1
        }
    }

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Same full-display layout as the pack screens.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!landscape) {
                TopAppBar(
                    title = { Text(state.itinerary?.name(lang) ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            }
        },
    ) { padding ->
        val itinerary = state.itinerary
        if (state.loading || itinerary == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
            }
            return@Scaffold
        }

        fun jumpToStep(stepId: Int) {
            val index = state.steps.indexOfFirst { it.stepId == stepId }
            if (index >= 0) {
                viewModel.selectStep(index)
                tab = 1
            }
        }

        val onLink = rememberMarkupLinkHandler(
            trackPackId = itinerary.trackPackId,
            sourceItineraryId = itinerary.itineraryId.ifEmpty { null },
            onOpenItinerary = onOpenItinerary,
            onOpenSidebar = onOpenSidebar,
            onJumpToStep = ::jumpToStep,
            onOpenShopPack = onOpenPack,
            mapProvider = { map },
        )

        VerticalSplit(
            modifier = Modifier.fillMaxSize().padding(padding),
            top = {
                Box(modifier = Modifier.fillMaxSize()) {
                    LocalItineraryMap(
                        trackPackId = itinerary.trackPackId,
                        tracks = state.tracks,
                        steps = state.steps,
                        waypoints = state.waypoints,
                        selectedStepId = state.steps.getOrNull(selectedStepIndex)?.stepId,
                        onTapped = { tap ->
                            when (tap) {
                                // A step tap selects it in the viewer directly,
                                // no popup.
                                is ItineraryMapTap.OnStep -> jumpToStep(tap.stepId)
                                is ItineraryMapTap.OnWaypoint ->
                                    state.waypoints.firstOrNull { it.documentId == tap.documentId }
                                        ?.let {
                                            popup = MapPopupState(
                                                tap.position,
                                                MapPopupKind.OfWaypoint(it),
                                            )
                                        }
                                // A blue line: another itinerary of the pack. Taps
                                // on the itinerary already on screen are ignored.
                                is ItineraryMapTap.OnItinerary ->
                                    if (!tap.itinerarySlug.equals(itinerary.itineraryId, true)) {
                                        scope.launch {
                                            val target = app.libraryRepository.itineraryBySlug(
                                                itinerary.trackPackId, tap.itinerarySlug,
                                            )
                                            popup = MapPopupState(
                                                tap.position,
                                                if (target != null && !target.isBuyable) {
                                                    MapPopupKind.OfItinerary(target)
                                                } else {
                                                    MapPopupKind.Buy(
                                                        packId = itinerary.trackPackId,
                                                        packName = null,
                                                        notInSample = true,
                                                    )
                                                },
                                            )
                                        }
                                    }
                            }
                        },
                        onMapReady = { map = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    popup?.let { current ->
                        MapObjectPopup(
                            state = current,
                            onDismiss = { popup = null },
                            onZoom = { kind -> map?.let { zoomToPopupObject(it, kind) } },
                            onOpen = { kind ->
                                when (kind) {
                                    is MapPopupKind.OfStep -> jumpToStep(kind.step.stepId)
                                    is MapPopupKind.OfWaypoint -> openWaypoint = kind.waypoint
                                    is MapPopupKind.OfItinerary ->
                                        onOpenItinerary(kind.itinerary.documentId, null)
                                    is MapPopupKind.Buy -> onOpenPack(kind.packId)
                                }
                            },
                        )
                    }
                }
            },
            bottom = {
                // Landscape drops the app bar and puts this beside the map, so
                // inset the tab row below the status bar to keep it tappable.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (landscape) Modifier.statusBarsPadding() else Modifier),
                ) {
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = {
                                Text(
                                    itinerary.itineraryId.ifEmpty { itinerary.name(lang) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(stringResource(R.string.steps)) },
                        )
                        Tab(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            text = { Text(stringResource(R.string.photos)) },
                        )
                        Tab(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            text = { Text(stringResource(R.string.comments)) },
                        )
                    }
                    when (tab) {
                        0 -> DescriptionTab(
                            itinerary = itinerary,
                            useMiles = useMiles,
                            lang = lang,
                            onLink = onLink,
                            onAddWaypoint = { showAddWaypoint = true },
                            onShareGpx = {
                                shareItineraryGpx(
                                    context, itinerary, state.tracks, state.steps,
                                    state.waypoints, lang,
                                )
                            },
                            onShareLink = {
                                shareItineraryLink(
                                    context,
                                    editorId = state.trackPack?.editor,
                                    packName = state.trackPack?.name,
                                    itineraryId = itinerary.itineraryId,
                                )
                            },
                            onZoom = { map?.let { zoomToItinerary(it, itinerary) } },
                        )
                        1 -> StepsTab(
                            state, selectedStepIndex, lang, useMiles, useFeet,
                            onLink, viewModel::selectStep,
                        )
                        2 -> PhotosTab(state)
                        3 -> CommentsTab(comments, lang)
                    }
                }
            },
        )

        openWaypoint?.let { waypoint ->
            WaypointDialog(waypoint, lang, onLink = onLink, onDismiss = { openWaypoint = null })
        }

        if (showAddWaypoint) {
            AlertDialog(
                onDismissRequest = { showAddWaypoint = false },
                title = { Text(stringResource(R.string.add_waypoint)) },
                text = { Text(stringResource(R.string.add_waypoint_coming_soon)) },
                confirmButton = {
                    TextButton(onClick = { showAddWaypoint = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
    }
}

@Composable
private fun DescriptionTab(
    itinerary: Itinerary,
    useMiles: Boolean,
    lang: String,
    onLink: (MarkupLink, String) -> Unit,
    onAddWaypoint: () -> Unit,
    onShareGpx: () -> Unit,
    onShareLink: () -> Unit,
    onZoom: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Property box with its action icons alongside; the description now
        // runs full width below the two.
        Row {
            PropertiesBox(itinerary, useMiles)
            ItineraryActions(
                onAddWaypoint = onAddWaypoint,
                onShareGpx = onShareGpx,
                onShareLink = onShareLink,
                onZoom = onZoom,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        itinerary.description(lang)?.let {
            MarkupText(
                it,
                style = MaterialTheme.typography.bodyMedium,
                onLinkClick = onLink,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/** The three itinerary actions stacked to the right of the property box. */
@Composable
private fun ItineraryActions(
    onAddWaypoint: () -> Unit,
    onShareGpx: () -> Unit,
    onShareLink: () -> Unit,
    onZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onAddWaypoint) {
            Icon(
                Icons.Filled.AddLocationAlt,
                contentDescription = stringResource(R.string.add_waypoint),
            )
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_as_gpx)) },
                    onClick = {
                        menu = false
                        onShareGpx()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_link)) },
                    onClick = {
                        menu = false
                        onShareLink()
                    },
                )
            }
        }
        IconButton(onClick = onZoom) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.zoom_to_itinerary),
            )
        }
    }
}

/** The top-left box with the four property rows (length, fuel, difficulty, offroad). */
@Composable
private fun PropertiesBox(itinerary: Itinerary, useMiles: Boolean) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            var length = UserPreferences.formatDistanceKm(itinerary.lengthKM, useMiles)
            if (itinerary.lengthDays > 0) {
                length += " · " + stringResource(R.string.length_days, itinerary.lengthDays)
            }
            PropertyRow(Icons.Filled.Route, MaterialTheme.colorScheme.primary, length)

            itinerary.fuelRange?.let {
                PropertyRow(
                    Icons.Filled.LocalGasStation,
                    MaterialTheme.colorScheme.primary,
                    stringResource(R.string.fuel_range, it),
                )
            }

            val difficulty = ItineraryDifficulty.fromRaw(itinerary.difficulty)
            PropertyRow(
                difficultyIcon(difficulty),
                difficultyColor(difficulty),
                difficultyLabelText(difficulty),
            )

            itinerary.offroadPercent?.let { percent ->
                PropertyRow(offroadIcon(percent), offroadColor(percent), offroadLabel(percent))
            }
        }
    }
}

@Composable
private fun PropertyRow(icon: ImageVector, tint: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun difficultyIcon(difficulty: ItineraryDifficulty): ImageVector = when (difficulty) {
    ItineraryDifficulty.EASY -> Icons.Filled.SentimentVerySatisfied
    ItineraryDifficulty.NORMAL -> Icons.Filled.SentimentSatisfied
    ItineraryDifficulty.HARD -> Icons.Filled.SentimentNeutral
    ItineraryDifficulty.EXTREME -> Icons.Filled.SentimentVeryDissatisfied
}

@Composable
private fun difficultyColor(difficulty: ItineraryDifficulty): Color = when (difficulty) {
    ItineraryDifficulty.EASY -> GREEN
    ItineraryDifficulty.NORMAL -> MaterialTheme.colorScheme.onSurface
    ItineraryDifficulty.HARD -> ORANGE
    ItineraryDifficulty.EXTREME -> RED
}

@Composable
private fun difficultyLabelText(difficulty: ItineraryDifficulty): String = stringResource(
    when (difficulty) {
        ItineraryDifficulty.EASY -> R.string.difficulty_easy
        ItineraryDifficulty.NORMAL -> R.string.difficulty_normal
        ItineraryDifficulty.HARD -> R.string.difficulty_hard
        ItineraryDifficulty.EXTREME -> R.string.difficulty_extreme
    },
)

// Same buckets as the Flutter app's itinerary description component.
private fun offroadIcon(percent: Int): ImageVector = when (percent) {
    0 -> Icons.Filled.AirportShuttle
    30 -> Icons.Filled.DirectionsCar
    75 -> Icons.Filled.LocalShipping
    100 -> Icons.Filled.Agriculture
    else -> Icons.Filled.AddRoad
}

private fun offroadColor(percent: Int): Color = when (percent) {
    0 -> GREEN
    30 -> BLUE
    75 -> ORANGE
    100 -> RED
    else -> BLUE
}

@Composable
private fun offroadLabel(percent: Int): String = when (percent) {
    0 -> stringResource(R.string.paved)
    30 -> stringResource(R.string.partly_offroad)
    75 -> stringResource(R.string.mostly_offroad)
    100 -> stringResource(R.string.fully_offroad)
    else -> stringResource(R.string.offroad_percent, percent)
}

@Composable
private fun StepsTab(
    state: ItineraryState,
    selectedStepIndex: Int,
    lang: String,
    useMiles: Boolean,
    useFeet: Boolean,
    onLink: (MarkupLink, String) -> Unit,
    onSelectStep: (Int) -> Unit,
) {
    val steps = state.steps
    if (steps.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.not_available))
        }
        return
    }
    val index = selectedStepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[index]
    var openPhoto by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            StepHeader(step, lang, useMiles, useFeet)
            step.description(lang)?.let {
                MarkupText(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    onLinkClick = onLink,
                    modifier = Modifier.padding(16.dp),
                )
            }
            // The title image goes at the end; tapping it opens the zoomable viewer.
            step.titlePhotoUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = step.titlePhotoCaption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clickable { openPhoto = true },
                    contentScale = ContentScale.Crop,
                )
                step.titlePhotoCaption?.let {
                    MarkupText(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        onLinkClick = onLink,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            // Clears the floating prev/next bar so it never hides the last line.
            Spacer(Modifier.height(56.dp))
        }

        // Prev/next arrows float over the scroll at the bottom, on a translucent
        // white strip.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.7f)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSelectStep(index - 1) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            }
            IconButton(onClick = { onSelectStep(index + 1) }, enabled = index < steps.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }

    if (openPhoto && step.titlePhotoUrl != null) {
        FullScreenPhotoViewer(
            photos = listOf(
                viewerPhoto(step.titlePhotoId, step.localPhotoPath, step.titlePhotoCaption),
            ),
            startIndex = 0,
            onDismiss = { openPhoto = false },
        )
    }
}

/** Decimal-degree coordinates of a step, or null when it has no position. */
private fun ItineraryStep.coordText(): String? {
    val la = lat ?: return null
    val lo = lon ?: return null
    return "%.5f, %.5f".format(la, lo)
}

/**
 * The step's title block: bold "id.Name", tappable coordinates + altitude
 * below (copies the coordinates), current distance at the top right, then a
 * row with the point-of-interest flags on the left and action buttons on the
 * right (actions TBD).
 */
@Composable
private fun StepHeader(step: ItineraryStep, lang: String, useMiles: Boolean, useFeet: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coords = step.coordText()
    val subtitle = listOfNotNull(
        coords,
        step.ele?.let { UserPreferences.formatElevationM(it, useFeet) },
    ).joinToString("  ·  ")

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${step.stepId}.${step.name(lang)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(
                                if (coords != null) {
                                    Modifier.clickable {
                                        clipboard.setText(AnnotatedString(coords))
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.copied_to_clipboard),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            Text(
                UserPreferences.formatDistanceKm(step.distanceKm, useMiles),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PoiIcon(Icons.Filled.LocalGasStation, step.hasFuel)
            PoiIcon(Icons.Filled.Hotel, step.hasHotel)
            PoiIcon(Icons.Filled.LocalPolice, step.isPoliceCheckpoint)
            Spacer(Modifier.weight(1f))
            // Meanings/actions TBD.
            IconButton(onClick = {}) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GREEN)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Help, contentDescription = null, tint = RED)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Share, contentDescription = null)
            }
        }
    }
}

/** A point-of-interest flag icon: black when set, gray otherwise, no background. */
@Composable
private fun PoiIcon(icon: ImageVector, active: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (active) Color.Black else Color.Gray,
        modifier = Modifier.padding(end = 12.dp).size(24.dp),
    )
}

@Composable
private fun PhotosTab(state: ItineraryState) {
    // The itinerary's own photos followed by each step's title photo (with its
    // caption). Deduplicated by URL, keeping the first (captioned) occurrence.
    val photos = remember(state) {
        buildList {
            state.itinerary?.let {
                if (it.titlePhotoId != null || it.localPhotoPath != null) {
                    add(viewerPhoto(it.titlePhotoId, it.localPhotoPath, null))
                }
            }
            state.itinerary?.localOtherPhotoPaths?.forEach {
                add(ViewerPhoto("file://$it", placeholderUrl = null, caption = null))
            }
            state.steps.forEach { step ->
                if (step.titlePhotoId != null || step.localPhotoPath != null) {
                    add(viewerPhoto(step.titlePhotoId, step.localPhotoPath, step.titlePhotoCaption))
                }
            }
        }.distinctBy { it.url }
    }
    var openedIndex by remember { mutableStateOf<Int?>(null) }

    if (photos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.not_available))
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(photos.size) { index ->
            AsyncImage(
                // Thumbnails use the local copy (or small remote), not the
                // high-res full-screen image.
                model = photos[index].placeholderUrl ?: photos[index].url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { openedIndex = index },
            )
        }
    }

    openedIndex?.let { start ->
        FullScreenPhotoViewer(photos, start, onDismiss = { openedIndex = null })
    }
}

/**
 * A photo for the full-screen viewer: [url] is loaded first (the high-res
 * online image when available), with [placeholderUrl] (the downloaded local
 * copy) shown while it loads and used as the fallback when offline.
 */
data class ViewerPhoto(val url: String, val placeholderUrl: String?, val caption: String?)

/**
 * Builds a [ViewerPhoto] preferring the high-res online image (from
 * [titlePhotoId]) with the local copy as placeholder/offline fallback.
 */
private fun viewerPhoto(titlePhotoId: String?, localPhotoPath: String?, caption: String?): ViewerPhoto {
    val local = localPhotoPath?.let { "file://$it" }
    val online = titlePhotoId?.let(AppConfig::fullPhotoUrl)
    return ViewerPhoto(url = online ?: local.orEmpty(), placeholderUrl = local, caption = caption)
}

/**
 * Full-screen photo viewer: letterboxed black, pinch-to-zoom (and pan) always
 * enabled, browsing between [photos] by horizontal swipe when not zoomed. The
 * caption shows below the photo and a tap toggles its visibility; the X closes.
 */
@Composable
private fun FullScreenPhotoViewer(
    photos: List<ViewerPhoto>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A tap toggles the caption and the close button together.
        var chromeVisible by remember { mutableStateOf(true) }
        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
        ) { photos.size }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // Zoom belongs to one page at a time: reset it when the page changes.
        LaunchedEffect(pagerState.currentPage) {
            scale = 1f
            offset = Offset.Zero
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                // Once zoomed, the pager releases horizontal drags so they pan.
                userScrollEnabled = scale <= 1f,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val current = page == pagerState.currentPage
                val pageCaption = photos[page].caption
                // The downloaded local copy shows immediately (and stays when
                // offline); the high-res online image loads over it. (Called
                // unconditionally; a null local path yields an empty painter.)
                val placeholder = rememberAsyncImagePainter(photos[page].placeholderUrl)
                // DEBUG (remove for release): which image is actually shown.
                var imageState by remember(photos[page].url) {
                    mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
                }
                val showingOnline = imageState is AsyncImagePainter.State.Success &&
                    photos[page].url.startsWith("http")
                // Photo + caption as one vertically-centered group, so the
                // caption sits right below the photo (in the letterbox for a
                // landscape shot; hugging its bottom for a full-height one).
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AsyncImage(
                        model = photos[page].url,
                        placeholder = placeholder,
                        error = placeholder,
                        onLoading = { imageState = it },
                        onSuccess = { imageState = it },
                        onError = { imageState = it },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .pointerInput(page) {
                                // Two-finger gestures always pinch/pan; a single
                                // finger pans only while zoomed, otherwise it's
                                // left for the pager (browse) or a tap. Zoom is
                                // anchored at the pinch centroid so the point
                                // under the fingers stays put (offset is kept in
                                // content coordinates; see the graphicsLayer).
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        // Require a finger down: on the final
                                        // (all-up) event the centroid is
                                        // Unspecified, which would push the
                                        // offset to NaN and blank the image.
                                        if (pressed >= 1 && (pressed >= 2 || scale > 1f)) {
                                            val zoomChange =
                                                if (pressed >= 2) event.calculateZoom() else 1f
                                            val pan = event.calculatePan()
                                            val centroid = event.calculateCentroid()
                                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                            offset = if (newScale == 1f) {
                                                Offset.Zero
                                            } else {
                                                (offset + centroid / scale) -
                                                    (centroid / newScale + pan / newScale)
                                            }
                                            scale = newScale
                                            event.changes.forEach {
                                                if (it.positionChanged()) it.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(page) {
                                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                            }
                            .graphicsLayer {
                                if (current) {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = -offset.x * scale
                                    translationY = -offset.y * scale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                            },
                    )
                    if (chromeVisible && !pageCaption.isNullOrBlank()) {
                        Text(
                            Markup.plainText(pageCaption),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    // DEBUG (remove for release): shows which image is displayed.
                    if (chromeVisible) {
                        Text(
                            if (showingOnline) "hi-res online photo" else "offline photo",
                            color = Color.Yellow,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                // Keep the text off the nav bar when the photo
                                // runs to the bottom (a Dialog reports insets 0).
                                .padding(bottom = navBarHeight().coerceAtLeast(16.dp)),
                        )
                    }
                }
            }
            if (chromeVisible) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

/**
 * The system navigation bar's height in dp. A Dialog window reports its own
 * insets as 0, so read the live inset from the host Activity window (falling
 * back to the platform resource) — otherwise the caption hides behind the bar.
 */
@Composable
private fun navBarHeight(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context) {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val live = (c as? Activity)?.window?.decorView
            ?.let { ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            ?: 0
        val px = if (live > 0) {
            live
        } else {
            val res = context.resources
            val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else 0
        }
        with(density) { px.toDp() }
    }
}
