package ch.overlandmap.map.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.overlandmap.map.ui.markup.Markup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A full-text hit: which object [type] and [documentId] matched. */
data class FtsHit(
    val type: String,
    val documentId: String,
    val name: String,
    val snippet: String,
    val trackPackId: String? = null,
)

/**
 * One object to index. [name] and [description] resolve the object's text for a
 * given language (translation, or the English original when it has none).
 */
data class FtsDoc(
    val type: String,
    val documentId: String,
    val name: (lang: String) -> String,
    /** The pack the object belongs to, so a pack's entries can be found and removed together. */
    val trackPackId: String? = null,
    val description: (lang: String) -> String,
)

/**
 * The SQLite FTS4 index: one table per supported language (`fts` for English,
 * `fts_<lang>` for the rest). Every object is written to every table with its
 * text for that language, so a search in the user's language matches both
 * translated and untranslated content. It is kept as raw SQL because one
 * logical index spans all object types and languages, which Room's per-entity
 * `@Fts4` mapping cannot express.
 */
class FtsIndex(private val db: AppDatabase) {

    private val sqlite: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    /** Inserts (replacing any existing rows) each object into every language table. */
    suspend fun index(docs: List<FtsDoc>) = withContext(Dispatchers.IO) {
        if (docs.isEmpty()) return@withContext
        val database = sqlite
        database.beginTransaction()
        try {
            docs.forEach { doc ->
                LANGUAGES.forEach { lang ->
                    val table = table(lang)
                    database.execSQL("DELETE FROM $table WHERE documentId = ?", arrayOf(doc.documentId))
                    // The index holds readable words, not link syntax: a search for "waypoint"
                    // must not match every `[x](waypoint)` link.
                    database.execSQL(
                        "INSERT INTO $table (name, description, type, documentId, trackPackId) " +
                            "VALUES (?, ?, ?, ?, ?)",
                        arrayOf(
                            Markup.stripForSearch(doc.name(lang)),
                            Markup.stripForSearch(doc.description(lang)),
                            doc.type, doc.documentId, doc.trackPackId,
                        ),
                    )
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Removes the given objects from every language table (on delete). */
    suspend fun deleteDocuments(documentIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (documentIds.isEmpty()) return@withContext
        val database = sqlite
        database.beginTransaction()
        try {
            documentIds.forEach { id ->
                LANGUAGES.forEach {
                    database.execSQL("DELETE FROM ${table(it)} WHERE documentId = ?", arrayOf(id))
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Removes everything indexed for a pack: its objects and the pack itself. */
    suspend fun deletePack(trackPackId: String) = withContext(Dispatchers.IO) {
        val database = sqlite
        database.beginTransaction()
        try {
            LANGUAGES.forEach {
                database.execSQL(
                    "DELETE FROM ${table(it)} WHERE trackPackId = ? OR documentId = ?",
                    arrayOf(trackPackId, trackPackId),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Clears every row of a type (before a full re-index, e.g. world data). */
    suspend fun deleteType(type: String) = withContext(Dispatchers.IO) {
        val database = sqlite
        LANGUAGES.forEach {
            database.execSQL("DELETE FROM ${table(it)} WHERE type = ?", arrayOf(type))
        }
    }

    /**
     * Full-text search of the [lang] table: the query is matched as a phrase whose last word
     * may be a prefix (iOS does the same with FTS5), so results appear as the user types.
     */
    suspend fun search(lang: String, query: String, limit: Int = DEFAULT_LIMIT): List<FtsHit> =
        withContext(Dispatchers.IO) {
            val match = matchExpression(query) ?: return@withContext emptyList()
            val table = table(if (lang in LANGUAGES) lang else "en")
            val sql = "SELECT type, documentId, trackPackId, name, snippet($table, '', '', '…') AS snip " +
                "FROM $table WHERE $table MATCH ? LIMIT ?"
            sqlite.query(SimpleSQLiteQuery(sql, arrayOf(match, limit))).use { c ->
                val typeCol = c.getColumnIndexOrThrow("type")
                val idCol = c.getColumnIndexOrThrow("documentId")
                val nameCol = c.getColumnIndexOrThrow("name")
                val snipCol = c.getColumnIndexOrThrow("snip")
                val packCol = c.getColumnIndexOrThrow("trackPackId")
                buildList {
                    while (c.moveToNext()) {
                        add(
                            FtsHit(
                                type = c.getString(typeCol),
                                documentId = c.getString(idCol),
                                name = c.getString(nameCol) ?: "",
                                snippet = c.getString(snipCol) ?: "",
                                trackPackId = c.getString(packCol),
                            )
                        )
                    }
                }
            }
        }

    companion object {
        // The `type` column holds the object kind under the names iOS uses (FTSIndexer.ObjectType).
        const val TYPE_TRACK_PACK = "TrackPack"
        const val TYPE_ITINERARY = "Itinerary"
        const val TYPE_STEP = "ItineraryStep"
        const val TYPE_WAYPOINT = "Waypoint"
        const val TYPE_SIDEBAR = "Sidebar"
        const val TYPE_CONTRIBUTED_WAYPOINT = "ContributedWaypoint"
        const val TYPE_DISCUSSION = "Discussion"
        const val TYPE_COUNTRY = "Country"
        const val TYPE_BORDER = "Border"
        const val TYPE_BORDER_POST = "BorderPost"
        const val TYPE_ZONE = "Zone"

        /** At most this many hits per search, as on iOS. */
        const val DEFAULT_LIMIT = 100

        /** English first (the `fts` table); the rest map to `fts_<code>`. */
        val LANGUAGES = listOf("en", "fr", "de", "es", "it", "nl", "pt", "ru")

        fun table(lang: String): String = if (lang == "en") "fts" else "fts_$lang"

        /** The column list of every FTS table, as iOS declares it (plus FTS4's `notindexed` options). */
        val COLUMNS = listOf("name", "description", "type", "documentId", "trackPackId")

        /** The `CREATE VIRTUAL TABLE` statement of a language's table. */
        fun createStatement(lang: String): String =
            "CREATE VIRTUAL TABLE IF NOT EXISTS ${table(lang)} USING fts4(" +
                "name, description, type, documentId, trackPackId, tokenize=unicode61, " +
                "notindexed=documentId, notindexed=type, notindexed=trackPackId)"

        /**
         * Creates the per-language FTS4 tables; safe to call on every open. A table left over
         * from a build that didn't have the `trackPackId` column is dropped and recreated (the
         * index is rebuilt from the content tables).
         */
        fun createTables(db: SupportSQLiteDatabase) {
            LANGUAGES.forEach { lang ->
                val name = table(lang)
                if (!hasColumn(db, name, "trackPackId") && tableExists(db, name)) {
                    db.execSQL("DROP TABLE $name")
                }
                db.execSQL(createStatement(lang))
            }
        }

        private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
            db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
                .use { it.moveToFirst() }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info($table)").use { c ->
                val nameCol = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(nameCol) else null }
                    .any { it == column }
            }

        /**
         * Turns the user's text into an FTS MATCH expression: one phrase whose last word is a
         * prefix (`"umling la*"`). Quotes and `*` in the input are dropped so they can't act as
         * operators. Null when nothing is searchable.
         */
        internal fun matchExpression(query: String): String? {
            val cleaned = query.replace("\"", " ").replace("*", " ").trim()
                .replace(Regex("\\s+"), " ")
                .trimEnd { !it.isLetterOrDigit() }
            return if (cleaned.isEmpty()) null else "\"$cleaned*\""
        }
    }
}
