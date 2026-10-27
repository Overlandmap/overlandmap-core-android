package ch.overlandmap.map.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** One decoded track point. */
data class TrackPoint(val lat: Double, val lon: Double, val ele: Double)

/**
 * A track geometry. Port of `models/track.dart`. The geometry stays encoded
 * as [coordsBase64] (Float32 lat/lon/ele triples, little endian) until a map
 * actually needs it, which keeps the library list cheap in memory.
 */
data class Track(
    val documentId: String,
    val trackPackId: String,
    val itineraryId: String,
    val name: String? = null,
    val coordsBase64: String = "",
) {
    fun coordinates(): List<TrackPoint> {
        val bytes = try {
            Base64.getDecoder().decode(coordsBase64)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val points = ArrayList<TrackPoint>(floats.remaining() / 3)
        while (floats.remaining() >= 3) {
            points.add(
                TrackPoint(
                    lat = floats.get().toDouble(),
                    lon = floats.get().toDouble(),
                    ele = floats.get().toDouble(),
                )
            )
        }
        return points
    }

    /** Bounds of the complete track, with degenerate axes widened for camera fitting. */
    fun extent(): ch.overlandmap.map.geo.GeoBounds? = ch.overlandmap.map.geo.GeoBounds.containing(
        coordinates().filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            .map { ch.overlandmap.map.geo.Coordinate(it.lat, it.lon) },
    )

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Track(
            documentId = documentId,
            trackPackId = FS.str(data["trackPackId"]) ?: "",
            itineraryId = FS.str(data["itineraryId"]) ?: "",
            name = FS.str(data["name"]),
            coordsBase64 = FS.str(data["coordsBase64"]) ?: "",
        )
    }
}
