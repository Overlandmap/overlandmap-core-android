package ch.overlandmap.map.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Extra
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryDifficulty
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.OpenKind
import ch.overlandmap.map.model.PackAsset
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.model.captionListsOrNull
import ch.overlandmap.map.model.FS
import ch.overlandmap.map.model.buildJsonColumn
import ch.overlandmap.map.model.doubleOrNull
import ch.overlandmap.map.model.extraOrEmpty
import ch.overlandmap.map.model.flag
import ch.overlandmap.map.model.intOrNull
import ch.overlandmap.map.model.longOrNull
import ch.overlandmap.map.model.parseJsonColumn
import ch.overlandmap.map.model.putIfNotEmpty
import ch.overlandmap.map.model.putIfNotNull
import ch.overlandmap.map.model.putExtra
import ch.overlandmap.map.model.putIfTrue
import ch.overlandmap.map.model.putWaypointCommon
import ch.overlandmap.map.model.stringListOrNull
import ch.overlandmap.map.model.stringMapOrNull
import ch.overlandmap.map.model.stringOrNull

/*
 * Room rows for the local library. Each table keeps only the columns its
 * queries filter, order or join on; every other field of the domain model is
 * packed into the `json` column, so a model can gain fields without a schema
 * migration. `toRow()` serializes, `toModel()` restores.
 */

@Entity(
    tableName = "track_pack",
    indices = [
        Index("name", name = "idx_trackPack_name"),
        Index("region", name = "idx_trackPack_region"),
        Index("editorId", name = "idx_trackPack_editor"),
        Index("latMin", "latMax", "lonMin", "lonMax", name = "idx_trackPack_bounds"),
    ],
)
data class TrackPackRow(
    @PrimaryKey val documentId: String,
    // Queryable columns matching the iOS schema (Schema.swift). `isOld` is the
    // iOS name for the local "needs update" flag; `isFreeSample` lives in `json`
    // as on iOS. `lastVersionCheck` is ISO-8601 text (see IsoDate).
    @ColumnInfo(name = "editorId") val editorId: String?,
    val name: String,
    val description: String?,
    val region: String?,
    val latMin: Double?,
    val latMax: Double?,
    val lonMin: Double?,
    val lonMax: Double?,
    @ColumnInfo(name = "lastVersionCheck") val lastVersionCheck: String?,
    @ColumnInfo(name = "isOld") val isOld: Boolean,
    val json: String?,
)

fun TrackPack.toRow() = TrackPackRow(
    documentId = documentId,
    editorId = editor,
    name = name,
    description = description,
    region = region,
    latMin = latMin,
    latMax = latMax,
    lonMin = lonMin,
    lonMax = lonMax,
    lastVersionCheck = lastUpdateCheck?.let(IsoDate::format),
    isOld = needsUpdate,
    json = buildJsonColumn {
        putIfTrue("isFreeSample", isFreeSample)
        putIfNotEmpty("translatedName", translatedName)
        // description, editor, region, latMin/Max, lonMin/Max and
        // lastUpdateCheck are promoted to columns (see above).
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("type", type)
        putIfNotNull("vehicle", vehicle)
        putIfNotNull("price", price)
        putIfNotNull("baseProductId", baseProductId)
        putIfNotNull("version", version)
        putIfNotNull("nbItineraries", nbItineraries)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titleBlurHash", titleBlurHash)
        putIfTrue("online", online)
        putIfTrue("beta", isBeta)
        putIfTrue("isFreeItinOnly", isFreeItineraryOnly)
        putIfNotNull("lovesCount", lovesCount)
        putIfNotNull("website", website)
        putIfNotNull("email", email)
        putIfNotNull("createdAt", createdAt)
        putIfNotNull("lastUpdate", lastUpdate)
        putIfNotNull("freeItineraryZip", freeItineraryZip)
        putIfNotNull("trackPackZip", trackPackZip)
        putIfNotNull("pmtilesMap", pmtilesMap)
        putIfNotNull("hillshade", hillshade)
        putIfNotNull("dem", dem)
        putIfNotNull("contour", contour)
        putIfNotNull("localPhotoPath", localPhotoPath)
    },
)

