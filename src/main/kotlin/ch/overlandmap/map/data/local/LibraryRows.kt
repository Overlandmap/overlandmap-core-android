package ch.overlandmap.map.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryDifficulty
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.OpenKind
import ch.overlandmap.map.model.PackAsset
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.model.buildJsonColumn
import ch.overlandmap.map.model.doubleOrNull
import ch.overlandmap.map.model.flag
import ch.overlandmap.map.model.intOrNull
import ch.overlandmap.map.model.longOrNull
import ch.overlandmap.map.model.parseJsonColumn
import ch.overlandmap.map.model.putIfNotEmpty
import ch.overlandmap.map.model.putIfNotNull
import ch.overlandmap.map.model.putIfTrue
import ch.overlandmap.map.model.stringListOrNull
import ch.overlandmap.map.model.stringMapOrNull
import ch.overlandmap.map.model.stringOrNull

/*
 * Room rows for the local library. Each table keeps only the columns its
 * queries filter, order or join on; every other field of the domain model is
 * packed into the `json` column, so a model can gain fields without a schema
 * migration. `toRow()` serializes, `toModel()` restores.
 */

@Entity(tableName = "track_pack")
data class TrackPackRow(
    @PrimaryKey val documentId: String,
    val name: String,
    val isFreeSample: Boolean,
    val needsUpdate: Boolean,
    val json: String?,
)

fun TrackPack.toRow() = TrackPackRow(
    documentId = documentId,
    name = name,
    isFreeSample = isFreeSample,
    needsUpdate = needsUpdate,
    json = buildJsonColumn {
        putIfNotEmpty("translatedName", translatedName)
        putIfNotNull("description", description)
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("editor", editor)
        putIfNotNull("region", region)
        putIfNotNull("type", type)
        putIfNotNull("vehicle", vehicle)
        putIfNotNull("price", price)
        putIfNotNull("baseProductId", baseProductId)
        putIfNotNull("version", version)
        putIfNotNull("nbItineraries", nbItineraries)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titleBlurHash", titleBlurHash)
        putIfTrue("online", online)
        putIfNotNull("lovesCount", lovesCount)
        putIfNotNull("latMin", latMin)
        putIfNotNull("latMax", latMax)
        putIfNotNull("lonMin", lonMin)
        putIfNotNull("lonMax", lonMax)
        putIfNotNull("website", website)
        putIfNotNull("email", email)
        putIfNotNull("createdAt", createdAt)
        putIfNotNull("lastUpdate", lastUpdate)
        putIfNotNull("freeItineraryZip", freeItineraryZip)
        putIfNotNull("trackPackZip", trackPackZip)
        putIfNotNull("pmtilesMap", pmtilesMap)
        putIfNotNull("hillshade", hillshade)
        putIfNotNull("contour", contour)
        putIfNotNull("localPhotoPath", localPhotoPath)
    },
)

fun TrackPackRow.toModel(): TrackPack = parseJsonColumn(json).let { j ->
    TrackPack(
        documentId = documentId,
        name = name,
        isFreeSample = isFreeSample,
        needsUpdate = needsUpdate,
        translatedName = j.stringMapOrNull("translatedName"),
        description = j.stringOrNull("description"),
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        editor = j.stringOrNull("editor"),
        region = j.stringOrNull("region"),
        type = j.stringOrNull("type"),
        vehicle = j.stringOrNull("vehicle"),
        price = j.doubleOrNull("price"),
        baseProductId = j.stringOrNull("baseProductId"),
        version = j.intOrNull("version"),
        nbItineraries = j.intOrNull("nbItineraries") ?: 0,
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titleBlurHash = j.stringOrNull("titleBlurHash"),
        online = j.flag("online"),
        lovesCount = j.intOrNull("lovesCount") ?: 0,
        latMin = j.doubleOrNull("latMin"),
        latMax = j.doubleOrNull("latMax"),
        lonMin = j.doubleOrNull("lonMin"),
        lonMax = j.doubleOrNull("lonMax"),
        website = j.stringOrNull("website"),
        email = j.stringOrNull("email"),
        createdAt = j.longOrNull("createdAt"),
        lastUpdate = j.longOrNull("lastUpdate"),
        freeItineraryZip = j.stringOrNull("freeItineraryZip"),
        trackPackZip = j.stringOrNull("trackPackZip"),
        pmtilesMap = j.stringOrNull("pmtilesMap"),
        hillshade = j.stringOrNull("hillshade"),
        contour = j.stringOrNull("contour"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
    )
}

@Entity(tableName = "itinerary")
data class ItineraryRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val itineraryId: String,
    val name: String,
    val lastOpenedAt: Long?,
    val json: String?,
)

