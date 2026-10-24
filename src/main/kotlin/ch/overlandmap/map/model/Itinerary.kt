package ch.overlandmap.map.model

import ch.overlandmap.map.AppConfig

/** Ordinals match the Firestore difficulty codes (easy=0 … challenging=3). */
enum class ItineraryDifficulty(val raw: String) {
    EASY("easy"), NORMAL("normal"), HARD("hard"), EXTREME("extreme");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: NORMAL
    }
}

/**
 * One itinerary of a track pack. Port of `models/itinerary.dart`. The first
 * entry of [trackIds] is the main track (name/length source).
 */
data class Itinerary(
    val documentId: String,
    val trackPackId: String,
    /** Stable slug carried by vector-tile features and deep links (not the doc ID). */
    val itineraryId: String = "",
    val name: String,
    val translatedName: Map<String, String>? = null,
    val description: String? = null,
    val translatedDesc: Map<String, String>? = null,
    val roadConditions: String? = null,
    val translatedRoadConditions: Map<String, String>? = null,
    val highlights: String? = null,
    val translatedHighlights: Map<String, String>? = null,
    val trackIds: List<String> = emptyList(),
    val lengthKM: Double = 0.0,
    val lengthDays: Int = 0,
    val difficulty: String = ItineraryDifficulty.NORMAL.raw,
    val fuelRange: Double? = null,
    val offroadPercent: Int? = null,
    val isFree: Boolean = false,
    /**
     * From the free sample's `buyable_itineraries`: part of the full pack but
     * not of the sample, so it has a description and photo but no steps or
     * tracks. Local-only flag, never read from a live Firestore document.
     */
    val isBuyable: Boolean = false,
    val permit: Boolean = false,
    /** A beta release (`beta`), shown with a badge. */
    val isBeta: Boolean = false,
    val lovesCount: Int = 0,
    val titlePhotoId: String? = null,
    val titleBlurHash: String? = null,
    /** Photo captions; indexed for search along with the description. */
    val titlePhotoCaption: String? = null,
    val translatedTitleCaption: Map<String, String>? = null,
    val otherPhotoCaptions: List<String>? = null,
    val translatedOtherPhotoCaptions: Map<String, List<String>>? = null,
    val latMin: Double? = null,
    val latMax: Double? = null,
    val lonMin: Double? = null,
    val lonMax: Double? = null,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val createdAt: Long? = null,
    val lastUpdate: Long? = null,
    /** Absolute path of the title photo unpacked from a downloaded zip. */
    val localPhotoPath: String? = null,
    /** Absolute paths of the extra photos unpacked from a downloaded zip. */
    val localOtherPhotoPaths: List<String>? = null,
    /** When this itinerary was last opened locally (epoch millis); local-only. */
    val lastOpenedAt: Long? = null,
    /** Unmodelled scalar fields of the export (`sidebarDocId`, `editorId`, …), see [scalarExtras]. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    val titlePhotoUrl: String?
        get() = localPhotoPath?.let { "file://$it" } ?: titlePhotoId?.let(AppConfig::photoUrl)

    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    fun description(lang: String): String? = localized(description, translatedDesc, lang)

    fun roadConditions(lang: String): String? =
        localized(roadConditions, translatedRoadConditions, lang)

    fun highlights(lang: String): String? = localized(highlights, translatedHighlights, lang)

    /**
     * The text an itinerary contributes to the search index in [lang] (iOS `TrackPackImporter`):
     * the description, then highlights, road conditions and the photo captions, one per line.
     * Only an exact [lang] key is used for the translation, as the index has one table per language.
     */
    fun indexableDescription(lang: String): String {
        val body = translatedDesc?.get(lang) ?: description.orEmpty()
        val captions = ((listOf(titlePhotoCaption) + (otherPhotoCaptions ?: emptyList())).filterNotNull())
            .joinToString("\n")
        val parts = listOfNotNull(
            translatedHighlights?.get(lang) ?: highlights,
            translatedRoadConditions?.get(lang) ?: roadConditions,
            captions.ifEmpty { null },
        )
        return if (parts.isEmpty()) body else body + "\n" + parts.joinToString("\n")
    }

    fun titlePhotoCaption(lang: String): String? = localized(titlePhotoCaption, translatedTitleCaption, lang)

    fun otherPhotoCaption(index: Int, lang: String): String? =
        localized(otherPhotoCaptions?.getOrNull(index),
            translatedOtherPhotoCaptions?.mapNotNull { (language, captions) ->
                captions.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { language to it }
            }?.toMap(), lang)

    companion object {
        private val KNOWN = arrayOf("beta", "center", "createdAt", "description", "difficulty", "fuelRange", "highlights", "isBuyable", "isFree", "itineraryId", "lastUpdate", "latMax", "latMin", "lengthDays", "lengthKM", "lonMax", "lonMin", "lovesCount", "name", "offroadPercent", "otherPhotoCaptions", "translatedOtherPhotoCaptions", "permit", "roadConditions", "titleBlurHash", "titlePhotoCaption", "translatedTitleCaption", "titlePhotoId", "trackIds", "trackPackId", "translatedDesc", "translatedHighlights", "translatedName", "translatedRoadConditions")

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Itinerary(
            documentId = documentId,
            trackPackId = FS.str(data["trackPackId"]) ?: "",
            itineraryId = FS.str(data["itineraryId"]) ?: "",
            name = FS.str(data["name"]) ?: "",
            translatedName = FS.stringMap(data["translatedName"]),
            description = FS.str(data["description"]),
            translatedDesc = FS.stringMap(data["translatedDesc"]),
            roadConditions = FS.str(data["roadConditions"]),
            translatedRoadConditions = FS.stringMap(data["translatedRoadConditions"]),
            highlights = FS.str(data["highlights"]),
            translatedHighlights = FS.stringMap(data["translatedHighlights"]),
            trackIds = FS.stringList(data["trackIds"]) ?: emptyList(),
            lengthKM = FS.double(data["lengthKM"]) ?: 0.0,
            lengthDays = FS.double(data["lengthDays"])?.toInt() ?: 0,
            difficulty = FS.str(data["difficulty"])
                ?: FS.int(data["difficulty"])?.let { ItineraryDifficulty.entries.getOrNull(it)?.raw }
                ?: ItineraryDifficulty.NORMAL.raw,
            fuelRange = FS.double(data["fuelRange"]),
            offroadPercent = FS.int(data["offroadPercent"]),
            isFree = FS.bool(data["isFree"]),
            permit = FS.bool(data["permit"]),
            isBeta = FS.bool(data["beta"]),
            lovesCount = FS.int(data["lovesCount"]) ?: 0,
            titlePhotoId = FS.str(data["titlePhotoId"]),
            titleBlurHash = FS.str(data["titleBlurHash"]),
            titlePhotoCaption = FS.str(data["titlePhotoCaption"]),
            translatedTitleCaption = FS.stringMap(data["translatedTitleCaption"]),
            otherPhotoCaptions = FS.stringList(data["otherPhotoCaptions"]),
            translatedOtherPhotoCaptions = FS.captionLists(data["translatedOtherPhotoCaptions"]),
            latMin = FS.double(data["latMin"]),
            latMax = FS.double(data["latMax"]),
            lonMin = FS.double(data["lonMin"]),
            lonMax = FS.double(data["lonMax"]),
            centerLat = FS.geoLat(data["center"]),
            centerLon = FS.geoLon(data["center"]),
            createdAt = FS.millis(data["createdAt"]),
            lastUpdate = FS.millis(data["lastUpdate"]),
            extra = scalarExtras(data, *KNOWN),
        )
    }
}