fun TrackPackRow.toModel(): TrackPack = parseJsonColumn(json).let { j ->
    TrackPack(
        documentId = documentId,
        name = name,
        isFreeSample = j.flag("isFreeSample"),
        needsUpdate = isOld,
        translatedName = j.stringMapOrNull("translatedName"),
        description = description,
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        editor = editorId,
        region = region,
        type = j.stringOrNull("type"),
        vehicle = j.stringOrNull("vehicle"),
        price = j.doubleOrNull("price"),
        baseProductId = j.stringOrNull("baseProductId"),
        version = j.intOrNull("version"),
        nbItineraries = j.intOrNull("nbItineraries") ?: 0,
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titleBlurHash = j.stringOrNull("titleBlurHash"),
        online = j.flag("online"),
        isBeta = j.flag("beta"),
        isFreeItineraryOnly = j.flag("isFreeItinOnly"),
        lovesCount = j.intOrNull("lovesCount") ?: 0,
        latMin = latMin,
        latMax = latMax,
        lonMin = lonMin,
        lonMax = lonMax,
        website = j.stringOrNull("website"),
        email = j.stringOrNull("email"),
        createdAt = j.longOrNull("createdAt"),
        lastUpdate = j.longOrNull("lastUpdate"),
        lastUpdateCheck = IsoDate.parse(lastVersionCheck),
        freeItineraryZip = j.stringOrNull("freeItineraryZip"),
        trackPackZip = j.stringOrNull("trackPackZip"),
        pmtilesMap = j.stringOrNull("pmtilesMap"),
        hillshade = j.stringOrNull("hillshade"),
        dem = j.stringOrNull("dem"),
        contour = j.stringOrNull("contour"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
    )
}

@Entity(
    tableName = "itinerary",
    indices = [
        Index("trackPackId", "itineraryId", name = "idx_itinerary_trackPack"),
        Index("itineraryId", name = "idx_itinerary_itineraryId"),
        Index("name", name = "idx_itinerary_name"),
        Index("lastSeen", name = "idx_itinerary_lastSeen"),
        Index("lengthKm", name = "idx_itinerary_lengthKm"),
        Index("lengthDays", name = "idx_itinerary_lengthDays"),
        Index("difficulty", name = "idx_itinerary_difficulty"),
        Index("roadType", name = "idx_itinerary_roadType"),
        Index("latMin", "latMax", "lonMin", "lonMax", name = "idx_itinerary_bounds"),
        Index("centerLat", "centerLon", name = "idx_itinerary_centre"),
    ],
)
data class ItineraryRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val itineraryId: String,
    val name: String,
    val description: String?,
    // iOS names/types (Schema.swift): lengthKm, lengthDays, difficulty (ordinal
    // 0–3), roadType (offroad %), bounds, centre, lastSeen, favourite.
    @ColumnInfo(name = "lengthKm") val lengthKm: Double,
    val lengthDays: Int,
    val difficulty: Int,
    @ColumnInfo(name = "roadType") val roadType: Int?,
    val latMin: Double?,
    val latMax: Double?,
    val lonMin: Double?,
    val lonMax: Double?,
    val centerLat: Double?,
    val centerLon: Double?,
    @ColumnInfo(name = "lastSeen") val lastSeen: String?,
    val favourite: String?,
    val json: String?,
)