fun Itinerary.toRow() = ItineraryRow(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryId = itineraryId,
    name = name,
    lastOpenedAt = lastOpenedAt,
    json = buildJsonColumn {
        putIfNotEmpty("translatedName", translatedName)
        putIfNotNull("description", description)
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("roadConditions", roadConditions)
        putIfNotEmpty("translatedRoadConditions", translatedRoadConditions)
        putIfNotNull("highlights", highlights)
        putIfNotEmpty("translatedHighlights", translatedHighlights)
        putIfNotEmpty("trackIds", trackIds)
        putIfNotNull("lengthKM", lengthKM)
        putIfNotNull("lengthDays", lengthDays)
        putIfNotNull("difficulty", difficulty)
        putIfNotNull("fuelRange", fuelRange)
        putIfNotNull("offroadPercent", offroadPercent)
        putIfTrue("isFree", isFree)
        putIfTrue("isBuyable", isBuyable)
        putIfTrue("permit", permit)
        putIfNotNull("lovesCount", lovesCount)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titleBlurHash", titleBlurHash)
        putIfNotNull("latMin", latMin)
        putIfNotNull("latMax", latMax)
        putIfNotNull("lonMin", lonMin)
        putIfNotNull("lonMax", lonMax)
        putIfNotNull("centerLat", centerLat)
        putIfNotNull("centerLon", centerLon)
        putIfNotNull("createdAt", createdAt)
        putIfNotNull("lastUpdate", lastUpdate)
        putIfNotNull("localPhotoPath", localPhotoPath)
        putIfNotEmpty("localOtherPhotoPaths", localOtherPhotoPaths)
    },
)

