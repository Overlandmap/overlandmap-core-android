package ch.overlandmap.map.ui.tracking

import java.net.URLDecoder

/** Classify the returned file, never the request URL (which may lead to HTML or a login page). */
internal fun isTrackFileResponse(mimeType: String?, contentDisposition: String?, fileName: String? = null): Boolean {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (mime == "text/html" || mime == "application/xhtml+xml") return false
    if (mime in setOf("application/gpx+xml", "application/gpx", "application/x-gpx+xml",
            "application/zip", "application/x-zip", "application/x-zip-compressed")) return true
    val disposition = contentDisposition.orEmpty()
    val encoded = Regex("""(?:^|;)\s*filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
    val decoded = encoded?.let {
        runCatching { URLDecoder.decode(it.substringAfter("''", it), "UTF-8") }.getOrNull()
    }
    val plain = Regex("""(?:^|;)\s*filename\s*=\s*(?:"([^"]*)"|([^;]*))""", RegexOption.IGNORE_CASE)
        .find(disposition)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
    return listOfNotNull(decoded, plain, fileName).any { name ->
        name.endsWith(".gpx", true) || name.endsWith(".zip", true)
    }
}

/** Fallback for mislabelled server responses; never uses the URL as proof of file type. */
internal fun looksLikeTrackFile(prefix: ByteArray): Boolean {
    if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
        (prefix[2].toInt() to prefix[3].toInt()) in setOf(3 to 4, 5 to 6, 7 to 8)) return true
    var xml = prefix.toString(Charsets.UTF_8).replace("\u0000", "").trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    while (xml.startsWith("<?") || xml.startsWith("<!--")) {
        val end = if (xml.startsWith("<?")) "?>" else "-->"
        val index = xml.indexOf(end)
        if (index < 0) return false
        xml = xml.substring(index + end.length).trimStart()
    }
    return Regex("""^<(?:[A-Za-z_][\w.-]*:)?gpx(?:\s|/?>)""").containsMatchIn(xml)
}