fun Itinerary.toRow() = ItineraryRow(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryId = itineraryId,
    name = name,
    description = description,
    lengthKm = lengthKM,
    lengthDays = lengthDays,
    // The itinerary grid facets, promoted to columns to match iOS. Difficulty
    // is stored as its ordinal (easy=0 … extreme=3) like iOS's INTEGER column.
    difficulty = ItineraryDifficulty.entries.indexOf(ItineraryDifficulty.fromRaw(difficulty)),
    roadType = offroadPercent,
    latMin = latMin,
    latMax = latMax,
    lonMin = lonMin,
    lonMax = lonMax,
    centerLat = centerLat,
    centerLon = centerLon,
    lastSeen = lastOpenedAt?.let(IsoDate::format),
    favourite = null,
    json = buildJsonColumn {
        putIfNotEmpty("translatedName", translatedName)
        // description, lengthKM, lengthDays, difficulty, offroadPercent, bounds,
        // centre and lastSeen are promoted to columns (see above).
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("roadConditions", roadConditions)
        putIfNotEmpty("translatedRoadConditions", translatedRoadConditions)
        putIfNotNull("highlights", highlights)
        putIfNotEmpty("translatedHighlights", translatedHighlights)
        putIfNotEmpty("trackIds", trackIds)
        putIfNotNull("fuelRange", fuelRange)
        putIfTrue("isFree", isFree)
        putIfTrue("isBuyable", isBuyable)
        putIfTrue("permit", permit)
        putIfTrue("beta", isBeta)
        putIfNotNull("lovesCount", lovesCount)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titleBlurHash", titleBlurHash)
        putIfNotNull("titlePhotoCaption", titlePhotoCaption)
        putIfNotEmpty("translatedTitleCaption", translatedTitleCaption)
        putIfNotEmpty("otherPhotoCaptions", otherPhotoCaptions)
        translatedOtherPhotoCaptions?.let { put("translatedOtherPhotoCaptions", org.json.JSONObject(it)) }
        putIfNotNull("createdAt", createdAt)
        putIfNotNull("lastUpdate", lastUpdate)
        putIfNotNull("localPhotoPath", localPhotoPath)
        putIfNotEmpty("localOtherPhotoPaths", localOtherPhotoPaths)
        putExtra(extra)
    },
)