fun ItineraryRow.toModel(): Itinerary = parseJsonColumn(json).let { j ->
    Itinerary(
        documentId = documentId,
        trackPackId = trackPackId,
        itineraryId = itineraryId,
        name = name,
        lastOpenedAt = lastOpenedAt,
        translatedName = j.stringMapOrNull("translatedName"),
        description = j.stringOrNull("description"),
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        roadConditions = j.stringOrNull("roadConditions"),
        translatedRoadConditions = j.stringMapOrNull("translatedRoadConditions"),
        highlights = j.stringOrNull("highlights"),
        translatedHighlights = j.stringMapOrNull("translatedHighlights"),
        trackIds = j.stringListOrNull("trackIds") ?: emptyList(),
        lengthKM = j.doubleOrNull("lengthKM") ?: 0.0,
        lengthDays = j.doubleOrNull("lengthDays") ?: 0.0,
        difficulty = j.stringOrNull("difficulty") ?: ItineraryDifficulty.NORMAL.raw,
        fuelRange = j.doubleOrNull("fuelRange"),
        offroadPercent = j.intOrNull("offroadPercent"),
        isFree = j.flag("isFree"),
        isBuyable = j.flag("isBuyable"),
        permit = j.flag("permit"),
        lovesCount = j.intOrNull("lovesCount") ?: 0,
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titleBlurHash = j.stringOrNull("titleBlurHash"),
        latMin = j.doubleOrNull("latMin"),
        latMax = j.doubleOrNull("latMax"),
        lonMin = j.doubleOrNull("lonMin"),
        lonMax = j.doubleOrNull("lonMax"),
        centerLat = j.doubleOrNull("centerLat"),
        centerLon = j.doubleOrNull("centerLon"),
        createdAt = j.longOrNull("createdAt"),
        lastUpdate = j.longOrNull("lastUpdate"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
        localOtherPhotoPaths = j.stringListOrNull("localOtherPhotoPaths"),
    )
}

@Entity(tableName = "itinerary_step", primaryKeys = ["itineraryId", "documentId"])
data class ItineraryStepRow(
    val documentId: String,
    val itineraryId: String,
    val trackPackId: String,
    val stepId: Int,
    val json: String?,
)

fun ItineraryStep.toRow() = ItineraryStepRow(
    documentId = documentId,
    itineraryId = itineraryId,
    trackPackId = trackPackId,
    stepId = stepId,
    json = buildJsonColumn {
        putIfNotNull("name", name)
        putIfNotEmpty("translatedName", translatedName)
        putIfNotNull("description", description)
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("distanceKm", distanceKm)
        putIfNotNull("lat", lat)
        putIfNotNull("lon", lon)
        putIfNotNull("ele", ele)
        putIfTrue("hasFuel", hasFuel)
        putIfTrue("hasHotel", hasHotel)
        putIfTrue("isViewpoint", isViewpoint)
        putIfTrue("isBivouac", isBivouac)
        putIfTrue("isPoliceCheckpoint", isPoliceCheckpoint)
        putIfTrue("isBorder", isBorder)
        putIfTrue("isEmbassy", isEmbassy)
        putIfTrue("isMountainPass", isMountainPass)
        putIfTrue("isBridge", isBridge)
        putIfTrue("isWaterCrossing", isWaterCrossing)
        putIfTrue("isHistoricalSite", isHistoricalSite)
        putIfTrue("isReligiousSite", isReligiousSite)
        putIfTrue("isHotSpring", isHotSpring)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titlePhotoCaption", titlePhotoCaption)
        putIfNotNull("openKind", openKind?.raw)
        putIfNotNull("openDetails", openDetails)
        putIfNotNull("localPhotoPath", localPhotoPath)
    },
)

fun ItineraryStepRow.toModel(): ItineraryStep = parseJsonColumn(json).let { j ->
    ItineraryStep(
        documentId = documentId,
        itineraryId = itineraryId,
        trackPackId = trackPackId,
        stepId = stepId,
        name = j.stringOrNull("name") ?: "",
        translatedName = j.stringMapOrNull("translatedName"),
        description = j.stringOrNull("description"),
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        distanceKm = j.doubleOrNull("distanceKm") ?: 0.0,
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
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titlePhotoCaption = j.stringOrNull("titlePhotoCaption"),
        openKind = OpenKind.fromRaw(j.stringOrNull("openKind")),
        openDetails = j.stringOrNull("openDetails"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
    )
}

@Entity(tableName = "track")
data class TrackRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val itineraryId: String,
    val json: String?,
)

fun Track.toRow() = TrackRow(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryId = itineraryId,
    json = buildJsonColumn {
        putIfNotNull("name", name)
        putIfNotNull("coordsBase64", coordsBase64)
    },
)

fun TrackRow.toModel(): Track = parseJsonColumn(json).let { j ->
    Track(
        documentId = documentId,
        trackPackId = trackPackId,
        itineraryId = itineraryId,
        name = j.stringOrNull("name"),
        coordsBase64 = j.stringOrNull("coordsBase64") ?: "",
    )
}

@Entity(tableName = "waypoint")
data class WaypointRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val itineraryId: String?,
    val name: String,
    val geohash: String?,
    val json: String?,
)

