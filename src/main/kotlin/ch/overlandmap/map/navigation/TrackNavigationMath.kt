package ch.overlandmap.map.navigation

import ch.overlandmap.map.geo.Coordinate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class CrossTrackSide { NONE, LEFT, RIGHT }

/** Where to head to stay on a track (iOS `TrackNavigationGuidance`). */
data class TrackNavigationGuidance(
    /** The point on the track closest to the current location. */
    val referencePoint: Coordinate,
    /** The point 100 m further along the track in the travel direction. */
    val lookaheadPoint: Coordinate,
    /** Bearing from the current location to the lookahead point, in [0, 360). */
    val trackBearing: Double,
    /** Distance in metres between the current location and the reference point. */
    val crossTrackDistanceMeters: Double,
    /** Which side of the track the location is on; only set beyond 20 m. */
    val crossTrackSide: CrossTrackSide,
)

/** Projection of a location on a polyline, and track guidance from it (iOS `TrackNavigationMath`). */
object TrackNavigationMath {

    private const val EARTH_RADIUS_M = 6371000.0
    private const val RAD = Math.PI / 180.0

    class TrackProjection(
        val point: Coordinate,
        val segmentIndex: Int,
        val fraction: Double,
        /** Metres from the start of the track to [point]. */
        val distanceAlongTrack: Double,
        /** Metres between the location and [point]. */
        val crossDistance: Double,
    )

