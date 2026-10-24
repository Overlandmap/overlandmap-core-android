package ch.overlandmap.map.bluetooth

import android.content.Context
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.tracking.*
import kotlinx.coroutines.*

/** A connected client uses remote fixes exclusively, including when they become stale. */
object LocationSourcePolicy {
    fun select(connected: Boolean,
        hardware: LocationFix?, remote: LocationFix?, now: Long): LocationFix? {
        fun fresh(fix: LocationFix?, age: Long) = fix?.takeIf { now - it.timeMs in 0..age && it.accuracyM >= 0 }
        if (!connected) return hardware?.takeIf { it.accuracyM >= 0 }
        return fresh(remote, 10_000)
    }
}

/** Shared source selection for maps, navigation and track recording. */
class SelectedLocationSource(private val context: Context, private val allowNetwork: Boolean = true, private val onUnavailable: () -> Unit = {}) : LocationSource {
    private val bluetooth = (context.applicationContext as OverlandApp).bluetoothGPS
    private var hardware = AndroidLocationSource(context, allowNetwork = allowNetwork)
    private var job: Job? = null
    private var ownFix: LocationFix? = null
    override val lastFix: LocationFix? get() = LocationSourcePolicy.select(bluetooth.state.value.connected,
        ownFix, bluetooth.remoteFix.value, System.currentTimeMillis())
    override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) {
        stop()
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            var listening = false
            var clientMode = false
            var previous: LocationFix? = null
            var initialized = false
            while (isActive) {
                val s = bluetooth.state.value
                val needsHardware = !s.connected
                if (needsHardware != listening || clientMode != s.connected) {
                    listening = needsHardware; clientMode = s.connected
                    hardware.stop(); ownFix = null
                    // Never let device positioning hide a connected remote GPS.
                    hardware = AndroidLocationSource(context, allowNetwork = allowNetwork && !s.connected)
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
