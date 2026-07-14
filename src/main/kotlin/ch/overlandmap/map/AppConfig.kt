package ch.overlandmap.map

object AppConfig {
    const val MAPBOX_ACCESS_TOKEN =
        "REDACTED_MAPBOX_TOKEN"

    private const val CLOUDFLARE_IMAGE_BASE_URL = "https://imagedelivery.net/kLFoGYyvldGdieKRrzQmRQ"

    /** Remote photo URL for a Cloudflare Images photo ID. */
    fun photoUrl(photoId: String) = "$CLOUDFLARE_IMAGE_BASE_URL/$photoId/public"

    /**
     * Style used when online. MapLibre cannot read Mapbox styles (mapbox:// tile
     * URLs need the proprietary SDK), so online browsing uses a free vector style;
     * downloaded MBTiles served by [ch.overlandmap.map.map.LocalTileServer] take
     * over when offline.
     */
    const val ONLINE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** Style of the shop's global map (same one the Flutter app uses). */
    const val GLOBAL_STYLE_URL = "https://overlanding.io/global_en.json"

    /** Languages offered in Settings, in display order. */
    val SUPPORTED_LANGUAGES = listOf(
        "en" to "English",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "es" to "Español",
        "pt" to "Português",
        "nl" to "Nederlands",
        "ru" to "Русский",
    )
}
