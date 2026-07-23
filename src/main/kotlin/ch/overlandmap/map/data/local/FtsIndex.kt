package ch.overlandmap.map.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A full-text hit: which object [type] and [documentId] matched. */
data class FtsHit(
    val type: String,
    val documentId: String,
    val name: String,
    val snippet: String,
)

/**
 * One object to index. [name] and [description] resolve the object's text for a
 * given language (translation, or the English original when it has none).
 */
data class FtsDoc(
    val type: String,
    val documentId: String,
    val name: (lang: String) -> String,
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
                    database.execSQL(
                        "INSERT INTO $table (name, description, type, documentId) VALUES (?, ?, ?, ?)",
                        arrayOf(doc.name(lang), doc.description(lang), doc.type, doc.documentId),
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

    /** Clears every row of a type (before a full re-index, e.g. world data). */
    suspend fun deleteType(type: String) = withContext(Dispatchers.IO) {
        val database = sqlite
        LANGUAGES.forEach {
            database.execSQL("DELETE FROM ${table(it)} WHERE type = ?", arrayOf(type))
        }
    }

    /** Full-text search of the [lang] table; prefix-matches each query token. */
    suspend fun search(lang: String, query: String, limit: Int = 50): List<FtsHit> =
        withContext(Dispatchers.IO) {
            val match = matchExpression(query) ?: return@withContext emptyList()
            val table = table(if (lang in LANGUAGES) lang else "en")
            val sql = "SELECT type, documentId, name, snippet($table, '', '', '…') AS snip " +
                "FROM $table WHERE $table MATCH ? LIMIT ?"
            sqlite.query(SimpleSQLiteQuery(sql, arrayOf(match, limit))).use { c ->
                val typeCol = c.getColumnIndexOrThrow("type")
                val idCol = c.getColumnIndexOrThrow("documentId")
                val nameCol = c.getColumnIndexOrThrow("name")
                val snipCol = c.getColumnIndexOrThrow("snip")
                buildList {
                    while (c.moveToNext()) {
                        add(
                            FtsHit(
                                type = c.getString(typeCol),
                                documentId = c.getString(idCol),
                                name = c.getString(nameCol) ?: "",
                                snippet = c.getString(snipCol) ?: "",
                            )
                        )
                    }
                }
            }
        }

    companion object {
        const val TYPE_TRACK_PACK = "track_pack"
        const val TYPE_ITINERARY = "itinerary"
        const val TYPE_STEP = "itinerary_step"
        const val TYPE_WAYPOINT = "waypoint"
        const val TYPE_SIDEBAR = "sidebar"
        const val TYPE_COUNTRY = "country"
        const val TYPE_BORDER = "border"
        const val TYPE_BORDER_POST = "border_post"

        /** English first (the `fts` table); the rest map to `fts_<code>`. */
        val LANGUAGES = listOf("en", "fr", "de", "es", "it", "nl", "pt", "ru")

        fun table(lang: String): String = if (lang == "en") "fts" else "fts_$lang"

        /** Creates the per-language FTS4 tables; safe to call on every open. */
        fun createTables(db: SupportSQLiteDatabase) {
            LANGUAGES.forEach { lang ->
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS ${table(lang)} USING fts4(" +
                        "name, description, type, documentId, " +
                        "notindexed=documentId, notindexed=type)"
                )
            }
        }

        /**
         * Turns the user's text into an FTS MATCH expression: each token
         * stripped of operators and prefix-matched (`man* leh*`), so partial
         * words match as the user types. Null when nothing is searchable.
         */
        private fun matchExpression(query: String): String? {
            val tokens = query.trim().split(Regex("\\s+"))
                .map { token -> token.filter { it.isLetterOrDigit() } }
                .filter { it.isNotBlank() }
            return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "$it*" }
        }
    }
}
