package ch.overlandmap.map.ui.tracking

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import ch.overlandmap.map.R
import ch.overlandmap.map.navigation.beginTrackNavigation
import ch.overlandmap.map.ui.currentLanguage
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.ui.home.SidebarFocus

/**
 * One recording on a map, in its colour, with its waypoints as pins; a tap on a pin shows its label
 * (iOS `RecordingViewer`). The menu holds the track actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingViewerScreen(trackId: String, onBack: () -> Unit, onOpenNavigation: (() -> Unit)? = null) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val all by app.trackRecorder.recordings.collectAsState()
    val track = all.firstOrNull { it.id == trackId }

    // The track is gone (deleted from the menu): leave.
    LaunchedEffect(track == null) { if (track == null) onBack() }
    if (track == null) return

    val openNavigation = onOpenNavigation ?: LocalOpenTrackNavigation.current
    val scope = rememberCoroutineScope()
    val lang = currentLanguage()
    var info by remember { mutableStateOf(false) }
    var waypoint by remember { mutableStateOf<Pair<ch.overlandmap.map.tracking.RecordedTrack, ch.overlandmap.map.tracking.RecordedWaypoint>?>(null) }
    var trackSheet by remember { mutableStateOf(false) }
    val folderWaypoints = if (track.trackFolderId == null) track.waypoints else all.filter { it.trackFolderId == track.trackFolderId }.flatMap { it.waypoints }.distinctBy { it.id }
    val displayedTrack = track.copy(waypoints = folderWaypoints)
    var zoomToken by remember { mutableIntStateOf(1) }
    var menu by remember { mutableStateOf(false) }
    // Frame the whole track once, whatever is recorded afterwards.
    var focus by remember(trackId) { mutableStateOf(SidebarFocus(bounds = track.trackBounds ?: track.bounds, token = 1)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { focus = SidebarFocus(bounds = track.trackBounds ?: track.bounds, token = ++zoomToken) }) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_to_object)) }
                    if (track.description.isNotBlank()) IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, stringResource(R.string.information)) }

                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                        TrackMenu(track = track, expanded = menu, onDismiss = { menu = false }, onDeleted = onBack, onNavigate = { choice, fix ->
                            scope.launch { if (app.beginTrackNavigation(track, choice.reverse, false, lang, startChoice = choice, initialLocation = fix)) openNavigation() }
                        })
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            TrackMap(
                tracks = listOf(displayedTrack),
                focus = focus,
                onWaypointTapped = { _, waypointId ->
                    folderWaypoints.firstOrNull { it.id == waypointId }?.let { waypoint = displayedTrack to it }
                },
                modifier = Modifier.fillMaxSize(),
                onTrackTapped = { trackSheet = true },
            )

        }
    }
    waypoint?.let { (t, w) -> RecordedWaypointSheet(t, w, { waypoint = null }, { waypoint = null; focus = SidebarFocus(lat = w.latitude, lon = w.longitude, token = ++zoomToken) }) }
    if (trackSheet) RecordedTrackSheet(track, { trackSheet = false }, { trackSheet = false }, { trackSheet = false; focus = SidebarFocus(bounds = track.trackBounds ?: track.bounds, token = ++zoomToken) })
    if (info) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { info = false }) {
        androidx.compose.foundation.layout.Column(Modifier.padding(20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(track.description, style = MaterialTheme.typography.bodyLarge)
        }
    }

}
