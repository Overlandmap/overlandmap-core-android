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
import ch.overlandmap.map.bluetooth.BluetoothGPS
import ch.overlandmap.map.navigation.TrackNavigationMath
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.tracking.GpxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SimulationChoice(val title: String, val points: List<Coordinate>, val elevations: List<Double?>, val steps: List<BluetoothGPS.SimulationStep> = emptyList())

@Composable
fun BluetoothGPSScreen(onBack: () -> Unit, simulationOnly: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val gps = app.bluetoothGPS
    val status by gps.state.collectAsState()
    val simulation by gps.simulation.collectAsState()
    val subscriberCount by gps.subscriberCount.collectAsState()
    val language = currentLanguage()
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
    var elevations by remember { mutableStateOf(emptyList<Double?>()) }
    var steps by remember { mutableStateOf(emptyList<BluetoothGPS.SimulationStep>()) }
    var multiplier by remember { mutableIntStateOf(1) }
    var backwards by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(emptyList<SimulationChoice>()) }
    var choosing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var stepMenu by remember { mutableStateOf(false) }
    LaunchedEffect(simulation.running, simulation.finished) {
        if (simulation.running || simulation.finished) { multiplier = simulation.multiplier; backwards = simulation.backwards }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it.readBytes()) }
                } ?: error(context.getString(R.string.bluetooth_gps_invalid_track))
                check(parsed.points.size >= 2) { context.getString(R.string.bluetooth_gps_invalid_track) }
                title = parsed.name ?: "GPX"; points = parsed.points.map { it.coordinate }; elevations = parsed.points.map { it.elevation }; steps = emptyList()
            } catch (e: Exception) { gps.attempt { throw e } }
        }
    }
    LaunchedEffect(simulationOnly, entitlements?.hasAdmin) {
        if (simulationOnly && entitlements?.hasAdmin == true) {
            try { tracks = withContext(Dispatchers.IO) {
                app.libraryRepository.allTrackPackIds().flatMap { pack ->
                    app.libraryRepository.itinerariesOf(pack).mapNotNull { itinerary ->
                        val track = app.libraryRepository.tracks(itinerary.documentId).firstOrNull { it.documentId == itinerary.trackIds.firstOrNull() }
                            ?: return@mapNotNull null
                        val trackPoints = track.coordinates()
                        val path = trackPoints.map { Coordinate(it.lat, it.lon) }
                        if (path.size < 2) return@mapNotNull null
                        val stepChoices = app.libraryRepository.steps(itinerary.documentId).sortedBy { it.stepId }.mapNotNull { step ->
                            val lat = step.lat ?: return@mapNotNull null
                            val lon = step.lon ?: return@mapNotNull null
                            val projection = TrackNavigationMath.closestTrackProjection(Coordinate(lat, lon), path) ?: return@mapNotNull null
                            BluetoothGPS.SimulationStep(step.documentId, step.fullName(language), projection.distanceAlongTrack)
                        }
                        SimulationChoice(itinerary.name(language), path, trackPoints.map { it.ele }, stepChoices)
                    }
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
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
                if (status.connected) {
                    OutlinedButton(onClick = { gps.disableClient(); authorized { gps.scan() } }) {
                        Text(stringResource(R.string.bluetooth_gps_connected_disconnect, status.connectedName.orEmpty()))
                    }
                } else if (status.reconnecting) {
                    Text(stringResource(R.string.bluetooth_gps_reconnecting))
                    TextButton(onClick = { gps.disableClient() }) {
                        Text(stringResource(R.string.stop))
                    }
                } else {
                    if (status.scanning || status.connecting) Text(stringResource(R.string.bluetooth_gps_scanning))
                    if (!status.scanning && !status.connecting && status.peers.isEmpty()) Text(stringResource(R.string.bluetooth_gps_no_servers))
                    status.peers.forEach { peer ->
                        OutlinedButton(onClick = { authorized { gps.connect(peer) } }, enabled = !status.connecting) {
                            Text(stringResource(R.string.bluetooth_gps_connect, peer.name))
                        }
                    }
                    if (!status.scanning && !status.connecting) {
                        TextButton(onClick = { authorized { gps.scan() } }) { Text(stringResource(R.string.bluetooth_gps_search)) }
                    }
                }
            }
        } else if (entitlements?.hasAdmin == true) {
            val active = simulation.running || simulation.finished
            Text(if (active) simulation.title else title.ifEmpty { stringResource(R.string.bluetooth_gps_no_track) })
            if (!active && points.isNotEmpty()) Text(stringResource(R.string.bluetooth_gps_points, points.size), style = MaterialTheme.typography.bodySmall)
            val deviceName = try { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.name } catch (_: SecurityException) { null }
            if (!deviceName.isNullOrBlank()) Text(stringResource(R.string.bluetooth_gps_visible_as, "$deviceName GPS"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { choosing = true }, enabled = !active) { Text(stringResource(R.string.bluetooth_gps_local_track)) }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml")) }, enabled = !active) { Text(stringResource(R.string.bluetooth_gps_import)) }
            Row { listOf(1, 5, 50).forEach { speed ->
                FilterChip(multiplier == speed, { multiplier = speed; gps.setSimulationSpeed(speed) }, { Text("${speed}×") }, Modifier.padding(end = 8.dp))
            } }
            Row {
                Text(stringResource(R.string.bluetooth_gps_backward), Modifier.weight(1f))
                Switch(backwards, { backwards = it; if (active) gps.setSimulationDirection(it) }, enabled = !active || points.isNotEmpty())
            }
            Text(stringResource(R.string.bluetooth_gps_simulation_description), style = MaterialTheme.typography.bodySmall)
            if (!active) {
                Button(onClick = { authorized { gps.simulate(title, points, backwards, multiplier, steps, elevations) } }, enabled = points.size >= 2) {
                    Text(stringResource(R.string.bluetooth_gps_play))
                }
            } else {
                Row {
                    if (simulation.running) TextButton(onClick = { gps.setSimulationPaused(!simulation.paused) }) {
                        Text(stringResource(if (simulation.paused) R.string.bluetooth_gps_resume else R.string.bluetooth_gps_pause))
                    }
                    TextButton(onClick = { gps.stopSimulation() }) { Text(stringResource(R.string.stop)) }
                }
                LinearProgressIndicator(progress = { simulation.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                if (simulation.steps.isNotEmpty()) Box {
                    TextButton(onClick = { stepMenu = true }) { Text(stringResource(R.string.bluetooth_gps_skip_step)) }
                    DropdownMenu(expanded = stepMenu, onDismissRequest = { stepMenu = false }) {
                        simulation.steps.forEach { step -> DropdownMenuItem(text = { Text(step.title) }, onClick = { stepMenu = false; gps.seekSimulation(step) }) }
                    }
                }
                if (simulation.finished) Text(stringResource(R.string.bluetooth_gps_finished))
            }
            if (status.server || status.startingServer) Text(stringResource(R.string.bluetooth_gps_connected_devices, subscriberCount))
        }
        if (!status.connected && !status.reconnecting) status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.bluetooth_gps_local_track)) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.search)) }, singleLine = true)
                val filtered = tracks.filter { it.title.contains(query.trim(), ignoreCase = true) }
                androidx.compose.foundation.lazy.LazyColumn { items(filtered.size) { index ->
                    val choice = filtered[index]
                    TextButton(onClick = { title = choice.title; points = choice.points; elevations = choice.elevations; steps = choice.steps; choosing = false }) { Text(choice.title) }
                } }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } })
}
