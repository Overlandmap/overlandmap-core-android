package ch.overlandmap.map.model


/**
 * A user comment on a track pack or itinerary. Port of the Flutter app's
 * `models/comment.dart`, reduced to the fields the shop displays.
 * Comments live in the Firestore `comment` collection, keyed by `objectId`
 * (the commented document's ID). Rows exist in Room as an offline cache of
 * the comments last fetched for a local pack or itinerary.
 */
data class Comment(
    val documentId: String,
    /** ID of the commented document (track pack or itinerary). */
    val objectId: String,
    val content: String,
    val langCode: String? = null,
    val userName: String? = null,
    val createdAt: Long? = null,
    val rating: Int? = null,
    val englishTranslation: String? = null,
    val translations: Map<String, String>? = null,
) {
    /** The comment text in [lang], falling back to English then the original. */
    fun content(lang: String): String = when {
        langCode == null || lang == langCode -> content
        else -> translations?.get(lang)
            ?: englishTranslation.takeIf { lang == "en" }
            ?: content
    }

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Comment(
            documentId = documentId,
            objectId = FS.str(data["objectId"]) ?: "",
            content = FS.str(data["content"]) ?: "",
            langCode = FS.str(data["langCode"]),
            userName = FS.str(data["userName"]),
            createdAt = FS.millis(data["createdAt"]),
            rating = FS.int(data["rating"]),
            englishTranslation = FS.str(data["englishTranslation"]),
            translations = FS.stringMap(data["translations"]),
        )
    }
}
