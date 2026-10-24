package ch.overlandmap.map.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import ch.overlandmap.map.tracking.*
import ch.overlandmap.map.geo.Coordinate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Application-owned BLE roles. All state and callbacks are serialized on the main thread. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BluetoothGPS(private val context: Context) {
    data class Peer(val address: String, val name: String, val seen: Long)
    data class Status(val client: Boolean = false,
        val scanning: Boolean = false, val connected: Boolean = false, val server: Boolean = false, val startingServer: Boolean = false, val connecting: Boolean = false,
        val peers: List<Peer> = emptyList(), val error: String? = null, val simulation: Boolean = false)
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
            disconnect(); change { it.copy(error = "GPS server connection timed out") }
        }
    }
    private var characteristic: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()
    private val hardware = AndroidLocationSource(context)
    var hardwareServer = false
        private set
    private var playback: Job? = null
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
            change { it.copy(peers = (it.peers.filter { p -> p.address != peer.address } + peer)) }
            if (peer.address == reconnectAddress || peer.name == reconnectName) { reconnectAddress = null; reconnectName = null; connect(peer) }
        }
        override fun onScanFailed(code: Int) = main { change { it.copy(scanning = false, error = "Bluetooth scan failed ($code)") } }
    }
    fun scan() = attempt(onFailure = { stopScan() }) {
        requireBluetooth(); stopServer(); disconnect()
        change { it.copy(client = true, peers = emptyList(), scanning = true) }
        adapter!!.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BluetoothGPSProtocol.service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.removeCallbacks(stopScanTask); handler.postDelayed(stopScanTask, 15000)
    }
    private val stopScanTask = Runnable { stopScan() }
    fun stopScan() { handler.removeCallbacks(stopScanTask); attempt { adapter?.bluetoothLeScanner?.stopScan(scan) }; change { it.copy(scanning = false) } }
    fun disableClient() {
        reconnectAddress = null; reconnectName = null
        if (preferences.getString("role", null) == "client") rememberRole(null)
        stopScan(); disconnect(); change { it.copy(client = false) }
    }
    fun disconnect() { val old = gatt; gatt = null; remoteFix.value = null;
        attempt { try { old?.disconnect() } finally { old?.close() } }; handler.removeCallbacks(connectionTimeout); change { it.copy(connected = false, connecting = false) } }
    fun connect(peer: Peer) = attempt(onFailure = { disconnect() }) {
        requireBluetooth(); stopServer(); stopScan(); disconnect(); connectingPeer = peer
        change { it.copy(client = true, connecting = true) }
        gatt = adapter!!.getRemoteDevice(peer.address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        check(gatt != null) { "Cannot connect to GPS server." }
        handler.postDelayed(connectionTimeout, 20000)
    }
    private fun receive(g: BluetoothGatt, bytes: ByteArray) = main {
        if (g === gatt) GPSFixPacket.decode(bytes)?.let { remoteFix.value = it.fix }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main {
            if (g !== gatt) { g.close(); return@main }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) { if (!g.discoverServices()) { disconnect(); change { it.copy(error = "Cannot discover GPS service") } } }
            else { disconnect(); change { it.copy(error = "GPS not available — server disconnected") } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main {
            if (g !== gatt) return@main
            val c = g.getService(BluetoothGPSProtocol.service)?.getCharacteristic(BluetoothGPSProtocol.characteristic)
            val d = c?.getDescriptor(BluetoothGPSProtocol.cccd)
            if (status != BluetoothGatt.GATT_SUCCESS || c == null || d == null || !g.setCharacteristicNotification(c, true)) {
                disconnect(); change { it.copy(error = "Incompatible GPS server") }; return@main
            }
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!g.writeDescriptor(d)) disconnect()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = main {
            if (g !== gatt) return@main
            if (status != BluetoothGatt.GATT_SUCCESS) { disconnect(); return@main }
            handler.removeCallbacks(connectionTimeout)
            connectingPeer?.let { preferences.edit().putString("role", "client").putString("address", it.address).putString("name", it.name).apply() }
            change { it.copy(connected = true, connecting = false) }; attempt { BluetoothGPSForegroundService.start(context) }; g.readCharacteristic(d.characteristic)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { receive(g, c.value ?: return) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { receive(g, value) }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) receive(g, c.value ?: return)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) receive(g, value)
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
            if (newState != BluetoothProfile.STATE_CONNECTED) subscribers.remove(device)
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
            if (valid) { if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers.add(device) else subscribers.remove(device) }
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
        if (!simulationOnly) hardware.start(0f) { if (it.accuracyM >= 0) broadcast(it) }
    }
    fun stopServer(clearPreference: Boolean = true) {
        if (clearPreference && preferences.getString("role", null) == "server") rememberRole(null)
        serverGeneration++; handler.removeCallbacks(serverTimeout)
        playback?.cancel(); playback = null; hardware.stop(); hardwareServer = false
        attempt { try { advertising?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } finally { server?.close() } }
        server = null; advertising = null; characteristic = null; subscribers.clear(); latest = ByteArray(0)
        change { it.copy(server = false, startingServer = false, simulation = false) }
    }
    private fun broadcast(fix: LocationFix, simulated: Boolean = false) {
        latest = GPSFixPacket(fix, simulated, sequence++ and 65535).encoded()
        val c = characteristic ?: return
        c.value = latest
        subscribers.toList().forEach { device -> attempt { server?.notifyCharacteristicChanged(device, c, false) } }
    }
    fun simulate(points: List<Coordinate>, backwards: Boolean, multiplier: Int) = attempt {
        check((context.applicationContext as ch.overlandmap.map.OverlandApp).isAdmin) { "Administrator access required." }
        val track = TrackPlayback(points, backwards)
        startServer(true); if (server == null) return@attempt
        change { it.copy(simulation = true) }
        playback = scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                val sample = track.advance((now - last) / 1000.0, multiplier); last = now
                broadcast(LocationFix(sample.coordinate.latitude, sample.coordinate.longitude,
                    timeMs = System.currentTimeMillis(), courseDeg = sample.course, speedMps = sample.speed), true)
                if (track.finished) { change { it.copy(simulation = false) }; break }
                delay(1000)
            }
        }
    }
}
