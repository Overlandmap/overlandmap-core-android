package ch.overlandmap.map.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.overlandmap.map.AppConfig

/**
 * A purchasable pack of itineraries. Kotlin port of the Flutter app's
 * `models/track_pack.dart` (FSTrackPack), reduced to the fields this app uses.
 * Rows exist in Room only for downloaded (purchased or free-itinerary) packs;
 * the shop reads the same model straight from Firestore.
 */
@Entity(tableName = "track_pack")
data class TrackPack(
    @PrimaryKey val documentId: String,
    val name: String,
    val translatedName: Map<String, String>? = null,
    val description: String? = null,
    val translatedDesc: Map<String, String>? = null,
    val editor: String? = null,
    val region: String? = null,
    val type: String? = null,
    val vehicle: String? = null,
    val price: Double? = null,
    val baseProductId: String? = null,
    val version: Int? = null,
    val nbItineraries: Int = 0,
    val titlePhotoId: String? = null,
    val titleBlurHash: String? = null,
    val online: Boolean = false,
    val lovesCount: Int = 0,
    val latMin: Double? = null,
    val latMax: Double? = null,
    val lonMin: Double? = null,
    val lonMax: Double? = null,
    val website: String? = null,
    val email: String? = null,
    val createdAt: Long? = null,
    val lastUpdate: Long? = null,
    /** Asset document IDs (collection `asset`) of the downloadable files. */
    val freeItineraryZip: String? = null,
    val trackPackZip: String? = null,
    val pmtilesMap: String? = null,
    val hillshade: String? = null,
    val contour: String? = null,
    /** Absolute path of the title photo unpacked from a downloaded zip. */
    val localPhotoPath: String? = null,
    /**
     * True when the pack came from a free-sample zip, not a full purchase
     * download. Local-only: never read from or written to Firestore.
     */
    val isFreeSample: Boolean = false,
    /**
     * True when "check for update" found a newer zip online. Local-only;
     * cleared by re-downloading (the fresh row starts at false).
     */
    val needsUpdate: Boolean = false,
) {
    /** Play Store product ID used to purchase this pack. */
    val productId: String? get() = baseProductId

    val titlePhotoUrl: String?
        get() = localPhotoPath?.let { "file://$it" } ?: titlePhotoId?.let(AppConfig::photoUrl)

    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    fun description(lang: String): String? = localized(description, translatedDesc, lang)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = TrackPack(
            documentId = documentId,
            name = FS.str(data["name"]) ?: "",
            translatedName = FS.stringMap(data["translatedName"]),
            description = FS.str(data["description"]),
            translatedDesc = FS.stringMap(data["translatedDesc"]),
            editor = FS.str(data["editor"]),
            region = FS.str(data["region"]),
            type = FS.str(data["type"]),
            vehicle = FS.str(data["vehicle"]),
            price = FS.double(data["price"]),
            baseProductId = FS.str(data["productId"]),
            version = FS.int(data["version"]),
            nbItineraries = FS.int(data["nbItineraries"]) ?: 0,
            titlePhotoId = FS.str(data["titlePhotoId"]),
            titleBlurHash = FS.str(data["titleBlurHash"]),
            online = FS.bool(data["online"]),
            lovesCount = FS.int(data["lovesCount"]) ?: 0,
            latMin = FS.double(data["latMin"]),
            latMax = FS.double(data["latMax"]),
            lonMin = FS.double(data["lonMin"]),
            lonMax = FS.double(data["lonMax"]),
            website = FS.str(data["website"]),
            email = FS.str(data["email"]),
            createdAt = FS.millis(data["createdAt"]),
            lastUpdate = FS.millis(data["lastUpdate"]),
            freeItineraryZip = FS.str(data["freeItineraryZip"]),
            trackPackZip = FS.str(data["trackPackZip"]),
            pmtilesMap = FS.str(data["pmtilesMap"]),
            hillshade = FS.str(data["hillshade"]),
            contour = FS.str(data["contour"]),
        )
    }
}
