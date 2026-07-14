package ch.overlandmap.map.model

/**
 * A downloadable file of a track pack (Firestore collection `asset`): the
 * free-itinerary zip, the offline vector map (pmtiles), or the hillshade and
 * contour maps (mbtiles). Track packs reference assets by document ID in
 * their `freeItineraryZip` / `pmtilesMap` / `hillshade` / `contour` fields.
 */
data class Asset(
    val documentId: String,
    val url: String? = null,
    val fileSizeMb: Int = 0,
    val name: String = "",
    val version: Int = 0,
    val versionDate: Long? = null,
    val type: String? = null,
    /** sha-256 of the file, when the backend recorded one. */
    val hash: String? = null,
) {
    val fileSizeBytes: Long get() = fileSizeMb * 1_000_000L

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Asset(
            documentId = documentId,
            url = FS.str(data["url"]),
            fileSizeMb = FS.int(data["fileSizeMb"]) ?: 0,
            name = FS.str(data["name"]) ?: "",
            version = FS.int(data["version"]) ?: 0,
            versionDate = FS.millis(data["versionDate"]),
            type = FS.str(data["type"]),
            hash = FS.str(data["hash"]),
        )
    }
}
