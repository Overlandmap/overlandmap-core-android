package ch.overlandmap.map.data

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Supplies the Mapbox access token that MapLibre needs to resolve `mapbox://`
 * styles. The token comes from the `getMapboxToken` Firebase function; it is
 * cached in [UserPreferences] with the date it was fetched, reused on later
 * runs, and refreshed once it is older than [MAX_AGE_MS].
 */
class MapboxTokenManager(
    private val preferences: UserPreferences,
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(),
) {

    /**
     * A valid token, fetching a fresh one when the cache is empty or stale.
     * Returns the cached token even if a refresh fails, and null only when no
     * token is available at all.
     */
    suspend fun validToken(): String? {
        val cached = preferences.mapboxTokenNow()
        if (cached != null && System.currentTimeMillis() - cached.second < MAX_AGE_MS) {
            return cached.first
        }
        val fetched = fetch()
        if (fetched != null) {
            preferences.setMapboxToken(fetched, System.currentTimeMillis())
            return fetched
        }
        // The refresh failed; fall back to the (possibly stale) cached token.
        return cached?.first
    }

    /** The cached token read synchronously, without a network refresh. */
    fun cachedTokenNow(): String? = preferences.mapboxTokenNow()?.first

    private suspend fun fetch(): String? = try {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable("getMapboxToken").call().await().getData()
            as? Map<String, Any?>
        (data?.get("mapboxToken") as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "getMapboxToken failed", e)
        null
    }

    private companion object {
        const val TAG = "MapboxTokenManager"
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000 // ~3 months
    }
}