fun ItineraryRow.toModel(): Itinerary = parseJsonColumn(json).let { j ->
    Itinerary(
        documentId = documentId,
        trackPackId = trackPackId,
        itineraryId = itineraryId,
        name = name,
        lastOpenedAt = IsoDate.parse(lastSeen),
        translatedName = j.stringMapOrNull("translatedName"),
        description = description,
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        roadConditions = j.stringOrNull("roadConditions"),
        translatedRoadConditions = j.stringMapOrNull("translatedRoadConditions"),
        highlights = j.stringOrNull("highlights"),
        translatedHighlights = j.stringMapOrNull("translatedHighlights"),
        trackIds = j.stringListOrNull("trackIds") ?: emptyList(),
        lengthKM = lengthKm,
        lengthDays = lengthDays,
        difficulty = ItineraryDifficulty.entries.getOrElse(difficulty) { ItineraryDifficulty.NORMAL }.raw,
        fuelRange = j.doubleOrNull("fuelRange"),
        offroadPercent = roadType,
        isFree = j.flag("isFree"),
        isBuyable = j.flag("isBuyable"),
        permit = j.flag("permit"),
        isBeta = j.flag("beta"),
        lovesCount = j.intOrNull("lovesCount") ?: 0,
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titleBlurHash = j.stringOrNull("titleBlurHash"),
        titlePhotoCaption = j.stringOrNull("titlePhotoCaption"),
        translatedTitleCaption = j.stringMapOrNull("translatedTitleCaption")
            ?: FS.stringMap(j.extraOrEmpty()["translatedTitleCaption"]),
        otherPhotoCaptions = j.stringListOrNull("otherPhotoCaptions"),
        translatedOtherPhotoCaptions = j.captionListsOrNull("translatedOtherPhotoCaptions"),
        latMin = latMin,
        latMax = latMax,
        lonMin = lonMin,
        lonMax = lonMax,
        centerLat = centerLat,
        centerLon = centerLon,
        createdAt = j.longOrNull("createdAt"),
        lastUpdate = j.longOrNull("lastUpdate"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
        localOtherPhotoPaths = j.stringListOrNull("localOtherPhotoPaths"),
        extra = j.extraOrEmpty(),
    )
}

@Entity(
    tableName = "itinerary_step",
    indices = [
        Index("itineraryId", "stepId", name = "idx_step_itinerary"),
        Index("trackPackId", name = "idx_step_trackPack"),
        Index("geohash", name = "idx_step_geohash"),
    ],
)
data class ItineraryStepRow(
    @PrimaryKey val documentId: String,
    val itineraryId: String,
    val trackPackId: String,
    val stepId: Int,
    // Promoted to columns to match iOS (Schema.swift itinerary_step).
    val name: String?,
    val description: String?,
    val geohash: String?,
    val json: String?,
)

fun ItineraryStep.toRow() = ItineraryStepRow(
    documentId = documentId,
    itineraryId = itineraryId,
    trackPackId = trackPackId,
    stepId = stepId,
    name = name,
    description = description,
    geohash = geohash,
    json = buildJsonColumn {
        putWaypointCommon(this@toRow)
        putIfNotNull("distanceKm", distanceKm)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titlePhotoCaption", titlePhotoCaption)
        putIfNotEmpty("translatedTitleCaption", translatedTitleCaption)
        putIfNotNull("localPhotoPath", localPhotoPath)
        putExtra(extra)
    },
)

fun ItineraryStepRow.toModel(): ItineraryStep = parseJsonColumn(json).let { j ->
    ItineraryStep(
        documentId = documentId,
        itineraryId = itineraryId,
        trackPackId = trackPackId,
        stepId = stepId,
        name = name ?: "",
        translatedName = j.stringMapOrNull("translatedName"),
        description = description,
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        distanceKm = j.doubleOrNull("distanceKm") ?: 0.0,
        lat = j.doubleOrNull("lat"),
        lon = j.doubleOrNull("lon"),
        ele = j.intOrNull("ele"),
        geohash = geohash,
        hasFuel = j.flag("hasFuel"),
        hasHotel = j.flag("hasHotel"),
        isViewpoint = j.flag("isViewpoint"),
        isBivouac = j.flag("isBivouac"),
        isPoliceCheckpoint = j.flag("isPoliceCheckpoint"),
        isBorder = j.flag("isBorder"),
        isEmbassy = j.flag("isEmbassy"),
        isMountainPass = j.flag("isMountainPass"),
        isBridge = j.flag("isBridge"),
        isWaterCrossing = j.flag("isWaterCrossing"),
        isHistoricalSite = j.flag("isHistoricalSite"),
        isReligiousSite = j.flag("isReligiousSite"),
        isHotSpring = j.flag("isHotSpring"),
        isIntersection = j.flag("isIntersection"),
        isCafe = j.flag("isCafe"),
        isFerry = j.flag("isFerry"),
        isWaterfall = j.flag("isWaterfall"),
        swimming = j.flag("swimming"),
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titlePhotoCaption = j.stringOrNull("titlePhotoCaption"),
        translatedTitleCaption = j.stringMapOrNull("translatedTitleCaption")
            ?: FS.stringMap(j.extraOrEmpty()["translatedTitleCaption"]),
        openKind = OpenKind.fromRaw(j.stringOrNull("openKind")),
        openDetails = j.stringOrNull("openDetails"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
        extra = j.extraOrEmpty(),
    )
}

@Entity(
    tableName = "track",
    indices = [
        Index("trackPackId", name = "idx_track_trackPack"),
        Index("itineraryDocId", name = "idx_track_itinerary"),
    ],
)
data class TrackRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    // iOS names the itinerary reference `itineraryDocId` (Schema.swift track).
    @ColumnInfo(name = "itineraryDocId") val itineraryDocId: String,
    val name: String?,
    val description: String?,
    val json: String?,
)

fun Track.toRow() = TrackRow(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryDocId = itineraryId,
    name = name,
    // The Track model carries no description; the column exists for iOS parity.
    description = null,
    json = buildJsonColumn {
        putIfNotNull("coordsBase64", coordsBase64)
    },
)

fun TrackRow.toModel(): Track = parseJsonColumn(json).let { j ->
    Track(
        documentId = documentId,
        trackPackId = trackPackId,
        itineraryId = itineraryDocId,
        name = name,
        coordsBase64 = j.stringOrNull("coordsBase64") ?: "",
    )
}

@Entity(
    tableName = "waypoint",
    indices = [
        Index("trackPackId", name = "idx_waypoint_trackPack"),
        Index("itineraryDocId", name = "idx_waypoint_itinerary"),
        Index("geohash", name = "idx_waypoint_geohash"),
        Index("name", name = "idx_waypoint_name"),
    ],
)
data class WaypointRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    // iOS names the itinerary reference `itineraryDocId` (Schema.swift waypoint).
    @ColumnInfo(name = "itineraryDocId") val itineraryDocId: String?,
    val name: String,
    val description: String?,
    val geohash: String?,
    val json: String?,
)

