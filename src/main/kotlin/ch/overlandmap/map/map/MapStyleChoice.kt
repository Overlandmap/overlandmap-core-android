package ch.overlandmap.map.map

/** The base map styles offered in the itinerary map's style menu. */
enum class BaseMapStyle {
    /** Offline vector map, the light `simplified.json` style. */
    OFFLINE_LIGHT,

    /** Offline vector map, the detailed `detailed.json` style. */
    OFFLINE_DETAILED,

    /** A Mapbox vector style (kind chosen in [MapStyleOptions.mapboxKind]). */
    MAPBOX,

    /** Mapbox satellite imagery, with or without roads. */
    SATELLITE,
    ;

    val isOffline: Boolean get() = this == OFFLINE_LIGHT || this == OFFLINE_DETAILED
}

/** The selectable Mapbox vector styles. */
enum class MapboxStyleKind(val styleUrl: String, val displayName: String) {
    STREETS("mapbox://styles/mapbox/streets-v12", "Streets"),
    OUTDOORS("mapbox://styles/mapbox/outdoors-v12", "Outdoors"),
    LIGHT("mapbox://styles/mapbox/light-v11", "Light"),
    DARK("mapbox://styles/mapbox/dark-v11", "Dark"),
    NAVIGATION_DAY("mapbox://styles/mapbox/navigation-day-v1", "Navigation (day)"),
    NAVIGATION_NIGHT("mapbox://styles/mapbox/navigation-night-v1", "Navigation (night)"),
}

/**
 * The user's map style selection and its per-style customization. The offline
 * styles carry hillshade/contour toggles; the Mapbox style carries its kind;
 * satellite carries a roads toggle.
 */
data class MapStyleOptions(
    val base: BaseMapStyle = BaseMapStyle.OFFLINE_DETAILED,
    val hillshade: Boolean = true,
    val contour: Boolean = true,
    val mapboxKind: MapboxStyleKind = MapboxStyleKind.STREETS,
    val satelliteRoads: Boolean = true,
) {
    /** True when the current base map is a Mapbox (mapbox://) style. */
    val isMapbox: Boolean get() = !base.isOffline

    companion object {
        // Classic satellite basemaps (the new "standard-satellite" isn't
        // renderable by MapLibre; satellite-streets is the roads equivalent).
        const val SATELLITE_WITH_ROADS = "mapbox://styles/mapbox/satellite-streets-v12"
        const val SATELLITE_NO_ROADS = "mapbox://styles/mapbox/satellite-v9"
    }
}
