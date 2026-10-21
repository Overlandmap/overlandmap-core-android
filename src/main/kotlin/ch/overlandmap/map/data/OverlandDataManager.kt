package ch.overlandmap.map.data

import android.content.Context
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.data.local.WorldDao
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The two general assets behind the Overlanding tab (as on iOS): the `country-borders` vector
 * tiles (pmtiles served by the local tile server) and the `countries` dump (countries, borders,
 * border posts, zones), imported into Room and the search index once downloaded.
 */
class OverlandDataManager(
    context: Context,
    scope: CoroutineScope,
    shop: ShopRepository,
    dao: WorldDao,
    fts: FtsIndex,
) {
    val borders = GeneralAssetManager(
        context, scope, shop,
        assetId = "country-borders",
        destination = File(context.filesDir, "osm_pmtiles/country-borders.pmtiles"),
        title = "Country borders",
        reloadTiles = true,
    )

    val countries = GeneralAssetManager(
        context, scope, shop,
        assetId = "countries",
        destination = File(context.filesDir, "overland/countries.json.gz"),
        title = "Countries",
        reloadTiles = false,
        onInstalled = { file -> OverlandDataImporter.import(file, dao, fts) },
    )

    /** Checks both assets online and downloads what is missing or outdated. */
    fun ensure() {
        borders.ensure()
        countries.ensure()
    }
}
