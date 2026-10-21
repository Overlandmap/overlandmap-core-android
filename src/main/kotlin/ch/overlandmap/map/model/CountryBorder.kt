package ch.overlandmap.map.model

import ch.overlandmap.map.geo.GeoBounds

/**
 * Open state shared by borders and border posts; raw values match Firestore
 * (iOS `BorderPostOpenStatus`: 4 = temporarily closed, only used by border posts).
 */
enum class BorderOpenState(val raw: Int, val colorHex: String) {
    // Colours are the map layer's (iOS `OverlandingMapView` / Flutter), shared by lines, dots and pills.
    CLOSED(0, "#D64545"),
    BILATERAL(1, "#3478C7"),
    OPEN(2, "#45A866"),
    RESTRICTIONS(3, "#E7A43D"),
    TEMPORARILY_CLOSED(4, "#8E3B3B"),
    UNKNOWN(-1, "#777777");

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * A border between two countries (Firestore collection `border`). Port of iOS `Border`.
 * [geomString] holds the GeoJSON geometry used to draw the border line on the map.
 *
 * `is_open` is an integer, or a legacy boolean (true = open) — see [FS.openState].
 * `border_posts` lists the ids of the crossings attached to the border.
 */
data class CountryBorder(
    val documentId: String,
    val name: String,
    val country1: String? = null,
    val country2: String? = null,
    val isOpen: Int = BorderOpenState.OPEN.raw,
    val geomType: String? = null,
    val geomString: String? = null,
    val comment: String? = null,
    val commentTranslations: Map<String, String>? = null,
    /** Ids of the border posts attached to this border. */
    val borderPostIds: List<String> = emptyList(),
    val boundingBox: GeoBounds? = null,
    /** Epoch milliseconds of the server-side `updatedAt`. */
    val updatedAt: Long? = null,
) {
    val openState get() = BorderOpenState.fromRaw(isOpen)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)

    /** The country on the other side from [countryCode], or null when the border doesn't touch it. */
    fun oppositeCountryCode(countryCode: String): String? = when (countryCode) {
        country2 -> country1
        country1 -> country2
        else -> null
    }

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>): CountryBorder {
            val posts = data["border_posts"]
            return CountryBorder(
                documentId = documentId,
                name = FS.str(data["name"]) ?: "",
                country1 = FS.str(data["country1"]),
                country2 = FS.str(data["country2"]),
                // An absent state means open (iOS `BorderOpenStatus.init(raw:)`).
                isOpen = FS.openState(data["is_open"]) ?: BorderOpenState.OPEN.raw,
                geomType = FS.str(data["geomType"]),
                geomString = FS.str(data["geomString"]),
                comment = FS.str(data["comment"]),
                commentTranslations = FS.stringMap(data["comment_translations"]),
                // Early exports encoded references as object keys.
                borderPostIds = FS.stringList(posts)
                    ?: (posts as? Map<*, *>)?.keys?.filterIsInstance<String>()?.sorted()
                    ?: emptyList(),
                boundingBox = boundsFromJsonArray(data["bounding_box"]),
                updatedAt = FS.millis(data["updatedAt"]),
            )
        }
    }
}
