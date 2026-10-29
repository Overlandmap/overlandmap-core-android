package ch.overlandmap.map.tracking

import ch.overlandmap.map.geo.Coordinate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A position fix, independent of the platform API (so the recorder and navigation are testable). */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val timeMs: Long,
    /** Horizontal accuracy in metres; negative when unknown (the fix is then ignored). */
    val accuracyM: Float = 5f,
    /** Direction of travel in degrees, when the receiver reports a valid one. */
    val courseDeg: Float? = null,
    /** Ground speed in m/s, when the receiver reports one. */
    val speedMps: Float? = null,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
}

/** A stream of fixes: started with a minimum distance between updates, stopped when not needed. */
interface LocationSource {
    /** The most recent fix seen, if any. */
    val lastFix: LocationFix?
    fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit)
    fun stop()
}

class TrackImportException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_GPX, EMPTY_GPX }
}

/** The track files in one directory (iOS keeps them in Documents). */
class TrackStore(private val directory: File) {

    fun loadAll(): List<RecordedTrack> =
        (directory.listFiles { f -> f.extension.equals("json", ignoreCase = true) } ?: emptyArray())
            .mapNotNull { runCatching { RecordedTrack.fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.startDateMs }

    fun save(track: RecordedTrack) {
        directory.mkdirs()
        val target = File(directory, track.fileName)
        val temp = File(directory, track.fileName + ".tmp")
        temp.writeText(track.toJson())
        // Replace atomically so a crash mid-write never loses the previous version.
        if (!temp.renameTo(target)) {
            target.writeText(track.toJson())
            temp.delete()
        }
    }

    fun delete(track: RecordedTrack) {
        File(directory, track.fileName).delete()
    }

    /** `yyyy-MM-dd.json`, or `yyyy-MM-dd-N.json` when that day already has a track. */
    fun uniqueFileName(dateMs: Long): String {
        val base = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dateMs))
        if (!File(directory, "$base.json").exists()) return "$base.json"
        var suffix = 1
        while (File(directory, "$base-$suffix.json").exists()) suffix++
        return "$base-$suffix.json"
    }
}

/**
 * Records GPS tracks and keeps the recordings and imported tracks (iOS `TrackRecorder`). The
 * current recording is written to its file as it grows, at most every [saveIntervalMs], and on
 * every stop and waypoint, so little is lost if the process dies.
 */
