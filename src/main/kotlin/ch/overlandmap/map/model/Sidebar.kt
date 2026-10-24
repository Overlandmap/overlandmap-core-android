package ch.overlandmap.map.model

import ch.overlandmap.map.AppConfig

/**
 * An informational article of a track pack (Firestore collection `sidebar`).
 * Port of `models/sidebar.dart`, reduced to the displayable fields.
 */
data class Sidebar(
    val documentId: String,
    val trackPackId: String,
    val name: String,
    val translatedName: Map<String, String>? = null,
    val description: String? = null,
    val translatedDesc: Map<String, String>? = null,
    val titlePhotoId: String? = null,
    val titlePhotoCaption: String? = null,
    val translatedTitleCaption: Map<String, String>? = null,
    /** Absolute path of the title photo unpacked from a downloaded zip. */
    val localPhotoPath: String? = null,
    /** Captions of the extra photos, in order (`otherPhotoCaptions`). */
    val otherPhotoCaptions: List<String>? = null,
    val translatedOtherPhotoCaptions: Map<String, List<String>>? = null,
    /** Absolute paths of the extra photos unpacked from a downloaded zip. */
    val localOtherPhotoPaths: List<String>? = null,
    /** Unmodelled fields: `hasMap`, the `table*` definition… (sidebars are small, so all are kept). */
    val extra: Map<String, Any?> = emptyMap(),
) {
    val titlePhotoUrl: String?
        get() = localPhotoPath?.let { "file://$it" } ?: titlePhotoId?.let(AppConfig::photoUrl)

    /** Whether the article shows the pack's map above its text. */
    val hasMap: Boolean get() = FS.boolOrNull(extra["hasMap"]) ?: false

    /** Column definitions: `[{"type": "...", "field": "...", "title": {"en": "..."}}, …]`. */
    val tableColumns: List<Map<String, Any?>>?
        get() = (extra["tableColumns"] as? List<*>)?.mapNotNull { column ->
            (column as? Map<*, *>)?.entries?.filter { it.key is String }?.associate { it.key as String to it.value }
        }?.takeIf { it.isNotEmpty() }

    val hasTable: Boolean get() = tableColumns != null

    /** The table heading per language, stored as a JSON string. */
    val tableNames: Map<String, String>?
        get() = (extra["tableNames"] as? String)?.let { raw ->
            runCatching {
                val obj = org.json.JSONObject(raw)
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    /** `Waypoint` (default), `ItineraryStep` or `Itinerary`. */
    val tableEntity: String? get() = FS.str(extra["tableEntity"])

    /** A flag or maki name rows must have; without one, rows are those marked with this sidebar's id. */
    val tableFilter: String? get() = FS.str(extra["tableFilter"])

    /** Sort field and direction: `{"field": "ele", "direction": "desc"}`. */
    val tableSort: Pair<String, Boolean>?
        get() = (extra["tableSort"] as? Map<*, *>)?.let { m ->
            val field = FS.str(m["field"]) ?: return@let null
            field to (FS.str(m["direction"]) == "desc")
        }

    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    fun description(lang: String): String? = localized(description, translatedDesc, lang)

    fun titlePhotoCaption(lang: String): String? = localized(titlePhotoCaption, translatedTitleCaption, lang)

    fun otherPhotoCaption(index: Int, lang: String): String? =
        localized(otherPhotoCaptions?.getOrNull(index),
            translatedOtherPhotoCaptions?.mapNotNull { (language, captions) ->
                captions.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { language to it }
            }?.toMap(), lang)

    companion object {
        private val KNOWN = arrayOf(
            "name", "trackPackId", "translatedName", "description", "translatedDesc", "titlePhotoId",
            "titlePhotoCaption", "translatedTitleCaption", "otherPhotoCaptions", "translatedOtherPhotoCaptions", "titlePhotoUrl", "otherPhotoUrls",
        )

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Sidebar(
            documentId = documentId,
            trackPackId = FS.str(data["trackPackId"]) ?: "",
            name = FS.str(data["name"]) ?: "",
            translatedName = FS.stringMap(data["translatedName"]),
            description = FS.str(data["description"]),
            translatedDesc = FS.stringMap(data["translatedDesc"]),
            titlePhotoId = FS.str(data["titlePhotoId"]),
            titlePhotoCaption = FS.str(data["titlePhotoCaption"]),
            translatedTitleCaption = FS.stringMap(data["translatedTitleCaption"]),
            otherPhotoCaptions = FS.stringList(data["otherPhotoCaptions"]),
            translatedOtherPhotoCaptions = FS.captionLists(data["translatedOtherPhotoCaptions"]),
            extra = FS.jsonSafe(data).without(*KNOWN),
        )
    }
}
