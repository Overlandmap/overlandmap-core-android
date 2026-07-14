package ch.overlandmap.map.ui.world

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.CountryBorder
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val BORDERS_LAYER = "world-borders-layer"
private const val POSTS_LAYER = "world-posts-layer"

/**
 * The world map: borders drawn from their GeoJSON geometry and border posts
 * as dots, both colored by open state. Taps resolve to the border/post ID.
 */
@Composable
fun WorldMap(
    borders: List<CountryBorder>,
    borderPosts: List<BorderPost>,
    onBorderTapped: (String) -> Unit,
    onBorderPostTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    MapLibreMapView(
        modifier = modifier,
        onStyleLoaded = { map, style ->
            style.addSource(GeoJsonSource("world-borders", borderCollection(borders)))
            style.addLayer(
                LineLayer(BORDERS_LAYER, "world-borders").withProperties(
                    lineColor(toColor(get("color"))),
                    lineWidth(3f),
                )
            )
            style.addSource(GeoJsonSource("world-posts", postCollection(borderPosts)))
            style.addLayer(
                CircleLayer(POSTS_LAYER, "world-posts").withProperties(
                    circleColor(toColor(get("color"))),
                    circleRadius(6f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(1.5f),
                )
            )
            installTapHandler(map, onBorderTapped, onBorderPostTapped)
        },
    )
}

private fun borderCollection(borders: List<CountryBorder>): FeatureCollection {
    val features = borders.mapNotNull { border ->
        val geometry = border.geomString ?: return@mapNotNull null
        try {
            Feature.fromJson(
                """{"type":"Feature","properties":{},"geometry":$geometry}"""
            ).also {
                it.addStringProperty("id", border.documentId)
                it.addStringProperty("color", border.openState.colorHex)
            }
        } catch (_: Exception) {
            null
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun postCollection(posts: List<BorderPost>): FeatureCollection {
    val features = posts.mapNotNull { post ->
        val lat = post.lat ?: return@mapNotNull null
        val lon = post.lon ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(lon, lat)).also {
            it.addStringProperty("id", post.documentId)
            it.addStringProperty("color", post.openState.colorHex)
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun installTapHandler(
    map: MapLibreMap,
    onBorderTapped: (String) -> Unit,
    onBorderPostTapped: (String) -> Unit,
) {
    map.addOnMapClickListener { latLng ->
        val screenPoint = map.projection.toScreenLocation(latLng)
        // Posts are small; give them priority and a generous hit box.
        val posts = map.queryRenderedFeatures(
            android.graphics.RectF(
                screenPoint.x - 24, screenPoint.y - 24,
                screenPoint.x + 24, screenPoint.y + 24,
            ),
            POSTS_LAYER,
        )
        posts.firstOrNull()?.getStringProperty("id")?.let {
            onBorderPostTapped(it)
            return@addOnMapClickListener true
        }
        val borders = map.queryRenderedFeatures(
            android.graphics.RectF(
                screenPoint.x - 12, screenPoint.y - 12,
                screenPoint.x + 12, screenPoint.y + 12,
            ),
            BORDERS_LAYER,
        )
        borders.firstOrNull()?.getStringProperty("id")?.let {
            onBorderTapped(it)
            return@addOnMapClickListener true
        }
        false
    }
}
