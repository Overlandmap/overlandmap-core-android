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
 * GPS fixes from the platform [LocationManager]. Updates keep arriving while the app is in the
 * background as long as the tracking foreground service runs (see [TrackingService]).
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    @Volatile
    override var lastFix: LocationFix? = null
        private set

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) {
        stop()
        if (!hasFineLocationPermission(context)) return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = location.toFix()
                if (fix.accuracyM >= 0) lastFix = fix
                onFix(fix)
            }

            // Required on API < 30, where these have no default implementation.
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        try {
            if (lastFix == null) {
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastFix = it.toFix() }
            }
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, minDistanceMeters, l, Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            listener = null
        } catch (_: IllegalArgumentException) {
            listener = null // no GPS provider on this device
        }
    }

    override fun stop() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
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
