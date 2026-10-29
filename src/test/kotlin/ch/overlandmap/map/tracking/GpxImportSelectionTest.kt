package ch.overlandmap.map.tracking

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GpxImportSelectionTest {
    private fun archive(): List<GpxDownloadParser.Document> {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (name in listOf("North", "South", "East")) {
                zip.putNextEntry(ZipEntry("$name.gpx"))
                zip.write("""<gpx><wpt lat="45" lon="6"><name>$name camp</name></wpt>
                    <trk><name>$name first</name><trkseg><trkpt lat="45" lon="6"/></trkseg></trk>
                    <trk><name>$name second</name><trkseg><trkpt lat="46" lon="7"/></trkseg></trk></gpx>""".toByteArray())
                zip.closeEntry()
            }
        }
        return GpxDownloadParser.parseDownload(output.toByteArray(), "all-regions.zip")
    }

    @Test fun importsOnlyCheckedFilesAndUsesTheFirstCheckedFileName() {
        val documents = archive()
        val selected = selectGpxDocuments(documents, listOf(2, 0))!!
        assertEquals("East", selected.folderName)
        assertEquals(listOf("East first", "East second", "North first", "North second"), selected.tracks.map { it.name })
        assertEquals(listOf("East camp", "North camp"), selected.tracks.flatMap { it.waypoints }.map { it.label })
        assertFalse(selected.tracks.any { it.name?.startsWith("South") == true })
    }

    @Test fun deselectionChangesDefaultNameAndDoesNotImportUncheckedContent() {
        val documents = archive()
        val selected = selectGpxDocuments(documents, listOf(0))!!
        assertEquals("North", selected.folderName)
        assertEquals(2, selected.tracks.size)
        assertEquals(1, selected.tracks.sumOf { it.waypoints.size })
        assertNull(selectGpxDocuments(documents, emptyList()))
    }

    @Test fun selectedFilesAreNotDuplicatedAndWaypointOnlyFilesRemainImportable() {
        val doc = GpxDownloadParser.parseGpx("<gpx><wpt lat='1' lon='2'><name>Camp</name></wpt></gpx>".toByteArray(), "Camps.GPX")!!
        val selected = selectGpxDocuments(listOf(doc), listOf(0, 0))!!
        assertEquals("Camps", selected.folderName)
        assertEquals(1, selected.tracks.size)
        assertEquals(1, selected.tracks.single().waypoints.size)
    }
}
