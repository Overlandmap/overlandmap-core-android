package ch.overlandmap.map.data

import ch.overlandmap.map.model.TrackPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFilesTest {

    private fun pack(id: String, map: String? = null, hillshade: String? = null) =
        TrackPack.fromFirestore(
            id,
            buildMap {
                put("name", id)
                map?.let { put("pmtilesMap", it) }
                hillshade?.let { put("hillshade", it) }
            },
        )

    @Test
    fun fileNamesComeFromTheAssetId() {
        assertEquals("abc123", AssetFiles.fileStem("abc123"))
        assertEquals("a_b_c", AssetFiles.fileStem("a/b c"))
    }

    @Test
    fun assetIdDependsOnKind() {
        val p = pack("p", map = "m1", hillshade = "h1")
        assertEquals("m1", AssetFiles.assetIdOf(p, PackAssetKind.OFFLINE_MAP))
        assertEquals("h1", AssetFiles.assetIdOf(p, PackAssetKind.HILLSHADE))
        assertNull(AssetFiles.assetIdOf(p, PackAssetKind.DEM))
        assertNull(AssetFiles.assetIdOf(p, PackAssetKind.FREE_ITINERARY))
    }

    @Test
    fun sharedAssetIsKeptWhileAnotherPackReferencesIt() {
        val a = pack("a", map = "shared")
        val b = pack("b", map = "shared")
        val c = pack("c", map = "own")
        assertTrue(AssetFiles.isReferencedByOtherPack("shared", PackAssetKind.OFFLINE_MAP, "a", listOf(a, b, c)))
        assertFalse(AssetFiles.isReferencedByOtherPack("shared", PackAssetKind.OFFLINE_MAP, "a", listOf(a, c)))
        // The same id under another kind is a different reference.
        assertFalse(AssetFiles.isReferencedByOtherPack("shared", PackAssetKind.HILLSHADE, "a", listOf(a, b)))
    }
}
