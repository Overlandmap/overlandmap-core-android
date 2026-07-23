package ch.overlandmap.map.map

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import ch.overlandmap.map.model.Track
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxStyleManager
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.skyLayer
import com.mapbox.maps.extension.style.layers.properties.generated.SkyType
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.rasterDemSource
import com.mapbox.maps.extension.style.terrain.generated.terrain
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TERRAIN_SOURCE = "terrain-dem"
private const val SKY_LAYER = "flyover-sky"

/** Camera framing followed along the route: ~this height above the track, pitched. */
private const val FLYOVER_ALTITUDE_M = 4000.0
private const val FLYOVER_PITCH = 62.0

/** Adds the Mapbox DEM source and binds it as the style's 3D terrain. */
fun enableTerrain(style: MapboxStyleManager) {
    if (!style.styleSourceExists(TERRAIN_SOURCE)) {
        style.addSource(
            rasterDemSource(TERRAIN_SOURCE) {
                url("mapbox://mapbox.mapbox-terrain-dem-v1")
                tileSize(514L)
            }
        )
    }
    terrain(TERRAIN_SOURCE) { exaggeration(1.3) }.bindTo(style)
}

/** Adds an atmospheric sky layer (for the 3D/flyover horizon). */
fun enableSky(style: MapboxStyleManager) {
    if (style.styleLayerExists(SKY_LAYER)) return
    style.addLayer(
        skyLayer(SKY_LAYER) {
            skyType(SkyType.ATMOSPHERE)
            skyAtmosphereSun(listOf(0.0, 90.0))
            skyAtmosphereSunIntensity(15.0)
        }
    )
}

/**
 * A cinematic 3D fly-over of an itinerary, in the spirit of Mapbox's "Building
 * cinematic route animations with Mapbox GL": enable terrain + sky, then chase
 * the camera along the route (its centre moving by distance, its bearing from
 * the direction of travel, held ~[FLYOVER_ALTITUDE_M] above the ground) so it
 * reads like flying the route. Exposes media-player [state] (progress, playing)
 * and controls, driven by a single [ValueAnimator].
 */
class Flyover {

    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val progress: Float = 0f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var animator: ValueAnimator? = null
    private var mapView: MapView? = null

    /** Enables terrain/sky and starts flying the route (auto-plays). */
    fun start(mapView: MapView, tracks: List<Track>) {
        stop()
        val route = tracks.flatMap { it.coordinates() }.map { Point.fromLngLat(it.lon, it.lat) }
        if (route.size < 2) return
        this.mapView = mapView
        val map = mapView.mapboxMap
        map.getStyle { style ->
            enableTerrain(style)
            enableSky(style)
        }

        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + haversineMeters(route[i - 1], route[i])
        }
        val total = cumulative.last()
        if (total <= 0.0) return
        val lookAhead = (total * 0.02).coerceAtLeast(80.0)
        val duration = total.toLong().coerceIn(10_000L, 45_000L) // ~1 s per km

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val travelled = total * animator.animatedFraction
                val here = pointAlong(route, cumulative, travelled)
                val ahead = pointAlong(route, cumulative, min(travelled + lookAhead, total))
                val zoom = zoomForAltitude(FLYOVER_ALTITUDE_M, here.latitude(), mapView.height)
                runCatching {
                    map.setCamera(
                        CameraOptions.Builder()
                            .center(here)
                            .bearing(bearingDegrees(here, ahead))
                            .pitch(FLYOVER_PITCH)
                            .zoom(zoom)
                            .build()
                    )
                }.onFailure { cancel() } // the map went away — stop.
                _state.update { it.copy(progress = animator.animatedFraction) }
            }
            doOnEnd { finish() } // fires on natural end and on cancel/stop
        }
        _state.value = State(active = true, playing = true, progress = 0f)
        animator?.start()
    }

    fun play() {
        val a = animator ?: return
        if (a.isPaused) a.resume() else if (!a.isStarted) a.start()
        _state.update { it.copy(playing = true) }
    }

    fun pause() {
        animator?.pause()
        _state.update { it.copy(playing = false) }
    }

    fun stop() {
        animator?.cancel() // → doOnEnd → finish()
    }

    private fun finish() {
        animator = null
        // Settle back to a flat, north-up view.
        runCatching {
            mapView?.camera?.easeTo(
                CameraOptions.Builder().pitch(0.0).bearing(0.0).build(),
                MapAnimationOptions.Builder().duration(1200L).build(),
            )
        }
        _state.value = State()
    }
}

/**
 * The Mapbox zoom that puts the camera roughly [altitude] metres above the
 * ground at [lat], for the current view [heightPx] and the flyover pitch —
 * derived from Mapbox's `cameraToCenterDistance = 1.5 * height` and 512-tile
 * ground resolution.
 */
private fun zoomForAltitude(altitude: Double, lat: Double, heightPx: Int): Double {
    val height = heightPx.coerceAtLeast(1)
    val pitch = Math.toRadians(FLYOVER_PITCH)
    val metersPerPixel = altitude / (1.5 * height * cos(pitch))
    val worldMeters = 40_075_016.686 * cos(Math.toRadians(lat))
    return (ln(worldMeters / (512.0 * metersPerPixel)) / ln(2.0)).coerceIn(2.0, 20.0)
}

/** The point [target] metres along [route], using precomputed [cumulative] sums. */
private fun pointAlong(route: List<Point>, cumulative: DoubleArray, target: Double): Point {
    if (target <= 0.0) return route.first()
    if (target >= cumulative.last()) return route.last()
    var lo = 1
    var hi = cumulative.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (cumulative[mid] < target) lo = mid + 1 else hi = mid
    }
    val segStart = cumulative[lo - 1]
    val segEnd = cumulative[lo]
    val f = if (segEnd == segStart) 0.0 else (target - segStart) / (segEnd - segStart)
    val a = route[lo - 1]
    val b = route[lo]
    return Point.fromLngLat(
        a.longitude() + (b.longitude() - a.longitude()) * f,
        a.latitude() + (b.latitude() - a.latitude()) * f,
    )
}

private const val EARTH_RADIUS_M = 6_371_000.0

private fun haversineMeters(a: Point, b: Point): Double {
    val lat1 = Math.toRadians(a.latitude())
    val lat2 = Math.toRadians(b.latitude())
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude() - a.longitude())
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

private fun bearingDegrees(from: Point, to: Point): Double {
    val lat1 = Math.toRadians(from.latitude())
    val lat2 = Math.toRadians(to.latitude())
    val dLon = Math.toRadians(to.longitude() - from.longitude())
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
