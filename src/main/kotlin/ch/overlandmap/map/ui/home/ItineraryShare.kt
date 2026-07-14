package ch.overlandmap.map.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.Waypoint
import java.io.File

/** The itinerary's universal link: editor / pack name / itinerary slug. */
private fun itineraryLink(editorId: String?, packName: String?, itineraryId: String): String {
    val editor = Uri.encode(editorId.orEmpty())
    val pack = Uri.encode(packName.orEmpty())
    val itinerary = Uri.encode(itineraryId)
    return "https://overlandmap.ch/itinerary/$editor/$pack/$itinerary"
}

/** Shares the itinerary's universal link through the system share sheet. */
fun shareItineraryLink(context: Context, editorId: String?, packName: String?, itineraryId: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, itineraryLink(editorId, packName, itineraryId))
    }
    context.startActivity(Intent.createChooser(intent, null))
}

/**
 * Writes the itinerary to a GPX 1.1 file in the cache and shares it through
 * the system sheet: its tracks as `<trk>`, its steps and waypoints as `<wpt>`.
 */
fun shareItineraryGpx(
    context: Context,
    itinerary: Itinerary,
    tracks: List<Track>,
    steps: List<ItineraryStep>,
    waypoints: List<Waypoint>,
    lang: String,
) {
    val gpx = buildGpx(itinerary, tracks, steps, waypoints, lang)
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "${safeFileName(itinerary.name(lang))}.gpx")
    file.writeText(gpx)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun buildGpx(
    itinerary: Itinerary,
    tracks: List<Track>,
    steps: List<ItineraryStep>,
    waypoints: List<Waypoint>,
    lang: String,
): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append(
        """<gpx version="1.1" creator="Overland Map" """ +
            """xmlns="http://www.topografix.com/GPX/1/1">""",
    ).append('\n')
    append("  <metadata><name>").append(esc(itinerary.name(lang))).append("</name></metadata>\n")

    steps.forEach { step ->
        val lat = step.lat ?: return@forEach
        val lon = step.lon ?: return@forEach
        waypoint(lat, lon, step.ele?.toDouble(), step.fullName(lang), step.description(lang))
    }
    waypoints.forEach { wp ->
        val lat = wp.lat ?: return@forEach
        val lon = wp.lon ?: return@forEach
        waypoint(lat, lon, wp.ele?.toDouble(), wp.name(lang), wp.description(lang))
    }
    tracks.forEach { track ->
        val points = track.coordinates()
        if (points.isEmpty()) return@forEach
        append("  <trk>")
        track.name?.let { append("<name>").append(esc(it)).append("</name>") }
        append("<trkseg>\n")
        points.forEach { p ->
            append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon)
                .append("\"><ele>").append(p.ele).append("</ele></trkpt>\n")
        }
        append("  </trkseg></trk>\n")
    }
    append("</gpx>\n")
}

private fun StringBuilder.waypoint(
    lat: Double,
    lon: Double,
    ele: Double?,
    name: String,
    desc: String?,
) {
    append("  <wpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\">")
    ele?.let { append("<ele>").append(it).append("</ele>") }
    append("<name>").append(esc(name)).append("</name>")
    desc?.let { append("<desc>").append(esc(it)).append("</desc>") }
    append("</wpt>\n")
}

private fun esc(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/** A file-system-safe stem for the shared file. */
private fun safeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9-_]+"), "_").trim('_').ifEmpty { "itinerary" }
