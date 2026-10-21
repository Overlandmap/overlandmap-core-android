package ch.overlandmap.map.data.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compares the schema Room exports (`core/schemas/.../<version>.json`, written at build time)
 * with the iOS `Schema.swift` (generation 8): same tables, same columns with the same type
 * affinity, same index names. Tables that exist only on Android are listed explicitly.
 */
class SchemaParityTest {

    /** table -> column -> affinity. `BIGINT` and `INTEGER` are both INTEGER affinity. */
    private val ios: Map<String, Map<String, String>> = mapOf(
        "track_pack" to cols(
            "documentId TEXT", "editorId TEXT", "name TEXT", "description TEXT", "region TEXT",
            "latMin REAL", "latMax REAL", "lonMin REAL", "lonMax REAL", "lastVersionCheck TEXT",
            "isOld INTEGER", "json TEXT",
        ),
        "itinerary" to cols(
            "documentId TEXT", "trackPackId TEXT", "itineraryId TEXT", "name TEXT", "description TEXT",
            "lengthKm REAL", "lengthDays INTEGER", "difficulty INTEGER", "roadType INTEGER",
            "latMin REAL", "latMax REAL", "lonMin REAL", "lonMax REAL", "centerLat REAL",
            "centerLon REAL", "lastSeen TEXT", "favourite TEXT", "json TEXT",
        ),
        "itinerary_step" to cols(
            "documentId TEXT", "stepId INTEGER", "itineraryId TEXT", "trackPackId TEXT", "name TEXT",
            "description TEXT", "geohash TEXT", "json TEXT",
        ),
        "waypoint" to cols(
            "documentId TEXT", "trackPackId TEXT", "itineraryDocId TEXT", "name TEXT",
            "description TEXT", "geohash TEXT", "json TEXT",
        ),
        "sidebar" to cols("documentId TEXT", "trackPackId TEXT", "name TEXT", "description TEXT", "json TEXT"),
        "track" to cols(
            "documentId TEXT", "trackPackId TEXT", "itineraryDocId TEXT", "name TEXT",
            "description TEXT", "json TEXT",
        ),
        "comment" to cols(
            "documentId TEXT", "objectId TEXT", "geohash TEXT", "type TEXT", "rating INTEGER",
            "itinDocumentId TEXT", "createdAt INTEGER", "userId TEXT", "json TEXT",
        ),
        "contributed_waypoint" to cols(
            "documentId TEXT", "active INTEGER", "trackPackId TEXT", "geohash TEXT", "type TEXT",
            "itinDocumentId TEXT", "lastUpdate INTEGER", "userId TEXT", "json TEXT",
        ),
        "check_in" to cols(
            "documentId TEXT", "objectId TEXT", "trackPackId TEXT", "itinDocumentId TEXT",
            "reason TEXT", "createdAt INTEGER", "userId TEXT", "json TEXT",
        ),
        "vote" to cols(
            "documentId TEXT", "objectId TEXT", "trackPackId TEXT", "content TEXT",
            "createdAt INTEGER", "userId TEXT", "upVote INTEGER", "json TEXT",
        ),
        "discussion" to cols(
            "documentId TEXT", "objectId TEXT", "topic TEXT", "geohash TEXT", "createdAt INTEGER",
            "userId TEXT", "json TEXT",
        ),
        "country" to cols(
            "documentId TEXT", "name TEXT", "iso_a2 TEXT", "overlanding INTEGER", "carnet INTEGER",
            "visa INTEGER", "updatedAt INTEGER", "json TEXT",
        ),
        "border" to cols(
            "documentId TEXT", "is_open INTEGER", "country1 TEXT", "country2 TEXT",
            "updatedAt INTEGER", "json TEXT",
        ),
        "border_post" to cols(
            "documentId TEXT", "name TEXT", "is_open INTEGER", "borderDocId TEXT", "geohash TEXT",
            "updatedAt INTEGER", "json TEXT",
        ),
        "zone" to cols(
            "documentId TEXT", "name TEXT", "country TEXT", "type INTEGER", "updatedAt INTEGER",
            "json TEXT",
        ),
        "climate" to cols(
            "documentId TEXT", "geohash TEXT", "month INTEGER", "updatedAt INTEGER", "json TEXT",
        ),
        "setting" to cols("documentId TEXT", "updatedAt INTEGER", "json TEXT"),
        "sync_state" to cols("trackPackId TEXT", "kind TEXT", "lastSync INTEGER"),
        "download_item" to cols(
            "id TEXT", "trackPackId TEXT", "kind TEXT", "url TEXT", "assetId TEXT",
            "expectedBytes INTEGER", "receivedBytes INTEGER", "status TEXT", "error TEXT",
            "localPath TEXT", "sessionTaskId INTEGER", "updatedAt INTEGER",
        ),
        "schema_meta" to cols("name TEXT", "value TEXT"),
    )

