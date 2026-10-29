package ch.overlandmap.map.tracking

import ch.overlandmap.map.geo.Geohash
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.ZipInputStream

/** Keeps every track/route in a document separate, with shared document waypoints. */
object GpxDownloadParser {
    data class Document(val fileName: String, val tracks: List<RecordedTrack>)
    const val MAX_BYTES = 64 * 1024 * 1024

    fun cleanName(name: String): String {
        val words = name.split(Regex("[ _-]+"))
        val remaining = words.dropWhile { word -> word.isEmpty() || (word.any { it.isLetter() } && word.filter { it.isLetter() }.all { it.isUpperCase() }) }
            .joinToString(" ").trim(' ', '-', '_')
        // Preserve punctuation and separators inside the remaining name.
        if (remaining.isEmpty()) return name
        var start = 0
        for (word in words) {
            if (word.isNotEmpty() && !(word.any { it.isLetter() } && word.filter { it.isLetter() }.all { it.isUpperCase() })) break
            if (word.isNotEmpty()) start = name.indexOf(word, start) + word.length
        }
        return name.substring(start).trimStart(' ', '-', '_').ifEmpty { name }
    }

    fun parseDownload(bytes: ByteArray, fileName: String): List<Document> {
        require(bytes.size <= MAX_BYTES) { "Download too large" }
        // Servers may use an incorrect MIME type or filename: identify the actual content.
        parseGpx(bytes, fileName)?.takeIf { it.tracks.isNotEmpty() }?.let {
            return listOf(it)
        }
        run {
            val documents = mutableListOf<Document>()
            var total = 0
            var entries = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    require(++entries <= 4096) { "Too many archive entries" }
                    if (!entry.isDirectory) {
                        val data = zip.readLimited(MAX_BYTES - total + 1)
                        total += data.size
                        require(total <= MAX_BYTES) { "Archive too large" }
                        if (entry.name.endsWith(".gpx", true)) {
                            val parsed = parseGpx(data, entry.name.substringAfterLast('/'))
                            if (parsed != null) documents.add(parsed)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return documents
        }
    }

    private val onxRgba = Regex(
        """rgba\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d+(?:\.\d+)?|\.\d+)\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    /** Imported tracks use the same opaque RGB palette as manually coloured tracks. */
    private fun onxColor(value: String): String? {
        val match = onxRgba.matchEntire(value.trim()) ?: return null
        val channels = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
        if (channels.any { it !in 0..255 }) return null
        val alpha = match.groupValues[4].toDoubleOrNull() ?: return null
        if (alpha !in 0.0..1.0) return null
        return "#" + channels.joinToString("") { it.toString(16).padStart(2, '0') }.uppercase(java.util.Locale.ROOT)
    }

    fun parseGpx(bytes: ByteArray, fileName: String): Document? = runCatching {
        // Android's XML provider does not implement Xerces' disallow-doctype feature.
        // Reject declarations before parsing, and deny external entity resolution as well.
        val declarationText = bytes.toString(Charsets.UTF_8).replace("\u0000", "")
        require(!declarationText.contains("<!DOCTYPE", true) && !declarationText.contains("<!ENTITY", true)) { "GPX rejected: DTD or ENTITY declaration" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        })
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are not supported") }
        val root = builder.parse(ByteArrayInputStream(bytes)).documentElement
        if ((root.localName ?: root.tagName) != "gpx") {
            return null
        }
        // Android's DOM may materialize a NodeList on every getChildNodes() call.
        // Walk siblings once instead of rebuilding/indexing it for every track point.
        fun Element.children(tag: String): List<Element> = buildList {
            var child = firstChild
            while (child != null) {
                if (child is Element && (child.localName ?: child.tagName) == tag) add(child)
                child = child.nextSibling
            }
        }
        fun Element.text(tag: String): String {
            var child = firstChild
            while (child != null) {
                if (child is Element && (child.localName ?: child.tagName) == tag) return child.textContent.trim()
                child = child.nextSibling
            }
            return ""
        }
        fun Element.coordinate(): Pair<Double, Double> {
            val lat = getAttribute("lat").toDouble(); val lon = getAttribute("lon").toDouble()
            require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
            return lat to lon
        }
        val now = System.currentTimeMillis()
        fun Element.time() = runCatching { Instant.parse(text("time")).toEpochMilli() }.getOrDefault(now)
        val waypoints = root.children("wpt").map { e ->
            val (lat, lon) = e.coordinate()
            RecordedWaypoint(latitude = lat, longitude = lon, elevation = e.text("ele").toDoubleOrNull(), timestampMs = e.time(),
                label = cleanName(e.text("name")), description = e.text("desc").ifEmpty { e.text("cmt") }, symbol = e.text("sym"), geohash = Geohash.encode(lat, lon))
        }
        val elements = root.children("trk") + root.children("rte")
        val tracks = elements.mapNotNull { e ->
            val pts = if ((e.localName ?: e.tagName) == "trk") e.children("trkseg").flatMap { it.children("trkpt") } else e.children("rtept")
            val points = pts.mapIndexed { i, pt ->
                val (lat, lon) = pt.coordinate()
                val time = pt.text("time")
                val timestamp = if (time.isEmpty()) now + i * 1000 else
                    runCatching { Instant.parse(time).toEpochMilli() }.getOrDefault(now)
                RecordedPoint(lat, lon, pt.text("ele").toDoubleOrNull(), timestamp)
            }
            if (points.isEmpty()) {
                return@mapNotNull null
            }
            val color = e.children("extensions").flatMap { it.children("color") }
                .firstOrNull { it.namespaceURI?.trimEnd('/') in setOf("http://www.onxmaps.com", "https://www.onxmaps.com") }
                ?.textContent?.let(::onxColor)
            RecordedTrack(fileName = "${java.util.UUID.randomUUID()}.json", name = cleanName(e.text("name").ifEmpty { fileName.substringBeforeLast('.') }),
                startDateMs = points.first().timestampMs, endDateMs = points.last().timestampMs, points = points,
                colorHex = color ?: RecordedTrack.DEFAULT_COLOR,
                waypoints = emptyList(), isImported = true, isDisplayed = true, description = e.text("desc").ifEmpty { e.text("cmt") })
        }.toMutableList()
        if (tracks.isEmpty() && waypoints.isNotEmpty()) tracks += RecordedTrack(fileName = "${java.util.UUID.randomUUID()}.json", name = fileName.substringBeforeLast('.'),
            startDateMs = now, endDateMs = now, isImported = true, isDisplayed = true)
        if (tracks.isNotEmpty()) tracks[0] = tracks[0].copy(waypoints = waypoints)
        val document = Document(fileName, tracks.map { t ->
            val b = t.trackBounds
            t.copy(geohash = b?.let { Geohash.encode((it.latMin + it.latMax) / 2, (it.lonMin + it.lonMax) / 2) })
        })
        document
    }.getOrNull()
}

/** A bounded read compatible with Android versions before InputStream.readNBytes. */
internal fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
