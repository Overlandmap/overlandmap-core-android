package ch.overlandmap.map.tracking

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

/*
 * Recorded and imported GPS tracks (iOS `RecordedTrack`). Each track is one JSON file in the same
 * shape the iOS app writes (ISO-8601 dates, same keys), so the files are interchangeable.
 */

/** A recorded GPS fix; [timestampMs] is epoch milliseconds. */
data class RecordedPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val timestampMs: Long,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
}

/** A labelled point dropped along a track. */
data class RecordedWaypoint(
    val id: String = UUID.randomUUID().toString(),
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val timestampMs: Long,
    val label: String = "",
    val description: String = "",
    val symbol: String = "",
    val downloadWebsiteId: String? = null,
    val trackFolderId: String? = null,
    val geohash: String? = null,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
}

data class RecordedTrack(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val name: String? = null,
    val startDateMs: Long,
    val endDateMs: Long? = null,
    val points: List<RecordedPoint> = emptyList(),
    val isDisplayed: Boolean = false,
    val colorHex: String = DEFAULT_COLOR,
    val waypoints: List<RecordedWaypoint> = emptyList(),
    val isImported: Boolean = false,
    val description: String = "",
    val downloadWebsiteId: String? = null,
    val trackFolderId: String? = null,
    val geohash: String? = null,
) {
    /** Total length in kilometres. */
    val lengthKm: Double
        get() = points.zipWithNext { a, b -> a.coordinate.distanceKm(b.coordinate) }.sum()

    /** Duration in hours from the start to the end (or [nowMs] while still recording). */
    fun lengthHours(nowMs: Long = System.currentTimeMillis()): Double =
        maxOf(0L, (endDateMs ?: nowMs) - startDateMs) / 3_600_000.0

    fun formattedDuration(nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f h", lengthHours(nowMs))

    fun formattedDistance(locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f km", lengthKm)

    fun formattedStartDate(): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startDateMs))

    /** The track's name, else its start date. */
    val displayName: String
        get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: formattedStartDate()

    val coordinates: List<Coordinate> get() = points.map { it.coordinate }

    /** The box enclosing the track and its waypoints. */
    val bounds: GeoBounds? by lazy { GeoBounds.containing(coordinates + waypoints.map { it.coordinate }) }

    /** Geometry-only extent, so a distant folder waypoint never changes track zoom or centre. */
    val trackBounds: GeoBounds? by lazy { GeoBounds.containing(coordinates) }

    /**
     * Points thinned so consecutive ones are at least [minDistanceMeters] apart; the first and last
     * are always kept.
     */
    fun simplified(minDistanceMeters: Double = 10.0): RecordedTrack {
        if (points.size <= 2) return this
        val minKm = minDistanceMeters / 1000.0
        val kept = ArrayList<RecordedPoint>()
        kept += points.first()
        var lastKept = points.first()
        for (i in 1 until points.size - 1) {
            val pt = points[i]
            if (lastKept.coordinate.distanceKm(pt.coordinate) >= minKm) {
                kept += pt
                lastKept = pt
            }
        }
        val last = points.last()
        if (kept.size > 1 && lastKept.coordinate.distanceKm(last.coordinate) < minKm) {
            kept[kept.lastIndex] = last
        } else {
            kept += last
        }
        return copy(points = kept)
    }

    // ── GPX ─────────────────────────────────────────────────────────────────

    /** The name used for the GPX document and its file. */
    val gpxName: String
        get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: fileName.removeSuffix(".json")

    /** A GPX 1.1 document of the track and its waypoints (iOS `exportGPX`). */
    fun toGpx(): String {
        val title = gpxName.xmlEscaped()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"OverlandMap\"\n")
            append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
            append(" xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
            append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
            append("  <metadata>\n    <name>$title</name>\n    <time>${iso(startDateMs)}</time>\n  </metadata>\n")
            for (w in waypoints) {
                append("  <wpt lat=\"${w.latitude}\" lon=\"${w.longitude}\">\n")
                w.elevation?.let { append("    <ele>$it</ele>\n") }
                append("    <time>${iso(w.timestampMs)}</time>\n")
                append("    <name>${w.label.xmlEscaped()}</name>\n")
                append("    <desc>${w.description.xmlEscaped()}</desc>\n    <sym>${w.symbol.xmlEscaped()}</sym>\n")
                append("  </wpt>\n")
            }
            append("  <trk>\n    <name>$title</name>\n    <desc>${description.xmlEscaped()}</desc>\n    <trkseg>\n")
            for (p in points) {
                append("        <trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">\n")
                p.elevation?.let { append("          <ele>$it</ele>\n") }
                append("          <time>${iso(p.timestampMs)}</time>\n")
                append("        </trkpt>\n")
            }
            append("    </trkseg>\n  </trk>\n</gpx>\n")
        }
    }

    // ── JSON (the iOS file format) ──────────────────────────────────────────

    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("fileName", fileName)
        .also { o -> name?.let { o.put("name", it) } }
        .put("startDate", iso(startDateMs))
        .also { o -> endDateMs?.let { o.put("endDate", iso(it)) } }
        .put("points", JSONArray(points.map { p ->
            JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)
                .also { o -> p.elevation?.let { o.put("elevation", it) } }
                .put("timestamp", iso(p.timestampMs))
        }))
        .put("isDisplayed", isDisplayed)
        .put("colorHex", colorHex)
        .put("waypoints", JSONArray(waypoints.map { w ->
            JSONObject().put("id", w.id).put("latitude", w.latitude).put("longitude", w.longitude)
                .also { o -> w.elevation?.let { o.put("elevation", it) } }
                .put("timestamp", iso(w.timestampMs)).put("label", w.label)
                .put("description", w.description).put("symbol", w.symbol)
                .put("downloadWebsiteId", w.downloadWebsiteId).put("trackFolderId", w.trackFolderId).put("geohash", w.geohash)
        }))
        .put("description", description)
        .put("downloadWebsiteId", downloadWebsiteId).put("trackFolderId", trackFolderId).put("geohash", geohash)
        .put("isImported", isImported)
        .toString()

    companion object {
        const val DEFAULT_COLOR = "#FF2D55"

        /** Seconds-precision ISO-8601 in UTC, as Swift's `.iso8601` strategy writes it. */
        fun iso(ms: Long): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(Math.floorDiv(ms, 1000L)))

        private fun parseIso(text: String?): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

        /** Reads a track file; null when it isn't one (missing id, file name or start date). */
        fun fromJson(json: String): RecordedTrack? = runCatching {
            val o = JSONObject(json)
            fun JSONObject.dbl(key: String) = if (has(key) && !isNull(key)) getDouble(key) else null
            RecordedTrack(
                id = o.getString("id"),
                fileName = o.getString("fileName"),
                name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
                startDateMs = parseIso(o.getString("startDate")) ?: return null,
                endDateMs = parseIso(o.optString("endDate")),
                points = o.optJSONArray("points")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val p = a.optJSONObject(i) ?: return@mapNotNull null
                        RecordedPoint(
                            p.getDouble("latitude"), p.getDouble("longitude"), p.dbl("elevation"),
                            parseIso(p.optString("timestamp")) ?: return@mapNotNull null,
                        )
                    }
                }.orEmpty(),
                isDisplayed = o.optBoolean("isDisplayed", false),
                colorHex = o.optString("colorHex", DEFAULT_COLOR).ifEmpty { DEFAULT_COLOR },
                waypoints = o.optJSONArray("waypoints")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val w = a.optJSONObject(i) ?: return@mapNotNull null
                        RecordedWaypoint(
                            w.getString("id"), w.getDouble("latitude"), w.getDouble("longitude"), w.dbl("elevation"),
                            parseIso(w.optString("timestamp")) ?: return@mapNotNull null, w.optString("label", ""),
                            w.optString("description", ""), w.optString("symbol", ""),
                            w.optString("downloadWebsiteId").takeIf { it.isNotEmpty() && it != "null" },
                            w.optString("trackFolderId").takeIf { it.isNotEmpty() && it != "null" },
                            w.optString("geohash").takeIf { it.isNotEmpty() && it != "null" },
                        )
                    }
                }.orEmpty(),
                isImported = o.optBoolean("isImported", false),
                description = o.optString("description", ""),
                downloadWebsiteId = o.optString("downloadWebsiteId").takeIf { it.isNotEmpty() && it != "null" },
                trackFolderId = o.optString("trackFolderId").takeIf { it.isNotEmpty() && it != "null" },
                geohash = o.optString("geohash").takeIf { it.isNotEmpty() && it != "null" },
            )
        }.getOrNull()
    }
}

internal fun String.xmlEscaped(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

/** Derive persisted spatial metadata from geometry, including legacy imports. */
internal fun RecordedTrack.withImportGeohashes(): RecordedTrack {
    if (!isImported) return this
    val hash = trackBounds?.centre?.let { ch.overlandmap.map.geo.Geohash.encode(it.latitude, it.longitude) }
    val pins = waypoints.map { waypoint ->
        val pinHash = ch.overlandmap.map.geo.Geohash.encode(waypoint.latitude, waypoint.longitude)
        if (waypoint.geohash == pinHash) waypoint else waypoint.copy(geohash = pinHash)
    }
    return if (hash == geohash && pins == waypoints) this else copy(geohash = hash, waypoints = pins)
}