    private val iosIndexes = setOf(
        "idx_trackPack_name", "idx_trackPack_region", "idx_trackPack_editor", "idx_trackPack_bounds",
        "idx_itinerary_trackPack", "idx_itinerary_itineraryId", "idx_itinerary_name",
        "idx_itinerary_lastSeen", "idx_itinerary_lengthKm", "idx_itinerary_lengthDays",
        "idx_itinerary_difficulty", "idx_itinerary_roadType", "idx_itinerary_bounds",
        "idx_itinerary_centre", "idx_step_itinerary", "idx_step_trackPack", "idx_step_geohash",
        "idx_waypoint_trackPack", "idx_waypoint_itinerary", "idx_waypoint_geohash",
        "idx_waypoint_name", "idx_sidebar_trackPack", "idx_sidebar_name", "idx_track_trackPack",
        "idx_track_itinerary", "idx_comment_object", "idx_comment_itin", "idx_checkin_object",
        "idx_checkin_trackPack", "idx_contributed_trackPack", "idx_contributed_itin",
        "idx_contributed_geohash", "idx_discussion_object", "idx_vote_object", "idx_vote_trackPack",
        "idx_border_countries", "idx_border_country2", "idx_border_isOpen", "idx_borderPost_border",
        "idx_borderPost_geohash", "idx_zone_country", "idx_climate_geohash", "idx_download_trackPack",
        "idx_download_status",
    )

    /** Tables only Android has: the pack's downloaded-asset list (iOS derives it elsewhere). */
    private val androidOnlyTables = setOf("pack_asset")

    private fun cols(vararg defs: String): Map<String, String> =
        defs.associate { it.substringBefore(' ') to it.substringAfter(' ') }

    private fun exported(): JSONObject {
        val dir = File("schemas/ch.overlandmap.map.data.local.AppDatabase")
        val file = File(dir, "${AppDatabase.CURRENT_VERSION}.json")
        assertTrue("Room schema not exported: ${file.absolutePath}", file.isFile)
        return JSONObject(file.readText()).getJSONObject("database")
    }

    @Test
    fun tablesAndColumnsMatchIos() {
        val entities = exported().getJSONArray("entities")
        val android = HashMap<String, Map<String, String>>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val fields = e.getJSONArray("fields")
            android[e.getString("tableName")] = (0 until fields.length()).associate {
                val f = fields.getJSONObject(it)
                f.getString("columnName") to f.getString("affinity")
            }
        }
        assertEquals(ios.keys, android.keys - androidOnlyTables)
        for ((table, columns) in ios) {
            assertEquals("columns of $table", columns, android[table])
        }
    }

    @Test
    fun indexesMatchIos() {
        val entities = exported().getJSONArray("entities")
        val names = HashSet<String>()
        for (i in 0 until entities.length()) {
            val indices = entities.getJSONObject(i).optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) names += indices.getJSONObject(j).getString("name")
        }
        assertEquals(iosIndexes, names)
    }

    @Test
    fun ftsTablesCarryTheSameColumnsAsIos() {
        // iOS: name, description, type, documentId, trackPackId per language table.
        assertEquals(listOf("name", "description", "type", "documentId", "trackPackId"), FtsIndex.COLUMNS)
        assertEquals(listOf("en", "fr", "de", "es", "it", "nl", "pt", "ru").sorted(), FtsIndex.LANGUAGES.sorted())
        assertEquals("fts", FtsIndex.table("en"))
        assertEquals("fts_fr", FtsIndex.table("fr"))
        assertTrue(FtsIndex.createStatement("de").contains("trackPackId"))
    }

    @Test
    fun syncKindsMatchIosRawValues() {
        assertEquals(
            listOf("comments", "checkIns", "votes", "contributedWaypoints", "trackPackVersion", "overlandData"),
            ch.overlandmap.map.data.SyncKind.entries.map { it.raw },
        )
    }
}
