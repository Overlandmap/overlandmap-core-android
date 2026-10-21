package ch.overlandmap.map.model

import ch.overlandmap.map.geo.Geohash

/**
 * A border crossing point (Firestore collection `border_post`). Port of iOS `BorderPost`.
 *
 * The location comes from a `location` GeoPoint, or from `latitude|lat` and
 * `longitude|lon|lng` fields; when the document carries no geohash, one is derived from
 * the location so the row can be found by proximity.
 */
data class BorderPost(
    val documentId: String,
    val name: String,
    /** "XX - YY" country code pair, e.g. "GE - AM". */
    val countries: String? = null,
    val isOpen: Int = BorderOpenState.OPEN.raw,
    val lat: Double? = null,
    val lon: Double? = null,
    val geohash: String? = null,
    val comment: String? = null,
    val commentTranslations: Map<String, String>? = null,
    /** The parent border's document id (`borderId`). */
    val borderId: String? = null,
    /** Epoch milliseconds of the server-side `updatedAt`. */
    val updatedAt: Long? = null,
) {
    val openState get() = BorderOpenState.fromRaw(isOpen)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>): BorderPost {
            val lat = FS.geoLat(data["location"]) ?: FS.double(data["latitude"]) ?: FS.double(data["lat"])
            val lon = FS.geoLon(data["location"]) ?: FS.double(data["longitude"])
                ?: FS.double(data["lon"]) ?: FS.double(data["lng"])
            val geohash = FS.str(data["geohash"])
                ?: if (lat != null && lon != null) Geohash.encode(lat, lon) else null
            return BorderPost(
                documentId = documentId,
                name = FS.str(data["name"]) ?: "",
                countries = FS.str(data["countries"]),
                isOpen = FS.openState(data["is_open"]) ?: BorderOpenState.OPEN.raw,
                lat = lat,
                lon = lon,
                geohash = geohash,
                comment = FS.str(data["comment"]),
                commentTranslations = FS.stringMap(data["comment_translations"]),
                borderId = FS.str(data["borderId"]),
                updatedAt = FS.millis(data["updatedAt"]),
            )
        }
    }
}
