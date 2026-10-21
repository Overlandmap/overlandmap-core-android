package ch.overlandmap.map.geo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One point on a track. [elevation] is metres, or null where none was recorded. */
data class TrackSample(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    /** Cumulative distance from the start, in kilometres. */
    val distanceKm: Double = 0.0,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
}

/**
 * Decodes and measures the packed coordinate stream carried by `Track.coordsBase64`.
 * Port of iOS `TrackGeometry`.
 *
 * Wire format: a flat little-endian Float32 array of (lat, lng, ele) **triples** — not
 * GeoJSON's (lng, lat), and not Double. An elevation of exactly 0.0 means "unknown"
 * (preserved deliberately so the elevation profile matches the other apps).
 */
object TrackGeometry {
    private const val STRIDE = 3
    private const val EARTH_RADIUS_KM = 6371.0088

    fun decode(base64: String?): List<TrackSample> {
        if (base64.isNullOrEmpty()) return emptyList()
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        return decode(bytes)
    }

    fun decode(data: ByteArray): List<TrackSample> {
        val floats = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (floats.remaining() < STRIDE) return emptyList()
        val points = ArrayList<TrackSample>(floats.remaining() / STRIDE)
        var cumulative = 0.0
        var previous: TrackSample? = null
        // A partial trailing triple is dropped, never read out of bounds.
        while (floats.remaining() >= STRIDE) {
            val lat = floats.get().toDouble()
            val lon = floats.get().toDouble()
            val ele = floats.get().toDouble()
            var point = TrackSample(lat, lon, if (ele == 0.0) null else ele)
            previous?.let { cumulative += haversineKm(it, point) }
            point = point.copy(distanceKm = cumulative)
            points.add(point)
            previous = point
        }
        return points
    }

    /** Encodes back to the packed format (tests and any future export). */
    fun encode(points: List<TrackSample>): String {
        val buffer = ByteBuffer.allocate(points.size * STRIDE * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (p in points) {
            buffer.putFloat(p.latitude.toFloat())
            buffer.putFloat(p.longitude.toFloat())
            buffer.putFloat((p.elevation ?: 0.0).toFloat())
        }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    /** Great-circle distance in km (R = 6371.0088), matching the Dart `distance()`. */
    fun haversineKm(from: TrackSample, to: TrackSample): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val a = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(lat1) * cos(lat2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun lengthKm(points: List<TrackSample>): Double = points.lastOrNull()?.distanceKm ?: 0.0

    fun bounds(points: List<TrackSample>): GeoBounds? {
        val first = points.firstOrNull() ?: return null
        var minLat = first.latitude
        var maxLat = first.latitude
        var minLon = first.longitude
        var maxLon = first.longitude
        for (p in points) {
            minLat = minOf(minLat, p.latitude)
            maxLat = maxOf(maxLat, p.latitude)
            minLon = minOf(minLon, p.longitude)
            maxLon = maxOf(maxLon, p.longitude)
        }
        return GeoBounds(minLat, maxLat, minLon, maxLon)
    }

    /**
     * Ramer–Douglas–Peucker, as the Dart `simplify()`. [tolerance] is in degrees and is
     * compared as a squared planar distance in lat/lon. Iterative, not recursive: a long
     * straight track would otherwise recurse to a depth proportional to its length.
     */
    fun simplify(points: List<TrackSample>, tolerance: Double = 0.1): List<TrackSample> {
        if (points.size <= 2) return points
        val squaredTolerance = tolerance * tolerance
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxSquared = 0.0
            var farthest = first
            if (last > first + 1) {
                for (i in first + 1 until last) {
                    val d = squaredSegmentDistance(points[i], points[first], points[last])
                    if (d > maxSquared) {
                        maxSquared = d
                        farthest = i
                    }
                }
            }
            if (maxSquared > squaredTolerance) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun squaredSegmentDistance(p: TrackSample, start: TrackSample, end: TrackSample): Double {
        var x = start.longitude
        var y = start.latitude
        var dx = end.longitude - x
        var dy = end.latitude - y
        if (dx != 0.0 || dy != 0.0) {
            val t = ((p.longitude - x) * dx + (p.latitude - y) * dy) / (dx * dx + dy * dy)
            if (t > 1) {
                x = end.longitude
                y = end.latitude
            } else if (t > 0) {
                x += dx * t
                y += dy * t
            }
        }
        dx = p.longitude - x
        dy = p.latitude - y
        return dx * dx + dy * dy
    }

    /** Length of the polyline itself (straight segments between consecutive points). */
    fun polylineLengthKm(points: List<TrackSample>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) total += haversineKm(points[i - 1], points[i])
        return total
    }

    /** The point [distanceKm] along the polyline, clamped to the ends (Turf's `along`). */
    fun interpolate(points: List<TrackSample>, distanceKm: Double): TrackSample? {
        val first = points.firstOrNull() ?: return null
        if (distanceKm <= 0) return first
        var traveled = 0.0
        for (i in 1 until points.size) {
            val start = points[i - 1]
            val end = points[i]
            val segment = haversineKm(start, end)
            if (traveled + segment >= distanceKm) {
                val fraction = if (segment > 0) (distanceKm - traveled) / segment else 0.0
                val a = start.elevation
                val b = end.elevation
                return TrackSample(
                    latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                    longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                    elevation = if (a != null && b != null) a + (b - a) * fraction else null,
                    distanceKm = distanceKm,
                )
            }
            traveled += segment
        }
        return points.last()
    }

    /** Initial great-circle bearing from [from] to [to], in degrees (0 = north, clockwise). */
    fun bearingDegrees(from: TrackSample, to: TrackSample): Double =
        from.coordinate.bearing(to.coordinate)
}
