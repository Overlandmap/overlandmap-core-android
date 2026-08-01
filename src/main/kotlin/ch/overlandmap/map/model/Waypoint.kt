package ch.overlandmap.map.model


/**
 * A point of interest of an itinerary (fuel, water, camp, …). Port of
 * `models/waypoint.dart`. Shares [WaypointType]'s shape and behavior with
 * [ItineraryStep].
 */
data class Waypoint(
    override val documentId: String,
    val trackPackId: String,
    val itineraryId: String? = null,
    override val name: String,
    override val translatedName: Map<String, String>? = null,
    override val description: String? = null,
    override val translatedDesc: Map<String, String>? = null,
    val type: String? = null,
    /** Marker icon name (a maki-png id); selects the map marker. */
    val maki: String? = null,
    override val lat: Double? = null,
    override val lon: Double? = null,
    override val ele: Int? = null,
    override val geohash: String? = null,
    /** Point-of-interest flags (Firestore booleans, null = false). */
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
    /** Access / opening status of this waypoint, and its free-text detail. */
    override val openKind: OpenKind? = null,
    override val openDetails: String? = null,
) : WaypointType {
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
            maki = FS.str(data["maki"]),
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
            openKind = OpenKind.fromRaw(FS.str(data["openKind"])),
            openDetails = FS.str(data["openDetails"]),
        )
    }
}
