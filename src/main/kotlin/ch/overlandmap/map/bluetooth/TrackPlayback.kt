package ch.overlandmap.map.bluetooth

import ch.overlandmap.map.geo.Coordinate

/** Same 14 m/s base speed, interpolation and travel bearing as iOS TrackPlayback. */
class TrackPlayback(points: List<Coordinate>, private val backwards: Boolean, elevations: List<Double?> = List(points.size) { null }) {
    data class Sample(val coordinate: Coordinate, val course: Float, val speed: Float, val progress: Double, val altitude: Double?)
    private data class Point(val coordinate: Coordinate, val altitude: Double?)
    private val path = points.mapIndexed { index, coordinate -> Point(coordinate, elevations.getOrNull(index)?.takeIf { it.isFinite() }) }
        .let { if (backwards) it.reversed() else it }.fold(mutableListOf<Point>()) { out, p ->
        require(p.coordinate.latitude in -90.0..90.0 && p.coordinate.longitude in -180.0..180.0) { "Invalid track coordinate." }
        out.apply { if (lastOrNull()?.coordinate?.distanceMeters(p.coordinate) != 0.0) add(p) }
    }
    private val lengths = path.zipWithNext { a, b -> a.coordinate.distanceMeters(b.coordinate) }
    private val total = lengths.sum()
    private var segment = 0
    private var along = 0.0
    private var travelled = 0.0
    val finished get() = travelled >= total
    init { require(path.size >= 2 && total > 0) { "The track needs two distinct points." } }
    fun seekToOriginalDistance(distance: Double) {
        val target = (if (backwards) total - distance else distance).coerceIn(0.0, total)
        travelled = target
        segment = 0
        along = target
        while (segment < lengths.lastIndex && along >= lengths[segment]) { along -= lengths[segment]; segment++ }
    }
    fun advance(seconds: Double, multiplier: Int): Sample {
        require(multiplier in listOf(1, 5, 50))
        val distance = seconds.coerceAtLeast(0.0) * 14 * multiplier
        travelled = (travelled + distance).coerceAtMost(total)
        along += distance
        while (segment < lengths.lastIndex && along >= lengths[segment]) { along -= lengths[segment]; segment++ }
        val a = path[segment]; val b = path[segment + 1]
        val fraction = (along / lengths[segment]).coerceIn(0.0, 1.0)
        val altitude = when {
            fraction <= 0.0 -> a.altitude
            fraction >= 1.0 -> b.altitude
            a.altitude != null && b.altitude != null -> a.altitude + (b.altitude - a.altitude) * fraction
            else -> null
        }
        return Sample(Coordinate(a.coordinate.latitude + (b.coordinate.latitude - a.coordinate.latitude) * fraction,
            a.coordinate.longitude + (b.coordinate.longitude - a.coordinate.longitude) * fraction), a.coordinate.bearing(b.coordinate).toFloat(),
            if (finished) 0f else 14f * multiplier, travelled / total, altitude)
    }
}
