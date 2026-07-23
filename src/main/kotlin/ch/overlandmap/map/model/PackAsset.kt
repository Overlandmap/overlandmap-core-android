package ch.overlandmap.map.model


/**
 * A record of one asset a pack offers — its itinerary zip, offline map,
 * hillshade or contour — written when the pack is downloaded so the Downloads
 * screen can list every asset (with its size) whether or not it was fetched.
 * The row is the catalogue entry; whether the file is on disk decides the
 * download status, so deleting a file keeps the entry (re-downloadable later).
 */
data class PackAsset(
    val trackPackId: String,
    /** [ch.overlandmap.map.data.PackAssetKind] name. */
    val kind: String,
    val assetId: String,
    val name: String,
    val fileSizeBytes: Long,
)
