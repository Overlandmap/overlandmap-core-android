package ch.overlandmap.map.model

import androidx.room.Entity
import ch.overlandmap.map.AppConfig

/**
 * A numbered step along an itinerary (Firestore subcollection
 * `itinerary/{id}/steps`). Port of ItineraryStep in `models/itinerary.dart`.
 */
@Entity(tableName = "itinerary_step", primaryKeys = ["itineraryId", "documentId"])
data class ItineraryStep(
    val documentId: String,
    val itineraryId: String,
    val trackPackId: String,
    /** Step number, 1-based and consecutive along the track. */
    val stepId: Int,
    val name: String,
    val translatedName: Map<String, String>? = null,
    val description: String? = null,
    val translatedDesc: Map<String, String>? = null,
    /** Distance from the start of the main track, in km. */
    val distanceKm: Double = 0.0,
    val lat: Double? = null,
    val lon: Double? = null,
    val ele: Int? = null,
    /** Point-of-interest flags at this step (Firestore hasFuel/hasHotel/isPoliceCheckpoint). */
    val hasFuel: Boolean = false,
    val hasHotel: Boolean = false,
    val isPoliceCheckpoint: Boolean = false,
    val titlePhotoId: String? = null,
    val titlePhotoCaption: String? = null,
    /** Absolute path of the title photo unpacked from a downloaded zip. */
    val localPhotoPath: String? = null,
) {
    val titlePhotoUrl: String?
        get() = localPhotoPath?.let { "file://$it" } ?: titlePhotoId?.let(AppConfig::photoUrl)

    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    fun fullName(lang: String): String = "$stepId. ${name(lang)}"

    fun description(lang: String): String? = localized(description, translatedDesc, lang)

    companion object {
        fun fromFirestore(documentId: String, itineraryId: String, data: Map<String, Any?>) =
            ItineraryStep(
                documentId = documentId,
                itineraryId = itineraryId,
                trackPackId = FS.str(data["trackPackId"]) ?: "",
                stepId = FS.int(data["id"]) ?: 0,
                name = FS.str(data["name"]) ?: "",
                translatedName = FS.stringMap(data["translatedName"]),
                description = FS.str(data["description"]),
                translatedDesc = FS.stringMap(data["translatedDesc"]),
                distanceKm = FS.double(data["distanceKm"]) ?: 0.0,
                lat = FS.geoLat(data["location"]),
                lon = FS.geoLon(data["location"]),
                ele = FS.int(data["ele"]),
                hasFuel = FS.bool(data["hasFuel"]),
                hasHotel = FS.bool(data["hasHotel"]),
                isPoliceCheckpoint = FS.bool(data["isPoliceCheckpoint"]),
                titlePhotoId = FS.str(data["titlePhotoId"]),
                titlePhotoCaption = FS.str(data["titlePhotoCaption"]),
            )
    }
}
