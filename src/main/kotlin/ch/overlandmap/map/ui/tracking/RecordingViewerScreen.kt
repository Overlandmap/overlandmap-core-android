package ch.overlandmap.map.ui.tracking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.ui.home.SidebarFocus
import kotlinx.coroutines.delay

/**
 * One recording on a map, in its colour, with its waypoints as pins; a tap on a pin shows its label
 * (iOS `RecordingViewer`). The menu holds the track actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingViewerScreen(trackId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val all by app.trackRecorder.recordings.collectAsState()
    val track = all.firstOrNull { it.id == trackId }

    // The track is gone (deleted from the menu): leave.
    LaunchedEffect(track == null) { if (track == null) onBack() }
    if (track == null) return

    var menu by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    // Frame the whole track once, whatever is recorded afterwards.
    val focus = remember(trackId) { SidebarFocus(bounds = track.bounds, token = 1) }

    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3000)
            banner = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                        TrackMenu(track = track, expanded = menu, onDismiss = { menu = false }, onDeleted = onBack)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            TrackMap(
                tracks = listOf(track),
                focus = focus,
                onWaypointTapped = { _, waypointId ->
                    banner = track.waypoints.firstOrNull { it.id == waypointId }?.label
                },
                modifier = Modifier.fillMaxSize(),
            )
            banner?.let { label ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    Text(label, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
