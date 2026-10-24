package ch.overlandmap.map.geo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NearbyGeohashesTest {
    @Test fun coversCircleAcrossCellsDateLineAndPoles() {
        val origins = listOf(Coordinate(46.2, 6.1), Coordinate(0.0, 0.0), Coordinate(0.0, 179.99),
            Coordinate(0.0, -179.99), Coordinate(89.99, 45.0), Coordinate(-89.99, -180.0))
        for (origin in origins) for (radius in listOf(0.01, 1.0, 50.0, 1000.0, 20000.0)) {
            val cover = NearbyGeohashes.cover(origin, radius)
            assertTrue(cover.size <= 64)
            assertEquals(cover.size, cover.distinct().size)
            for (bearing in 0 until 360 step 5) {
                val point = destination(origin, radius * 0.999, bearing.toDouble())
                assertTrue("Missed $point from $origin at $radius km", cover.any { Geohash.encode(point.latitude, point.longitude).startsWith(it) })
            }
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroRadius() {
        NearbyGeohashes.cover(Coordinate(0.0, 0.0), 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteRadius() {
        NearbyGeohashes.cover(Coordinate(0.0, 0.0), Double.NaN)
    }
    private fun destination(origin: Coordinate, km: Double, bearing: Double): Coordinate {
        val lat = Math.toRadians(origin.latitude); val lon = Math.toRadians(origin.longitude)
        val angle = km / Coordinate.EARTH_RADIUS_KM; val direction = Math.toRadians(bearing)
        val newLat = asin(sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(direction))
        val newLon = lon + atan2(sin(direction) * sin(angle) * cos(lat), cos(angle) - sin(lat) * sin(newLat))
        return Coordinate(Math.toDegrees(newLat), ((Math.toDegrees(newLon) + 540) % 360) - 180)
    }
}
