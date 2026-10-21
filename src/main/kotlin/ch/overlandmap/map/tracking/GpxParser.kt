package ch.overlandmap.map.tracking

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.SAXParserFactory

/**
 * Reads GPS Exchange Format data (iOS `GPXParser`): track points (`trkpt`), route points
 * (`rtept`), waypoints (`wpt`) and the track, route or metadata name.
 */
object GpxParser {

    class Parsed(
        val name: String?,
        val points: List<RecordedPoint>,
        val waypoints: List<RecordedWaypoint>,
        val startDateMs: Long,
    )

    /** Null when [data] isn't well-formed XML. */
    fun parse(data: ByteArray, nowMs: Long = System.currentTimeMillis()): Parsed? {
        val handler = Handler(nowMs)
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(data)), handler)
        } catch (_: Exception) {
            return null
        }
        var points: List<RecordedPoint> = handler.points
        val start = points.firstOrNull()?.timestampMs
            ?: handler.waypoints.firstOrNull()?.timestampMs
            ?: handler.metadataTime
            ?: nowMs
        // Points without timestamps all carry "now": space them a second apart instead.
        if (points.isNotEmpty() && points.first().timestampMs == points.last().timestampMs) {
            points = points.mapIndexed { i, p -> p.copy(timestampMs = start + i * 1000L) }
        }
        return Parsed(handler.trackName ?: handler.routeName ?: handler.metadataName, points, handler.waypoints, start)
    }

    private fun parseDate(text: String): Long? =
        runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrNull()

    private class Handler(private val nowMs: Long) : DefaultHandler() {
        val points = ArrayList<RecordedPoint>()
        val waypoints = ArrayList<RecordedWaypoint>()
        var trackName: String? = null
        var routeName: String? = null
        var metadataName: String? = null
        var metadataTime: Long? = null

        private val text = StringBuilder()
        private var insideMetadata = false
        private var insideTrk = false
        private var insideRte = false
        private var insideTrkpt = false
        private var insideRtept = false
        private var insideWpt = false

        private var lat: Double? = null
        private var lon: Double? = null
        private var ele: Double? = null
        private var time: Long? = null
        private var wptName = ""

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            text.setLength(0)
            when (qName.lowercase()) {
                "metadata" -> insideMetadata = true
                "trk" -> insideTrk = true
                "rte" -> insideRte = true
                "trkpt" -> { insideTrkpt = true; startPoint(attributes) }
                "rtept" -> { insideRtept = true; startPoint(attributes) }
                "wpt" -> { insideWpt = true; startPoint(attributes); wptName = "" }
            }
        }

        private fun startPoint(attributes: Attributes) {
            lat = attributes.getValue("lat")?.toDoubleOrNull()
            lon = attributes.getValue("lon")?.toDoubleOrNull()
            ele = null
            time = null
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val tag = qName.lowercase()
            val value = text.toString().trim()
            if (insideMetadata) {
                when {
                    tag == "name" && metadataName == null && value.isNotEmpty() -> metadataName = value
                    tag == "time" && metadataTime == null -> metadataTime = parseDate(value)
                    tag == "metadata" -> insideMetadata = false
                }
            }
            if (insideTrk) {
                if (tag == "name" && !insideTrkpt && trackName == null && value.isNotEmpty()) trackName = value
                else if (tag == "trk") insideTrk = false
            }
            if (tag == "rte") insideRte = false
            else if (insideRte && tag == "name" && !insideRtept && routeName == null && value.isNotEmpty()) routeName = value

            if (insideTrkpt || insideRtept) {
                when (tag) {
                    "ele" -> ele = value.toDoubleOrNull()
                    "time" -> time = parseDate(value)
                    "trkpt", "rtept" -> {
                        val la = lat
                        val lo = lon
                        if (la != null && lo != null) points += RecordedPoint(la, lo, ele, time ?: nowMs)
                        insideTrkpt = false
                        insideRtept = false
                    }
                }
            }
            if (insideWpt) {
                when (tag) {
                    "name" -> wptName = value
                    "ele" -> ele = value.toDoubleOrNull()
                    "time" -> time = parseDate(value)
                    "wpt" -> {
                        val la = lat
                        val lo = lon
                        if (la != null && lo != null) {
                            waypoints += RecordedWaypoint(latitude = la, longitude = lo, elevation = ele, timestampMs = time ?: nowMs, label = wptName)
                        }
                        insideWpt = false
                    }
                }
            }
        }
    }
}
