package ch.overlandmap.map.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class TrackExtentTest {
    private fun track(vararg points: Pair<Float, Float>): Track {
        val bytes = ByteBuffer.allocate(points.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        points.forEach { (lat, lon) -> bytes.putFloat(lat).putFloat(lon).putFloat(0f) }
        return Track("t", "p", "i", "Named track", Base64.getEncoder().encodeToString(bytes.array()))
    }

    @Test fun extentIncludesInteriorPointsAndNotJustEndpoints() {
        val bounds = track(10f to 20f, 30f to -5f, 15f to 25f).extent()!!
        assertEquals(10.0, bounds.latMin, 0.0)
        assertEquals(30.0, bounds.latMax, 0.0)
        assertEquals(-5.0, bounds.lonMin, 0.0)
        assertEquals(25.0, bounds.lonMax, 0.0)
    }

    @Test fun emptyOrInvalidTracksHaveNoExtent() {
        assertNull(track().extent())
        assertNull(track(Float.NaN to 20f, 100f to 10f).extent())
    }

    @Test fun singlePointIsWidenedToUsableCameraBounds() {
        val bounds = track(10f to 20f).extent()!!
        assertTrue(bounds.latMin < 10 && bounds.latMax > 10)
        assertTrue(bounds.lonMin < 20 && bounds.lonMax > 20)
    }
}
