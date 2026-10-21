package ch.overlandmap.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Confirms the JVM test setup works, including the real org.json. */
class SanityTest {
    @Test
    fun jsonRoundTrip() {
        val obj = JSONObject().put("a", 1).put("b", "x")
        assertEquals(1, JSONObject(obj.toString()).getInt("a"))
    }
}
