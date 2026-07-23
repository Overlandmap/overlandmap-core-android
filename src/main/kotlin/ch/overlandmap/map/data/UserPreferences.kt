package ch.overlandmap.map.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.overlandmap.map.map.BaseMapStyle
import ch.overlandmap.map.map.MapStyleOptions
import ch.overlandmap.map.map.MapboxStyleKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Unit preferences (the UI language is handled by AppCompat per-app locales). */
class UserPreferences(private val context: Context) {

    private val useMilesKey = booleanPreferencesKey("use_miles")
    private val useFeetKey = booleanPreferencesKey("use_feet")
    private val mapLanguageKey = stringPreferencesKey("map_language")

    private val mapStyleKey = stringPreferencesKey("map_style")
    private val mapboxKindKey = stringPreferencesKey("mapbox_kind")
    private val satelliteRoadsKey = booleanPreferencesKey("satellite_roads")
    private val offlineHillshadeKey = booleanPreferencesKey("offline_hillshade")
    private val offlineContourKey = booleanPreferencesKey("offline_contour")
    private val mapboxTokenKey = stringPreferencesKey("mapbox_token")
    private val mapboxTokenDateKey = longPreferencesKey("mapbox_token_date")
    private val lastRouteKey = stringPreferencesKey("last_route")
    // Itinerary screen state, restored only on a cold-start route restore.
    private val lastTabKey = intPreferencesKey("last_tab")
    private val lastStepKey = intPreferencesKey("last_step")
    private val lastZoomKey = doublePreferencesKey("last_zoom")
    private val lastLatKey = doublePreferencesKey("last_lat")
    private val lastLonKey = doublePreferencesKey("last_lon")
    private val debugShowZoomKey = booleanPreferencesKey("debug_show_zoom")
    private val lastCheckInsFetchKey = longPreferencesKey("last_check_ins_fetch")
    private val gpsFormatKey = stringPreferencesKey("gps_format")

    val useMiles: Flow<Boolean> = context.dataStore.data.map { it[useMilesKey] ?: false }
    val useFeet: Flow<Boolean> = context.dataStore.data.map { it[useFeetKey] ?: false }

    /** Debug: overlay the current map zoom on the itinerary map (default off). */
    val debugShowZoom: Flow<Boolean> = context.dataStore.data.map { it[debugShowZoomKey] ?: false }

    fun debugShowZoomNow(): Boolean = runBlocking { debugShowZoom.first() }

    suspend fun setDebugShowZoom(value: Boolean) {
        context.dataStore.edit { it[debugShowZoomKey] = value }
    }

    /** Epoch millis when check-ins were last fetched from Firestore (0 = never). */
    val lastCheckInsFetch: Flow<Long> =
        context.dataStore.data.map { it[lastCheckInsFetchKey] ?: 0L }

    fun lastCheckInsFetchNow(): Long = runBlocking { lastCheckInsFetch.first() }

    suspend fun setLastCheckInsFetch(epochMillis: Long) {
        context.dataStore.edit { it[lastCheckInsFetchKey] = epochMillis }
    }

    /** GPS coordinate display format: DD (decimal degrees), DDMM, or DDMMSS. */
    val gpsFormat: Flow<GpsFormat> =
        context.dataStore.data.map { p ->
            p[gpsFormatKey]?.let { runCatching { GpsFormat.valueOf(it) }.getOrNull() }
                ?: GpsFormat.DD
        }

    fun gpsFormatNow(): GpsFormat = runBlocking { gpsFormat.first() }

    suspend fun setGpsFormat(format: GpsFormat) {
        context.dataStore.edit { it[gpsFormatKey] = format.name }
    }

    /** The route of the screen last shown, restored after the app is killed. */
    fun lastRouteNow(): String? = runBlocking { context.dataStore.data.first()[lastRouteKey] }

    suspend fun setLastRoute(route: String) {
        context.dataStore.edit { it[lastRouteKey] = route }
    }

    fun lastTabNow(): Int = runBlocking { context.dataStore.data.first()[lastTabKey] ?: 0 }

    fun lastStepIndexNow(): Int = runBlocking { context.dataStore.data.first()[lastStepKey] ?: 0 }

    /** The saved (zoom, lat, lon), or null when none is stored. */
    fun lastCameraNow(): Triple<Double, Double, Double>? = runBlocking {
        val p = context.dataStore.data.first()
        val zoom = p[lastZoomKey]
        val lat = p[lastLatKey]
        val lon = p[lastLonKey]
        if (zoom != null && lat != null && lon != null) Triple(zoom, lat, lon) else null
    }

    suspend fun setLastTab(tab: Int) {
        context.dataStore.edit { it[lastTabKey] = tab }
    }

    suspend fun setLastStepIndex(step: Int) {
        context.dataStore.edit { it[lastStepKey] = step }
    }

    suspend fun setLastCamera(zoom: Double, lat: Double, lon: Double) {
        context.dataStore.edit {
            it[lastZoomKey] = zoom
            it[lastLatKey] = lat
            it[lastLonKey] = lon
        }
    }

