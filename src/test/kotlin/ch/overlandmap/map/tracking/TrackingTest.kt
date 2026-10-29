package ch.overlandmap.map.tracking

import ch.overlandmap.map.geo.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class TrackingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    private fun pt(lat: Double, lon: Double, t: Long = t0, ele: Double? = null) = RecordedPoint(lat, lon, ele, t)

    private class FakeLocation : LocationSource {
        override var lastFix: LocationFix? = null
        var started = 0
        var stopped = 0
        var minDistance = 0f
        var listener: ((LocationFix) -> Unit)? = null
        override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) {
            started++; minDistance = minDistanceMeters; listener = onFix
        }
        override fun stop() { stopped++; listener = null }
        fun emit(fix: LocationFix) { if (fix.accuracyM >= 0) lastFix = fix; listener?.invoke(fix) }
    }

    private fun recorder(location: FakeLocation, clock: () -> Long = { t0 }, changes: MutableList<Boolean> = mutableListOf()) =
        TrackRecorder(TrackStore(File(tmp.root, "rec")), location, clock, saveIntervalMs = 0, onRecordingChanged = { changes += it })

    // ── Model ───────────────────────────────────────────────────────────────

    @Test
    fun lengthDurationAndNames() {
        val track = RecordedTrack(
            fileName = "2024-01-02.json", startDateMs = t0, endDateMs = t0 + 5_400_000,
            points = listOf(pt(0.0, 0.0), pt(0.0, 0.1), pt(0.0, 0.2)),
        )
        assertEquals(22.2, track.lengthKm, 0.1)
        assertEquals(1.5, track.lengthHours(), 1e-9)
        assertEquals("1.5 h", track.formattedDuration(locale = Locale.US))
        assertEquals("22.2 km", track.formattedDistance(Locale.US))
        assertEquals("2024-01-02", track.gpxName)
        assertEquals(track.formattedStartDate(), track.displayName)
        assertEquals("Pass", track.copy(name = "  Pass ").displayName)
        // An ongoing track lasts until "now".
        assertEquals(2.0, track.copy(endDateMs = null).lengthHours(t0 + 7_200_000), 1e-9)
    }

    @Test
    fun simplifyKeepsEndsAndThinsClosePoints() {
        // Points ~1.1 m apart along a line, 300 of them.
        val points = (0 until 300).map { pt(0.0, it * 0.00001, t0 + it * 1000L) }
        val track = RecordedTrack(fileName = "a.json", startDateMs = t0, points = points)
        val simple = track.simplified(10.0)
        assertTrue(simple.points.size < 60)
        assertEquals(points.first(), simple.points.first())
        assertEquals(points.last(), simple.points.last())
        // Consecutive kept points (but the last) are at least 10 m apart.
        simple.points.dropLast(1).zipWithNext().forEach { (a, b) ->
            assertTrue(a.coordinate.distanceMeters(b.coordinate) >= 9.9)
        }
        val two = track.copy(points = points.take(2))
        assertEquals(two, two.simplified())
    }

    @Test
    fun jsonRoundTripsAndUsesTheIosShape() {
        val track = RecordedTrack(
            id = "id1", fileName = "2024-01-02.json", name = "Trip", startDateMs = t0, endDateMs = t0 + 60_000,
            points = listOf(pt(41.1, 69.2, t0, 1200.5), pt(41.2, 69.3, t0 + 1000)),
            isDisplayed = true, colorHex = "#00FF00",
            waypoints = listOf(RecordedWaypoint("w1", 41.15, 69.25, null, t0 + 2000, "Camp")),
            isImported = true,
        )
        val json = track.toJson()
        assertTrue(json.contains("\"startDate\":\"2023-11-14T22:13:20Z\""))
        assertTrue(json.contains("\"colorHex\":\"#00FF00\""))
        assertEquals(track, RecordedTrack.fromJson(json))
    }

    @Test
    fun jsonFromIosWithMissingOptionalKeysStillLoads() {
        val ios = """{"id":"x","fileName":"f.json","startDate":"2024-01-02T03:04:05Z","points":[
            {"latitude":1,"longitude":2,"timestamp":"2024-01-02T03:04:06Z"}]}"""
        val t = RecordedTrack.fromJson(ios)!!
        assertEquals("#FF2D55", t.colorHex)
        assertFalse(t.isDisplayed)
        assertFalse(t.isImported)
        assertNull(t.endDateMs)
        assertNull(t.points[0].elevation)
        assertNull(RecordedTrack.fromJson("""{"id":"x"}"""))
        assertNull(RecordedTrack.fromJson("not json"))
    }

    // ── GPX ─────────────────────────────────────────────────────────────────

    private val gpx = """<?xml version="1.0"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Meta</name><time>2024-01-02T03:00:00Z</time></metadata>
          <wpt lat="41.5" lon="69.5"><ele>900</ele><time>2024-01-02T03:10:00Z</time><name>Spring &amp; well</name></wpt>
          <trk><name>Track A</name><trkseg>
            <trkpt lat="41.0" lon="69.0"><ele>1000.5</ele><time>2024-01-02T03:04:05Z</time></trkpt>
            <trkpt lat="41.1" lon="69.1"><time>2024-01-02T03:04:15.500Z</time></trkpt>
          </trkseg></trk>
        </gpx>""".trimIndent()

    @Test
    fun gpxParsingReadsPointsWaypointsAndNames() {
        val parsed = GpxParser.parse(gpx.toByteArray(), now = 0L)!!
        assertEquals("Track A", parsed.name)
        assertEquals(2, parsed.points.size)
        assertEquals(1000.5, parsed.points[0].elevation!!, 0.0)
        assertNull(parsed.points[1].elevation)
        assertEquals(java.time.Instant.parse("2024-01-02T03:04:05Z").toEpochMilli(), parsed.startDateMs)
        assertEquals("Spring & well", parsed.waypoints.single().label)
        assertEquals(900.0, parsed.waypoints.single().elevation!!, 0.0)
        assertNull(GpxParser.parse("<gpx><oops></gpx>".toByteArray()))
    }

    private fun GpxParser.parse(data: ByteArray, now: Long) = parse(data, nowMs = now)

    @Test
    fun gpxWithoutTimesGetsSpacedTimestampsAndNamesFallBack() {
        val noTimes = """<gpx><metadata><name>Only meta</name></metadata><rte><name>Route</name>
            <rtept lat="1" lon="1"/><rtept lat="2" lon="2"/><rtept lat="3" lon="3"/></rte></gpx>"""
        val parsed = GpxParser.parse(noTimes.toByteArray(), 5_000L)!!
        assertEquals("Route", parsed.name)
        assertEquals(listOf(5_000L, 6_000L, 7_000L), parsed.points.map { it.timestampMs })
    }

    @Test
    fun exportedGpxParsesBackToTheSameTrack() {
        val track = RecordedTrack(
            fileName = "f.json", name = "A <b> & \"c\"", startDateMs = t0,
            points = listOf(pt(41.0, 69.0, t0, 100.0), pt(41.1, 69.1, t0 + 5000)),
            waypoints = listOf(RecordedWaypoint("w", 41.05, 69.05, 50.0, t0 + 2000, "Tom's")),
        )
        val parsed = GpxParser.parse(track.toGpx().toByteArray(), nowMs = 0L)!!
        assertEquals(track.points, parsed.points)
        assertEquals("A <b> & \"c\"", parsed.name)
        assertEquals("Tom's", parsed.waypoints.single().label)
        assertEquals(50.0, parsed.waypoints.single().elevation!!, 0.0)
    }

    @Test
    fun spokenRepliesAreClassified() {
        assertEquals(VoiceCommand.OK, VoiceCommand.classify("OK"))
        assertEquals(VoiceCommand.OK, VoiceCommand.classify("oui merci"))
        assertEquals(VoiceCommand.OK, VoiceCommand.classify("да"))
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.classify("Annuler"))
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.classify("отмена"))
        assertEquals(VoiceCommand.CHANGE, VoiceCommand.classify("change it"))
        assertEquals(VoiceCommand.CHANGE, VoiceCommand.classify("ändern"))
        // Cancel beats change beats ok.
        assertEquals(VoiceCommand.CANCEL, VoiceCommand.classify("ok cancel"))
        assertEquals(VoiceCommand.CHANGE, VoiceCommand.classify("ok change"))
        assertEquals(VoiceCommand.NONE, VoiceCommand.classify("camp near the river"))
        assertEquals(VoiceCommand.NONE, VoiceCommand.classify(""))
    }

    // ── Recorder ────────────────────────────────────────────────────────────

    @Test
    fun recordingCollectsFixesAndSavesAFileThatReloads() {
        val loc = FakeLocation()
        val changes = mutableListOf<Boolean>()
        var clock = t0
        val rec = recorder(loc, { clock }, changes)
        rec.startRecording()
        assertTrue(rec.isRecording)
        assertEquals(5f, loc.minDistance, 0f)
        assertEquals(listOf(true), changes)

        clock += 1000
        loc.emit(LocationFix(41.0, 69.0, 1000.0, clock))
        clock += 1000
        loc.emit(LocationFix(41.001, 69.0, 1005.0, clock))
        loc.emit(LocationFix(0.0, 0.0, null, clock, accuracyM = -1f)) // ignored
        assertEquals(2, rec.currentTrack!!.points.size)

        clock += 1000
        rec.addWaypoint("Camp")
        assertEquals(41.001, rec.currentTrack!!.waypoints.single().latitude, 0.0)

        rec.stopRecording()
        assertFalse(rec.isRecording)
        assertEquals(listOf(true, false), changes)
        assertEquals(1, loc.stopped)
        val stopped = rec.recordings.value.single()
        assertEquals(clock, stopped.endDateMs)

        val reloaded = recorder(FakeLocation()).recordings.value.single()
        assertEquals(stopped, reloaded)
    }

    @Test
    fun waypointsFallBackToTheLastPointWhenThereIsNoFix() {
        val rec = recorder(FakeLocation())
        rec.startRecording()
        rec.addWaypoint("nowhere") // no point, no fix: nothing to place it at
        assertTrue(rec.currentTrack!!.waypoints.isEmpty())
        rec.addPoint(pt(1.0, 2.0))
        rec.addWaypoint("here")
        assertEquals(1.0, rec.currentTrack!!.waypoints.single().latitude, 0.0)
        rec.addWaypoint("given", Coordinate(5.0, 6.0), 7.0)
        assertEquals(5.0, rec.currentTrack!!.waypoints.last().latitude, 0.0)
        assertEquals(7.0, rec.currentTrack!!.waypoints.last().elevation!!, 0.0)
    }

    @Test
    fun fileNamesAreUniquePerDay() {
        val loc = FakeLocation()
        val rec = recorder(loc)
        rec.startRecording(); rec.stopRecording()
        rec.startRecording(); rec.stopRecording()
        val names = rec.recordings.value.map { it.fileName }
        assertEquals(2, names.size)
        assertEquals(1, names.count { it.matches(Regex("""\d{4}-\d\d-\d\d\.json""")) })
        assertEquals(1, names.count { it.matches(Regex("""\d{4}-\d\d-\d\d-1\.json""")) })
    }

    @Test
    fun editingAndDeletingTracks() {
        val rec = recorder(FakeLocation())
        rec.startRecording()
        (0 until 100).forEach { rec.addPoint(pt(0.0, it * 0.00001, t0 + it)) }
        val id = rec.currentTrackId.value!!
        rec.stopRecording()

        rec.toggleDisplayed(id)
        assertTrue(rec.track(id)!!.isDisplayed)
        rec.setDisplayed(id, true)
        assertTrue(rec.track(id)!!.isDisplayed)
        rec.updateColor(id, "#123456")
        rec.rename(id, "  Named ")
        rec.simplify(id)
        assertTrue(rec.track(id)!!.points.size < 100)
        assertEquals("#123456", rec.track(id)!!.colorHex)
        assertEquals("Named", rec.track(id)!!.name)

        val file = File(tmp.root, "rec/" + rec.track(id)!!.fileName)
        assertTrue(file.exists())
        rec.delete(id)
        assertFalse(file.exists())
        assertTrue(rec.recordings.value.isEmpty())
    }

    @Test
    fun deletingTheTrackBeingRecordedStopsTheRecording() {
        val loc = FakeLocation()
        val changes = mutableListOf<Boolean>()
        val rec = recorder(loc, changes = changes)
        rec.startRecording()
        rec.delete(rec.currentTrackId.value!!)
        assertFalse(rec.isRecording)
        assertEquals(listOf(true, false), changes)
        assertEquals(1, loc.stopped)
    }

    @Test
    fun importedChangesNotifyIndexesBeforeReturningAndPersistGeohashes() {
        val rec = recorder(FakeLocation())
        val snapshots = mutableListOf<List<RecordedTrack>>()
        rec.onImportedContentChanged = { snapshots += it }
        val track = RecordedTrack(fileName = "import.json", name = "Alpine trail", startDateMs = t0, endDateMs = t0,
            points = listOf(pt(45.0, 6.0), pt(46.0, 8.0)), isImported = true, trackFolderId = "folder",
            waypoints = listOf(RecordedWaypoint(latitude = 50.0, longitude = 10.0, timestampMs = t0, label = "Camp")))
        rec.addImported(listOf(track))
        val imported = snapshots.single().single()
        // Track centre uses only the line, while folder extents also include its waypoints.
        assertEquals(ch.overlandmap.map.geo.Geohash.encode(45.5, 7.0), imported.geohash)
        assertEquals(ch.overlandmap.map.geo.Geohash.encode(50.0, 10.0), imported.waypoints.single().geohash)
        assertEquals(50.0, imported.bounds!!.latMax, 0.0)
        assertEquals(imported.geohash, recorder(FakeLocation()).track(track.id)!!.geohash)
        rec.rename(track.id, "New trail")
        assertEquals("New trail", snapshots.last().single().name)
        rec.delete(track.id)
        assertTrue(snapshots.last().isEmpty())
    }

    @Test
    fun loadingLegacyImportsBackfillsAndStoresSpatialMetadata() {
        val store = TrackStore(File(tmp.root, "rec"))
        val legacy = RecordedTrack(fileName = "legacy.json", startDateMs = t0, endDateMs = t0, isImported = true,
            points = listOf(pt(1.0, 2.0), pt(3.0, 4.0)),
            waypoints = listOf(RecordedWaypoint(latitude = 5.0, longitude = 6.0, timestampMs = t0)))
        store.save(legacy)
        val loaded = recorder(FakeLocation()).track(legacy.id)!!
        assertEquals(ch.overlandmap.map.geo.Geohash.encode(2.0, 3.0), loaded.geohash)
        assertEquals(ch.overlandmap.map.geo.Geohash.encode(5.0, 6.0), loaded.waypoints.single().geohash)
        assertEquals(loaded, store.loadAll().single())
    }

    @Test
    fun deletingAnImportedWaypointFindsItsOwnerAndPersistsTheRemainingContent() {
        val rec = recorder(FakeLocation())
        val a = RecordedWaypoint(latitude = 45.0, longitude = 6.0, timestampMs = t0, label = "Keep")
        val b = RecordedWaypoint(latitude = 50.0, longitude = 10.0, timestampMs = t0, label = "Delete")
        val imported = RecordedTrack(fileName = "waypoints.json", startDateMs = t0, endDateMs = t0,
            isImported = true, trackFolderId = "folder", points = listOf(pt(44.0, 5.0)), waypoints = listOf(a, b))
        rec.addImported(listOf(imported))
        var updated: List<RecordedTrack>? = null
        rec.onImportedContentChanged = { updated = it }
        rec.deleteImportedWaypoint(b.id)
        val remaining = rec.track(imported.id)!!
        assertEquals(listOf(a.id), remaining.waypoints.map { it.id })
        assertEquals(imported.points, remaining.points)
        assertEquals("folder", remaining.trackFolderId)
        assertEquals(45.0, remaining.bounds!!.latMax, 0.0)
        assertEquals(remaining, updated!!.single())
        assertEquals(remaining, recorder(FakeLocation()).track(imported.id))
    }

    @Test
    fun waypointDeletionDoesNotChangeRecordedTracks() {
        val rec = recorder(FakeLocation())
        rec.startRecording()
        rec.addWaypoint("Recorded waypoint", ch.overlandmap.map.geo.Coordinate(45.0, 6.0))
        val track = rec.currentTrack!!
        rec.deleteImportedWaypoint(track.waypoints.single().id)
        assertEquals(track, rec.currentTrack)
    }

    @Test
    fun importingGpxAddsADisplayedImportedTrack() {
        val rec = recorder(FakeLocation())
        val track = rec.importGpx(gpx.toByteArray())
        assertTrue(track.isImported && track.isDisplayed)
        assertEquals("Track A", track.name)
        assertEquals(listOf(track), rec.importedTracks)
        assertTrue(rec.recordedTracks.isEmpty())
        try { rec.importGpx("<gpx/>".toByteArray()); fail() } catch (e: TrackImportException) {
            assertEquals(TrackImportException.Reason.EMPTY_GPX, e.reason)
        }
        try { rec.importGpx("garbage".toByteArray()); fail() } catch (e: TrackImportException) {
            assertEquals(TrackImportException.Reason.INVALID_GPX, e.reason)
        }
    }

    @Test
    fun aTrackLeftOpenByACrashIsClosedAtItsLastPointOnLoad() {
        val store = TrackStore(File(tmp.root, "rec"))
        store.save(RecordedTrack(id = "x", fileName = "x.json", startDateMs = t0, points = listOf(pt(1.0, 1.0, t0 + 9000))))
        val rec = TrackRecorder(store, FakeLocation(), { t0 })
        assertEquals(t0 + 9000, rec.track("x")!!.endDateMs)
        assertNotNull(store.loadAll().single().endDateMs)
    }
}
