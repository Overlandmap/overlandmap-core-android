package ch.overlandmap.map.model

/**
 * The shared shape of a waypoint-like object — an [ItineraryStep], a [Waypoint]
 * and (in future) a contributed waypoint: a localizable name and description, a
 * location, the point-of-interest flags, and an access/open status.
 *
 * Implementing models get the localization behavior ([name]/[description]) for
 * free and can be handled polymorphically (rendering, serialization). It stays
 * to the fields common to all of them — type-specific ones (a step's number and
 * photos, a waypoint's `type`/`maki`, …) live on the concrete classes.
 */
interface WaypointType {
    val documentId: String
    val name: String
    val translatedName: Map<String, String>?
    val description: String?
    val translatedDesc: Map<String, String>?
    val lat: Double?
    val lon: Double?
    val ele: Int?
    val geohash: String?

    /** Point-of-interest flags (Firestore booleans, absent = false). */
    val hasFuel: Boolean
    val hasHotel: Boolean
    val isViewpoint: Boolean
    val isBivouac: Boolean
    val isPoliceCheckpoint: Boolean
    val isBorder: Boolean
    val isEmbassy: Boolean
    val isMountainPass: Boolean
    val isBridge: Boolean
    val isWaterCrossing: Boolean
    val isHistoricalSite: Boolean
    val isReligiousSite: Boolean
    val isHotSpring: Boolean
    val isIntersection: Boolean
    val isCafe: Boolean
    val isFerry: Boolean

    /** Access / opening status, and its free-text detail. */
    val openKind: OpenKind?
    val openDetails: String?

    /** The name in [lang], falling back to the original. */
    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    /** The description in [lang], or null. */
    fun description(lang: String): String? = localized(description, translatedDesc, lang)
}
