package ch.overlandmap.map.planning

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Port of the web `route-geometry.ts` (iOS `RouteGeometry.swift`): projection of a flag onto a
 * segment, and trimming of the first and last segment of a trip at the flags.
 */

private const val RADIANS = Math.PI / 180
private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val BLOCK_SIZE = 64

internal fun routeMeters(a: RoutePoint, b: RoutePoint): Double {
    val dLat = (b.latitude - a.latitude) * RADIANS
    val dLon = (b.longitude - a.longitude) * RADIANS
    val h = sin(dLat / 2).pow(2) +
        cos(a.latitude * RADIANS) * cos(b.latitude * RADIANS) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}

/** Polyline of one segment, from its `start_node` to its `end_node`. */
class SegmentGeometry private constructor(
    val coordinates: List<RoutePoint>,
    internal val cumulative: DoubleArray,
    private val blocks: List<Block>,
) {
    private class Block(val from: Int, val to: Int, val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)

    val total: Double get() = cumulative[cumulative.size - 1]

    /**
     * Projects [point] in a local metre-scale plane. Blocks of vertices whose bounding box is
     * farther than the best candidate are skipped, so dragging stays cheap.
     */
    fun project(point: RoutePoint): RouteProjection {
        val xScale = max(1e-9, cos(point.latitude * RADIANS))
        var bestDistance = Double.POSITIVE_INFINITY
        var best: RouteProjection? = null
        for (block in blocks) {
            val dx = max(max(block.minX - point.longitude, 0.0), point.longitude - block.maxX) * xScale
            val dy = max(max(block.minY - point.latitude, 0.0), point.latitude - block.maxY)
            if (dx * dx + dy * dy > bestDistance) continue
            for (index in block.from until block.to) {
                val a = coordinates[index]
                val b = coordinates[index + 1]
                val ax = (a.longitude - point.longitude) * xScale
                val ay = a.latitude - point.latitude
                val bx = (b.longitude - a.longitude) * xScale
                val by = b.latitude - a.latitude
                val denominator = bx * bx + by * by
                val fraction = if (denominator > 0) max(0.0, min(1.0, -(ax * bx + ay * by) / denominator)) else 0.0
                val distance = (ax + bx * fraction).pow(2) + (ay + by * fraction).pow(2)
                if (distance < bestDistance) {
                    val coordinate = RoutePoint(
                        longitude = a.longitude + (b.longitude - a.longitude) * fraction,
                        latitude = a.latitude + (b.latitude - a.latitude) * fraction,
                    )
                    bestDistance = distance
                    val edgeLength = cumulative[index + 1] - cumulative[index]
                    best = RouteProjection(
                        coordinate = coordinate,
                        index = index,
                        fraction = fraction,
                        along = cumulative[index] + min(edgeLength, routeMeters(a, coordinate)),
                    )
                }
            }
        }
        return best ?: RouteProjection(coordinates[0], 0, 0.0, 0.0)
    }

    internal fun slice(first: RouteProjection, last: RouteProjection): List<RoutePoint> {
        if (first.along > last.along) return slice(last, first).reversed()
        val output = ArrayList<RoutePoint>()
        output += first.coordinate
        if (last.index >= first.index + 1) {
            for (index in (first.index + 1)..last.index) output += coordinates[index]
        }
        if (output.last() != last.coordinate) output += last.coordinate
        return output
    }

    /** `FORWARD` is the end of the polyline, `REVERSE` its start. */
    internal fun endpoint(end: TravelDirection): RouteProjection = when (end) {
        TravelDirection.REVERSE -> RouteProjection(coordinates[0], 0, 0.0, 0.0)
        TravelDirection.FORWARD -> RouteProjection(coordinates[coordinates.size - 1], coordinates.size - 2, 1.0, total)
    }

    companion object {
        /** Null when the polyline has fewer than two valid points. */
        fun create(coordinates: List<RoutePoint>): SegmentGeometry? {
            if (coordinates.size < 2 || !coordinates.all { it.longitude.isFinite() && it.latitude.isFinite() }) return null
            val cumulative = DoubleArray(coordinates.size)
            for (index in 1 until coordinates.size) {
                cumulative[index] = cumulative[index - 1] + routeMeters(coordinates[index - 1], coordinates[index])
            }
            val blocks = ArrayList<Block>()
            var from = 0
            while (from < coordinates.size - 1) {
                val to = min(coordinates.size - 1, from + BLOCK_SIZE)
                var minX = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY
                var minY = Double.POSITIVE_INFINITY
                var maxY = Double.NEGATIVE_INFINITY
                for (index in from..to) {
                    minX = min(minX, coordinates[index].longitude)
                    maxX = max(maxX, coordinates[index].longitude)
                    minY = min(minY, coordinates[index].latitude)
                    maxY = max(maxY, coordinates[index].latitude)
                }
                blocks += Block(from, to, minX, maxX, minY, maxY)
                from += BLOCK_SIZE
            }
            return SegmentGeometry(coordinates, cumulative, blocks)
        }
    }
}

