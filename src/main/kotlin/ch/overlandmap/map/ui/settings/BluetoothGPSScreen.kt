package ch.overlandmap.map.ui.settings

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.tracking.GpxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BluetoothGPSScreen(onBack: () -> Unit, simulationOnly: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val gps = app.bluetoothGPS
    val status by gps.state.collectAsState()
    val entitlements by app.entitlements.collectAsState()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending; pending = null
        if (result.values.all { it }) action?.invoke()
        else gps.attempt { error(context.getString(R.string.bluetooth_gps_permission)) }
    }
    fun authorized(action: () -> Unit) {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) action()
        else { pending = action; permissions.launch(required.toTypedArray()) }
    }
    var title by remember { mutableStateOf("") }
    var points by remember { mutableStateOf(emptyList<Coordinate>()) }
    var multiplier by remember { mutableIntStateOf(1) }
    var backwards by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(emptyList<Pair<String, List<Coordinate>>>()) }
    var choosing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it.readBytes()) }
                } ?: error(context.getString(R.string.bluetooth_gps_invalid_track))
                check(parsed.points.size >= 2) { context.getString(R.string.bluetooth_gps_invalid_track) }
                title = parsed.name ?: "GPX"; points = parsed.points.map { it.coordinate }
            } catch (e: Exception) { gps.attempt { throw e } }
        }
    }
    LaunchedEffect(simulationOnly, entitlements?.hasAdmin) {
        if (simulationOnly && entitlements?.hasAdmin == true) {
            try { tracks = withContext(Dispatchers.IO) {
                app.libraryRepository.allTrackPackIds().flatMap { pack ->
                    app.libraryRepository.itinerariesOf(pack).flatMap { itinerary ->
                        app.libraryRepository.tracks(itinerary.documentId).map { track ->
                            "${itinerary.name} — ${track.name ?: "Track"}" to track.coordinates().map { Coordinate(it.lat, it.lon) }
                        }
                    }
                }
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) { gps.attempt { throw e } }
        }
    }
    SettingsSubScreen(stringResource(if (simulationOnly) R.string.bluetooth_gps_simulation else R.string.bluetooth_gps), onBack) {
        if (!simulationOnly) {
            var clientTab by rememberSaveable { mutableStateOf(status.client) }
            LaunchedEffect(clientTab) {
                if (clientTab && !status.connected && !status.connecting && !status.scanning) authorized { gps.scan() }
            }
            TabRow(selectedTabIndex = if (clientTab) 1 else 0) {
                Tab(selected = !clientTab, onClick = { clientTab = false; gps.disableClient() },
                    text = { Text(stringResource(R.string.bluetooth_gps_server_tab)) })
                Tab(selected = clientTab, onClick = {
                    if (!clientTab) { gps.stopServer(); clientTab = true }
                    else if (!status.connected && !status.connecting) authorized { gps.scan() }
                }, text = { Text(stringResource(R.string.bluetooth_gps_client_tab)) })
            }
            Spacer(Modifier.height(12.dp))
            if (!clientTab) {
                Button(onClick = { if (status.server || status.startingServer) gps.stopServer() else authorized { gps.startServer() } },
                    enabled = status.server || status.startingServer || gps.hasHardware) {
                    Text(stringResource(if (status.server || status.startingServer) R.string.bluetooth_gps_stop else R.string.bluetooth_gps_start))
                }
            } else {
                Text(stringResource(R.string.bluetooth_gps_client_description))
                Spacer(Modifier.height(12.dp))
                if (status.scanning) Text(stringResource(R.string.bluetooth_gps_scanning))
                if (!status.scanning && !status.connected && !status.connecting) {
                    Text(stringResource(R.string.bluetooth_gps_no_servers))
                    TextButton(onClick = { authorized { gps.scan() } }) { Text(stringResource(R.string.bluetooth_gps_search)) }
                }
                status.peers.forEach { peer ->
                    OutlinedButton(onClick = { authorized { gps.connect(peer) } }, enabled = !status.connecting && !status.connected) {
                        Text(stringResource(R.string.bluetooth_gps_connect, peer.name))
                    }
                }
                if (status.connected || status.connecting) {
                    Text(stringResource(if (status.connected) R.string.bluetooth_gps_connected else R.string.bluetooth_gps_scanning))
                    TextButton(onClick = { gps.disableClient(); authorized { gps.scan() } }) { Text(stringResource(R.string.bluetooth_gps_disconnect)) }
                }
            }
        } else if (entitlements?.hasAdmin == true) {
            Text(title)
            OutlinedButton(onClick = { choosing = true }, enabled = !status.simulation) { Text(stringResource(R.string.bluetooth_gps_local_track)) }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !status.simulation) { Text(stringResource(R.string.bluetooth_gps_import)) }
            Row { listOf(1, 5, 50).forEach { speed ->
                FilterChip(multiplier == speed, { multiplier = speed }, { Text("${speed}×") }, Modifier.padding(end = 8.dp), enabled = !status.simulation)
            } }
            Row {
                Text(stringResource(R.string.bluetooth_gps_backward), Modifier.weight(1f))
                Switch(backwards, { backwards = it }, enabled = !status.simulation)
            }
            Button(onClick = { authorized { gps.simulate(points, backwards, multiplier) } }, enabled = points.size >= 2 && !status.simulation) {
                Text(stringResource(R.string.bluetooth_gps_play))
            }
            if (status.server || status.startingServer || status.simulation) TextButton(onClick = { gps.stopServer() }) { Text(stringResource(R.string.bluetooth_gps_stop)) }
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.bluetooth_gps_local_track)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn { items(tracks.size) { index ->
                TextButton(onClick = { title = tracks[index].first; points = tracks[index].second; choosing = false }) { Text(tracks[index].first) }
            } }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } })
}