class TrackRecorder(
    private val store: TrackStore,
    private val location: LocationSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val saveIntervalMs: Long = 10_000,
    /** Called with true when a recording starts and false when it ends (to run the foreground service). */
    private val onRecordingChanged: (Boolean) -> Unit = {},
) {
    private val _recordings = MutableStateFlow<List<RecordedTrack>>(emptyList())
    val recordings: StateFlow<List<RecordedTrack>> = _recordings.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    /** The id of the track being recorded, or null. */
    val currentTrackId: StateFlow<String?> = _currentId.asStateFlow()

    val isRecording: Boolean get() = _currentId.value != null

    /** The downloaded library persists extents and both indexes before an import mutation returns. */
    internal var onImportedContentChanged: ((List<RecordedTrack>) -> Unit)? = null

    private var lastSaveMs = 0L

    init {
        load()
    }

    val recordedTracks: List<RecordedTrack> get() = _recordings.value.filter { !it.isImported }
    val importedTracks: List<RecordedTrack> get() = _recordings.value.filter { it.isImported }
    val currentTrack: RecordedTrack? get() = _currentId.value?.let { id -> _recordings.value.firstOrNull { it.id == id } }

    fun track(id: String): RecordedTrack? = _recordings.value.firstOrNull { it.id == id }

    /** Loads the files; a track left open by a crash is closed at its last point. */
    fun load() {
        _recordings.value = store.loadAll().map { stored ->
            val t = stored.withImportGeohashes()
            if (t !== stored) store.save(t)
            if (t.endDateMs == null && t.id != _currentId.value) {
                t.copy(endDateMs = t.points.lastOrNull()?.timestampMs ?: t.startDateMs).also(store::save)
            } else t
        }
    }

    fun toggleRecording() = if (isRecording) stopRecording() else startRecording()

    fun startRecording() {
        if (isRecording) return
        val start = now()
        val track = RecordedTrack(fileName = store.uniqueFileName(start), startDateMs = start)
        _recordings.update { listOf(track) + it }
        _currentId.value = track.id
        store.save(track)
        lastSaveMs = start
        onRecordingChanged(true)
        location.start(MIN_DISTANCE_M) { fix -> onFix(fix) }
    }

    fun stopRecording() {
        val id = _currentId.value ?: return
        location.stop()
        val ended = update(id) { it.copy(endDateMs = now()) }
        ended?.let(store::save)
        _currentId.value = null
        onRecordingChanged(false)
    }

    internal fun onFix(fix: LocationFix) {
        if (fix.accuracyM < 0) return
        addPoint(RecordedPoint(fix.latitude, fix.longitude, fix.altitude, fix.timeMs))
    }

    fun addPoint(point: RecordedPoint) {
        val id = _currentId.value ?: return
        val updated = update(id) { it.copy(points = it.points + point) } ?: return
        if (now() - lastSaveMs >= saveIntervalMs) {
            store.save(updated)
            lastSaveMs = now()
        }
    }

    /** Drops a labelled waypoint at [coordinate], else at the last fix, else at the last recorded point. */
    fun addWaypoint(label: String, coordinate: Coordinate? = null, elevation: Double? = null) {
        val id = _currentId.value ?: return
        val track = track(id) ?: return
        val fix = location.lastFix
        val (at, ele) = when {
            coordinate != null -> coordinate to elevation
            fix != null -> fix.coordinate to fix.altitude
            track.points.isNotEmpty() -> track.points.last().let { it.coordinate to it.elevation }
            else -> return
        }
        val waypoint = RecordedWaypoint(latitude = at.latitude, longitude = at.longitude, elevation = ele, timestampMs = now(), label = label)
        update(id) { it.copy(waypoints = it.waypoints + waypoint) }?.let {
            store.save(it)
            lastSaveMs = now()
        }
    }

    /** Imports GPX [data] as a displayed, imported track. */
    fun importGpx(data: ByteArray): RecordedTrack {
        val parsed = GpxParser.parse(data, now()) ?: throw TrackImportException(TrackImportException.Reason.INVALID_GPX)
        if (parsed.points.isEmpty() && parsed.waypoints.isEmpty()) throw TrackImportException(TrackImportException.Reason.EMPTY_GPX)
        val end = parsed.points.lastOrNull()?.timestampMs ?: parsed.waypoints.last().timestampMs
        val track = RecordedTrack(
            fileName = store.uniqueFileName(parsed.startDateMs),
            name = parsed.name,
            startDateMs = parsed.startDateMs,
            endDateMs = end,
            points = parsed.points,
            isDisplayed = true,
            waypoints = parsed.waypoints,
            isImported = true,
        )
        val imported = track.withImportGeohashes()
        _recordings.update { listOf(imported) + it }
        store.save(imported)
        onImportedContentChanged?.invoke(_recordings.value)
        return imported
    }

    /** Publishes a parsed import only after the user has chosen its folder. */
    fun addImported(tracks: List<RecordedTrack>) {
        val imported = tracks.map { it.withImportGeohashes() }
        imported.forEach(store::save)
        _recordings.update { imported + it }
        onImportedContentChanged?.invoke(_recordings.value)
    }

    fun toggleDisplayed(id: String) {
        update(id) { it.copy(isDisplayed = !it.isDisplayed) }?.let(store::save)
    }

    fun setDisplayed(id: String, displayed: Boolean) {
        track(id)?.takeIf { it.isDisplayed != displayed }?.let { toggleDisplayed(id) }
    }

    fun simplify(id: String, minDistanceMeters: Double = 10.0) {
        update(id) { it.simplified(minDistanceMeters) }?.let(store::save)
    }

    fun updateColor(id: String, colorHex: String) {
        update(id) { it.copy(colorHex = colorHex) }?.let(store::save)
    }

    fun rename(id: String, name: String?) {
        update(id) { it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }) }?.let(store::save)
    }

    /** Find the owning imported track: a viewer may display waypoints from sibling tracks. */
    fun deleteImportedWaypoint(waypointId: String) {
        val owners = _recordings.value.filter { track -> track.isImported && track.waypoints.any { it.id == waypointId } }
        owners.forEach { track ->
            update(track.id) { it.copy(waypoints = it.waypoints.filterNot { waypoint -> waypoint.id == waypointId }) }?.let(store::save)
        }
    }

    fun delete(id: String) {
        val track = track(id) ?: return
        if (_currentId.value == id) {
            location.stop()
            _currentId.value = null
            onRecordingChanged(false)
        }
        _recordings.update { list -> list.filter { it.id != id } }
        store.delete(track)
        if (track.isImported) onImportedContentChanged?.invoke(_recordings.value)
    }

    private fun update(id: String, change: (RecordedTrack) -> RecordedTrack): RecordedTrack? {
        var result: RecordedTrack? = null
        _recordings.update { list ->
            list.map { t -> if (t.id == id) change(t).withImportGeohashes().also { result = it } else t }
        }
        if (result?.isImported == true) onImportedContentChanged?.invoke(_recordings.value)
        return result
    }

    companion object {
        /** Minimum distance between recorded fixes. */
        const val MIN_DISTANCE_M = 5f
    }
}
