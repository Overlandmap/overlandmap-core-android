package ch.overlandmap.map.tracking

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GpxDownloadParserTest {
    private val gpx = """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
      <wpt lat="45" lon="6"><name>TET - Great camp</name><desc>See https://example.com</desc><sym>Campground</sym></wpt>
      <trk><name>ACT_FR - Mountain route</name><desc>Full track description</desc><trkseg><trkpt lat="45" lon="6"/><trkpt lat="46" lon="7"/></trkseg></trk>
      <trk><name>Second route</name><trkseg><trkpt lat="47" lon="8"/><trkpt lat="48" lon="9"/></trkseg></trk>
    </gpx>""".toByteArray()

    @Test fun keepsTracksSeparateAndPreservesWaypointMetadata() {
        val doc = GpxDownloadParser.parseGpx(gpx, "tour.gpx")!!
        assertEquals(2, doc.tracks.size)
        assertEquals("Mountain route", doc.tracks[0].name)
        assertEquals("Full track description", doc.tracks[0].description)
        assertEquals(2, doc.tracks[0].points.size)
        assertEquals(1, doc.tracks.sumOf { it.waypoints.size })
        val w = doc.tracks[0].waypoints.single()
        assertEquals("Great camp", w.label)
        assertEquals("Campground", w.symbol)
        assertEquals("See https://example.com", w.description)
        assertNotNull(w.geohash)
        assertNotNull(doc.tracks[1].geohash)
        assertEquals(48.0, doc.tracks[1].bounds!!.latMax, 0.0)
    }
    @Test fun zipReadsEveryGpxWithoutExtractingPaths() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("../one.gpx", "nested/two.GPX", "ignored.txt").forEach { name -> zip.putNextEntry(ZipEntry(name)); zip.write(gpx); zip.closeEntry() }
        }
        val docs = GpxDownloadParser.parseDownload(out.toByteArray(), "archive.zip")
        assertEquals(listOf("one.gpx", "two.GPX"), docs.map { it.fileName })
        assertEquals(4, docs.sumOf { it.tracks.size })
    }
    @Test fun trimsOnlyUppercasePrefixAndPreservesInternalSeparators() {
        assertEquals("Route - des_Alpes", GpxDownloadParser.cleanName("TET_FR - Route - des_Alpes"))
        assertEquals("ALL CAPS", GpxDownloadParser.cleanName("ALL CAPS"))
        assertEquals("Mixed CASE", GpxDownloadParser.cleanName("Mixed CASE"))
        assertEquals("Été", GpxDownloadParser.cleanName("ACT _ Été"))
    }
    @Test fun rejectsNonGpxAndExternalEntitiesAndInvalidCoordinates() {
        assertNull(GpxDownloadParser.parseGpx("<html/>".toByteArray(), "x.gpx"))
        assertNull(GpxDownloadParser.parseGpx("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><gpx>&x;</gpx>".toByteArray(), "x.gpx"))
        assertNull(GpxDownloadParser.parseGpx("<gpx><wpt lat='999' lon='0'/></gpx>".toByteArray(), "x.gpx"))
    }
    @Test fun importedMetadataSurvivesTrackFileRoundTrip() {
        val track = GpxDownloadParser.parseGpx(gpx, "tour.gpx")!!.tracks.first().copy(downloadWebsiteId = "site", trackFolderId = "folder")
        val restored = RecordedTrack.fromJson(track.toJson())!!
        assertEquals(track.description, restored.description)
        assertEquals("site", restored.downloadWebsiteId)
        assertEquals("folder", restored.trackFolderId)
        assertEquals(track.waypoints.single().description, restored.waypoints.single().description)
        assertEquals(track.waypoints.single().symbol, restored.waypoints.single().symbol)
        assertTrue(restored.toGpx().contains("<sym>Campground</sym>"))
    }

    @Test fun importsEachTracksOnxColorAndPersistsIt() {
        val xml = """<gpx xmlns:onx="http://www.onxmaps.com" xmlns:otherPrefix="http://www.onxmaps.com">
            <trk><extensions><onx:color>rgba(63, 70, 245, 1)</onx:color></extensions>
                <trkseg><trkpt lat="45" lon="6"/></trkseg></trk>
            <trk><extensions><otherPrefix:color> rgba( 0,255,16,0.5 ) </otherPrefix:color></extensions>
                <trkseg><trkpt lat="46" lon="7"/></trkseg></trk>
            <trk><trkseg><trkpt lat="47" lon="8"/></trkseg></trk>
        </gpx>"""
        val tracks = GpxDownloadParser.parseDownload(xml.toByteArray(), "colors.gpx").single().tracks
        assertEquals(listOf("#3F46F5", "#00FF10", RecordedTrack.DEFAULT_COLOR), tracks.map { it.colorHex })
        assertEquals("#3F46F5", RecordedTrack.fromJson(tracks.first().toJson())!!.colorHex)
    }

    @Test fun invalidOrUnrelatedColorsKeepTheDefaultWithoutRejectingTheTrack() {
        for (color in listOf("rgba(256, 0, 0, 1)", "rgba(0, -1, 0, 1)", "rgba(0, 0, 0, 2)", "not a color", "")) {
            val xml = """<gpx xmlns:onx="http://www.onxmaps.com"><trk>
                <extensions><onx:color>$color</onx:color></extensions>
                <trkseg><trkpt lat="45" lon="6"/></trkseg></trk></gpx>"""
            assertEquals(color, RecordedTrack.DEFAULT_COLOR,
                GpxDownloadParser.parseGpx(xml.toByteArray(), "invalid-color.gpx")!!.tracks.single().colorHex)
        }
        val xml = """<gpx xmlns:other="http://example.com"><trk>
            <extensions><other:color>rgba(63,70,245,1)</other:color></extensions>
            <trkseg><trkpt lat="45" lon="6"/></trkseg></trk></gpx>"""
        assertEquals(RecordedTrack.DEFAULT_COLOR, GpxDownloadParser.parseGpx(xml.toByteArray(), "other.gpx")!!.tracks.single().colorHex)
    }

    @Test fun probesGpxThenZipWithoutDependingOnDownloadFileName() {
        assertEquals(2, GpxDownloadParser.parseDownload(gpx, "wikiloc-download.bin").single().tracks.size)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("route.gpx")); zip.write(gpx); zip.closeEntry()
        }
        assertEquals(2, GpxDownloadParser.parseDownload(out.toByteArray(), "wikiloc-download.html").single().tracks.size)
        assertTrue(GpxDownloadParser.parseDownload("<html>Login required</html>".toByteArray(), "route.gpx").isEmpty())
        assertTrue(GpxDownloadParser.parseDownload("not a GPX or ZIP".toByteArray(), "route.zip").isEmpty())
    }

    @Test fun waypointOnlyFileIsImported() {
        val doc = GpxDownloadParser.parseGpx("<gpx><wpt lat='1' lon='2'><name>Place</name></wpt></gpx>".toByteArray(), "places.gpx")!!
        assertEquals(1, doc.tracks.size)
        assertTrue(doc.tracks.single().points.isEmpty())
        assertEquals(1, doc.tracks.single().waypoints.size)
    }
}
