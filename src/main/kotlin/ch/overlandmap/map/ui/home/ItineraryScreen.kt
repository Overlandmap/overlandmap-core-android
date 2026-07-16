package ch.overlandmap.map.ui.home

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
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.markup.rememberMarkupLinkHandler
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.shop.CommentsTab
import ch.overlandmap.map.ui.shop.zoomToItinerary
import ch.overlandmap.map.ui.zoomToPopupObject
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap

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
                        1 -> StepsTab(state, selectedStepIndex, lang, onLink, viewModel::selectStep)
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

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = { onSelectStep(index - 1) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            }
            Text(
                step.fullName(lang),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onSelectStep(index + 1) }, enabled = index < steps.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            step.titlePhotoUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = step.titlePhotoCaption,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
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
            step.description(lang)?.let {
                MarkupText(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    onLinkClick = onLink,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotosTab(state: ItineraryState) {
    // The itinerary's own photos followed by each step's title photo.
    val photos = remember(state) {
        buildList {
            state.itinerary?.titlePhotoUrl?.let(::add)
            state.itinerary?.localOtherPhotoPaths?.forEach { add("file://$it") }
            state.steps.forEach { step -> step.titlePhotoUrl?.let(::add) }
        }.distinct()
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
                model = photos[index],
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

@Composable
private fun FullScreenPhotoViewer(photos: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val pagerState = rememberPagerState(initialPage = startIndex) { photos.size }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = photos[page],
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}
