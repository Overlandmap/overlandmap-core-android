package ch.overlandmap.map.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Vectors from the iOS geo tests plus well-known geohash reference values. */
class GeohashTest {

    @Test
    fun knownReferenceValues() {
        // Standard published examples (Wikipedia / geohash.org).
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, precision = 11))
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, precision = 5))
    }

    @Test
    fun roundTripAtPrecision9() {
        // iOS geohashRoundTrip vector.
        val hash = Geohash.encode(23.202766, 57.513833)
        assertEquals(9, hash.length)
        val back = Geohash.decode(hash)!!
        assertEquals(23.202766, back.latitude, 0.001)
        assertEquals(57.513833, back.longitude, 0.001)
    }

    @Test
    fun decodeIsCaseInsensitiveAndRejectsInvalid() {
        assertNotNull(Geohash.boundingBox("EZS42"))
        assertNull(Geohash.boundingBox("ezs4a")) // 'a' is not in the alphabet
        assertNull(Geohash.boundingBox(""))
        assertNull(Geohash.decode(null))
    }

    @Test
    fun boundingBoxContainsItsCentre() {
        val box = Geohash.boundingBox("u4pruyd")!!
        assertTrue(box.contains(box.centre))
    }

    @Test
    fun distanceBetweenHashes() {
        val a = Geohash.encode(0.0, 0.0)
        val b = Geohash.encode(1.0, 0.0)
        assertEquals(111.19, Geohash.distanceKm(a, b)!!, 0.05)
        assertNull(Geohash.distanceKm(a, "!"))
    }

    @Test
    fun prefixLengthTable() {
        assertEquals(9, Geohash.prefixLength(0.01))
        assertEquals(7, Geohash.prefixLength(0.1))
        assertEquals(6, Geohash.prefixLength(0.5))
        assertEquals(5, Geohash.prefixLength(4.9))
        assertEquals(4, Geohash.prefixLength(10.0))
        assertEquals(3, Geohash.prefixLength(100.0))
        assertEquals(2, Geohash.prefixLength(999.0))
        assertEquals(1, Geohash.prefixLength(5000.0))
    }
}

class CoordinateTest {

    @Test
    fun oneDegreeOfLatitudeIs111Km() {
        val d = Coordinate(0.0, 0.0).distanceKm(Coordinate(1.0, 0.0))
        assertEquals(111.19, d, 0.05)
        assertEquals(d * 1000, Coordinate(0.0, 0.0).distanceMeters(Coordinate(1.0, 0.0)), 1e-6)
    }

    @Test
    fun bearings() {
        val o = Coordinate(0.0, 0.0)
        assertEquals(0.0, o.bearing(Coordinate(1.0, 0.0)), 1e-6)
        assertEquals(90.0, o.bearing(Coordinate(0.0, 1.0)), 1e-6)
        assertEquals(180.0, o.bearing(Coordinate(-1.0, 0.0)), 1e-6)
        assertEquals(270.0, o.bearing(Coordinate(0.0, -1.0)), 1e-6)
    }

    @Test
    fun cardinalDirections() {
        assertEquals("N", Coordinate.cardinalDirection(0.0))
        assertEquals("N", Coordinate.cardinalDirection(359.0))
        assertEquals("NNE", Coordinate.cardinalDirection(22.5))
        assertEquals("E", Coordinate.cardinalDirection(90.0))
        assertEquals("S", Coordinate.cardinalDirection(180.0))
        assertEquals("W", Coordinate.cardinalDirection(-90.0))
    }
}

class GeoBoundsTest {

    @Test
    fun containingWidensDegenerateAxes() {
        val b = GeoBounds.containing(listOf(Coordinate(10.0, 20.0)))!!
        assertEquals(9.995, b.latMin, 1e-9)
        assertEquals(10.005, b.latMax, 1e-9)
        assertEquals(19.995, b.lonMin, 1e-9)
        assertEquals(20.005, b.lonMax, 1e-9)
        assertNull(GeoBounds.containing(emptyList()))
    }

    @Test
    fun containingCoversAllPoints() {
        val b = GeoBounds.containing(
            listOf(Coordinate(10.0, 20.0), Coordinate(30.0, 5.0), Coordinate(-5.0, 40.0)),
        )!!
        assertEquals(-5.0, b.latMin, 0.0)
        assertEquals(30.0, b.latMax, 0.0)
        assertEquals(5.0, b.lonMin, 0.0)
        assertEquals(40.0, b.lonMax, 0.0)
    }

    @Test
    fun unionAndPadding() {
        val u = GeoBounds.union(listOf(GeoBounds(0.0, 1.0, 0.0, 1.0), GeoBounds(2.0, 3.0, -1.0, 0.5)))!!
        assertEquals(GeoBounds(0.0, 3.0, -1.0, 1.0), u)
        assertNull(GeoBounds.union(emptyList()))

        val p = GeoBounds(0.0, 10.0, 0.0, 20.0).padded(0.1)
        assertEquals(-1.0, p.latMin, 1e-9)
        assertEquals(11.0, p.latMax, 1e-9)
        assertEquals(-2.0, p.lonMin, 1e-9)
        assertEquals(22.0, p.lonMax, 1e-9)
        // Degenerate box still frames.
        val d = GeoBounds(5.0, 5.0, 5.0, 5.0).padded()
        assertEquals(4.99, d.latMin, 1e-9)
    }

