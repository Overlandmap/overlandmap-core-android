package ch.overlandmap.map.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource

/** Tile source of every itinerary track, same as the global style's. */
private const val TRACKS_TILES_URL = "https://overlanding.io/tiles/GOA/{z}/{x}/{y}.pbf"

/**
 * Makes sure the style has the `tracks` source and blue line layer that the
 * online global style ships built in: the offline style renders the local
 * planet base map only, so the map composables that show itineraries call
 * this before touching the layer. No-op when the style already has it.
 */
fun ensureTracksLayer(style: Style) {
    if (style.getLayer("tracks") != null) return
    if (style.getSource("tracks") == null) {
        val tileSet = TileSet("2.1.0", TRACKS_TILES_URL).apply {
            minZoom = 0f
            maxZoom = 7f
        }
        style.addSource(VectorSource("tracks", tileSet))
    }
    // Same paint as the global style's tracks layer.
    style.addLayer(
        LineLayer("tracks", "tracks").apply {
            sourceLayer = "tracks"
            setProperties(
                lineColor("#0000FF"),
                lineOpacity(0.8f),
                lineWidth(
                    interpolate(
                        linear(), zoom(),
                        stop(0, 0.5f),
                        stop(5, 2f),
                        stop(8, 8f),
                    )
                ),
            )
        }
    )
}
