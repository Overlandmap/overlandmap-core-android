package ch.overlandmap.map.ui.search

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import java.text.NumberFormat
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.NearbySearchRepository
import ch.overlandmap.map.data.SearchResult
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.ui.rememberUnitFormatter
import ch.overlandmap.map.ui.social.ContributedWaypointViewer
import ch.overlandmap.map.ui.tracking.rememberUserLocation
import ch.overlandmap.map.ui.world.LocalOpenOverlanding
import ch.overlandmap.map.ui.world.OverlandingSelection
import ch.overlandmap.map.data.local.FtsIndex

/** The same two search modes in Home search and the local-pack search tab. */
@Composable
internal fun SearchContent(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    onResultClick: (SearchResult) -> Unit,
) {
    val mode by viewModel.mode.collectAsState()
    LaunchedEffect(mode) { ch.overlandmap.map.diagnostics.CrashReports.note("Search UI opened; mode=$mode") }
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val searchError by viewModel.error.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val units = rememberUnitFormatter()
    val metric = units.format.distanceMetric
    var radius by rememberSaveable(metric) { mutableStateOf(if (metric) 50.0 else 30.0) }
    var selectedType by rememberSaveable { mutableStateOf(NearbySearchRepository.Type.ALL) }
    val openOverlanding = LocalOpenOverlanding.current
    var contributed by remember { mutableStateOf<ContributedWaypoint?>(null) }
    var imported by remember { mutableStateOf<SearchResult?>(null) }
    var viewingTrack by remember { mutableStateOf<String?>(null) }
    viewingTrack?.let { id ->
        ch.overlandmap.map.ui.tracking.RecordingViewerScreen(id, { viewingTrack = null })
        return
    }
    val open: (SearchResult) -> Unit = { result ->
        val communityWaypoint = result.contributedWaypoint
        when {
            result.importedTrack != null -> imported = result
            communityWaypoint != null -> contributed = communityWaypoint
            result.type == FtsIndex.TYPE_BORDER_POST -> openOverlanding(OverlandingSelection.OfBorderPost(result.documentId))
            else -> onResultClick(result)
        }
    }
    BackHandler(enabled = mode == SearchViewModel.Mode.NEARBY) { viewModel.mode.value = SearchViewModel.Mode.TEXT }
    Column(modifier.fillMaxSize()) {
        SearchField(
            query = if (mode == SearchViewModel.Mode.TEXT) query else "",
            onQueryChange = { value -> viewModel.setQuery(value); viewModel.mode.value = SearchViewModel.Mode.TEXT },
            leading = leading,
        )
        HorizontalDivider()
        if (mode == SearchViewModel.Mode.TEXT && searchError != null) {
            Text(searchError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        if (mode == SearchViewModel.Mode.TEXT) {
            SearchResultsList(
                results, query, viewModel.minQueryLength, Modifier.weight(1f), open,
                footer = {
                    Button(
                        onClick = {
                            keyboard?.hide()
                            radius = if (metric) 50.0 else 30.0
                            selectedType = NearbySearchRepository.Type.ALL
                            viewModel.mode.value = SearchViewModel.Mode.NEARBY
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text(stringResource(R.string.search_nearby_action)) }
                },
            )
        } else {
            NearbyContent(viewModel, radius, { radius *= 2 }, selectedType, { selectedType = it }, onResultClick = open)
        }
    }

    imported?.let { result ->
        val track = result.importedTrack!!
        val waypoint = result.importedWaypoint
        if (waypoint != null) ch.overlandmap.map.ui.tracking.RecordedWaypointSheet(track, waypoint, { imported = null }, { imported = null; viewingTrack = track.id })
        else ch.overlandmap.map.ui.tracking.RecordedTrackSheet(track, { imported = null }, { imported = null; viewingTrack = track.id }, { imported = null; viewingTrack = track.id })
    }
    contributed?.let { waypoint ->
        ContributedWaypointViewer(waypoint, waypoint.trackPackId,
            onZoom = waypoint.itinDocumentId?.let { id -> {
                contributed = null
                onResultClick(SearchResult(FtsIndex.TYPE_ITINERARY, id, waypoint.name, ""))
            } }, onDismiss = { contributed = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.NearbyContent(
    viewModel: SearchViewModel,
    radius: Double,
    onExtend: () -> Unit,
    selectedType: NearbySearchRepository.Type,
    onTypeChange: (NearbySearchRepository.Type) -> Unit,
    onResultClick: (SearchResult) -> Unit,
) {
    val units = rememberUnitFormatter()
    val metric = units.format.distanceMetric
    val radiusKm = if (metric) radius else radius * 1.609344
    var originLatitude by rememberSaveable { mutableStateOf<Double?>(null) }
    var originLongitude by rememberSaveable { mutableStateOf<Double?>(null) }
    // Acquire one fix for this search, then dispose the location listener. Type and radius
    // changes reuse the captured origin; starting a new Nearby search acquires a new fix.
    if (originLatitude == null || originLongitude == null) {
        val location by rememberUserLocation()
        LaunchedEffect(location) {
            location?.let { fix ->
                originLatitude = fix.latitude
                originLongitude = fix.longitude
            }
        }
    }
    val origin = originLatitude?.let { lat -> originLongitude?.let { lon -> Coordinate(lat, lon) } }
    val state by viewModel.nearby.collectAsState()
    LaunchedEffect(origin, radiusKm, selectedType) { viewModel.searchNearby(origin, radiusKm, selectedType) }
    val types = NearbySearchRepository.Type.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        types.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selectedType == type,
                onClick = { onTypeChange(type) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                modifier = Modifier.weight(1f),
                icon = {},
            ) {
                Text(stringResource(typeLabel(type)), style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
    HorizontalDivider()
    when {
        origin == null -> SearchNotice(stringResource(R.string.search_location_unavailable))
        state.loading && state.results.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        state.error != null -> SearchNotice(state.error!!)
        else -> SearchResultsList(
            state.results, "", 0, Modifier.weight(1f), onResultClick,
            footer = {
                OutlinedButton(
                    onClick = onExtend,
                    enabled = (radius * 2 * 1.609344).isFinite() && !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(stringResource(R.string.search_extend_radius,
                        NumberFormat.getIntegerInstance().format(radius * 2), if (metric) "km" else "mi"))
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.SearchNotice(text: String) {
    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun typeLabel(type: NearbySearchRepository.Type): Int = when (type) {
    NearbySearchRepository.Type.ALL -> R.string.search_type_all
    NearbySearchRepository.Type.ITINERARY -> R.string.itineraries
    NearbySearchRepository.Type.WAYPOINT -> R.string.search_type_waypoint
    NearbySearchRepository.Type.BORDER_POST -> R.string.search_type_border_post
}
