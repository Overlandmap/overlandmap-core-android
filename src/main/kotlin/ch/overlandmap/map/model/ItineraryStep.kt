package ch.overlandmap.map.model

import ch.overlandmap.map.AppConfig

/**
 * A numbered step along an itinerary (Firestore subcollection
 * `itinerary/{id}/steps`). Port of ItineraryStep in `models/itinerary.dart`.
 */
data class ItineraryStep(
    override val documentId: String,
    val itineraryId: String,
    val trackPackId: String,
    /** Step number, 1-based and consecutive along the track. */
    val stepId: Int,
    override val name: String,
    override val translatedName: Map<String, String>? = null,
    override val description: String? = null,
    override val translatedDesc: Map<String, String>? = null,
    /** Distance from the start of the main track, in km. */
    val distanceKm: Double = 0.0,
    override val lat: Double? = null,
    override val lon: Double? = null,
    override val ele: Int? = null,
    override val geohash: String? = null,
    /** Point-of-interest flags at this step (Firestore booleans, null = false). */
    override val hasFuel: Boolean = false,
    override val hasHotel: Boolean = false,
    override val isViewpoint: Boolean = false,
    override val isBivouac: Boolean = false,
    override val isPoliceCheckpoint: Boolean = false,
    override val isBorder: Boolean = false,
    override val isEmbassy: Boolean = false,
    override val isMountainPass: Boolean = false,
    override val isBridge: Boolean = false,
    override val isWaterCrossing: Boolean = false,
    override val isHistoricalSite: Boolean = false,
    override val isReligiousSite: Boolean = false,
    override val isHotSpring: Boolean = false,
    override val isIntersection: Boolean = false,
    override val isCafe: Boolean = false,
    override val isFerry: Boolean = false,
    val titlePhotoId: String? = null,
    val titlePhotoCaption: String? = null,
    /** Access / opening status of this step, and its free-text detail. */
    override val openKind: OpenKind? = null,
    override val openDetails: String? = null,
    /** Absolute path of the title photo unpacked from a downloaded zip. */
    val localPhotoPath: String? = null,
) : WaypointType {
    val titlePhotoUrl: String?
        get() = localPhotoPath?.let { "file://$it" } ?: titlePhotoId?.let(AppConfig::photoUrl)

    fun fullName(lang: String): String = "$stepId. ${name(lang)}"

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
                geohash = FS.str(data["geohash"]),
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
                isIntersection = FS.bool(data["isIntersection"]),
                isCafe = FS.bool(data["isCafe"]),
                isFerry = FS.bool(data["isFerry"]),
                titlePhotoId = FS.str(data["titlePhotoId"]),
                titlePhotoCaption = FS.str(data["titlePhotoCaption"]),
                openKind = OpenKind.fromRaw(FS.str(data["openKind"])),
                openDetails = FS.str(data["openDetails"]),
            )
    }
}
