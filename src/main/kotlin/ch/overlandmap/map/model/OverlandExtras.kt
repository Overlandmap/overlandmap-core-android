package ch.overlandmap.map.model

import ch.overlandmap.map.geo.GeoBounds

/** A region with a travel advisory or access restriction (Firestore collection `zone`). */
data class Zone(
    val documentId: String,
    val name: String = "",
    val country: String? = null,
    /** Advisory severity, see [ZoneType]. */
    val type: Int? = null,
    val comment: String? = null,
    val commentTranslations: Map<String, String>? = null,
    val boundingBox: GeoBounds? = null,
    /** Epoch milliseconds of the server-side `updatedAt`. */
    val updatedAt: Long? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    val zoneType: ZoneType get() = ZoneType.fromRaw(type)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)

    fun stored(): Map<String, Any?> = buildMap {
        putAll(extra)
        if (name.isNotEmpty()) put("name", name)
        country?.let { put("country", it) }
        type?.let { put("type", it) }
        comment?.let { put("comment", it) }
        commentTranslations?.let { put("comment_translations", it) }
        boundingBox?.let { put("bounding_box", listOf(it.lonMin, it.latMin, it.lonMax, it.latMax)) }
        updatedAt?.let { put("updatedAt", it) }
    }

    companion object {
        private val KNOWN = arrayOf(
            "name", "country", "type", "comment", "comment_translations", "bounding_box", "updatedAt",
        )

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Zone(
            documentId = documentId,
            name = FS.str(data["name"]) ?: "",
            country = FS.str(data["country"]),
            type = FS.int(data["type"]),
            comment = FS.str(data["comment"]),
            commentTranslations = FS.stringMap(data["comment_translations"]),
            boundingBox = boundsFromJsonArray(data["bounding_box"]),
            updatedAt = FS.millis(data["updatedAt"]),
            extra = FS.jsonSafe(data).without(*KNOWN),
        )
    }
}

/** Monthly climate normals for a place, keyed by geohash and month (collection `climate`). */
data class Climate(
    val documentId: String,
    val geohash: String? = null,
    val month: Int? = null,
    val updatedAt: Long? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    val temperatureMinC: Double? get() = FS.double(extra["tempMin"])
    val temperatureMaxC: Double? get() = FS.double(extra["tempMax"])
    val precipitationMm: Double? get() = FS.double(extra["precipitation"])

    fun stored(): Map<String, Any?> = buildMap {
        putAll(extra)
        geohash?.let { put("geohash", it) }
        month?.let { put("month", it) }
        updatedAt?.let { put("updatedAt", it) }
    }

    companion object {
        private val KNOWN = arrayOf("geohash", "month", "updatedAt")

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Climate(
            documentId = documentId,
            geohash = FS.str(data["geohash"]),
            month = FS.int(data["month"]),
            updatedAt = FS.millis(data["updatedAt"]),
            extra = FS.jsonSafe(data).without(*KNOWN),
        )
    }
}

/**
 * A document from Firestore's `global` collection: app-wide configuration authored
 * server-side (today only the travel-advisory URL templates). The document's own fields
 * *are* the payload, so nothing is promoted out of [extra] except `updatedAt`.
 */
data class GlobalSetting(
    val documentId: String,
    val updatedAt: Long? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** `{"gbr_en": "https://…/{translation}", …}`, one entry per source country and language. */
    val advisoryUrlTemplates: Map<String, String>
        get() = extra.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    companion object {
        /** The document the country screen reads for its "Official website" links. */
        const val ADVISORY_URL_TEMPLATE_ID = "advisory_url_template"

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = GlobalSetting(
            documentId = documentId,
            updatedAt = FS.millis(data["updatedAt"]),
            extra = FS.jsonSafe(data).without("updatedAt"),
        )
    }
}
