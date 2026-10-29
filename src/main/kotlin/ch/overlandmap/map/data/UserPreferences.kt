package ch.overlandmap.map.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.overlandmap.map.map.BaseMapStyle
import ch.overlandmap.map.map.MapStyleOptions
import ch.overlandmap.map.model.WaypointCategory
import ch.overlandmap.map.map.MapboxStyleKind
import ch.overlandmap.map.units.GpsFormat
import ch.overlandmap.map.units.UnitFormat
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
    private val waypointFilterKey = stringSetPreferencesKey("waypoint_filter")
    private val mapboxTokenKey = stringPreferencesKey("mapbox_token")
    private val mapboxTokenDateKey = longPreferencesKey("mapbox_token_date")
    private val lastRouteKey = stringPreferencesKey("last_route")
    // Itinerary screen state, restored only on a cold-start route restore.
    private val lastTabKey = intPreferencesKey("last_tab")
    private val lastStepKey = intPreferencesKey("last_step")
    private val lastZoomKey = doublePreferencesKey("last_zoom")
    private val lastLatKey = doublePreferencesKey("last_lat")
    private val lastLonKey = doublePreferencesKey("last_lon")
    private val debugShowZoomButtonsKey = booleanPreferencesKey("debug_show_zoom_buttons")
    private val debugShowZoomKey = booleanPreferencesKey("debug_show_zoom")
    private val debugShowPhotoSourceKey = booleanPreferencesKey("debug_show_photo_source")
    private val lastCheckInsFetchKey = longPreferencesKey("last_check_ins_fetch")
    private val gpsFormatKey = stringPreferencesKey("gps_format")
    private val useFahrenheitKey = booleanPreferencesKey("use_fahrenheit")
    private val fontSizeKey = stringPreferencesKey("font_size")
    private val onboardingShownKey = booleanPreferencesKey("onboarding_shown")

    // Until the user chooses, each unit axis follows the device region (iOS parity).
    val useMiles: Flow<Boolean> =
        context.dataStore.data.map { it[useMilesKey] ?: !deviceDefault().distanceMetric }
    val useFeet: Flow<Boolean> =
        context.dataStore.data.map { it[useFeetKey] ?: !deviceDefault().heightMetric }

    /** The four independent unit axes combined, as consumed by the `UnitFormatter`. */
    val unitFormat: Flow<UnitFormat> = context.dataStore.data.map { p ->
        val fallback = deviceDefault()
        UnitFormat(
            heightMetric = !(p[useFeetKey] ?: !fallback.heightMetric),
            distanceMetric = !(p[useMilesKey] ?: !fallback.distanceMetric),
            temperatureCelsius = !(p[useFahrenheitKey] ?: !fallback.temperatureCelsius),
            gpsFormat = p[gpsFormatKey]?.let { runCatching { GpsFormat.valueOf(it) }.getOrNull() }
                ?: GpsFormat.DD,
        )
    }

    /** [unitFormat] read synchronously, to seed UI state before the flow emits. */
    fun unitFormatNow(): UnitFormat = runBlocking { unitFormat.first() }

    /** Debug: show map zoom in/out buttons (default hidden). */
    val debugShowZoomButtons: Flow<Boolean> = context.dataStore.data.map { it[debugShowZoomButtonsKey] ?: false }

    suspend fun setDebugShowZoomButtons(value: Boolean) {
        context.dataStore.edit { it[debugShowZoomButtonsKey] = value }
    }

    /** Debug: overlay the current map zoom on the itinerary map (default off). */
    val debugShowZoom: Flow<Boolean> = context.dataStore.data.map { it[debugShowZoomKey] ?: false }

    fun debugShowZoomNow(): Boolean = runBlocking { debugShowZoom.first() }

    suspend fun setDebugShowZoom(value: Boolean) {
        context.dataStore.edit { it[debugShowZoomKey] = value }
    }

    /** Debug: overlay "hi-res online photo"/"offline photo" in the viewer (default off). */
    val debugShowPhotoSource: Flow<Boolean> =
        context.dataStore.data.map { it[debugShowPhotoSourceKey] ?: false }

    fun debugShowPhotoSourceNow(): Boolean = runBlocking { debugShowPhotoSource.first() }

    suspend fun setDebugShowPhotoSource(value: Boolean) {
        context.dataStore.edit { it[debugShowPhotoSourceKey] = value }
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

    /** Temperature unit preference: Fahrenheit when true, Celsius when false. */
    val useFahrenheit: Flow<Boolean> =
        context.dataStore.data.map { it[useFahrenheitKey] ?: !deviceDefault().temperatureCelsius }

    suspend fun setUseFahrenheit(value: Boolean) {
        context.dataStore.edit { it[useFahrenheitKey] = value }
    }

    /** Text-size preference for displayed-object content (default [FontSize.MEDIUM]). */
    val fontSize: Flow<FontSize> = context.dataStore.data.map { p ->
        p[fontSizeKey]?.let { runCatching { FontSize.valueOf(it) }.getOrNull() } ?: FontSize.MEDIUM
    }

    /** [fontSize] read synchronously, to seed the theme before the flow emits. */
    fun fontSizeNow(): FontSize = runBlocking { fontSize.first() }

    suspend fun setFontSize(value: FontSize) {
        context.dataStore.edit { it[fontSizeKey] = value.name }
    }

    /** Whether the help/tutorial onboarding has been shown (first-launch gate). */
    fun onboardingShownNow(): Boolean =
        runBlocking { context.dataStore.data.first()[onboardingShownKey] ?: false }

    suspend fun setOnboardingShown(value: Boolean) {
        context.dataStore.edit { it[onboardingShownKey] = value }
    }

    /** The route of the screen last shown, restored after the app is killed. */
    /** Website and folder last viewed in the imported-track library, saved together. */
    fun importLocationNow(): Pair<String?, String?> = runBlocking {
        val values = context.dataStore.data.first()
        values[stringPreferencesKey("import_website_id")] to values[stringPreferencesKey("import_folder_id")]
    }

    suspend fun setImportLocation(websiteId: String?, folderId: String?) {
        context.dataStore.edit { values ->
            val websiteKey = stringPreferencesKey("import_website_id")
            val folderKey = stringPreferencesKey("import_folder_id")
            if (websiteId == null) values.remove(websiteKey) else values[websiteKey] = websiteId
            if (folderId == null) values.remove(folderKey) else values[folderKey] = folderId
        }
    }

    fun lastRouteNow(): String? = runBlocking { context.dataStore.data.first()[lastRouteKey] }

    suspend fun setLastRoute(route: String) {
        context.dataStore.edit { it[lastRouteKey] = route }
    }

    /** Clears the saved route so the next launch starts on the home screen. */
    fun clearLastRoute() {
        runBlocking { context.dataStore.edit { it.remove(lastRouteKey) } }
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
        contour = p[offlineContourKey] ?: false,
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

    /**
     * Which waypoint categories are shown on the itinerary map. Defaults to all
     * on. Stored as the set of enabled [WaypointCategory.key]s.
     */
    val waypointFilter: Flow<Set<WaypointCategory>> =
        context.dataStore.data.map(::readWaypointFilter)

    fun waypointFilterNow(): Set<WaypointCategory> = runBlocking { waypointFilter.first() }

    private fun readWaypointFilter(p: Preferences): Set<WaypointCategory> {
        val keys = p[waypointFilterKey] ?: return WaypointCategory.DEFAULT
        return keys.mapNotNull { key -> WaypointCategory.entries.firstOrNull { it.key == key } }
            .toSet()
    }

    suspend fun setWaypointFilter(categories: Set<WaypointCategory>) {
        context.dataStore.edit { it[waypointFilterKey] = categories.map { c -> c.key }.toSet() }
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

    // ── Split pane ratios (per screen key) ──────────────────────────────────

    /** Reads the persisted split fraction for [key] synchronously; null if never saved. */
    fun splitFractionNow(key: String): Float? = runBlocking {
        context.dataStore.data.first()[floatPreferencesKey("split_$key")]
    }

    suspend fun setSplitFraction(key: String, fraction: Float) {
        context.dataStore.edit { it[floatPreferencesKey("split_$key")] = fraction }
    }

    // ── Saved navigation (so a killed app can resume) ───────────────────────

    fun savedNavigationNow(): String? = runBlocking {
        context.dataStore.data.first()[stringPreferencesKey("saved_navigation")]
    }

    suspend fun setSavedNavigation(json: String?) {
        context.dataStore.edit {
            val key = stringPreferencesKey("saved_navigation")
            if (json == null) it.remove(key) else it[key] = json
        }
    }

    // ── Overlanding tab state (camera, selection, tab choices, legend) ──────

    /** A free-form string stored under `overlanding.<name>`; null if never saved. */
    fun overlandingStringNow(name: String): String? = runBlocking {
        context.dataStore.data.first()[stringPreferencesKey("overlanding.$name")]
    }

    suspend fun setOverlandingString(name: String, value: String?) {
        context.dataStore.edit {
            val key = stringPreferencesKey("overlanding.$name")
            if (value == null) it.remove(key) else it[key] = value
        }
    }

    fun overlandingBooleanNow(name: String, default: Boolean): Boolean = runBlocking {
        context.dataStore.data.first()[booleanPreferencesKey("overlanding.$name")] ?: default
    }

    suspend fun setOverlandingBoolean(name: String, value: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("overlanding.$name")] = value }
    }

    companion object {
        /** Map labels keep each place's local name. */
        const val MAP_LANGUAGE_NATIVE = "native"

        /**
         * The unit format implied by the device's *system* region, used until the user
         * chooses (as on iOS). The system locale is read rather than the app locale, so a
         * US user running the app in French still gets imperial by default.
         */
        private fun deviceDefault(): UnitFormat = UnitFormat.deviceDefault(
            android.content.res.Resources.getSystem().configuration.locales.get(0)?.country,
        )
    }
}

/**
 * Text-size preference for displayed-object content. [scale] multiplies the
 * font size of content text (names, descriptions, road conditions, highlights,
 * …); fixed UI chrome such as buttons and labels is not scaled. Titles inside
 * formatted text are derived from the content size, so they grow with it.
 */
enum class FontSize(val scale: Float) {
    SMALL(0.85f),
    MEDIUM(1f),
    LARGE(1.2f),
    VERY_LARGE(1.45f),
}
