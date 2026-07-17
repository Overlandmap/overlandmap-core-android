package ch.overlandmap.map.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rewrites the bundled offline style ([MapStyles]'s `detailed.json`) so the
 * downloaded per-pack tiles are actually rendered. As shipped, every layer
 * uses only the world `planet` source (zoom 0–6); the `detail` (7–13),
 * `contour` and offline hillshade tiles are downloaded but never shown.
 *
 * Ports the Flutter app's `duplicateStyleLayers`: the planet layers stay, then
 * an opaque "earth" mask fill on the `detail` source hides the planet exactly
 * where a detail tile exists, then the detail-sourced copies of the layers are
 * drawn on top. Where no detail tile exists the mask draws nothing, so the
 * overzoomed planet shows through — "the most detailed tile available".
 *
 * Layer order (per the app's design): planet layers, earth mask, detail fills,
 * hillshade, contours, then detail lines and labels — so hillshade and contours
 * sit above the offline map's fills but below its lines and text.
 */
object OfflineStyle {

    /** Authored port; [LocalTileServer] rewrites it to the bound one. */
    private const val BASE_URL = "http://localhost:8000"

    private val FILL_TYPES = setOf("fill", "fill-extrusion")

    fun transform(
        styleJson: String,
        showHillshade: Boolean,
        showContour: Boolean,
        hasOfflineHillshade: Boolean,
        hasContour: Boolean,
    ): String {
        val root = JSONObject(styleJson)
        val sources = root.getJSONObject("sources")
        val layers = root.getJSONArray("layers")

        var background: JSONObject? = null
        var onlineHillshade: JSONObject? = null
        val planet = ArrayList<JSONObject>()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            when (layer.optString("type")) {
                "background" -> background = layer
                "hillshade", "raster" -> onlineHillshade = layer
                else -> if (layer.optString("source") == "planet") planet.add(layer)
            }
        }

        // The same layers re-pointed at the detail source, split so hillshade
        // and contours can be inserted between the fills and the lines/labels.
        val detailFills = ArrayList<JSONObject>()
        val detailLines = ArrayList<JSONObject>()
        for (layer in planet) {
            val copy = JSONObject(layer.toString())
            copy.put("id", layer.getString("id") + "_detail")
            copy.put("source", "detail")
            if (layer.optString("type") in FILL_TYPES) detailFills.add(copy) else detailLines.add(copy)
        }

        val earthMask = JSONObject()
            .put("id", "detail_earth")
            .put("type", "fill")
            .put("source", "detail")
            .put("source-layer", "earth")
            .put("paint", JSONObject().put("fill-color", "rgb(239,239,239)"))

        val hillshade = ArrayList<JSONObject>()
        if (showHillshade) {
            when {
                // Pre-rendered raster hillshade downloaded with the pack.
                hasOfflineHillshade -> {
                    sources.put(
                        "hillshadeRaster",
                        JSONObject()
                            .put("type", "raster")
                            .put(
                                "tiles",
                                JSONArray().put("$BASE_URL/tiles/hillshade/{z}/{x}/{y}.webp"),
                            )
                            .put("tileSize", 256)
                            .put("minzoom", 7)
                            // Pre-rendered hillshade stops at z11; overzoom above.
                            .put("maxzoom", 11),
                    )
                    hillshade.add(
                        JSONObject()
                            .put("id", "hillshade")
                            .put("type", "raster")
                            .put("source", "hillshadeRaster")
                            .put("paint", JSONObject().put("raster-opacity", 0.5)),
                    )
                }
                // Fall back to the online elevation-derived hillshade.
                onlineHillshade != null -> hillshade.add(onlineHillshade)
            }
        }

        val contours = ArrayList<JSONObject>()
        if (showContour && hasContour) {
            contours.add(contourLine("contour_minor", "c100", "rgba(120,90,60,0.35)", 0.7, 12))
            contours.add(contourLine("contour_index", "c500", "rgba(120,90,60,0.6)", 1.1, 11))
            contours.add(contourLabel())
        }

        val out = JSONArray()
        background?.let(out::put)
        planet.forEach(out::put)
        out.put(earthMask)
        detailFills.forEach(out::put)
        hillshade.forEach(out::put)
        contours.forEach(out::put)
        detailLines.forEach(out::put)
        root.put("layers", out)
        return root.toString()
    }

    private fun contourLine(
        id: String,
        sourceLayer: String,
        color: String,
        width: Double,
        minZoom: Int,
    ) = JSONObject()
        .put("id", id)
        .put("type", "line")
        .put("source", "contour")
        .put("source-layer", sourceLayer)
        .put("minzoom", minZoom)
        .put("paint", JSONObject().put("line-color", color).put("line-width", width))

    /** Elevation label for the 500 m index contours, e.g. "4500 m". */
    private fun contourLabel(): JSONObject {
        val elevation = JSONArray().put("get").put("h500")
        val text = JSONArray()
            .put("concat")
            .put(JSONArray().put("to-string").put(elevation))
            .put(" m")
        return JSONObject()
            .put("id", "contour_index_label")
            .put("type", "symbol")
            .put("source", "contour")
            .put("source-layer", "c500")
            .put("minzoom", 13)
            .put(
                "layout",
                JSONObject()
                    .put("symbol-placement", "line")
                    .put("text-field", text)
                    .put("text-size", 10)
                    .put("text-font", JSONArray().put("Roboto Regular")),
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "rgba(90,60,40,0.9)")
                    .put("text-halo-color", "#ffffff")
                    .put("text-halo-width", 1),
            )
    }
}
