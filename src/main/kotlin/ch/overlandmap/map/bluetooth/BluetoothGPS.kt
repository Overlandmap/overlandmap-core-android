package ch.overlandmap.map.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import ch.overlandmap.map.tracking.*
import ch.overlandmap.map.geo.Coordinate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Application-owned BLE roles. All state and callbacks are serialized on the main thread. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BluetoothGPS(private val context: Context) {
    data class Peer(val address: String, val name: String, val seen: Long)
    data class SimulationStep(val id: String, val title: String, val distance: Double)
    data class Simulation(val title: String = "", val running: Boolean = false, val paused: Boolean = false,
        val finished: Boolean = false, val progress: Double = 0.0, val multiplier: Int = 1,
        val backwards: Boolean = false, val steps: List<SimulationStep> = emptyList())
    val simulation = MutableStateFlow(Simulation())
    val subscriberCount = MutableStateFlow(0)
    data class Status(val client: Boolean = false,
        val scanning: Boolean = false, val connected: Boolean = false, val server: Boolean = false, val startingServer: Boolean = false, val connecting: Boolean = false,
        val peers: List<Peer> = emptyList(), val error: String? = null, val simulation: Boolean = false,
        val connectedName: String? = null, val reconnecting: Boolean = false)
    val state = MutableStateFlow(Status())
    val remoteFix = MutableStateFlow<LocationFix?>(null)
    val hasHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    private val preferences = context.getSharedPreferences("bluetooth-gps", Context.MODE_PRIVATE)
    private var reconnectAddress: String? = null
    private var reconnectName: String? = null
    private var connectingPeer: Peer? = null
    private var restored = false
    /** Called only once an activity is visible, so location foreground services may start. */
    fun restore() {
        if (restored) return
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31) listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()
        if (permissions.any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) return
        if (adapter?.isEnabled != true || !hasFineLocationPermission(context)) return
        restored = true
        when (preferences.getString("role", null)) {
            "server" -> startServer()
            "client" -> {
                reconnectAddress = preferences.getString("address", null)
                reconnectName = preferences.getString("name", null)
                change { it.copy(reconnecting = reconnectAddress != null || reconnectName != null) }
                scan()
            }
        }
    }
    private fun rememberRole(role: String?) { preferences.edit().putString("role", role).apply() }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private var gatt: BluetoothGatt? = null
    private var gpsCharacteristics = emptyList<BluetoothGattCharacteristic>()
    private var gpsCharacteristicIndex = 0
    private var activeGpsCharacteristic: BluetoothGattCharacteristic? = null
    private var healthReadFailures = 0
    private val healthReadTimeout = Runnable {
        // A stalled GATT operation cannot safely be followed by another read.
        if (state.value.connected) retryConnection()
    }
    private val healthCheck = object : Runnable {
        override fun run() {
            val connection = gatt ?: return
            val characteristic = activeGpsCharacteristic ?: return
            if (!state.value.connected) return
            val started = try { connection.readCharacteristic(characteristic) } catch (_: SecurityException) { false }
            if (started) handler.postDelayed(healthReadTimeout, 10_000)
            else handler.postDelayed(this, 5_000)
        }
    }
    private var server: BluetoothGattServer? = null
    private var serverGeneration = 0
    private var advertising: AdvertiseCallback? = null
    private val serverTimeout = Runnable {
        if (state.value.startingServer) {
            stopServer(); change { it.copy(error = "GPS server startup timed out") }
        }
    }
    private val connectionTimeout = Runnable {
        if (gatt != null && !state.value.connected) {
            if (state.value.reconnecting) retryConnection() else { disconnect(); change { it.copy(error = "GPS server connection timed out") } }
        }
    }
    private var characteristic: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()
    private val hardware = AndroidLocationSource(context)
    var hardwareServer = false
        private set
    private var playback: Job? = null
    private var simulationTrack: TrackPlayback? = null
    private var simulationPoints: List<Coordinate> = emptyList()
    private var simulationElevations: List<Double?> = emptyList()
    private var serverStartedBySimulation = false
    private var sequence = 0
    private var latest = ByteArray(0)
    private fun main(block: () -> Unit) { handler.post(block) }
    private fun change(block: (Status) -> Status) { state.value = block(state.value) }
    fun attempt(onFailure: (() -> Unit)? = null, block: () -> Unit) { try { change { it.copy(error = null) }; block() }
        catch (e: Exception) { onFailure?.invoke(); change { it.copy(error = e.localizedMessage ?: "Bluetooth unavailable") } } }
    init {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                    attempt { disconnect() }; stopServer(clearPreference = false); restored = false; change { it.copy(scanning = false, peers = emptyList()) }
                }
            }
        }
        val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }
    private fun requireBluetooth() {
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { "Bluetooth LE is unavailable on this device." }
        check(adapter?.isEnabled == true) { "Turn on Bluetooth to continue." } }

    private val scan = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) = main {
            if (!state.value.scanning) return@main
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "OverlandMap"
            val peer = Peer(result.device.address, if (name.endsWith(" GPS")) name else "$name GPS", System.currentTimeMillis())
            change { status ->
                val previous = status.peers.firstOrNull { it.address == peer.address }
                val updated = previous?.copy(seen = peer.seen) ?: peer
                status.copy(peers = (status.peers.filter { it.address != peer.address } + updated)
                    .sortedWith(compareBy<Peer, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.address }))
            }
            if (peer.address == reconnectAddress || peer.name == reconnectName) connect(peer, reconnecting = true)
        }
        override fun onScanFailed(code: Int) = main {
            handler.removeCallbacks(stopScanTask)
            change { it.copy(scanning = false, error = "Bluetooth scan failed ($code)") }
            if (state.value.reconnecting) handler.postDelayed(retryScanTask, 5_000)
        }
    }
    fun scan(): Unit = attempt(onFailure = { stopScan() }) {
        requireBluetooth(); stopServer(); disconnect()
        change { it.copy(client = true, peers = emptyList(), scanning = true) }
        adapter!!.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BluetoothGPSProtocol.service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.removeCallbacks(stopScanTask); handler.postDelayed(stopScanTask, 15000)
    }
    private val retryScanTask = Runnable { if (state.value.reconnecting) scan() }
    private val stopScanTask = Runnable {
        stopScan()
        if (state.value.reconnecting) handler.postDelayed(retryScanTask, 5_000)
    }
    fun stopScan() { handler.removeCallbacks(stopScanTask); attempt { adapter?.bluetoothLeScanner?.stopScan(scan) }; change { it.copy(scanning = false) } }
    fun disableClient() {
        reconnectAddress = null; reconnectName = null
        handler.removeCallbacks(retryScanTask)
        if (preferences.getString("role", null) == "client") rememberRole(null)
        stopScan(); disconnect(); change { it.copy(client = false, reconnecting = false, connectedName = null) }
    }
    fun disconnect() { val old = gatt; gatt = null; gpsCharacteristics = emptyList(); activeGpsCharacteristic = null; healthReadFailures = 0;
        handler.removeCallbacks(healthCheck); handler.removeCallbacks(healthReadTimeout); remoteFix.value = null;
        attempt { try { old?.disconnect() } finally { old?.close() } }; handler.removeCallbacks(connectionTimeout); change { it.copy(connected = false, connecting = false) } }
    private fun retryConnection() {
        val peer = connectingPeer
        disconnect()
        if (peer != null) {
            reconnectAddress = peer.address; reconnectName = peer.name
            change { it.copy(reconnecting = true, connectedName = null, error = null) }
            handler.removeCallbacks(retryScanTask)
            handler.postDelayed(retryScanTask, 1_000)
        }
    }
    fun connect(peer: Peer, reconnecting: Boolean = false): Unit = attempt(onFailure = { if (reconnecting) retryConnection() else disconnect() }) {
        requireBluetooth(); stopServer(); stopScan(); disconnect(); connectingPeer = peer
        if (!reconnecting) { reconnectAddress = null; reconnectName = null; handler.removeCallbacks(retryScanTask) }
        change { it.copy(client = true, connecting = true, reconnecting = reconnecting, connectedName = null) }
        gatt = adapter!!.getRemoteDevice(peer.address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        check(gatt != null) { "Cannot connect to GPS server." }
        handler.postDelayed(connectionTimeout, 20000)
    }
    private fun receive(g: BluetoothGatt, bytes: ByteArray) = main {
        if (g === gatt) {
            val packet = GPSFixPacket.decode(bytes)
            Log.d("BluetoothGPS", "GPS packet: bytes=${bytes.size}, valid=${packet != null}")
            packet?.let { remoteFix.value = it.fix }
        }
    }
    private fun subscribeNext(g: BluetoothGatt) {
        if (g !== gatt) return
        while (gpsCharacteristicIndex < gpsCharacteristics.size) {
            val c = gpsCharacteristics[gpsCharacteristicIndex++]
            val d = c.getDescriptor(BluetoothGPSProtocol.cccd) ?: continue
            if (!g.setCharacteristicNotification(c, true)) continue
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (g.writeDescriptor(d)) return
            g.setCharacteristicNotification(c, false)
        }
        if (state.value.reconnecting) retryConnection()
        else { disconnect(); change { it.copy(error = "GPS server is unavailable. Please try again.") } }
    }
    private fun confirmConnection(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (g !== gatt || state.value.connected) return
        handler.removeCallbacks(connectionTimeout)
        connectingPeer?.let { preferences.edit().putString("role", "client").putString("address", it.address).putString("name", it.name).apply() }
        reconnectAddress = null; reconnectName = null
        activeGpsCharacteristic = c
        healthReadFailures = 0
        change { it.copy(connected = true, connecting = false, reconnecting = false, connectedName = connectingPeer?.name) }
        attempt { BluetoothGPSForegroundService.start(context) }
        handler.postDelayed(healthCheck, 5_000)
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main {
            if (g !== gatt) { g.close(); return@main }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) { if (!g.discoverServices()) { if (state.value.reconnecting) retryConnection() else { disconnect(); change { it.copy(error = "Cannot discover GPS service") } } } }
            else if (state.value.connected || state.value.reconnecting) retryConnection()
            else { disconnect(); change { it.copy(error = "GPS not available — server disconnected") } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main {
            if (g !== gatt) return@main
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (state.value.reconnecting) retryConnection() else { disconnect(); change { it.copy(error = "Incompatible GPS server") } }; return@main
            }
            gpsCharacteristics = g.services.filter { it.uuid == BluetoothGPSProtocol.service }
                .mapNotNull { it.getCharacteristic(BluetoothGPSProtocol.characteristic) }
            gpsCharacteristicIndex = 0
            subscribeNext(g)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = main {
            if (g !== gatt) return@main
            Log.d("BluetoothGPS", "GPS subscription status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS || !g.readCharacteristic(d.characteristic)) { subscribeNext(g); return@main }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { receive(g, c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { receive(g, value) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            Log.d("BluetoothGPS", "GPS read status=$status")
            handleInitialRead(g, c, c.value, status)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            Log.d("BluetoothGPS", "GPS read status=$status")
            handleInitialRead(g, c, value, status)
        }
    }
    private fun handleInitialRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) = main {
        if (g !== gatt) return@main
        if (state.value.connected) {
            if (c !== activeGpsCharacteristic) return@main
            handler.removeCallbacks(healthReadTimeout)
            if (status == BluetoothGatt.GATT_SUCCESS) healthReadFailures = 0 else healthReadFailures++
            if (healthReadFailures >= 2) retryConnection()
            else handler.postDelayed(healthCheck, 5_000)
            // Reads may return the server's old cached fix; only notifications refresh it.
            return@main
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            confirmConnection(g, c)
            if (value != null) receive(g, value)
        } else {
            g.setCharacteristicNotification(c, false)
            subscribeNext(g)
        }
    }
    private fun serverMain(generation: Int, block: () -> Unit) = main {
        if (generation == serverGeneration && server != null) block()
    }
    private fun advertiser(generation: Int) = object : AdvertiseCallback() {
        override fun onStartSuccess(settings: AdvertiseSettings) = serverMain(generation) {
            handler.removeCallbacks(serverTimeout); if (hardwareServer) rememberRole("server"); change { it.copy(server = true, startingServer = false) }
        }
        override fun onStartFailure(code: Int) = serverMain(generation) {
            stopServer(); change { it.copy(error = "Cannot advertise GPS ($code)") }
        }
    }
    private fun serverCallback(generation: Int) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = serverMain(generation) {
            if (server == null) return@serverMain
            if (status != BluetoothGatt.GATT_SUCCESS) { stopServer(); return@serverMain }
            attempt(onFailure = { stopServer() }) {
                adapter!!.bluetoothLeAdvertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                    AdvertiseData.Builder().addServiceUuid(ParcelUuid(BluetoothGPSProtocol.service)).build(),
                    AdvertiseData.Builder().setIncludeDeviceName(true).build(), advertising!!)
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = serverMain(generation) {
            if (newState != BluetoothProfile.STATE_CONNECTED) { subscribers.remove(device); subscriberCount.value = subscribers.size }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) = serverMain(generation) {
            val valid = offset in 0..latest.size
            server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset,
                if (valid) latest.copyOfRange(offset, latest.size) else null)
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) = serverMain(generation) {
            val value = if (device in subscribers) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            val valid = offset in 0..value.size
            server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,
                offset, if (valid) value.copyOfRange(offset, value.size) else null)
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) = serverMain(generation) {
            val valid = !preparedWrite && offset == 0 && d.uuid == BluetoothGPSProtocol.cccd &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) || value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            if (valid) { if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers.add(device) else subscribers.remove(device); subscriberCount.value = subscribers.size }
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
    }
    fun startServer(simulationOnly: Boolean = false) = attempt(onFailure = { stopServer() }) {
        requireBluetooth(); check(simulationOnly || hasHardware) { "This device has no GPS hardware." }
        check(simulationOnly || hasFineLocationPermission(context)) { "Location permission is required." }
        check(adapter?.bluetoothLeAdvertiser != null) { "Bluetooth LE advertising is unavailable on this device." }
        disableClient(); stopServer(clearPreference = false)
        val c = BluetoothGattCharacteristic(BluetoothGPSProtocol.characteristic,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        c.addDescriptor(BluetoothGattDescriptor(BluetoothGPSProtocol.cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        characteristic = c
        server = manager!!.openGattServer(context, serverCallback(serverGeneration))
        check(server != null) { "Cannot open Bluetooth GPS server." }
        advertising = advertiser(serverGeneration)
        check(server!!.addService(BluetoothGattService(BluetoothGPSProtocol.service, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(c) }))
        hardwareServer = !simulationOnly
        change { it.copy(startingServer = true) }
        handler.postDelayed(serverTimeout, 10000)
        BluetoothGPSForegroundService.start(context)
        if (!simulationOnly) hardware.start(0f) { if (it.accuracyM >= 0 && simulationTrack == null) broadcast(it) }
    }
    fun stopServer(clearPreference: Boolean = true) {
        if (clearPreference && preferences.getString("role", null) == "server") rememberRole(null)
        serverGeneration++; handler.removeCallbacks(serverTimeout)
        playback?.cancel(); playback = null; simulationTrack = null; simulationPoints = emptyList(); simulationElevations = emptyList(); simulation.value = Simulation(); serverStartedBySimulation = false; hardware.stop(); hardwareServer = false
        attempt { try { advertising?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } finally { server?.close() } }
        server = null; advertising = null; characteristic = null; subscribers.clear(); subscriberCount.value = 0; latest = ByteArray(0)
        change { it.copy(server = false, startingServer = false, simulation = false) }
    }
    private fun broadcast(fix: LocationFix, simulated: Boolean = false) {
        latest = GPSFixPacket(fix, simulated, sequence++ and 65535).encoded()
        val c = characteristic ?: return
        c.value = latest
        subscribers.toList().forEach { device -> attempt { server?.notifyCharacteristicChanged(device, c, false) } }
    }
    fun simulate(title: String, points: List<Coordinate>, backwards: Boolean, multiplier: Int, steps: List<SimulationStep> = emptyList(), elevations: List<Double?> = emptyList()) = attempt {
        check((context.applicationContext as ch.overlandmap.map.OverlandApp).isAdmin) { "Administrator access required." }
        val track = TrackPlayback(points, backwards, elevations)
        if (server == null) { startServer(true); if (server == null) return@attempt; serverStartedBySimulation = true }
        playback?.cancel()
        simulationPoints = points
        simulationElevations = elevations
        simulationTrack = track
        simulation.value = Simulation(title, running = true, multiplier = multiplier, backwards = backwards, steps = steps)
        change { it.copy(simulation = true) }
        runSimulation()
    }
    private fun runSimulation() {
        playback?.cancel()
        playback = scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                val state = simulation.value
                val track = simulationTrack ?: break
                val sample = track.advance(if (state.paused) 0.0 else (now - last) / 1000.0, state.multiplier); last = now
                broadcast(LocationFix(sample.coordinate.latitude, sample.coordinate.longitude, altitude = sample.altitude,
                    timeMs = System.currentTimeMillis(), courseDeg = sample.course, speedMps = if (state.paused) 0f else sample.speed), true)
                simulation.value = state.copy(progress = sample.progress, running = !track.finished, finished = track.finished)
                if (track.finished) break
                delay(1000)
            }
        }
    }
    fun setSimulationSpeed(multiplier: Int) { if (multiplier in listOf(1, 5, 50)) simulation.value = simulation.value.copy(multiplier = multiplier) }
    fun setSimulationPaused(paused: Boolean) { if (simulation.value.running) simulation.value = simulation.value.copy(paused = paused) }
    fun setSimulationDirection(backwards: Boolean) {
        val state = simulation.value
        if (simulationTrack != null && state.backwards != backwards) simulate(state.title, simulationPoints, backwards, state.multiplier, state.steps, simulationElevations)
    }
    fun seekSimulation(step: SimulationStep) {
        val track = simulationTrack ?: return
        track.seekToOriginalDistance(step.distance)
        val sample = track.advance(0.0, simulation.value.multiplier)
        val wasFinished = simulation.value.finished
        simulation.value = simulation.value.copy(progress = sample.progress, finished = track.finished,
            running = if (wasFinished && !track.finished) true else simulation.value.running, paused = if (wasFinished) false else simulation.value.paused)
        broadcast(LocationFix(sample.coordinate.latitude, sample.coordinate.longitude, altitude = sample.altitude, timeMs = System.currentTimeMillis(), courseDeg = sample.course, speedMps = 0f), true)
        if (wasFinished && !track.finished) runSimulation()
    }
    fun stopSimulation() {
        if (simulationTrack == null) return
        playback?.cancel(); playback = null; simulationTrack = null; simulationPoints = emptyList(); simulationElevations = emptyList(); simulation.value = Simulation()
        change { it.copy(simulation = false) }
        if (serverStartedBySimulation) stopServer()
    }
}