fun Waypoint.toRow() = WaypointRow(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryDocId = itineraryId,
    name = name,
    description = description,
    geohash = geohash,
    json = buildJsonColumn {
        putWaypointCommon(this@toRow)
        putIfNotNull("type", type)
        putIfNotNull("maki", maki)
        putExtra(extra)
    },
)

fun WaypointRow.toModel(): Waypoint = parseJsonColumn(json).let { j ->
    Waypoint(
        documentId = documentId,
        trackPackId = trackPackId,
        itineraryId = itineraryDocId,
        name = name,
        geohash = geohash,
        translatedName = j.stringMapOrNull("translatedName"),
        description = description,
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        type = j.stringOrNull("type"),
        maki = j.stringOrNull("maki"),
        lat = j.doubleOrNull("lat"),
        lon = j.doubleOrNull("lon"),
        ele = j.intOrNull("ele"),
        hasFuel = j.flag("hasFuel"),
        hasHotel = j.flag("hasHotel"),
        isViewpoint = j.flag("isViewpoint"),
        isBivouac = j.flag("isBivouac"),
        isPoliceCheckpoint = j.flag("isPoliceCheckpoint"),
        isBorder = j.flag("isBorder"),
        isEmbassy = j.flag("isEmbassy"),
        isMountainPass = j.flag("isMountainPass"),
        isBridge = j.flag("isBridge"),
        isWaterCrossing = j.flag("isWaterCrossing"),
        isHistoricalSite = j.flag("isHistoricalSite"),
        isReligiousSite = j.flag("isReligiousSite"),
        isHotSpring = j.flag("isHotSpring"),
        isIntersection = j.flag("isIntersection"),
        isCafe = j.flag("isCafe"),
        isFerry = j.flag("isFerry"),
        isWaterfall = j.flag("isWaterfall"),
        swimming = j.flag("swimming"),
        openKind = OpenKind.fromRaw(j.stringOrNull("openKind")),
        openDetails = j.stringOrNull("openDetails"),
        extra = j.extraOrEmpty(),
    )
}

@Entity(
    tableName = "sidebar",
    indices = [
        Index("trackPackId", name = "idx_sidebar_trackPack"),
        Index("name", name = "idx_sidebar_name"),
    ],
)
data class SidebarRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val name: String,
    val description: String?,
    val json: String?,
)

