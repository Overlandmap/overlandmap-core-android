package ch.overlandmap.map.model


/**
 * A point of interest of an itinerary (fuel, water, camp, …). Port of
 * `models/waypoint.dart`.
 */
data class Waypoint(
    val documentId: String,
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
    /** Point-of-interest flags (Firestore booleans, null = false). */
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
    /** Access / opening status of this waypoint, and its free-text detail. */
    val openKind: OpenKind? = null,
    val openDetails: String? = null,
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
            openKind = OpenKind.fromRaw(FS.str(data["openKind"])),
            openDetails = FS.str(data["openDetails"]),
        )
    }
}
