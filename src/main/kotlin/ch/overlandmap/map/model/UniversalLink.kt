package ch.overlandmap.map.model

import java.net.URI

/**
 * Public `https://overlandmap.ch/…` links (iOS `UniversalLink`); markup routes (`@country/FRA`,
 * `:itinerary:`…) are a separate thing. [url] builds the link, [parse] reads one back.
 */
sealed interface UniversalLink {
    /** An itinerary, optionally at a step: `/itinerary/<editor>/<pack>/<id>` or `/<editor>/<pack>/<id>?step=N`. */
    data class Itinerary(
        val editorId: String,
        val packName: String,
        val shortId: String,
        val stepId: Int? = null,
    ) : UniversalLink

    data class Waypoint(val geohash: String) : UniversalLink
    data class TrackPack(val editorId: String, val name: String) : UniversalLink

    /** Overland entities, keyed by alpha-3 (a country's document id) or document id. */
    data class Country(val alpha3: String) : UniversalLink
    data class Border(val id: String) : UniversalLink
    data class BorderPost(val id: String) : UniversalLink
    data class Zone(val id: String) : UniversalLink

    /** The link's URL; null when a segment is empty (e.g. a pack without an editor). */
    fun url(): String? {
        val query: String?
        val parts: List<String>
        when (this) {
            is Itinerary -> {
                parts = if (stepId == null) listOf("itinerary", editorId, packName, shortId)
                else listOf(editorId, packName, shortId)
                query = stepId?.let { "step=$it" }
            }
            is Waypoint -> { parts = listOf("waypoint", geohash); query = null }
            is TrackPack -> { parts = listOf("trackpack", editorId, name); query = null }
            is Country -> { parts = listOf("country", alpha3); query = null }
            is Border -> { parts = listOf("border", id); query = null }
            is BorderPost -> { parts = listOf("border_post", id); query = null }
            is Zone -> { parts = listOf("zone", id); query = null }
        }
        if (parts.any { it.isEmpty() }) return null
        // Each segment is encoded on its own: a slash, '%', '?' or '#' in a pack name must neither
        // change the URL's structure nor be decoded twice.
        val path = parts.joinToString("/") { encodeSegment(it) }
        return "https://$HOST/$path" + (query?.let { "?$it" } ?: "")
    }

    companion object {
        const val HOST = "overlandmap.ch"

        fun parse(link: String): UniversalLink? {
            val uri = try { URI(link.trim()) } catch (_: Exception) { return null }
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (!HOST.equals(uri.host, ignoreCase = true)) return null
            if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return null

            val rawParts = (uri.rawPath ?: "").split('/').toMutableList()
            if (rawParts.firstOrNull() == "") rawParts.removeAt(0)
            if (rawParts.lastOrNull() == "") rawParts.removeAt(rawParts.lastIndex)
            val parts = rawParts.map { decodeSegment(it) ?: return null }
            if (parts.any { it.isEmpty() }) return null

            val stepItems = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }
                .map { it.substringBefore('=') to it.substringAfter('=', "") }
                .filter { it.first == "step" }
            if (stepItems.size > 1) return null
            val step = stepItems.firstOrNull()?.let { (_, value) ->
                value.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            }

            return when {
                parts.size == 4 && parts[0] == "itinerary" -> Itinerary(parts[1], parts[2], parts[3], step)
                parts.size == 3 && parts[0] == "trackpack" && step == null -> TrackPack(parts[1], parts[2])
                parts.size == 3 && step != null -> Itinerary(parts[0], parts[1], parts[2], step)
                parts.size == 2 && step == null -> when (parts[0]) {
                    "waypoint" -> Waypoint(parts[1])
                    "country" -> Country(parts[1])
                    "border" -> Border(parts[1])
                    "border_post" -> BorderPost(parts[1])
                    "zone" -> Zone(parts[1])
                    else -> null
                }
                else -> null
            }
        }

        private fun isUnreserved(c: Char) =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

        private fun encodeSegment(segment: String): String = buildString {
            for (byte in segment.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && isUnreserved(c)) append(c) else append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }

        /** Percent-decodes one path segment as UTF-8; null on a malformed escape. */
        private fun decodeSegment(segment: String): String? {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c == '%') {
                    val hex = segment.substring(i + 1, minOf(i + 3, segment.length))
                    val value = hex.takeIf { it.length == 2 }?.toIntOrNull(16) ?: return null
                    out.write(value)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