fun Waypoint.toRow() = WaypointRow(
    documentId = documentId,
    trackPackId = trackPackId,
    itineraryId = itineraryId,
    name = name,
    geohash = geohash,
    json = buildJsonColumn {
        putIfNotEmpty("translatedName", translatedName)
        putIfNotNull("description", description)
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("type", type)
        putIfNotNull("lat", lat)
        putIfNotNull("lon", lon)
        putIfNotNull("ele", ele)
        putIfTrue("hasFuel", hasFuel)
        putIfTrue("hasHotel", hasHotel)
        putIfTrue("isViewpoint", isViewpoint)
        putIfTrue("isBivouac", isBivouac)
        putIfTrue("isPoliceCheckpoint", isPoliceCheckpoint)
        putIfTrue("isBorder", isBorder)
        putIfTrue("isEmbassy", isEmbassy)
        putIfTrue("isMountainPass", isMountainPass)
        putIfTrue("isBridge", isBridge)
        putIfTrue("isWaterCrossing", isWaterCrossing)
        putIfTrue("isHistoricalSite", isHistoricalSite)
        putIfTrue("isReligiousSite", isReligiousSite)
        putIfTrue("isHotSpring", isHotSpring)
        putIfNotNull("openKind", openKind?.raw)
        putIfNotNull("openDetails", openDetails)
    },
)

fun WaypointRow.toModel(): Waypoint = parseJsonColumn(json).let { j ->
    Waypoint(
        documentId = documentId,
        trackPackId = trackPackId,
        itineraryId = itineraryId,
        name = name,
        geohash = geohash,
        translatedName = j.stringMapOrNull("translatedName"),
        description = j.stringOrNull("description"),
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        type = j.stringOrNull("type"),
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
        openKind = OpenKind.fromRaw(j.stringOrNull("openKind")),
        openDetails = j.stringOrNull("openDetails"),
    )
}

@Entity(tableName = "sidebar")
data class SidebarRow(
    @PrimaryKey val documentId: String,
    val trackPackId: String,
    val name: String,
    val json: String?,
)

fun Sidebar.toRow() = SidebarRow(
    documentId = documentId,
    trackPackId = trackPackId,
    name = name,
    json = buildJsonColumn {
        putIfNotEmpty("translatedName", translatedName)
        putIfNotNull("description", description)
        putIfNotEmpty("translatedDesc", translatedDesc)
        putIfNotNull("titlePhotoId", titlePhotoId)
        putIfNotNull("titlePhotoCaption", titlePhotoCaption)
        putIfNotNull("localPhotoPath", localPhotoPath)
    },
)

fun SidebarRow.toModel(): Sidebar = parseJsonColumn(json).let { j ->
    Sidebar(
        documentId = documentId,
        trackPackId = trackPackId,
        name = name,
        translatedName = j.stringMapOrNull("translatedName"),
        description = j.stringOrNull("description"),
        translatedDesc = j.stringMapOrNull("translatedDesc"),
        titlePhotoId = j.stringOrNull("titlePhotoId"),
        titlePhotoCaption = j.stringOrNull("titlePhotoCaption"),
        localPhotoPath = j.stringOrNull("localPhotoPath"),
    )
}

@Entity(tableName = "comment", primaryKeys = ["objectId", "documentId"])
data class CommentRow(
    val documentId: String,
    val objectId: String,
    val createdAt: Long?,
    val json: String?,
)

fun Comment.toRow() = CommentRow(
    documentId = documentId,
    objectId = objectId,
    createdAt = createdAt,
    json = buildJsonColumn {
        putIfNotNull("content", content)
        putIfNotNull("langCode", langCode)
        putIfNotNull("userName", userName)
        putIfNotNull("rating", rating)
        putIfNotNull("englishTranslation", englishTranslation)
        putIfNotEmpty("translations", translations)
    },
)

fun CommentRow.toModel(): Comment = parseJsonColumn(json).let { j ->
    Comment(
        documentId = documentId,
        objectId = objectId,
        createdAt = createdAt,
        content = j.stringOrNull("content") ?: "",
        langCode = j.stringOrNull("langCode"),
        userName = j.stringOrNull("userName"),
        rating = j.intOrNull("rating"),
        englishTranslation = j.stringOrNull("englishTranslation"),
        translations = j.stringMapOrNull("translations"),
    )
}

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
