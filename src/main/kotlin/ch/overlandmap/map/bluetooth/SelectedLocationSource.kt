package ch.overlandmap.map.bluetooth

import android.content.Context
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.tracking.*
import kotlinx.coroutines.*

/** Hardware takes priority while producing fixes, unless explicitly ignored. */
object LocationSourcePolicy {
    fun select(client: Boolean, hasHardware: Boolean, ignoreHardware: Boolean,
        hardware: LocationFix?, remote: LocationFix?, now: Long): LocationFix? {
        fun fresh(fix: LocationFix?, age: Long) = fix?.takeIf { now - it.timeMs in 0..age && it.accuracyM >= 0 }
        if (!client) return hardware?.takeIf { it.accuracyM >= 0 }
        if (hasHardware && !ignoreHardware) fresh(hardware, 5_000)?.let { return it }
        return fresh(remote, 10_000)
    }
}

/** A single source policy for map positions and navigation; recordings retain hardware GPS. */
class SelectedLocationSource(private val context: Context, private val onUnavailable: () -> Unit = {}) : LocationSource {
    private val bluetooth = (context.applicationContext as OverlandApp).bluetoothGPS
    private var hardware = AndroidLocationSource(context, allowNetwork = true)
    private var job: Job? = null
    private var ownFix: LocationFix? = null
    override val lastFix: LocationFix? get() = LocationSourcePolicy.select(bluetooth.state.value.client,
        bluetooth.hasHardware, bluetooth.state.value.ignoreHardware, ownFix, bluetooth.remoteFix.value, System.currentTimeMillis())
    override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) {
        stop()
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            var listening = false
            var clientMode = false
            var previous: LocationFix? = null
            var initialized = false
            while (isActive) {
                val s = bluetooth.state.value
                val needsHardware = !s.client || (bluetooth.hasHardware && !s.ignoreHardware)
                if (needsHardware != listening || clientMode != s.client) {
                    listening = needsHardware; clientMode = s.client
                    hardware.stop(); ownFix = null
                    // A client may prefer genuine GPS; network positioning must not hide a remote fix.
                    hardware = AndroidLocationSource(context, allowNetwork = !s.client)
                    // Receive stationary fixes too, so freshness never depends on travelling 2 metres.
                    if (listening) hardware.start(0f) { ownFix = it }
                }
                val selected = lastFix
                if (!initialized || selected != previous) {
                    initialized = true; previous = selected
                    if (selected == null) onUnavailable() else onFix(selected)
                }
                delay(200)
            }
        }
    }
    override fun stop() { job?.cancel(); job = null; hardware.stop(); ownFix = null }
}
