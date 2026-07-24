package ch.overlandmap.map.model

import ch.overlandmap.map.AppConfig

/**
 * A numbered step along an itinerary (Firestore subcollection
 * `itinerary/{id}/steps`). Port of ItineraryStep in `models/itinerary.dart`.
 */
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
    /** Point-of-interest flags at this step (Firestore booleans, null = false). */
    val hasFuel: Boolean = false,
    val hasHotel: Boolean = false,
    val isViewpoint: Boolean = false,
    val isBivouac: Boolean = false,
    val isPoliceCheckpoint: Boolean = false,
    val isBorder: Boolean = false,
    val isEmbassy: Boolean = false,
    val isMountainPass: Boolean = false,
    val isBridge: Boolean = false,
    val isWaterCrossing: Boolean = false,
    val isHistoricalSite: Boolean = false,
    val isReligiousSite: Boolean = false,
    val isHotSpring: Boolean = false,
    val titlePhotoId: String? = null,
    val titlePhotoCaption: String? = null,
    /** Access / opening status of this step, and its free-text detail. */
    val openKind: OpenKind? = null,
    val openDetails: String? = null,
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
                isViewpoint = FS.bool(data["isViewpoint"]),
                isBivouac = FS.bool(data["isBivouac"]),
                isPoliceCheckpoint = FS.bool(data["isPoliceCheckpoint"]),
                isBorder = FS.bool(data["isBorder"]),
                isEmbassy = FS.bool(data["isEmbassy"]),
                isMountainPass = FS.bool(data["isMountainPass"]),
                isBridge = FS.bool(data["isBridge"]),
                isWaterCrossing = FS.bool(data["isWaterCrossing"]),
                isHistoricalSite = FS.bool(data["isHistoricalSite"]),
                isReligiousSite = FS.bool(data["isReligiousSite"]),
                isHotSpring = FS.bool(data["isHotSpring"]),
                titlePhotoId = FS.str(data["titlePhotoId"]),
                titlePhotoCaption = FS.str(data["titlePhotoCaption"]),
                openKind = OpenKind.fromRaw(FS.str(data["openKind"])),
                openDetails = FS.str(data["openDetails"]),
            )
    }
}
