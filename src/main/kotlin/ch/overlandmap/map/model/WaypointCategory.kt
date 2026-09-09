package ch.overlandmap.map.model

/**
 * The categories of waypoint the itinerary map's style picker can show or hide.
 * A port of the iOS waypoint filter: five toggles governing which waypoints
 * *not* belonging to the open itinerary are drawn on the map.
 *
 * [ITINERARY] is the open itinerary's own waypoints (always the current
 * itinerary's points of interest); the other four select points of interest
 * from the rest of the pack by their flags. [VIEWPOINT] is a broad "photo/POI"
 * bucket — viewpoints, historical and religious sites, and hot springs —
 * matching the iOS `isPhotoFlag`.
 */
enum class WaypointCategory(val key: String) {
    ITINERARY("itinerary"),
    MOUNTAIN_PASS("pass"),
    FUEL("fuel"),
    POLICE_CHECKPOINT("checkpoint"),
    VIEWPOINT("photo");

    companion object {
        /** All categories are shown by default. */
        val DEFAULT: Set<WaypointCategory> = entries.toSet()

        /**
         * The category a non-itinerary waypoint belongs to, or null when it is
         * not one of the filterable points of interest. Mirrors the iOS
         * `buildFilteredMarkers` bucketing, checked in the same priority order
         * as the marker-icon selection.
         */
        fun of(o: WaypointType): WaypointCategory? = when {
            o.isPoliceCheckpoint -> POLICE_CHECKPOINT
            o.isMountainPass -> MOUNTAIN_PASS
            o.hasFuel -> FUEL
            o.isViewpoint || o.isHistoricalSite || o.isReligiousSite || o.isHotSpring -> VIEWPOINT
            else -> null
        }
    }
}
