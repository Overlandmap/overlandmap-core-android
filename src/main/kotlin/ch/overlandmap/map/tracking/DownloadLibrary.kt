package ch.overlandmap.map.tracking

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.geo.Geohash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/** Website and import batches are local user data, independent of the redownloadable pack database. */
data class DownloadWebsite(val id: String, val name: String, val url: String, val bounds: GeoBounds? = null)
data class TrackFolder(val id: String, val websiteId: String?, val name: String, val downloadDate: Long, val bounds: GeoBounds? = null)

class DownloadLibrary(context: Context, private val recorder: TrackRecorder, scope: CoroutineScope) {
    private val helper = object : SQLiteOpenHelper(context, "downloaded_tracks.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE download_website (id TEXT PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL UNIQUE, south REAL, north REAL, west REAL, east REAL)")
            db.execSQL("CREATE TABLE track_folder (id TEXT PRIMARY KEY, website_id TEXT, name TEXT NOT NULL, downloaded_at INTEGER NOT NULL, south REAL, north REAL, west REAL, east REAL)")
            db.execSQL("CREATE INDEX track_folder_website ON track_folder(website_id)")
            db.execSQL("CREATE TABLE imported_index (id TEXT PRIMARY KEY, track_id TEXT NOT NULL, geohash TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, waypoint INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX imported_geohash ON imported_index(geohash)")
            createTextIndex(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createTextIndex(db)
        }
        private fun createTextIndex(db: SQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS imported_text USING fts4(name, description, object_id, track_id, waypoint, notindexed=object_id, notindexed=track_id, notindexed=waypoint, tokenize=unicode61)")
        }
    }
    private val _websites = MutableStateFlow<List<DownloadWebsite>>(emptyList())
    val websites = _websites.asStateFlow()
    private val _folders = MutableStateFlow<List<TrackFolder>>(emptyList())
    val folders = _folders.asStateFlow()
    private var indexedTracks: List<RecordedTrack>? = null
    init {
        recorder.onImportedContentChanged = { refreshBoundsAndIndex() }
        scope.launch(Dispatchers.IO) {
            reload()
            recorder.recordings.collect { refreshBoundsAndIndex() }
        }
    }
    @Synchronized private fun reload() {
        val db = helper.readableDatabase
        fun android.database.Cursor.bounds(): GeoBounds? = if (isNull(getColumnIndexOrThrow("south"))) null else GeoBounds(
            getDouble(getColumnIndexOrThrow("south")), getDouble(getColumnIndexOrThrow("north")),
            getDouble(getColumnIndexOrThrow("west")), getDouble(getColumnIndexOrThrow("east")))
        _websites.value = db.rawQuery("SELECT * FROM download_website ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(DownloadWebsite(c.getString(0), c.getString(1), c.getString(2), c.bounds())) }
        }
        _folders.value = db.rawQuery("SELECT * FROM track_folder ORDER BY downloaded_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(TrackFolder(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.bounds())) }
        }
    }
    @Synchronized fun seedWebsites() {
        reload()
        if (_websites.value.isNotEmpty()) return
        listOf("TET" to "https://transeurotrail.org", "BDR" to "https://ridebdr.com", "ACT" to "https://adventurecountrytracks.com", "WikiLoc" to "https://www.wikiloc.com").forEach { (name, url) -> website(url, name) }
    }
    @Synchronized fun website(address: String, name: String? = null): DownloadWebsite {
        reload()
        val uri = URI(if (address.contains("://")) address else "https://$address")
        require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty()) { "Invalid website address" }
        val host = uri.host.lowercase().removePrefix("www.")
        val root = "${uri.scheme}://${uri.rawAuthority}/"
        _websites.value.firstOrNull { URI(it.url).host.lowercase().removePrefix("www.") == host }?.let { return it }
        val w = DownloadWebsite(UUID.randomUUID().toString(), name ?: host, root)
        helper.writableDatabase.insertOrThrow("download_website", null, ContentValues().apply { put("id", w.id); put("name", w.name); put("url", w.url) })
        reload(); return w
    }
    fun existingFolder(websiteId: String?, name: String) = _folders.value.firstOrNull { it.websiteId == websiteId && it.name.equals(name.trim(), true) }
    @Synchronized fun commit(websiteId: String?, name: String, tracks: List<RecordedTrack>, replace: Boolean = false): TrackFolder {
        require(name.isNotBlank() && tracks.isNotEmpty())
        val folder = existingFolder(websiteId, name) ?: TrackFolder(UUID.randomUUID().toString(), websiteId, name.trim(), System.currentTimeMillis())
        val replaced = if (replace) recorder.recordings.value.filter { it.trackFolderId == folder.id } else emptyList()
        helper.writableDatabase.insertWithOnConflict("track_folder", null, ContentValues().apply {
            put("id", folder.id); put("website_id", websiteId); put("name", name.trim()); put("downloaded_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        recorder.addImported(tracks.map { t -> t.copy(downloadWebsiteId = websiteId, trackFolderId = folder.id,
            waypoints = t.waypoints.map { it.copy(downloadWebsiteId = websiteId, trackFolderId = folder.id) }) })
        replaced.forEach { recorder.delete(it.id) }
        reload(); refreshBoundsAndIndex()
        return _folders.value.first { it.id == folder.id }
    }
    @Synchronized fun rename(folder: TrackFolder, name: String) {
        require(name.isNotBlank() && (existingFolder(folder.websiteId, name)?.id ?: folder.id) == folder.id)
        helper.writableDatabase.update("track_folder", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(folder.id)); reload()
    }
    @Synchronized fun clearFolder(id: String) {
        recorder.recordings.value.filter { it.trackFolderId == id }.forEach { recorder.delete(it.id) }
        refreshBoundsAndIndex()
    }
    @Synchronized fun deleteFolder(id: String) {
        recorder.recordings.value.filter { it.trackFolderId == id }.forEach { recorder.delete(it.id) }
        helper.writableDatabase.delete("track_folder", "id = ?", arrayOf(id)); reload(); refreshBoundsAndIndex(force = true)
    }
    @Synchronized fun deleteWebsiteContent(id: String) {
        _folders.value.filter { it.websiteId == id }.forEach { deleteFolder(it.id) }
    }
    @Synchronized private fun refreshBoundsAndIndex(force: Boolean = false) {
        val tracks = recorder.recordings.value
        if (indexedTracks === tracks && !force) return
        reload()
        val db = helper.writableDatabase
        val extents = calculateDownloadExtents(_folders.value, tracks)
        fun boundsValues(b: GeoBounds?) = ContentValues().apply {
            if (b == null) listOf("south", "north", "west", "east").forEach(::putNull) else {
                put("south", b.latMin); put("north", b.latMax); put("west", b.lonMin); put("east", b.lonMax)
            }
        }
        db.beginTransaction()
        try {
            _folders.value.forEach { f -> db.update("track_folder", boundsValues(extents.folders[f.id]), "id = ?", arrayOf(f.id)) }
            _websites.value.forEach { w -> db.update("download_website", boundsValues(extents.websites[w.id]), "id = ?", arrayOf(w.id)) }
            if (indexedTracks !== tracks) {
                db.delete("imported_index", null, null)
                db.delete("imported_text", null, null)
                for (t in tracks.filter { it.isImported }) {
                    fun index(id: String, lat: Double, lon: Double, waypoint: Boolean, name: String, description: String) {
                        db.insert("imported_index", null, ContentValues().apply {
                            put("id", id); put("track_id", t.id); put("latitude", lat); put("longitude", lon)
                            put("geohash", Geohash.encode(lat, lon)); put("waypoint", if (waypoint) 1 else 0)
                        })
                        db.insertOrThrow("imported_text", null, ContentValues().apply {
                            put("object_id", id); put("track_id", t.id); put("waypoint", if (waypoint) 1 else 0)
                            put("name", ch.overlandmap.map.ui.markup.Markup.stripForSearch(name))
                            put("description", ch.overlandmap.map.ui.markup.Markup.stripForSearch(description))
                        })
                    }
                    if (t.points.isNotEmpty()) t.trackBounds?.let { index(t.id, (it.latMin + it.latMax) / 2, (it.lonMin + it.lonMax) / 2, false, t.displayName, t.description) }
                    t.waypoints.forEach { index(it.id, it.latitude, it.longitude, true, it.label, it.description) }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        indexedTracks = tracks
        reload()
    }
    /** Text and spatial indexes are rebuilt together, including after rename or deletion. */
    @Synchronized fun textSearch(query: String): List<ch.overlandmap.map.data.SearchResult> {
        refreshBoundsAndIndex()
        val match = ch.overlandmap.map.data.local.FtsIndex.matchExpression(query) ?: return emptyList()
        return helper.readableDatabase.rawQuery(
            "SELECT object_id, track_id, waypoint, name, snippet(imported_text, '', '', '…', 1) FROM imported_text WHERE imported_text MATCH ? LIMIT 100",
            arrayOf(match),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val track = recorder.track(cursor.getString(1)) ?: continue
                    val waypoint = if (cursor.getInt(2) == 1) track.waypoints.firstOrNull { it.id == cursor.getString(0) } ?: continue else null
                    add(ch.overlandmap.map.data.SearchResult(
                        type = if (waypoint == null) "imported_track" else "imported_waypoint",
                        documentId = cursor.getString(0), name = cursor.getString(3), snippet = cursor.getString(4),
                        importedTrack = track, importedWaypoint = waypoint,
                    ))
                }
            }
        }
    }

    data class IndexedPoint(val id: String, val trackId: String, val lat: Double, val lon: Double, val waypoint: Boolean)
    @Synchronized fun nearby(prefixes: List<String>): List<IndexedPoint> {
        refreshBoundsAndIndex()
        return prefixes.flatMap { prefix ->
        helper.readableDatabase.rawQuery("SELECT id, track_id, latitude, longitude, waypoint FROM imported_index WHERE geohash >= ? AND geohash < ?", arrayOf(prefix, prefix + "~")).use { c ->
            buildList { while (c.moveToNext()) add(IndexedPoint(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getInt(4) == 1)) }
        }
    }.distinctBy { it.id }
    }
}
