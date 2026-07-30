package ch.overlandmap.map.map

import android.content.Context
import android.util.Log
import ch.overlandmap.map.AppConfig
import ch.overlandmap.map.data.StyleAssetsManager
import java.io.File

/**
 * Picks the style for a map. Offline first: when the needed local pieces are
 * available they are served by [LocalTileServer]; otherwise an online style
 * is used.
 */
object MapStyles {

    /** Relative path of the offline style inside `files/assets`. */
    private const val OFFLINE_STYLE_PATH = "styles/detailed.json"

    private val offlineStyleUrl: String
        get() = "${LocalTileServer.baseUrl}/$OFFLINE_STYLE_PATH"

    /**
     * Style of the world (borders) map: the offline style when detail tiles
     * were downloaded, else the free online style.
     */
    fun styleUrl(assetsDirectory: File): String {
        val offlineStyle = File(assetsDirectory, OFFLINE_STYLE_PATH)
        return if (offlineStyle.isFile && LocalTileServer.hasOfflineTiles()) {
            offlineStyleUrl
        } else {
            AppConfig.ONLINE_STYLE_URL
        }
    }

    /**
     * Style of the track pack and itinerary maps: the local world base map
     * (planet.pmtiles + bundled style + downloaded fonts/sprites) once it is
     * on the device, else the same online style the Flutter app uses. The
     * tracks layer is not part of the offline style; the map composables add
     * it through [ensureTracksLayer].
     */
    fun globalStyleUrl(context: Context): String {
        val url = if (LocalTileServer.hasPlanet() && StyleAssetsManager(context).ready()) {
            offlineStyleUrl
        } else {
            AppConfig.GLOBAL_STYLE_URL
        }
        Log.d("MapStyles", "globalStyleUrl → $url (serverPort=${LocalTileServer.port}, hasPlanet=${LocalTileServer.hasPlanet()}, assetsReady=${StyleAssetsManager(context).ready()})")
        return url
    }

    private const val LIGHT_STYLE_PATH = "styles/simplified.json"

    /**
     * The style URL for the itinerary map's [options]. Offline styles carry
     * their hillshade/contour toggles as query params (read by the server) and
     * fall back to the online base map until the local pieces are ready; Mapbox
     * and satellite styles are `mapbox://` URLs, falling back to the offline
     * detailed style when no Mapbox token is available.
     */
    fun resolve(
        context: Context,
        options: MapStyleOptions,
        hasMapboxToken: Boolean,
        mapLanguage: String,
    ): String =
        when (options.base) {
            BaseMapStyle.OFFLINE_LIGHT -> offlineStyle(context, LIGHT_STYLE_PATH, options, mapLanguage)
            BaseMapStyle.OFFLINE_DETAILED -> offlineStyle(context, OFFLINE_STYLE_PATH, options, mapLanguage)
            BaseMapStyle.MAPBOX ->
                if (hasMapboxToken) options.mapboxKind.styleUrl
                else offlineStyle(context, OFFLINE_STYLE_PATH, options, mapLanguage)
            BaseMapStyle.SATELLITE ->
                if (hasMapboxToken) {
                    if (options.satelliteRoads) MapStyleOptions.SATELLITE_WITH_ROADS
                    else MapStyleOptions.SATELLITE_NO_ROADS
                } else {
                    offlineStyle(context, OFFLINE_STYLE_PATH, options, mapLanguage)
                }
        }

    private fun offlineStyle(
        context: Context,
        path: String,
        options: MapStyleOptions,
        mapLanguage: String,
    ): String {
        val url = if (LocalTileServer.hasPlanet() && StyleAssetsManager(context).ready()) {
            "${LocalTileServer.baseUrl}/$path" +
                "?hillshade=${bit(options.hillshade)}&contour=${bit(options.contour)}" +
                "&lang=$mapLanguage"
        } else {
            AppConfig.GLOBAL_STYLE_URL
        }
        Log.d("MapStyles", "offlineStyle → $url (serverPort=${LocalTileServer.port})")
        return url
    }

    private fun bit(value: Boolean) = if (value) 1 else 0
}
