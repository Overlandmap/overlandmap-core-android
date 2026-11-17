package ch.overlandmap.map.data

import android.content.Context
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.data.local.WorldDao
import ch.overlandmap.map.data.routes.RouteDataManager
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The general assets behind the Overlanding tab (as on iOS): the `country-borders` vector tiles
 * (pmtiles served by the local tile server), the `countries` dump (countries, borders, border
 * posts, zones), imported into Room and the search index once downloaded, the `routes-pmtiles`
 * vector tiles of the route network, and the `routes-graph` zip, the route-planning graph (see
 * [RouteDataManager]).
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

    /**
     * The `routes-pmtiles` vector tiles of the route network (layers `segments` and `nodes`),
     * served by the local tile server as source `routes`: the Overlanding map draws the routes
     * from them offline.
     */
    val routeTiles = GeneralAssetManager(
        context, scope, shop,
        assetId = "routes-pmtiles",
        destination = File(context.filesDir, "osm_pmtiles/routes.pmtiles"),
        title = "Overlanding routes",
        reloadTiles = true,
    )

    /** The route-planning graph. */
    val routes = RouteDataManager(context, scope, shop)

    /** Checks the assets online and downloads what is missing or outdated. */
    fun ensure() {
        borders.ensure()
        countries.ensure()
        routeTiles.ensure()
        routes.ensure()
    }
}
