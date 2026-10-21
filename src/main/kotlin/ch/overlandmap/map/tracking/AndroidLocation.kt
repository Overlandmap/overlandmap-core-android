package ch.overlandmap.map.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the app may read the precise device position. */
fun hasFineLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun Location.toFix() = LocationFix(
    latitude = latitude,
    longitude = longitude,
    altitude = if (hasAltitude()) altitude else null,
    timeMs = if (time > 0) time else System.currentTimeMillis(),
    accuracyM = if (hasAccuracy()) accuracy else -1f,
    courseDeg = if (hasBearing() && hasSpeed() && speed > 0.5f) bearing else null,
    speedMps = if (hasSpeed()) speed else null,
)

/**
 * Fixes from the platform [LocationManager]. Updates keep arriving while the app is in the
 * background as long as the tracking foreground service runs (see [TrackingService]).
 *
 * The GPS is the source. With [allowNetwork] the network provider fills in while the GPS has no
 * recent fix (indoors, before the first fix, a device or emulator without GPS); its fixes are never
 * used once the GPS delivers. A recording keeps to the GPS: network positions would spoil a track.
 */
class AndroidLocationSource(private val context: Context, private val allowNetwork: Boolean = false) : LocationSource {

    @Volatile
    override var lastFix: LocationFix? = null
        private set

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = mutableListOf<LocationListener>()
    private var lastGpsMs = 0L
    private var fixCount = 0

    private fun listener(gps: Boolean, onFix: (LocationFix) -> Unit) = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = System.currentTimeMillis()
            if (gps) lastGpsMs = now
            else if (now - lastGpsMs < NETWORK_FALLBACK_AFTER_MS) return
            val fix = location.toFix()
            if (fix.accuracyM >= 0) lastFix = fix
            if (++fixCount <= 3) Log.d(TAG, "location fix #$fixCount from ${location.provider}: ${fix.latitude},${fix.longitude} acc=${fix.accuracyM}")
            onFix(fix)
        }

        // Required on API < 30, where these have no default implementation.
        override fun onProviderEnabled(provider: String) { Log.d(TAG, "location: provider $provider enabled") }
        override fun onProviderDisabled(provider: String) { Log.d(TAG, "location: provider $provider disabled") }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) {
        stop()
        val permitted = hasFineLocationPermission(context)
        Log.d(TAG, "location start: permission=$permitted allowNetwork=$allowNetwork")
        if (!permitted) return
        lastGpsMs = 0L
        fixCount = 0
        fun register(provider: String, gps: Boolean) {
            try {
                // A disabled provider can still be listened to: updates start when the user turns location on.
                if (!manager.isProviderEnabled(provider)) Log.d(TAG, "location: provider $provider is disabled in the system settings")
                if (lastFix == null) manager.getLastKnownLocation(provider)?.let { lastFix = it.toFix() }
                val l = listener(gps, onFix)
                manager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, minDistanceMeters, l, Looper.getMainLooper())
                listeners += l
                Log.d(TAG, "location: listening to $provider (last known ${lastFix != null})")
            } catch (e: SecurityException) {
                Log.d(TAG, "location: $provider refused: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "location: no $provider on this device")
            }
        }
        register(LocationManager.GPS_PROVIDER, gps = true)
        if (allowNetwork) register(LocationManager.NETWORK_PROVIDER, gps = false)
    }

    override fun stop() {
        listeners.forEach { manager.removeUpdates(it) }
        listeners.clear()
    }

    private companion object {
        const val TAG = "NavDebug"
        const val UPDATE_INTERVAL_MS = 1_000L

        /** Network fixes are used only when the GPS has been silent this long. */
        const val NETWORK_FALLBACK_AFTER_MS = 10_000L
    }
}

/**
 * The device's compass heading in degrees from true north (the rotation-vector sensor corrected
 * by the magnetic declination at the last known position), or null while unavailable.
 */
class HeadingSensor(context: Context, private val position: () -> LocationFix?) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors.unregisterListener(this)
        _heading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)
        var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
        position()?.let { fix ->
            degrees += GeomagneticField(
                fix.latitude.toFloat(), fix.longitude.toFloat(), (fix.altitude ?: 0.0).toFloat(), System.currentTimeMillis(),
            ).declination
        }
        val normalized = (degrees % 360f + 360f) % 360f
        // Ignore sub-degree jitter so the compass doesn't redraw constantly.
        val previous = _heading.value
        if (previous == null || kotlin.math.abs(normalized - previous) >= 1f) _heading.value = normalized
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
