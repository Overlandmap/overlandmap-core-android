package ch.overlandmap.map.bluetooth

import ch.overlandmap.map.geo.Coordinate

/** Same 14 m/s base speed, interpolation and travel bearing as iOS TrackPlayback. */
class TrackPlayback(points: List<Coordinate>, backwards: Boolean) {
    data class Sample(val coordinate: Coordinate, val course: Float, val speed: Float, val progress: Double)
    private val path = (if (backwards) points.reversed() else points).fold(mutableListOf<Coordinate>()) { out, p ->
        require(p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0) { "Invalid track coordinate." }
        out.apply { if (lastOrNull()?.distanceMeters(p) != 0.0) add(p) }
    }
    private val lengths = path.zipWithNext { a, b -> a.distanceMeters(b) }
    private val total = lengths.sum()
    private var segment = 0
    private var along = 0.0
    private var travelled = 0.0
    val finished get() = travelled >= total
    init { require(path.size >= 2 && total > 0) { "The track needs two distinct points." } }
    fun advance(seconds: Double, multiplier: Int): Sample {
        require(multiplier in listOf(1, 5, 50))
        val distance = seconds.coerceAtLeast(0.0) * 14 * multiplier
        travelled = (travelled + distance).coerceAtMost(total)
        along += distance
        while (segment < lengths.lastIndex && along >= lengths[segment]) { along -= lengths[segment]; segment++ }
        val a = path[segment]; val b = path[segment + 1]
        val fraction = (along / lengths[segment]).coerceIn(0.0, 1.0)
        return Sample(Coordinate(a.latitude + (b.latitude - a.latitude) * fraction,
            a.longitude + (b.longitude - a.longitude) * fraction), a.bearing(b).toFloat(),
            if (finished) 0f else 14f * multiplier, travelled / total)
    }
}