    /** The chosen map style and its per-style customization. */
    val mapStyle: Flow<MapStyleOptions> = context.dataStore.data.map(::readMapStyle)

    /** [mapStyle] read synchronously, for the style-load path. */
    fun mapStyleNow(): MapStyleOptions = runBlocking { mapStyle.first() }

    private fun readMapStyle(p: Preferences) = MapStyleOptions(
        base = p[mapStyleKey]?.let { runCatching { BaseMapStyle.valueOf(it) }.getOrNull() }
            ?: BaseMapStyle.OFFLINE_DETAILED,
        hillshade = p[offlineHillshadeKey] ?: true,
        contour = p[offlineContourKey] ?: true,
        mapboxKind = p[mapboxKindKey]?.let { runCatching { MapboxStyleKind.valueOf(it) }.getOrNull() }
            ?: MapboxStyleKind.STREETS,
        satelliteRoads = p[satelliteRoadsKey] ?: true,
    )

    suspend fun setMapStyle(options: MapStyleOptions) {
        context.dataStore.edit {
            it[mapStyleKey] = options.base.name
            it[offlineHillshadeKey] = options.hillshade
            it[offlineContourKey] = options.contour
            it[mapboxKindKey] = options.mapboxKind.name
            it[satelliteRoadsKey] = options.satelliteRoads
        }
    }

    /** The cached Mapbox token, or null; paired with when it was fetched. */
    fun mapboxTokenNow(): Pair<String, Long>? = runBlocking {
        context.dataStore.data.first().let { p ->
            val token = p[mapboxTokenKey] ?: return@let null
            token to (p[mapboxTokenDateKey] ?: 0L)
        }
    }

    suspend fun setMapboxToken(token: String, fetchedAt: Long) {
        context.dataStore.edit {
            it[mapboxTokenKey] = token
            it[mapboxTokenDateKey] = fetchedAt
        }
    }

    suspend fun clearMapboxToken() {
        context.dataStore.edit {
            it.remove(mapboxTokenKey)
            it.remove(mapboxTokenDateKey)
        }
    }

    /** Language of map labels: an ISO code or [MAP_LANGUAGE_NATIVE]. */
    val mapLanguage: Flow<String> =
        context.dataStore.data.map { it[mapLanguageKey] ?: MAP_LANGUAGE_NATIVE }

    /** [mapLanguage] read synchronously, for the style-load path. */
    fun mapLanguageNow(): String = runBlocking { mapLanguage.first() }

    suspend fun setUseMiles(value: Boolean) {
        context.dataStore.edit { it[useMilesKey] = value }
    }

    suspend fun setUseFeet(value: Boolean) {
        context.dataStore.edit { it[useFeetKey] = value }
    }

    suspend fun setMapLanguage(value: String) {
        context.dataStore.edit { it[mapLanguageKey] = value }
    }

    companion object {
        /** Map labels keep each place's local name. */
        const val MAP_LANGUAGE_NATIVE = "native"

        fun formatDistanceKm(km: Double, useMiles: Boolean): String =
            if (useMiles) "%.1f mi".format(km * 0.621371) else "%.1f km".format(km)

        fun formatElevationM(meters: Int, useFeet: Boolean): String =
            if (useFeet) "${(meters * 3.28084).toInt()} ft" else "$meters m"

        /** Format coordinates according to the chosen GPS format. */
        fun formatCoordinates(lat: Double, lon: Double, format: GpsFormat): String =
            when (format) {
                GpsFormat.DD -> "%.5f, %.5f".format(lat, lon)
                GpsFormat.DDMM -> "${ddToddmm(lat, true)}, ${ddToddmm(lon, false)}"
                GpsFormat.DDMMSS -> "${ddToddmmss(lat, true)}, ${ddToddmmss(lon, false)}"
            }

        private fun ddToddmm(decimal: Double, isLat: Boolean): String {
            val abs = Math.abs(decimal)
            val deg = abs.toInt()
            val min = (abs - deg) * 60
            val suffix = if (isLat) { if (decimal >= 0) "N" else "S" }
            else { if (decimal >= 0) "E" else "W" }
            return "%d°%06.3f'%s".format(deg, min, suffix)
        }

        private fun ddToddmmss(decimal: Double, isLat: Boolean): String {
            val abs = Math.abs(decimal)
            val deg = abs.toInt()
            val minFull = (abs - deg) * 60
            val min = minFull.toInt()
            val sec = (minFull - min) * 60
            val suffix = if (isLat) { if (decimal >= 0) "N" else "S" }
            else { if (decimal >= 0) "E" else "W" }
            return "%d°%02d'%04.1f\"%s".format(deg, min, sec, suffix)
        }
    }
}

/** GPS coordinate display formats. */
enum class GpsFormat(val label: String) {
    DD("DD (decimal)"),
    DDMM("DD°MM.MMM'"),
    DDMMSS("DD°MM'SS.S\""),
}
