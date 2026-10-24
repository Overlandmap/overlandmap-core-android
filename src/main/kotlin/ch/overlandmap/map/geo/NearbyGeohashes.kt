package ch.overlandmap.map.geo

import kotlin.math.*

/** Covers the entire search circle, including neighbouring cells, date-line crossings and poles. */
object NearbyGeohashes {
    fun cover(origin: Coordinate, radiusKm: Double): List<String> {
        require(origin.latitude in -90.0..90.0 && origin.longitude in -180.0..180.0)
        require(radiusKm.isFinite() && radiusKm > 0)
        val angle = (radiusKm / Coordinate.EARTH_RADIUS_KM).coerceAtMost(PI)
        val latitude = Math.toRadians(origin.latitude)
        val south = Math.toDegrees(latitude - angle).coerceAtLeast(-90.0)
        val north = Math.toDegrees(latitude + angle).coerceAtMost(90.0)
        val longitudeSpan = if (south <= -90 || north >= 90 || angle >= PI / 2) 180.0
            else Math.toDegrees(asin((sin(angle) / cos(latitude)).coerceIn(-1.0, 1.0)))
        val west = origin.longitude - longitudeSpan
        val east = origin.longitude + longitudeSpan
        val longitudeRanges = when {
            longitudeSpan >= 180 -> listOf(-180.0 to 180.0)
            west < -180 -> listOf(west + 360 to 180.0, -180.0 to east)
            east > 180 -> listOf(west to 180.0, -180.0 to east - 360)
            else -> listOf(west to east)
        }
        var precision = Geohash.prefixLength(radiusKm)
        while (true) {
            val bits = precision * 5
            val latCells = 1 shl (bits / 2)
            val lonCells = 1 shl ((bits + 1) / 2)
            val latStep = 180.0 / latCells
            val lonStep = 360.0 / lonCells
            fun latIndex(v: Double) = floor((v + 90) / latStep).toInt().coerceIn(0, latCells - 1)
            fun lonIndex(v: Double) = floor((v + 180) / lonStep).toInt().coerceIn(0, lonCells - 1)
            val latRange = latIndex(south)..latIndex(north)
            val lonRanges = longitudeRanges.map { lonIndex(it.first)..lonIndex(it.second) }
            val count = latRange.count().toLong() * lonRanges.sumOf { it.count().toLong() }
            if (count > 64 && precision > 1) { precision--; continue }
            return buildSet {
                for (lat in latRange) for (range in lonRanges) for (lon in range) {
                    add(Geohash.encode(-90 + (lat + 0.5) * latStep, -180 + (lon + 0.5) * lonStep, precision))
                }
            }.sortedBy { origin.distanceKm(Geohash.decode(it)!!) }
        }
    }
}
