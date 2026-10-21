package ch.overlandmap.map.ui.tracking

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.tracking.RecordedTrack
import ch.overlandmap.map.tracking.TrackImportException
import ch.overlandmap.map.ui.home.SidebarFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which recordings a list shows. */
enum class TrackListMode { RECORDINGS, IMPORTED }

/**
 * The map and the table of recorded or imported tracks (iOS `TrackListMapScreen`): the eye shows a
 * track on the map, the magnifier frames it, the row opens the viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    mode: TrackListMode,
    onBack: () -> Unit,
    onOpenTrack: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val recorder = app.trackRecorder
    val all by recorder.recordings.collectAsState()
    val currentId by recorder.currentTrackId.collectAsState()
    val isBeta = rememberIsBeta()
    val startRecording = rememberStartRecording()
    val scope = rememberCoroutineScope()

    val tracks = remember(all, mode) { all.filter { it.isImported == (mode == TrackListMode.IMPORTED) } }
    val displayed = remember(tracks) { tracks.filter { it.isDisplayed } }

    var focus by remember { mutableStateOf<SidebarFocus?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }
    var importError by remember { mutableStateOf<Int?>(null) }
    var addingWaypoint by remember { mutableStateOf(false) }

    fun zoomTo(track: RecordedTrack) {
        focus = SidebarFocus(bounds = track.bounds, token = ++focusToken)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw TrackImportException(TrackImportException.Reason.INVALID_GPX)
                    recorder.importGpx(bytes)
                }
            }
            result.onSuccess { zoomTo(it) }.onFailure {
                importError = when ((it as? TrackImportException)?.reason) {
                    TrackImportException.Reason.EMPTY_GPX -> R.string.empty_gpx_file
                    else -> R.string.invalid_gpx_file
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (mode == TrackListMode.IMPORTED) R.string.imported else R.string.recordings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (mode == TrackListMode.IMPORTED) {
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.FileOpen, contentDescription = stringResource(R.string.import_gpx))
                        }
                    }
                    if (mode == TrackListMode.RECORDINGS && isBeta) {
                        if (currentId != null) {
                            IconButton(onClick = { addingWaypoint = true }) {
                                Icon(Icons.Filled.AddLocationAlt, contentDescription = stringResource(R.string.add_waypoint))
                            }
                            IconButton(onClick = { recorder.stopRecording() }) {
                                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop), tint = Color(0xFFD32F2F))
                            }
                        } else {
                            IconButton(onClick = startRecording) {
                                Icon(Icons.Filled.FiberManualRecord, contentDescription = stringResource(R.string.record), tint = Color(0xFFD32F2F))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TrackMap(
                tracks = displayed,
                focus = focus,
                onWaypointTapped = { trackId, _ -> onOpenTrack(trackId) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            HorizontalDivider()
            if (tracks.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_list), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            recording = track.id == currentId,
                            onOpen = { onOpenTrack(track.id) },
                            onZoom = { zoomTo(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (addingWaypoint) VoiceWaypointSheet(onDismiss = { addingWaypoint = false })
}

@Composable
private fun TrackRow(track: RecordedTrack, recording: Boolean, onOpen: () -> Unit, onZoom: () -> Unit) {
    val recorder = (LocalContext.current.applicationContext as OverlandApp).trackRecorder
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { recorder.toggleDisplayed(track.id) }) {
            Icon(
                if (track.isDisplayed) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = stringResource(if (track.isDisplayed) R.string.hide else R.string.show),
                tint = if (track.isDisplayed) track.colorHex.toComposeColor() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                track.formattedStartDate() + if (recording) "  ●" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(track.formattedDistance(), style = MaterialTheme.typography.bodyMedium)
            Text(
                track.formattedDuration(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onZoom, enabled = track.bounds != null) {
            Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.show_on_map))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
            TrackMenu(
                track = track,
                expanded = menu,
                onDismiss = { menu = false },
                onShowOnMap = onZoom,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
