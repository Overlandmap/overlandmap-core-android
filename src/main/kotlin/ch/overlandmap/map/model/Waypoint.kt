package ch.overlandmap.map.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A point of interest of an itinerary (fuel, water, camp, …). Port of
 * `models/waypoint.dart`.
 */
@Entity(tableName = "waypoint")
data class Waypoint(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val itineraryId: String? = null,
    val name: String,
    val translatedName: Map<String, String>? = null,
    val description: String? = null,
    val translatedDesc: Map<String, String>? = null,
    val type: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val ele: Int? = null,
    val geohash: String? = null,
) {
    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    fun description(lang: String): String? = localized(description, translatedDesc, lang)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Waypoint(
            documentId = documentId,
            trackPackId = FS.str(data["trackPackId"]) ?: "",
            itineraryId = FS.str(data["itineraryId"]),
            name = FS.str(data["name"]) ?: "",
            translatedName = FS.stringMap(data["translatedName"]),
            description = FS.str(data["description"]),
            translatedDesc = FS.stringMap(data["translatedDesc"]),
            type = FS.str(data["type"]),
            lat = FS.geoLat(data["location"]),
            lon = FS.geoLon(data["location"]),
            ele = FS.int(data["ele"]),
            geohash = FS.str(data["geohash"]),
        )
    }
}
