package ch.overlandmap.map.model

/**
 * A user comment on a track pack or itinerary. Port of iOS `Comment`.
 * Comments live in the Firestore `comment` collection, keyed by `objectId`
 * (the commented document's ID). Rows exist in Room as an offline cache of
 * the comments last fetched for a local pack or itinerary.
 *
 * Queryable columns (iOS schema): objectId, geohash, type, rating, itinDocumentId,
 * createdAt, userId. Everything else (content, userName, collection, langCode,
 * trackPackId, translations, …) stays in [extra].
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
    val geohash: String? = null,
    val type: String? = null,
    val itinDocumentId: String? = null,
    val userId: String? = null,
    /** The pack the comment belongs to; lets a pack's comments be deleted in one query. */
    val trackPackId: String? = null,
    /** Any other Firestore field (collection, …), preserved verbatim. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** The rating clamped to 0..5; the UI treats a missing rating as 0. */
    val ratingValue: Int get() = (rating ?: 0).coerceIn(0, 5)

    /**
     * Machine translation supplied by the `translateComment` callable. Reads the
     * `translations` map in [lang], tolerating region variants ("pt" finds "pt-PT"), and
     * falls back to `englishTranslation` for English, which older documents carry instead
     * of an `en` entry.
     */
    fun translation(lang: String): String? =
        LocalizedLookup.value(translations, lang)
            ?: englishTranslation.takeIf { LocalizedLookup.canonicalCode(lang) == "en" }

    /** The comment text in [lang]: its translation when it was written in another language. */
    fun content(lang: String): String = when {
        langCode == null || LocalizedLookup.canonicalCode(lang) == LocalizedLookup.canonicalCode(langCode) ->
            content
        else -> translation(lang) ?: content
    }

    companion object {
        /** Keys promoted to properties or columns; the rest is [extra]. */
        private val KNOWN = arrayOf(
            "objectId", "content", "langCode", "userName", "createdAt", "created", "rating",
            "englishTranslation", "translations", "geohash", "type", "itinDocumentId", "userId",
            "trackPackId",
        )

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Comment(
            documentId = documentId,
            objectId = FS.str(data["objectId"]) ?: "",
            content = FS.str(data["content"]) ?: "",
            langCode = FS.str(data["langCode"]),
            userName = FS.str(data["userName"]),
            createdAt = FS.millis(data["createdAt"] ?: data["created"]),
            rating = FS.int(data["rating"]),
            englishTranslation = FS.str(data["englishTranslation"]),
            translations = FS.stringMap(data["translations"]),
            geohash = FS.str(data["geohash"]),
            type = FS.str(data["type"]),
            itinDocumentId = FS.str(data["itinDocumentId"]),
            userId = FS.str(data["userId"]),
            trackPackId = FS.str(data["trackPackId"]),
            extra = FS.jsonSafe(data).without(*KNOWN),
        )
    }
}
