package ch.overlandmap.map.model

import ch.overlandmap.map.geo.Geohash

/*
 * User-contributed content: check-ins, votes, contributed waypoints and discussions.
 * Ports of iOS `Social.swift`. Every model here is built from a Firestore-shaped map
 * (`fromFirestore`), keeps the fields it doesn't model in `extra`, and can give the map
 * back (`stored()`), so the Room rows can keep the queryable columns next to a `json`
 * blob exactly as the iOS schema does.
 */

// ── Check-ins ───────────────────────────────────────────────────────────────

/** Why someone checked in against an object. Raw values are what Firestore stores. */
enum class CheckInReason(val raw: String) {
    DOES_NOT_EXIST("doesNotExist"), CLOSED("closed"), REPAIRED("repaired"), CHECK("check"),
    WRONG_DESCRIPTION("wrongDescription"), WRONG_LOCATION("wrongLocation"), OTHER("other");

    /** Reasons that flag something needing correction (drives the warning treatment). */
    val indicatesProblem: Boolean
        get() = this == DOES_NOT_EXIST || this == CLOSED || this == REPAIRED ||
            this == WRONG_DESCRIPTION || this == WRONG_LOCATION

    companion object {
        /** A plain confirmation that the object is as described. */
        val confirming: Set<CheckInReason> = setOf(CHECK)

        /** An absent or unrecognised reason is [OTHER], as on iOS. */
        fun fromRaw(raw: String?): CheckInReason =
            if (raw == null) OTHER else entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

/** A user confirming or correcting an object in the field. */
data class CheckIn(
    val documentId: String,
    /** The object checked in against: a step, waypoint or contributed waypoint. */
    val objectId: String,
    val trackPackId: String? = null,
    val itinDocumentId: String? = null,
    val reason: CheckInReason = CheckInReason.CHECK,
    /** Epoch milliseconds. */
    val createdAt: Long? = null,
    val userId: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Firestore path of the collection the object lives in, e.g. `itinerary/<id>/steps`. */
    val geohash: String? get() = FS.str(extra["geohash"])
    val collection: String? get() = FS.str(extra["collection"])
    val userName: String? get() = FS.str(extra["userName"])
    val content: String? get() = FS.str(extra["content"])
    val lat: Double? get() = FS.geoLat(extra["location"]) ?: FS.double(extra["lat"])
    val lon: Double? get() = FS.geoLon(extra["location"]) ?: FS.double(extra["lon"])
    val upVotes: Int get() = FS.int(extra["upVotes"]) ?: 0
    val downVotes: Int get() = FS.int(extra["downVotes"]) ?: 0

    /** AI-translated description from the `translatedDesc` map. */
    fun translation(lang: String): String? = FS.stringMap(extra["translatedDesc"])?.get(lang)

    /** The Firestore-shaped document (without `documentId`). */
    fun stored(): Map<String, Any?> = buildMap {
        putAll(extra)
        if (objectId.isNotEmpty()) put("objectId", objectId)
        trackPackId?.let { put("trackPackId", it) }
        itinDocumentId?.let { put("itinDocumentId", it) }
        put("reason", reason.raw)
        createdAt?.let { put("createdAt", it) }
        userId?.let { put("userId", it) }
    }

    companion object {
        private val KNOWN = arrayOf(
            "objectId", "trackPackId", "itinDocumentId", "reason", "userId", "createdAt", "created",
        )

        /** Imported waypoints are attached by geohash instead of object ID. */
        fun fromFirestore(documentId: String, data: Map<String, Any?>): CheckIn? {
            val objectId = FS.str(data["objectId"]).orEmpty()
            if (objectId.isEmpty() && FS.str(data["geohash"]).isNullOrEmpty()) return null
            return CheckIn(
                documentId = documentId,
                objectId = objectId,
                trackPackId = FS.str(data["trackPackId"]),
                itinDocumentId = FS.str(data["itinDocumentId"]),
                reason = CheckInReason.fromRaw(FS.str(data["reason"])),
                createdAt = FS.millis(data["createdAt"] ?: data["created"]),
                userId = FS.str(data["userId"]),
                extra = FS.jsonSafe(data).without(*KNOWN),
            )
        }
    }
}

// ── Votes ───────────────────────────────────────────────────────────────────

/** An up or down vote on a contributed object. */
data class Vote(
    val documentId: String,
    val isUpVote: Boolean,
    val objectId: String? = null,
    val trackPackId: String? = null,
    val content: String? = null,
    val createdAt: Long? = null,
    val userId: String? = null,
    /** Overflow blob (userName, …) so a field added upstream isn't dropped. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    val userName: String? get() = FS.str(extra["userName"])

    fun stored(): Map<String, Any?> = buildMap {
        putAll(extra)
        put("upVote", isUpVote)
        objectId?.let { put("objectId", it) }
        trackPackId?.let { put("trackPackId", it) }
        content?.let { put("content", it) }
        createdAt?.let { put("createdAt", it) }
        userId?.let { put("userId", it) }
    }

    companion object {
        private val KNOWN = arrayOf(
            "upVote", "objectId", "objectID", "trackPackId", "trackpackId", "trackPackID",
            "content", "userId", "userID", "createdAt",
        )

        private fun first(data: Map<String, Any?>, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { FS.str(data[it]) }

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Vote(
            documentId = documentId,
            // Stored as a boolean or as 0/1; absent means an up-vote.
            isUpVote = FS.boolOrNull(data["upVote"]) ?: true,
            objectId = first(data, "objectId", "objectID"),
            trackPackId = first(data, "trackPackId", "trackpackId", "trackPackID"),
            content = FS.str(data["content"]),
            createdAt = FS.millis(data["createdAt"]),
            userId = first(data, "userId", "userID"),
            extra = FS.jsonSafe(data).without(*KNOWN),
        )
    }
}

// ── Contributed waypoints ───────────────────────────────────────────────────

/** The boolean amenity/hazard flags shared by steps, waypoints and contributed waypoints. */
data class WaypointFlags(
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
    val isWaterfall: Boolean = false,
    val isSwimming: Boolean = false,
    val isHistoricalSite: Boolean = false,
    val isReligiousSite: Boolean = false,
    val isHotSpring: Boolean = false,
    val isIntersection: Boolean = false,
    val isFerry: Boolean = false,
    val isCafe: Boolean = false,
) {
    companion object {
        /** Keys of every flag, in the order above. */
        val KEYS = arrayOf(
            "hasFuel", "hasHotel", "isViewpoint", "isBivouac", "isPoliceCheckpoint", "isBorder",
            "isEmbassy", "isMountainPass", "isBridge", "isWaterCrossing", "isWaterfall", "isSwimming",
            "isHistoricalSite", "isReligiousSite", "isHotSpring", "isIntersection", "isFerry", "isCafe",
        )

        fun from(data: Map<String, Any?>) = WaypointFlags(
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
            isWaterfall = FS.bool(data["isWaterfall"]),
            isSwimming = FS.bool(data["isSwimming"]),
            isHistoricalSite = FS.bool(data["isHistoricalSite"]),
            isReligiousSite = FS.bool(data["isReligiousSite"]),
            isHotSpring = FS.bool(data["isHotSpring"]),
            isIntersection = FS.bool(data["isIntersection"]),
            isFerry = FS.bool(data["isFerry"]),
            isCafe = FS.bool(data["isCafe"]),
        )
    }
}

/**
 * A waypoint added by a user rather than shipped in a track pack. Same flags and icon as a
 * [Waypoint], plus authorship and moderation state. Flags stay in [extra] (read through
 * [flags]) so a flag added upstream shows up without a schema change.
 */
data class ContributedWaypoint(
    override val documentId: String,
    val trackPackId: String,
    override val geohash: String? = null,
    val type: String? = null,
    val itinDocumentId: String? = null,
    /** Epoch milliseconds. */
    val lastUpdate: Long? = null,
    val userId: String? = null,
    /** Rows with `active == false` are moderated out and never cached. Absent = active. */
    val isActive: Boolean = true,
    override val name: String = "",
    override val description: String? = null,
    override val translatedName: Map<String, String>? = null,
    override val translatedDesc: Map<String, String>? = null,
    override val openKind: OpenKind? = null,
    override val openDetails: String? = null,
    override val lat: Double? = null,
    override val lon: Double? = null,
    override val ele: Int? = null,
    val extra: Map<String, Any?> = emptyMap(),
) : WaypointType {
    val flags: WaypointFlags by lazy(LazyThreadSafetyMode.NONE) { WaypointFlags.from(extra) }

    val content: String? get() = FS.str(extra["content"])
    val langCode: String? get() = FS.str(extra["langCode"])
    val userName: String? get() = FS.str(extra["userName"])
    val collection: String? get() = FS.str(extra["collection"])
    val maki: String? get() = FS.str(extra["maki"])

    override val hasFuel get() = flags.hasFuel
    override val hasHotel get() = flags.hasHotel
    override val isViewpoint get() = flags.isViewpoint
    override val isBivouac get() = flags.isBivouac
    override val isPoliceCheckpoint get() = flags.isPoliceCheckpoint
    override val isBorder get() = flags.isBorder
    override val isEmbassy get() = flags.isEmbassy
    override val isMountainPass get() = flags.isMountainPass
    override val isBridge get() = flags.isBridge
    override val isWaterCrossing get() = flags.isWaterCrossing
    override val isHistoricalSite get() = flags.isHistoricalSite
    override val isReligiousSite get() = flags.isReligiousSite
    override val isHotSpring get() = flags.isHotSpring
    override val isIntersection get() = flags.isIntersection
    override val isCafe get() = flags.isCafe
    override val isFerry get() = flags.isFerry
    override val isWaterfall get() = flags.isWaterfall
    override val isSwimming get() = flags.isSwimming

    /** The Firestore-shaped document (without `documentId`). */
    fun stored(): Map<String, Any?> = buildMap {
        putAll(extra)
        put("trackPackId", trackPackId)
        geohash?.let { put("geohash", it) }
        type?.let { put("type", it) }
        itinDocumentId?.let { put("itinDocumentId", it) }
        lastUpdate?.let { put("lastUpdate", it) }
        userId?.let { put("userId", it) }
        put("active", isActive)
        if (name.isNotEmpty()) put("name", name)
        description?.let { put("description", it) }
        translatedName?.let { put("translatedName", it) }
        translatedDesc?.let { put("translatedDesc", it) }
        openKind?.let { put("openKind", it.raw) }
        openDetails?.let { put("openDetails", it) }
        lat?.let { put("lat", it) }
        lon?.let { put("lon", it) }
        ele?.let { put("ele", it) }
    }

    companion object {
        private val KNOWN = arrayOf(
            "trackPackId", "geohash", "type", "itinDocumentId", "userId", "active", "name",
            "description", "translatedName", "translatedDesc", "openKind", "openDetails",
            "lastUpdate", "location", "lat", "lon", "ele",
        )

        fun fromFirestore(
            documentId: String,
            data: Map<String, Any?>,
            fallbackTrackPackId: String = "",
        ): ContributedWaypoint {
            val lat = FS.geoLat(data["location"]) ?: FS.double(data["lat"])
            val lon = FS.geoLon(data["location"]) ?: FS.double(data["lon"])
            var geohash = FS.str(data["geohash"])
            var resolvedLat = lat
            var resolvedLon = lon
            if (lat == null || lon == null) {
                // No explicit location: fall back to the geohash cell centre.
                Geohash.decode(geohash)?.let {
                    resolvedLat = it.latitude
                    resolvedLon = it.longitude
                }
            } else if (geohash == null) {
                geohash = Geohash.encode(lat, lon)
            }
            return ContributedWaypoint(
                documentId = documentId,
                trackPackId = FS.str(data["trackPackId"]) ?: fallbackTrackPackId,
                geohash = geohash,
                type = FS.str(data["type"]),
                itinDocumentId = FS.str(data["itinDocumentId"]),
                lastUpdate = FS.millis(data["lastUpdate"]),
                userId = FS.str(data["userId"]),
                // Absent means active: only explicit moderation sets it false.
                isActive = FS.boolOrNull(data["active"]) ?: true,
                name = FS.str(data["name"]) ?: "",
                description = FS.str(data["description"]),
                translatedName = FS.stringMap(data["translatedName"]),
                translatedDesc = FS.stringMap(data["translatedDesc"]),
                openKind = FS.str(data["openKind"])?.let { OpenKind.fromRaw(it) },
                openDetails = FS.str(data["openDetails"]),
                lat = resolvedLat,
                lon = resolvedLon,
                ele = FS.int(data["ele"]),
                extra = FS.jsonSafe(data).without(*KNOWN),
            )
        }
    }
}

// ── Discussions ─────────────────────────────────────────────────────────────

/** The topic column of a [Discussion]. */
enum class DiscussionTopic(val raw: String) {
    GENERAL("general"), VISA("visa"), EMBASSY("embassy"), SAFETY("safety"),
    BORDER_CROSSING("borderCrossing"), HEALTH("health"), VEHICLE_INSURANCE("vehicleInsurance"),
    VEHICLE_TIP("vehicleTip"), CARNET("carnet"), VEHICLE_STORAGE("vehicleStorage"), FUEL("fuel"),
    TELCOS("telcos"), PETS("pets"), OTHER("other"), COMMENT("comment"), DESCRIPTION("description"),
    MONEY("money"), DRIVING("driving"), STAY_DURATION("stayDuration"), STATUS("status"),
    ZONE_TYPE("zoneType"), INSURANCE_SCHEME("insuranceScheme");

    companion object {
        /**
         * Matched case-insensitively as the Dart side lowercases, so `bordercrossing` and
         * `borderCrossing` both resolve. Absent is GENERAL, unrecognised is OTHER.
         */
        fun fromRaw(raw: String?): DiscussionTopic {
            if (raw == null) return GENERAL
            entries.firstOrNull { it.raw == raw }?.let { return it }
            return entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: OTHER
        }
    }
}

/** Why a [Discussion] was written. */
enum class DiscussionReason(val raw: String) {
    GENERAL("general"), VISA("visa"), EMBASSY("embassy"), SAFETY("safety"),
    BORDER_CROSSING("borderCrossing"), HEALTH("health"), VEHICLE_INSURANCE("vehicleInsurance"),
    VEHICLE_TIP("vehicleTip"), CARNET("carnet"), VEHICLE_STORAGE("vehicleStorage"), FUEL("fuel"),
    TELCOS("telcos"), PETS("pets"), FOLLOWUP("followup"), SUGGESTION("suggestion"), OTHER("other");

    companion object {
        fun fromRaw(raw: String?): DiscussionReason =
            if (raw == null) OTHER else entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: OTHER

        /** Reasons offered in the "Add your input" dialog (not crossing reports, replies or suggestions). */
        val inputReasons: List<DiscussionReason> =
            entries.filter { it != BORDER_CROSSING && it != FOLLOWUP && it != SUGGESTION }
    }
}

/** How a border crossing was made; [dbName] is the string stored on the document. */
enum class CrossingVehicle(val dbName: String?) {
    ON_FOOT("on foot"), BICYCLE("bicycle"), CAR("car"), MOTO("moto"), CAMPER("camper"),
    TRUCK("truck"), OTHER(null);

    companion object {
        /** The vehicles offered when reporting a crossing, in display order. */
        val inputCases = listOf(CAR, MOTO, CAMPER, TRUCK, BICYCLE, ON_FOOT)

        fun fromRaw(raw: String?): CrossingVehicle {
            if (raw == null) return OTHER
            return when (raw.lowercase()) {
                "on foot", "onfoot" -> ON_FOOT
                "bicycle" -> BICYCLE
                "car" -> CAR
                "camper" -> CAMPER
                "truck" -> TRUCK
                "moto", "motorcycle" -> MOTO
                else -> entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER
            }
        }
    }
}

/** A user contribution attached to an overland object (discussion, testimonial, crossing report). */
data class Discussion(
    val documentId: String,
    val objectId: String? = null,
    val topic: DiscussionTopic = DiscussionTopic.GENERAL,
    val geohash: String? = null,
    /** Epoch milliseconds. */
    val createdAt: Long? = null,
    val userId: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    private val rawReason: String? get() = FS.str(extra["reason"])

    val reason: DiscussionReason get() = DiscussionReason.fromRaw(rawReason)
    val content: String? get() = FS.str(extra["content"])
    val userName: String? get() = FS.str(extra["userName"])
    val collection: String? get() = FS.str(extra["collection"])
    val lang: String? get() = FS.str(extra["lang"])
    val threadId: String? get() = FS.str(extra["threadId"])
    val isDeleted: Boolean get() = FS.boolOrNull(extra["deleted"]) ?: false
    val upVotes: Int get() = FS.int(extra["upVotes"]) ?: 0
    val downVotes: Int get() = FS.int(extra["downVotes"]) ?: 0

    /** A testimonial is just a discussion with this reason. */
    val isTestimonial: Boolean get() = rawReason?.lowercase() == "testimonial"

    /** A crossing report is a discussion carrying the border fields. */
    val isCrossingReport: Boolean
        get() = reason == DiscussionReason.BORDER_CROSSING ||
            extra["border_direction"] != null ||
            rawReason?.lowercase() == "bordercrossing"

    /** Whether this discussion is a reply to another one. */
    val isFollowup: Boolean
        get() = (reason == DiscussionReason.FOLLOWUP || rawReason?.lowercase() == "followup") &&
            !threadId.isNullOrEmpty()

    /** Whether this discussion concerns [field] (compared to the stored topic, case-insensitively). */
    fun matches(field: String): Boolean =
        FS.str(extra["topic"])?.equals(field, ignoreCase = true) == true ||
            topic.raw.equals(field, ignoreCase = true)

    val lat: Double? get() = FS.geoLat(extra["location"])
    val lon: Double? get() = FS.geoLon(extra["location"])

    val crossingVehicle: CrossingVehicle?
        get() = FS.str(extra["border_vehicle"])?.let { CrossingVehicle.fromRaw(it) }
    val crossingDirection: String? get() = FS.str(extra["border_direction"])
    val crossingPassport: String? get() = FS.str(extra["border_passport"])
    val crossingTimeSpent: Int? get() = FS.int(extra["border_time_spent"])
    val crossingTimeString: String?
        get() = FS.str(extra["border_time_spent"]) ?: FS.int(extra["border_time_spent"])?.toString()

    /** `border_ammount_paid` (sic) is the key the apps write. */
    val crossingAmountPaid: Double? get() = FS.double(extra["border_ammount_paid"])
    val crossingAmountString: String?
        get() = FS.str(extra["border_amount_paid"]) ?: FS.str(extra["border_ammount_paid"])
            ?: FS.double(extra["border_ammount_paid"])?.toString()
    val crossingDate: Long? get() = FS.millis(extra["border_crossing_date"])

    val translations: Map<String, String>? get() = FS.stringMap(extra["translations"])

    /** The text in [lang]: its translation, else the English one, else the original. */
    fun displayContent(lang: String): String? {
        val t = translations
        t?.get(lang)?.takeIf { it.isNotEmpty() }?.let { return it }
        t?.get("en")?.takeIf { it.isNotEmpty() }?.let { return it }
        return content
    }

    fun translation(lang: String): String? = LocalizedLookup.value(translations, lang)

    /** The Firestore-shaped document (without `documentId`). */
    fun stored(): Map<String, Any?> = buildMap {
        putAll(extra)
        objectId?.let { put("objectId", it) }
        // Like iOS, the topic string as received is kept in the blob.
        if (!containsKey("topic")) put("topic", topic.raw)
        geohash?.let { put("geohash", it) }
        createdAt?.let { put("createdAt", it) }
        userId?.let { put("userId", it) }
    }

    companion object {
        private val KNOWN = arrayOf("objectId", "geohash", "userId", "createdAt")

        fun fromFirestore(documentId: String, data: Map<String, Any?>): Discussion {
            val topicRaw = FS.str(data["topic"])
            return Discussion(
                documentId = documentId,
                objectId = FS.str(data["objectId"]),
                topic = DiscussionTopic.fromRaw(topicRaw),
                geohash = FS.str(data["geohash"]),
                createdAt = FS.millis(data["createdAt"] ?: data["created"]),
                userId = FS.str(data["userId"]),
                extra = FS.jsonSafe(data).without(*KNOWN),
            )
        }
    }
}

/**
 * A contributed waypoint as a plain pack [Waypoint], so the itinerary map draws it with the same
 * marker layer. The community text becomes the description; the id stays the contributed id.
 */
fun ContributedWaypoint.asWaypoint() = Waypoint(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryId = itinDocumentId,
    name = name,
    translatedName = translatedName,
    description = content,
    type = type,
    maki = maki,
    lat = lat,
    lon = lon,
    ele = ele,
    geohash = geohash,
    hasFuel = hasFuel,
    hasHotel = hasHotel,
    isViewpoint = isViewpoint,
    isBivouac = isBivouac,
    isPoliceCheckpoint = isPoliceCheckpoint,
    isBorder = isBorder,
    isEmbassy = isEmbassy,
    isMountainPass = isMountainPass,
    isBridge = isBridge,
    isWaterCrossing = isWaterCrossing,
    isHistoricalSite = isHistoricalSite,
    isReligiousSite = isReligiousSite,
    isHotSpring = isHotSpring,
    isIntersection = isIntersection,
    isCafe = isCafe,
    isFerry = isFerry,
    isWaterfall = isWaterfall,
    isSwimming = isSwimming,
    openKind = openKind,
    openDetails = openDetails,
)
