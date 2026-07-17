package ch.overlandmap.map.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    val useMiles: Flow<Boolean> = context.dataStore.data.map { it[useMilesKey] ?: false }
    val useFeet: Flow<Boolean> = context.dataStore.data.map { it[useFeetKey] ?: false }

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
    }
}
