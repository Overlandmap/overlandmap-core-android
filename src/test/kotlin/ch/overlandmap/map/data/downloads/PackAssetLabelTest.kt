package ch.overlandmap.map.data.downloads

import ch.overlandmap.map.R
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.data.labelRes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackAssetLabelTest {
    @Test fun kindsUseTheDownloadsScreenLabels() {
        assertEquals(R.string.itineraries, PackAssetKind.FREE_ITINERARY.labelRes())
        assertEquals(R.string.offline_map, PackAssetKind.OFFLINE_MAP.labelRes())
        assertEquals(R.string.hillshade_map, PackAssetKind.HILLSHADE.labelRes())
        assertEquals(R.string.relief_map, PackAssetKind.DEM.labelRes())
        assertEquals(R.string.contour_map, PackAssetKind.CONTOUR.labelRes())
    }

    @Test fun olderQueuedDownloadsResolveKindsFromTheirDestinations() {
        assertEquals(PackAssetKind.FREE_ITINERARY, assetKind(null, File("/cache/pack.zip"), true))
        assertEquals(PackAssetKind.OFFLINE_MAP, assetKind(null, File("/files/osm_pmtiles/detail/asset.pmtiles"), false))
        assertEquals(PackAssetKind.HILLSHADE, assetKind(null, File("/files/hillshade/asset.mbtiles"), false))
        assertEquals(PackAssetKind.DEM, assetKind(null, File("/files/dem/asset.pmtiles"), false))
        assertEquals(PackAssetKind.CONTOUR, assetKind(null, File("/files/contour/asset.mbtiles"), false))
        assertEquals(PackAssetKind.DEM, assetKind("DEM", File("/unknown/file"), false))
        assertNull(assetKind(null, File("/unknown/file"), false))
    }
}
