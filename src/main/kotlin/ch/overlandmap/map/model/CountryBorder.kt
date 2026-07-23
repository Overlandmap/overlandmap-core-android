package ch.overlandmap.map.model


/** Open state shared by borders and border posts; raw values match Firestore. */
enum class BorderOpenState(val raw: Int, val colorHex: String) {
    CLOSED(0, "#ef4444"),
    BILATERAL(1, "#3b82f6"),
    OPEN(2, "#22c55e"),
    RESTRICTIONS(3, "#eab308"),
    UNKNOWN(-1, "#9ca3af");

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * A border between two countries (Firestore collection `border`). Port of
 * `models/country_border.dart`. [geomString] holds the GeoJSON geometry used
 * to draw the border line on the map.
 */
data class CountryBorder(
    val documentId: String,
    val name: String,
    val country1: String? = null,
    val country2: String? = null,
    val isOpen: Int = BorderOpenState.UNKNOWN.raw,
    val geomType: String? = null,
    val geomString: String? = null,
    val comment: String? = null,
    val commentTranslations: Map<String, String>? = null,
    /** Border post document IDs, keyed by ID with the post name as value. */
    val borderPostsIds: Map<String, String>? = null,
) {
    val openState get() = BorderOpenState.fromRaw(isOpen)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = CountryBorder(
            documentId = documentId,
            name = FS.str(data["name"]) ?: "",
            country1 = FS.str(data["country1"]),
            country2 = FS.str(data["country2"]),
            isOpen = FS.int(data["is_open"]) ?: BorderOpenState.UNKNOWN.raw,
            geomType = FS.str(data["geomType"]),
            geomString = FS.str(data["geomString"]),
            comment = FS.str(data["comment_original"]),
            commentTranslations = FS.stringMap(data["comment_translations"]),
            borderPostsIds = FS.stringMap(data["borderPostsIds"]),
        )
    }
}
