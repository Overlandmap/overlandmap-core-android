package ch.overlandmap.map.map

import com.mapbox.common.MapboxOptions

/**
 * One-time setup of the Mapbox Maps SDK, used only by the itinerary screen (the
 * rest of the app renders with MapLibre). The public `pk.…` access token comes
 * from [ch.overlandmap.map.data.MapboxTokenManager] (fetched/cached at startup);
 * the Mapbox SDK reads it from [MapboxOptions.accessToken].
 */
object MapboxInit {

    @Volatile
    private var initialized = false

    /** Applies [token] to the Mapbox SDK once; no-op until a token is known. */
    @Synchronized
    fun ensure(token: String?) {
        if (initialized || token.isNullOrEmpty()) return
        MapboxOptions.accessToken = token
        initialized = true
    }
}