class RouteProjection(
    val coordinate: RoutePoint,
    val index: Int,
    val fraction: Double,
    /** Distance along the segment from its first vertex. */
    val along: Double,
)

/** A piece of a trip, in travel order. A single-coordinate fragment is a point. */
data class RouteFragment(val segmentId: String, val coordinates: List<RoutePoint>)

class TrimmedRoute(
    /** The first and last traversals, cut at the flags. */
    val fragments: List<RouteFragment>,
    /** Length of every traversal in metres, the first and last ones trimmed. */
    val lengths: List<Double>,
    val distanceMeters: Double,
)

object RouteGeometryBuilder {

    /**
     * Trims the first and last traversal at the first and last flag. Null when the geometry of
     * either end segment is not on the device (or the trip has no flags).
     */
    fun trim(trip: PlannedTrip, graph: RouteGraph): TrimmedRoute? {
        val ids = trip.segmentIds
        if (ids.isEmpty()) return TrimmedRoute(emptyList(), emptyList(), 0.0)
        val vertices = RoutePlanner.vertices(trip, graph) ?: return null
        val points = trip.waypoints ?: return null
        val firstPoint = points.firstOrNull() ?: return null
        val lastPoint = points.last()
        val first = graph.geometry(ids[0]) ?: return null
        val last = graph.geometry(ids[ids.size - 1]) ?: return null

        val firstProjection = first.project(firstPoint.coordinate)
        val lastProjection = last.project(lastPoint.coordinate)
        val lengths = ids.map { graph.segments[it]?.lengthMeters ?: 0.0 }.toMutableList()
        val fragments = ArrayList<RouteFragment>()

        if (ids.size == 1) {
            fragments += RouteFragment(ids[0], first.slice(firstProjection, lastProjection))
            lengths[0] = if (first.total > 0) lengths[0] * abs(lastProjection.along - firstProjection.along) / first.total else 0.0
        } else {
            val lastIndex = ids.size - 1
            val startDirection = firstPoint.direction
                ?: if (graph.segments[ids[0]]?.startNode == vertices[0]) TravelDirection.FORWARD else TravelDirection.REVERSE
            val endDirection = lastPoint.direction
                ?: if (graph.segments[ids[lastIndex]]?.startNode == vertices[lastIndex]) TravelDirection.FORWARD else TravelDirection.REVERSE
            val firstEnd = first.endpoint(startDirection)
            // The last traversal begins at the end opposite to its travel direction.
            val lastStart = last.endpoint(
                if (endDirection == TravelDirection.FORWARD) TravelDirection.REVERSE else TravelDirection.FORWARD,
            )
            fragments += RouteFragment(ids[0], first.slice(firstProjection, firstEnd))
            fragments += RouteFragment(ids[lastIndex], last.slice(lastStart, lastProjection))
            lengths[0] = if (first.total > 0) lengths[0] * abs(firstEnd.along - firstProjection.along) / first.total else 0.0
            lengths[lastIndex] =
                if (last.total > 0) lengths[lastIndex] * abs(lastProjection.along - lastStart.along) / last.total else 0.0
        }
        return TrimmedRoute(fragments, lengths, lengths.sum())
    }
}
