package ch.overlandmap.map.data.local

import java.time.Instant

/**
 * ISO-8601 UTC text with second precision ("2026-07-23T10:15:00Z"), the format iOS stores
 * its `TEXT` date columns in (`ISO8601DateFormatter`). The fixed width makes the strings
 * sort chronologically, which `ORDER BY lastSeen DESC` relies on.
 */
object IsoDate {
    fun format(millis: Long): String = Instant.ofEpochSecond(Math.floorDiv(millis, 1000L)).toString()

    /** Epoch millis of an ISO-8601 instant, or null when [text] isn't one. */
    fun parse(text: String?): Long? =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