    @Test
    fun expandedByKm() {
        val b = GeoBounds(0.0, 0.0, 0.0, 0.0).expanded(byKm = 111.0)
        assertEquals(-1.0, b.latMin, 1e-9)
        assertEquals(1.0, b.latMax, 1e-9)
        assertTrue(b.contains(Coordinate(0.5, 0.5)))
        assertFalse(b.contains(Coordinate(2.0, 0.0)))
    }
}

class TrackGeometryTest {

    private fun dartEncoded(vararg triples: Triple<Double, Double, Double>): String =
        TrackGeometry.encode(triples.map { (lat, lon, ele) -> TrackSample(lat, lon, ele) })

    @Test
    fun decodesTriplesInDartOrder() {
        val points = TrackGeometry.decode(
            dartEncoded(Triple(42.87, 74.59, 800.0), Triple(42.88, 74.60, 850.0)),
        )
        assertEquals(2, points.size)
        // Latitude first: the opposite of GeoJSON.
        assertEquals(42.87, points[0].latitude, 0.001)
        assertEquals(74.59, points[0].longitude, 0.001)
        assertEquals(850.0, points[1].elevation!!, 0.5)
    }

    @Test
    fun zeroElevationIsUnknown() {
        val points = TrackGeometry.decode(dartEncoded(Triple(10.0, 20.0, 0.0), Triple(11.0, 21.0, 100.0)))
        assertNull(points[0].elevation)
        assertNotNull(points[1].elevation)
    }

    @Test
    fun cumulativeDistance() {
        val points = TrackGeometry.decode(
            dartEncoded(Triple(0.0, 0.0, 0.0), Triple(1.0, 0.0, 0.0), Triple(2.0, 0.0, 0.0)),
        )
        assertEquals(0.0, points[0].distanceKm, 0.0)
        assertEquals(111.2, points[1].distanceKm, 1.0)
        assertEquals(222.4, points[2].distanceKm, 1.0)
        assertEquals(222.4, TrackGeometry.lengthKm(points), 1.0)
    }

    @Test
    fun malformedInputsDecodeToNothing() {
        assertTrue(TrackGeometry.decode(null as String?).isEmpty())
        assertTrue(TrackGeometry.decode("").isEmpty())
        assertTrue(TrackGeometry.decode("not base64 at all!!").isEmpty())
        // Four floats = one triple plus a stray value: the partial point is dropped.
        val partial = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(1f).putFloat(2f).putFloat(3f).putFloat(4f).array(),
        )
        assertEquals(1, TrackGeometry.decode(partial).size)
    }

    @Test
    fun roundTripsThroughEncoder() {
        val original = listOf(
            TrackSample(42.5, 74.25, 1200.0),
            TrackSample(42.6, 74.30, null),
        )
        val decoded = TrackGeometry.decode(TrackGeometry.encode(original))
        assertEquals(2, decoded.size)
        assertEquals(42.5, decoded[0].latitude, 0.001)
        assertNull(decoded[1].elevation)
    }

    @Test
    fun boundsCoverEveryPoint() {
        val points = TrackGeometry.decode(
            dartEncoded(Triple(10.0, 20.0, 0.0), Triple(30.0, 5.0, 0.0), Triple(-5.0, 40.0, 0.0)),
        )
        val b = TrackGeometry.bounds(points)!!
        assertEquals(-5.0, b.latMin, 0.01)
        assertEquals(30.0, b.latMax, 0.01)
        assertEquals(5.0, b.lonMin, 0.01)
        assertEquals(40.0, b.lonMax, 0.01)
    }

    @Test
    fun simplifyKeepsEndpointsAndDropsCollinearPoints() {
        val points = (0 until 50).map { TrackSample(it.toDouble(), 0.0) }
        val simplified = TrackGeometry.simplify(points, tolerance = 0.1)
        assertEquals(2, simplified.size)
        assertEquals(0.0, simplified.first().latitude, 0.0)
        assertEquals(49.0, simplified.last().latitude, 0.0)
    }

    @Test
    fun simplifyPreservesAGenuineDeviation() {
        val points = listOf(TrackSample(0.0, 0.0), TrackSample(0.5, 5.0), TrackSample(1.0, 0.0))
        assertEquals(3, TrackGeometry.simplify(points, tolerance = 0.1).size)
    }

    @Test
    fun simplifyLongStraightTrackDoesNotOverflowTheStack() {
        val points = (0 until 60_000).map { TrackSample(it * 0.0001, 0.0) }
        assertTrue(TrackGeometry.simplify(points, tolerance = 0.01).size >= 2)
    }

    @Test
    fun interpolateAlongThePolyline() {
        val points = listOf(TrackSample(0.0, 0.0, 100.0), TrackSample(1.0, 0.0, 200.0))
        val total = TrackGeometry.polylineLengthKm(points)
        val mid = TrackGeometry.interpolate(points, total / 2)!!
        assertEquals(0.5, mid.latitude, 1e-3)
        assertEquals(150.0, mid.elevation!!, 0.5)
        // Clamped at both ends.
        assertEquals(0.0, TrackGeometry.interpolate(points, -5.0)!!.latitude, 0.0)
        assertEquals(1.0, TrackGeometry.interpolate(points, total + 50)!!.latitude, 0.0)
    }

    @Test
    fun bearingAlongMeridianIsNorth() {
        assertEquals(
            0.0,
            TrackGeometry.bearingDegrees(TrackSample(0.0, 0.0), TrackSample(1.0, 0.0)),
            1e-6,
        )
    }
}
