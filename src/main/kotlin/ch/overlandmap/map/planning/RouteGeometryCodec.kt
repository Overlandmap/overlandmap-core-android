package ch.overlandmap.map.planning

import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Decoder for the segment geometry stored in Firestore: the documents of `segment/{id}/geometry`
 * (`000000`, `000001`, …) carry `content` strings that, once concatenated, are base64 of a gzip of
 * the JSON `[[longitude, latitude], …]` (iOS `RouteGeometryCodec`).
 */
object RouteGeometryCodec {

    /** The JSON text (`[[lon, lat], …]`) for the chunks, in document order; null when undecodable. */
    fun jsonFromChunks(chunks: List<String>): String? = runCatching {
        val packed = Base64.getDecoder().decode(chunks.joinToString(""))
        GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }.toString(Charsets.UTF_8)
    }.getOrNull()

    /** The polyline of a JSON array of `[lon, lat]` pairs; null when malformed. */
    fun points(json: String): List<RoutePoint>? = runCatching {
        val array = JSONArray(json)
        val points = ArrayList<RoutePoint>(array.length())
        for (i in 0 until array.length()) {
            val pair = array.getJSONArray(i)
            if (pair.length() < 2) return@runCatching null
            points += RoutePoint(pair.getDouble(0), pair.getDouble(1))
        }
        points
    }.getOrNull()
}
