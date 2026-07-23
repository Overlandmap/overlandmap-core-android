package ch.overlandmap.map.model


/**
 * A border crossing point (Firestore collection `border_post`). Port of
 * `models/border_post.dart`.
 */
data class BorderPost(
    val documentId: String,
    val name: String,
    /** "XX - YY" country code pair, e.g. "GE - AM". */
    val countries: String? = null,
    val isOpen: Int = BorderOpenState.UNKNOWN.raw,
    val lat: Double? = null,
    val lon: Double? = null,
    val geohash: String? = null,
    val comment: String? = null,
    val commentTranslations: Map<String, String>? = null,
) {
    val openState get() = BorderOpenState.fromRaw(isOpen)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = BorderPost(
            documentId = documentId,
            name = FS.str(data["name"]) ?: "",
            countries = FS.str(data["countries"]),
            isOpen = FS.int(data["is_open"]) ?: BorderOpenState.UNKNOWN.raw,
            lat = FS.geoLat(data["location"]),
            lon = FS.geoLon(data["location"]),
            geohash = FS.str(data["geohash"]),
            comment = FS.str(data["comment_original"]),
            commentTranslations = FS.stringMap(data["comment_translations"]),
        )
    }
}
