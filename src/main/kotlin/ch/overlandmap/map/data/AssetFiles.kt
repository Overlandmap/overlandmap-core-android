package ch.overlandmap.map.data

import ch.overlandmap.map.model.TrackPack

/**
 * Rules for the map files downloaded for track packs (iOS keeps them by asset id, too).
 *
 * A file is named after its *asset* document, not after the pack that asked for it, so two
 * packs that reference the same asset share one file and one download. That makes deletion
 * reference-counted: removing a pack's asset only deletes the file when no other local pack
 * still references the same asset id.
 */
object AssetFiles {

    /** `assetId` as a safe file name stem. */
    fun fileStem(assetId: String): String = assetId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** The asset id [pack] references for [kind], or null (free itinerary zips aren't kept). */
    fun assetIdOf(pack: TrackPack, kind: PackAssetKind): String? = when (kind) {
        PackAssetKind.OFFLINE_MAP -> pack.pmtilesMap
        PackAssetKind.HILLSHADE -> pack.hillshade
        PackAssetKind.DEM -> pack.dem
        PackAssetKind.CONTOUR -> pack.contour
        PackAssetKind.FREE_ITINERARY -> null
    }?.takeIf { it.isNotEmpty() }

    /** True when a local pack other than [packId] still references [assetId] as [kind]. */
    fun isReferencedByOtherPack(
        assetId: String,
        kind: PackAssetKind,
        packId: String,
        localPacks: List<TrackPack>,
    ): Boolean = localPacks.any { it.documentId != packId && assetIdOf(it, kind) == assetId }
}