    /** The point of [track] closest to [location], on a local flat approximation per segment. */
    fun closestTrackProjection(location: Coordinate, track: List<Coordinate>): TrackProjection? {
        if (track.size < 2) return null
        var bestSegment = 0
        var bestFraction = 0.0
        var bestPoint = track[0]
        var minDistanceSq = Double.POSITIVE_INFINITY
        val segmentLengths = ArrayList<Double>(track.size - 1)
        for (i in 0 until track.size - 1) {
            val a = track[i]
            val b = track[i + 1]
            val midLat = (a.latitude + b.latitude) * 0.5 * RAD
            val cosMid = cos(midLat)
            val xb = (b.longitude - a.longitude) * RAD * cosMid * EARTH_RADIUS_M
            val yb = (b.latitude - a.latitude) * RAD * EARTH_RADIUS_M
            val segLenSq = xb * xb + yb * yb
            segmentLengths += sqrt(segLenSq)
            val xu = (location.longitude - a.longitude) * RAD * cosMid * EARTH_RADIUS_M
            val yu = (location.latitude - a.latitude) * RAD * EARTH_RADIUS_M
            val t = if (segLenSq < 1e-6) 0.0 else max(0.0, min(1.0, (xu * xb + yu * yb) / segLenSq))
            val qx = t * xb
            val qy = t * yb
            val distSq = (xu - qx) * (xu - qx) + (yu - qy) * (yu - qy)
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                bestSegment = i
                bestFraction = t
                bestPoint = Coordinate(
                    a.latitude + (qy / EARTH_RADIUS_M) / RAD,
                    a.longitude + (qx / (EARTH_RADIUS_M * cosMid)) / RAD,
                )
            }
        }
        var cumulative = 0.0
        for (i in 0 until bestSegment) cumulative += segmentLengths[i]
        cumulative += bestFraction * segmentLengths[bestSegment]
        return TrackProjection(bestPoint, bestSegment, bestFraction, cumulative, location.distanceMeters(bestPoint))
    }

    /**
     * The step to head for when navigation starts: the first one after the user's position along
     * the track (the last one before it when [reverse]). Further than [maxDistanceMeters] from the
     * track, the start of the journey is chosen.
     */
    fun initialTargetStepIndex(
        userLocation: Coordinate,
        track: List<Coordinate>,
        stepCoordinates: List<Coordinate>,
        reverse: Boolean,
        maxDistanceMeters: Double = 30_000.0,
    ): Int {
        if (stepCoordinates.size <= 1) return 0
        val effectiveTrack = if (track.size >= 2) track else stepCoordinates
        val start = if (reverse) stepCoordinates.lastIndex else 0
        val userProjection = closestTrackProjection(userLocation, effectiveTrack) ?: return start
        if (userProjection.crossDistance > maxDistanceMeters) return start
        val stepDistances = stepCoordinates.map { closestTrackProjection(it, effectiveTrack)?.distanceAlongTrack ?: 0.0 }
        val ref = userProjection.distanceAlongTrack
        return if (!reverse) {
            if (ref <= stepDistances.first()) 0
            else stepCoordinates.indices.firstOrNull { stepDistances[it] > ref } ?: stepCoordinates.lastIndex
        } else {
            if (ref >= stepDistances.last()) stepCoordinates.lastIndex
            else stepCoordinates.indices.lastOrNull { stepDistances[it] < ref } ?: 0
        }
    }

    /**
     * Guidance along [track]: the closest point, the point 100 m ahead in the travel direction, the
     * bearing to it, and — beyond 20 m off the track — on which side the user is.
     */
    fun computeGuidance(location: Coordinate, track: List<Coordinate>, reverse: Boolean): TrackNavigationGuidance? {
        if (track.size < 2) return null
        val projection = closestTrackProjection(location, track) ?: return null
        val reference = projection.point
        val lookahead = findLookaheadPoint(projection.segmentIndex, reference, 100.0, track, reverse)

        val tangentBearing = if (reference.distanceMeters(lookahead) > 0.5) {
            reference.bearing(lookahead)
        } else if (!reverse) {
            val last = max(0, track.size - 2)
            track[last].bearing(track[last + 1])
        } else {
            track[1].bearing(track[0])
        }
        // The arrow points from where the user is to the point 100 m ahead on the track.
        val trackBearing = if (location.distanceMeters(lookahead) > 0.5) location.bearing(lookahead) else tangentBearing

        val cross = projection.crossDistance
        val side = if (cross > 20.0) {
            var diff = (reference.bearing(location) - tangentBearing) % 360
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            if (diff < 0) CrossTrackSide.LEFT else CrossTrackSide.RIGHT
        } else {
            CrossTrackSide.NONE
        }
        return TrackNavigationGuidance(reference, lookahead, trackBearing, cross, side)
    }

    private fun findLookaheadPoint(
        fromSegment: Int,
        reference: Coordinate,
        distanceMeters: Double,
        track: List<Coordinate>,
        reverse: Boolean,
    ): Coordinate {
        if (!reverse) {
            val segEnd = track[fromSegment + 1]
            val remOnSeg = reference.distanceMeters(segEnd)
            if (remOnSeg >= distanceMeters) {
                return interpolate(reference, segEnd, min(1.0, if (remOnSeg > 0) distanceMeters / remOnSeg else 1.0))
            }
            var remaining = distanceMeters - remOnSeg
            for (seg in fromSegment + 1 until track.size - 1) {
                val p1 = track[seg]
                val p2 = track[seg + 1]
                val len = p1.distanceMeters(p2)
                if (len >= remaining) return interpolate(p1, p2, min(1.0, if (len > 0) remaining / len else 1.0))
                remaining -= len
            }
            return track.last()
        }
        val segStart = track[fromSegment]
        val remOnSeg = reference.distanceMeters(segStart)
        if (remOnSeg >= distanceMeters) {
            return interpolate(reference, segStart, min(1.0, if (remOnSeg > 0) distanceMeters / remOnSeg else 1.0))
        }
        var remaining = distanceMeters - remOnSeg
        for (seg in fromSegment - 1 downTo 0) {
            val p2 = track[seg + 1]
            val p1 = track[seg]
            val len = p2.distanceMeters(p1)
            if (len >= remaining) return interpolate(p2, p1, min(1.0, if (len > 0) remaining / len else 1.0))
            remaining -= len
        }
        return track.first()
    }

    private fun interpolate(a: Coordinate, b: Coordinate, fraction: Double) = Coordinate(
        a.latitude + (b.latitude - a.latitude) * fraction,
        a.longitude + (b.longitude - a.longitude) * fraction,
    )

    /** The signed difference between two bearings, in [-180, 180]. */
    fun relativeBearing(target: Double, from: Double): Double {
        var diff = (target - from) % 360
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }

    /** True when [a] is within [tolerance] metres of [b] (test helper for geometry). */
    internal fun near(a: Coordinate, b: Coordinate, tolerance: Double) = abs(a.distanceMeters(b)) <= tolerance
}
