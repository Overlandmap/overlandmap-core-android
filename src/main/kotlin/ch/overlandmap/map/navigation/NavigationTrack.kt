package ch.overlandmap.map.navigation

import ch.overlandmap.map.geo.Coordinate
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The polyline being navigated, prepared once so every location fix can be projected onto it
 * cheaply (iOS `NavigationTrack`).
 *
 * Positions on the track are expressed two ways, both increasing from the first point to the last:
 * - [Position.index]: the track-point index plus the fraction toward the next point (`3.25` is a
 *   quarter of the way from point 3 to point 4). Step targeting compares these.
 * - [Position.distanceAlongTrack]: metres from the first point. Used wherever a tolerance in metres
 *   is needed (dead bands, search windows).
 */
class NavigationTrack private constructor(
    val points: List<Coordinate>,
    /** `cumulativeMeters[i]` = distance along the track from `points[0]` to `points[i]`. */
    val cumulativeMeters: DoubleArray,
) {
    data class Position(
        /** The point on the track. */
        val point: Coordinate,
        /** Segment `i` joins `points[i]` and `points[i + 1]`. */
        val segmentIndex: Int,
        /** 0..1 along that segment. */
        val fraction: Double,
        /** Metres from the first track point. */
        val distanceAlongTrack: Double,
        /** Metres between the projected location and [point]. */
        val crossDistance: Double,
    ) {
        /** Track-point index with the fraction toward the next point. */
        val index: Double get() = segmentIndex + fraction
    }

    val segmentCount: Int get() = points.size - 1
    val totalLength: Double get() = cumulativeMeters.last()

    /**
     * The closest position on the track to [location].
     *
     * [segments] restricts the search (inclusive segment indices); the whole track when null. When
     * several segments are almost equally close (an out-and-back on the same road), the earliest
     * one within [preferEarliestWithin] metres of the best distance wins; 0 picks the strict closest.
     */
    fun project(location: Coordinate, segments: IntRange? = null, preferEarliestWithin: Double = 0.0): Position? {
        val first = max(0, segments?.first ?: 0)
        val last = min(segmentCount - 1, segments?.last ?: (segmentCount - 1))
        if (first > last) return null

        val distances = DoubleArray(last - first + 1)
        var best = Double.POSITIVE_INFINITY
        for (i in first..last) {
            val hit = projectOnSegment(location, points[i], points[i + 1])
            val d = location.distanceMeters(hit.second)
            distances[i - first] = d
            if (d < best) best = d
        }
        if (!best.isFinite()) return null

        val limit = best + max(0.0, preferEarliestWithin)
        val chosen = (first..last).firstOrNull { distances[it - first] <= limit } ?: return null
        val (t, point) = projectOnSegment(location, points[chosen], points[chosen + 1])
        val segmentLength = cumulativeMeters[chosen + 1] - cumulativeMeters[chosen]
        return Position(
            point = point,
            segmentIndex = chosen,
            fraction = t,
            distanceAlongTrack = cumulativeMeters[chosen] + t * segmentLength,
            crossDistance = distances[chosen - first],
        )
    }

    /** The segments overlapping `[center - before, center + after]` metres along the track. */
    fun segments(center: Double, before: Double, after: Double): IntRange {
        val low = max(0.0, center - before)
        val high = min(totalLength, center + after)
        val first = segmentIndexAt(low)
        val last = segmentIndexAt(high)
        return first..max(first, last)
    }

    /**
     * The track position of each step, in step order.
     *
     * Each step is searched only from the previous step's segment onward, and ties go to the earliest
     * segment, so on an out-and-back itinerary the outbound steps land on the outbound leg and the
     * return steps on the return leg. Steps without a coordinate get null.
     */
    fun stepIndices(coordinates: List<Coordinate?>): List<Double?> {
        var lowerSegment = 0
        return coordinates.map { coordinate ->
            if (coordinate == null) return@map null
            val position = project(coordinate, lowerSegment..(segmentCount - 1), STEP_TIE_METERS) ?: return@map null
            lowerSegment = position.segmentIndex
            position.index
        }
    }

    /** Metres along the track for a track-point index (see [Position.index]). */
    fun distanceAtIndex(index: Double): Double {
        val clamped = max(0.0, min(segmentCount.toDouble(), index))
        val segment = min(segmentCount - 1, clamped.toInt())
        val fraction = clamped - segment
        val length = cumulativeMeters[segment + 1] - cumulativeMeters[segment]
        return cumulativeMeters[segment] + fraction * length
    }

    /** The last segment whose start is at or before [distance]. */
    private fun segmentIndexAt(distance: Double): Int {
        var low = 0
        var high = segmentCount - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (cumulativeMeters[mid] <= distance) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        /** Steps whose distances to two segments differ by less than this go to the earlier one. */
        const val STEP_TIE_METERS = 25.0

        /** Needs at least two points; null otherwise. */
        fun of(points: List<Coordinate>): NavigationTrack? {
            if (points.size < 2) return null
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) {
                cumulative[i] = cumulative[i - 1] + points[i - 1].distanceMeters(points[i])
            }
            return NavigationTrack(points, cumulative)
        }

        /** Local equirectangular projection of [p] on the segment a-b: the fraction and the point. */
        private fun projectOnSegment(p: Coordinate, a: Coordinate, b: Coordinate): Pair<Double, Coordinate> {
            val cosLat = cos((a.latitude + b.latitude) * 0.5 * RAD)
            val bx = (b.longitude - a.longitude) * cosLat
            val by = b.latitude - a.latitude
            val px = (p.longitude - a.longitude) * cosLat
            val py = p.latitude - a.latitude
            val lengthSq = bx * bx + by * by
            val t = if (lengthSq < 1e-18) 0.0 else max(0.0, min(1.0, (px * bx + py * by) / lengthSq))
            return t to Coordinate(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
        }

        private const val RAD = Math.PI / 180.0

        /** [from] moved [meters] along [bearing] (degrees, 0 = north). Flat earth: fine for tens of metres. */
        fun offset(from: Coordinate, bearing: Double, meters: Double): Coordinate {
            val metersPerDegreeLat = 111_320.0
            val metersPerDegreeLon = metersPerDegreeLat * max(1e-6, cos(from.latitude * RAD))
            return Coordinate(
                from.latitude + meters * cos(bearing * RAD) / metersPerDegreeLat,
                from.longitude + meters * sin(bearing * RAD) / metersPerDegreeLon,
            )
        }
    }
}