fun Sidebar.toRow() = SidebarRow(
    documentId = documentId,
    trackPackId = trackPackId,
    name = name,
    description = description,
    json = buildJsonColumn {
        putIfNotEmpty("translatedName", translatedName)
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titlePhotoCaption", titlePhotoCaption)
        putIfNotEmpty("translatedTitleCaption", translatedTitleCaption)
        putIfNotNull("localPhotoPath", localPhotoPath)
        putIfNotEmpty("otherPhotoCaptions", otherPhotoCaptions)
        translatedOtherPhotoCaptions?.let { put("translatedOtherPhotoCaptions", org.json.JSONObject(it)) }
        putIfNotEmpty("localOtherPhotoPaths", localOtherPhotoPaths)
        putExtra(extra)
    },
)

fun SidebarRow.toModel(): Sidebar = parseJsonColumn(json).let { j ->
    Sidebar(
        documentId = documentId,
        trackPackId = trackPackId,
        name = name,
        translatedName = j.stringMapOrNull("translatedName"),
        description = description,
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titlePhotoCaption = j.stringOrNull("titlePhotoCaption"),
        translatedTitleCaption = j.stringMapOrNull("translatedTitleCaption")
            ?: FS.stringMap(j.extraOrEmpty()["translatedTitleCaption"]),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
        otherPhotoCaptions = j.stringListOrNull("otherPhotoCaptions"),
        translatedOtherPhotoCaptions = j.captionListsOrNull("translatedOtherPhotoCaptions"),
        localOtherPhotoPaths = j.stringListOrNull("localOtherPhotoPaths"),
        extra = j.extraOrEmpty(),
    )
}

@Entity(
    tableName = "comment",
    indices = [
        Index("objectId", name = "idx_comment_object"),
        Index("itinDocumentId", name = "idx_comment_itin"),
    ],
)
data class CommentRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val geohash: String?,
    val type: String?,
    val rating: Int?,
    val itinDocumentId: String?,
    val createdAt: Long?,
    val userId: String?,
    val json: String?,
)

fun Comment.toRow() = CommentRow(
    documentId = documentId,
    objectId = objectId,
    geohash = geohash,
    type = type,
    rating = rating,
    itinDocumentId = itinDocumentId,
    createdAt = createdAt,
    userId = userId,
    json = Extra.toJson(
        buildMap {
            putAll(extra)
            if (content.isNotEmpty()) put("content", content)
            langCode?.let { put("langCode", it) }
            userName?.let { put("userName", it) }
            englishTranslation?.let { put("englishTranslation", it) }
            translations?.let { put("translations", it) }
            trackPackId?.let { put("trackPackId", it) }
        },
    ),
)

fun CommentRow.toModel(): Comment = Comment.fromFirestore(
    documentId,
    Extra.fromJson(json) + columns(
        "objectId" to objectId, "geohash" to geohash, "type" to type, "rating" to rating,
        "itinDocumentId" to itinDocumentId, "createdAt" to createdAt, "userId" to userId,
    ),
)

/** The non-null [pairs] as a map, for merging a row's columns back into its `json` payload. */
internal fun columns(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
    pairs.filter { it.second != null }.toMap()

@Entity(tableName = "pack_asset", primaryKeys = ["trackPackId", "kind"])
data class PackAssetRow(
    val trackPackId: String,
    val kind: String,
    val json: String?,
)

fun PackAsset.toRow() = PackAssetRow(
    trackPackId = trackPackId,
    kind = kind,
    json = buildJsonColumn {
        putIfNotNull("assetId", assetId)
        putIfNotNull("name", name)
        putIfNotNull("fileSizeBytes", fileSizeBytes)
    },
)

fun PackAssetRow.toModel(): PackAsset = parseJsonColumn(json).let { j ->
    PackAsset(
        trackPackId = trackPackId,
        kind = kind,
        assetId = j.stringOrNull("assetId") ?: "",
        name = j.stringOrNull("name") ?: "",
        fileSizeBytes = j.longOrNull("fileSizeBytes") ?: 0L,
    )
}
