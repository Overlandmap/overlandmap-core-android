package ch.overlandmap.map.bluetooth

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.tracking.LocationFix
import org.junit.Assert.*
import org.junit.Test

class BluetoothGPSTest {
    @Test fun iosWireVector() {
        // iOS BluetoothGPSTests.packetRoundTrips values, encoded in the documented LE layout.
        val packet = GPSFixPacket(LocationFix(46.204391, 6.143158, 375.0, 1234, 4.5f, 271.3f, 13.89f), true, 513)
        val expected = "011f063b8a1b1c5fa90377012d006d05990a0102".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, packet.encoded())
        val decoded = GPSFixPacket.decode(expected, 1234)!!
        assertEquals(packet, decoded)
        assertEquals(20, packet.encoded().size)
    }
    @Test fun rejectsMalformedAndAcceptsExtensions() {
        assertNull(GPSFixPacket.decode(ByteArray(19)))
        assertNull(GPSFixPacket.decode(ByteArray(20)))
        assertNull(GPSFixPacket.decode(GPSFixPacket(LocationFix(120.0, 0.0, timeMs = 0)).encoded()))
        val encoded = GPSFixPacket(LocationFix(-33.9249, -70.6483, -85.0, 0, -1f, 365f), sequence = 65535).encoded()
        val decoded = GPSFixPacket.decode(encoded + byteArrayOf(99), 10)!!
        assertEquals(-33.9249, decoded.fix.latitude, 1e-7)
        assertEquals(-70.6483, decoded.fix.longitude, 1e-7)
        assertEquals(-85.0, decoded.fix.altitude!!, 0.0)
        assertEquals(5f, decoded.fix.courseDeg!!, 0f)
        assertNull(decoded.fix.speedMps)
        assertEquals(65535, decoded.sequence)
    }
    @Test fun sourcePriorityAndFallback() {
        val own = LocationFix(1.0, 2.0, timeMs = 99000)
        val remote = LocationFix(3.0, 4.0, timeMs = 100000)
        assertEquals(own, LocationSourcePolicy.select(false, own, remote, 100000))
        assertEquals(remote, LocationSourcePolicy.select(true, own, remote, 100000))
        assertNull(LocationSourcePolicy.select(true, own, remote, 120000))
        assertNull(LocationSourcePolicy.select(true, own, null, 100000))
        assertEquals(remote, LocationSourcePolicy.select(true, null, remote, 100000))
    }
    @Test fun playbackSpeedDirectionDuplicatesAndFinish() {
        val a = Coordinate(0.0, 0.0); val b = Coordinate(0.0, 0.01)
        val track = TrackPlayback(listOf(a, a, b, b), false)
        assertEquals(a, track.advance(0.0, 1).coordinate)
        val sample = track.advance(1.0, 5)
        assertEquals(70.0, a.distanceMeters(sample.coordinate), 0.01)
        assertEquals(90f, sample.course, 0.001f)
        assertEquals(70f, sample.speed, 0f)
        val reverse = TrackPlayback(listOf(a, b), true)
        assertEquals(b, reverse.advance(0.0, 1).coordinate)
        assertEquals(270f, reverse.advance(1.0, 1).course, 0.001f)
        val end = track.advance(10000.0, 50)
        assertEquals(b, end.coordinate)
        assertTrue(track.finished)
        assertEquals(0f, end.speed, 0f)
        assertEquals(1.0, end.progress, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPlayback() {
        TrackPlayback(listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.0)), false)
    }
}
