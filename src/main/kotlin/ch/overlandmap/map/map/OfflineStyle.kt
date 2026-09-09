package ch.overlandmap.map.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rewrites the bundled offline style ([MapStyles]'s `detailed.json` /
 * `simplified.json`) so the downloaded per-pack tiles are actually rendered.
 * As shipped, every content layer uses only the world `planet` source
 * (zoom 0–6); the `detail` (7–13), `contour` and DEM tiles are downloaded but
 * never referenced.
 *
 * Ported from the iOS `StyleBuilder.duplicateStyleLayers` (which in turn ports
 * the Flutter app's `MapStyle.duplicateStyleLayers`). The bundled styles define
 * content layers only against the `planet` source. This creates a parallel set
 * against the higher-zoom `detail` source and inserts a solid country fill
 * between them so detail tiles mask the coarser planet roads where they exist,
 * and the overzoomed planet shows through where they don't.
 *
 * The relief pair — a DEM `hillshade` layer (from `demSource`) and an
 * `elevation_color` raster (from `demColorSource`) — is spliced directly above
 * the `landuse_school` fill in *both* the planet and detail sections, so it
 * underlays roads, tracks and labels but overlays the base fills. The detail
 * copy is essential: from zoom 7 the opaque `country` fill masks everything
 * beneath it, so relief drawn only in the planet section (below `country`)
 * would be hidden. Its visibility is gated by the elevation/hillshade toggle.
 *
 * Contour lines from the `contour` vector source sit at the very top of the
 * detail section, above all roads and labels; gated by the contour toggle.
 */
object OfflineStyle {

    /** Authored port; [LocalTileServer] rewrites it to the bound one. */
    private const val BASE_URL = "http://localhost:8000"

    /** The bundled borders layer Ride2Ladakh hides to reduce clutter. */
    private const val BORDERS_LAYER_ID = "borders country"

    /** Anchor above which the relief pair is spliced in each source section. */
    private const val SCHOOL_ANCHOR = "landuse_school"

    fun transform(
        styleJson: String,
        showHillshade: Boolean,
        showContour: Boolean,
        removeBorders: Boolean,
        demMaxZoom: Int? = null,
    ): String {
        val root = JSONObject(styleJson)

        // Cap the DEM sources' maxzoom to what is actually on disk. The bundled
        // style assumes a per-pack DEM (maxzoom 11); when only the global
        // planet-dem (z0–6) is present, lowering maxzoom lets the map SDK
        // overzoom those tiles for z7–11 instead of requesting tiles that 404.
        if (demMaxZoom != null) {
            root.optJSONObject("sources")?.let { sources ->
                for (name in listOf("demSource", "demColorSource")) {
                    sources.optJSONObject(name)?.let { src ->
                        val declared = src.optInt("maxzoom", demMaxZoom)
                        src.put("maxzoom", minOf(declared, demMaxZoom))
                    }
                }
            }
        }

        val layers = root.getJSONArray("layers")

        var background: JSONObject? = null
        val planet = ArrayList<JSONObject>()
        val relief = ArrayList<JSONObject>()
        val contour = ArrayList<JSONObject>()

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val type = layer.optString("type")
            val source = layer.optString("source")
            // Drop the bundled borders layer (Ride2Ladakh) before classifying.
            if (removeBorders && layer.optString("id") == BORDERS_LAYER_ID) continue
            when {
                type == "background" -> background = layer
                // The DEM relief pair (hillshade + elevation colour). Both read
                // from the DEM sources and are toggled together by the
                // elevation/hillshade switch.
                type == "hillshade" || source == "demSource" || source == "demColorSource" ->
                    relief.add(setVisibility(layer, showHillshade))
                source == "planet" -> planet.add(layer)
                else -> contour.add(layer)
            }
        }

        // Detail layers: duplicate every planet layer with its source changed
        // to "detail" and a unique id.
        val detail = ArrayList<JSONObject>()
        for (layer in planet) {
            val copy = JSONObject(layer.toString())
            copy.put("id", layer.getString("id") + "_detail")
            copy.put("source", "detail")
            detail.add(copy)
        }

        // Country fill: masks planet roads where detail tiles are present.
        val country = JSONObject()
            .put("id", "country")
            .put("type", "fill")
            .put("source", "detail")
            .put("source-layer", "earth")
            .put("paint", JSONObject().put("fill-color", "rgb(239,239,239)"))

        // A detail-side copy of the relief pair with unique ids. Same DEM
        // sources (which cover every zoom) — only the ids must differ.
        val detailRelief = ArrayList<JSONObject>()
        for (layer in relief) {
            val copy = JSONObject(layer.toString())
            copy.put("id", layer.getString("id") + "_detail")
            detailRelief.add(copy)
        }

        // Reassemble: background, planet (relief above landuse_school), country,
        // contour?, detail (relief above landuse_school_detail), contour overlay.
        val out = JSONArray()
        background?.let(out::put)
        splice(relief, SCHOOL_ANCHOR, planet).forEach(out::put)
        out.put(country)
        if (showContour) contour.forEach(out::put)
        splice(detailRelief, SCHOOL_ANCHOR + "_detail", detail).forEach(out::put)
        if (showContour) contourOverlayLayers().forEach(out::put)

        root.put("layers", out)
        return root.toString()
    }

    /**
     * The three contour overlay layers drawn from the `contour` vector source:
     * `c100` fine lines (no labels), `c500` coarse lines, and the `c500`
     * elevation labels read from the `h500` property. Appended on top of the
     * detail section so they overlay everything.
     */
    private fun contourOverlayLayers(): List<JSONObject> {
        val c100 = JSONObject()
            .put("id", "contour_100")
            .put("type", "line")
            .put("source", "contour")
            .put("source-layer", "c100")
            // Fine 100 m lines only from zoom 11; below that they crowd the map.
            .put("minzoom", 11)
            .put(
                "paint",
                JSONObject()
                    .put("line-color", "blue")
                    .put("line-width", 0.5)
                    .put("line-opacity", 0.3),
            )
        val c500 = JSONObject()
            .put("id", "contour_500")
            .put("type", "line")
            .put("source", "contour")
            .put("source-layer", "c500")
            // Coarse 500 m lines from zoom 9; they also carry the labels.
            .put("minzoom", 9)
            .put(
                "paint",
                JSONObject()
                    .put("line-color", "red")
                    .put("line-width", 0.8)
                    .put("line-opacity", 0.3),
            )
        // Build the label from an expression: `h500` is numeric, so `concat` +
        // `to-string` coerces it explicitly.
        val labelText = JSONArray()
            .put("concat")
            .put(JSONArray().put("to-string").put(JSONArray().put("get").put("h500")))
            .put(" m")
        val label = JSONObject()
            .put("id", "contour_500_label")
            .put("type", "symbol")
            .put("source", "contour")
            .put("source-layer", "c500")
            .put("minzoom", 11)
            .put(
                "layout",
                JSONObject()
                    .put("symbol-placement", "line")
                    .put("text-field", labelText)
                    .put("text-font", JSONArray().put("Roboto Regular"))
                    .put("text-size", 10)
                    .put("symbol-spacing", 300),
            )
            .put(
                "paint",
                JSONObject()
                    .put("text-color", "red")
                    .put("text-halo-color", "white")
                    .put("text-halo-width", 1),
            )
        return listOf(c100, c500, label)
    }

    /**
     * Inserts [inserts] directly after the layer whose id is [anchorId] within
     * [layers]. If no such layer exists, the inserts are appended so the relief
     * is never silently dropped.
     */
    private fun splice(
        inserts: List<JSONObject>,
        anchorId: String,
        layers: List<JSONObject>,
    ): List<JSONObject> {
        val hasAnchor = layers.any { it.optString("id") == anchorId }
        if (!hasAnchor) return layers + inserts
        val result = ArrayList<JSONObject>()
        for (layer in layers) {
            result.add(layer)
            if (layer.optString("id") == anchorId) result.addAll(inserts)
        }
        return result
    }

    /** Returns [layer] with its `layout.visibility` set to match [visible]. */
    private fun setVisibility(layer: JSONObject, visible: Boolean): JSONObject {
        val copy = JSONObject(layer.toString())
        val layout = copy.optJSONObject("layout") ?: JSONObject()
        layout.put("visibility", if (visible) "visible" else "none")
        copy.put("layout", layout)
        return copy
    }

    /**
     * Rewrites label `text-field`s to the map-language [lang] — a `name:<lang>`
     * field with fallbacks — mirroring the iOS `StyleBuilder.applyLanguage`.
     * "native" keeps each feature's local name.
     */
    fun translateLabels(styleJson: String, lang: String): String {
        val root = JSONObject(styleJson)
        val layers = root.optJSONArray("layers") ?: return styleJson
        for (i in 0 until layers.length()) {
            val layout = layers.getJSONObject(i).optJSONObject("layout") ?: continue
            when (val textField = layout.opt("text-field")) {
                "{name}" -> layout.put("text-field", languageExpression(lang))
                is JSONArray -> for (j in 0 until textField.length()) {
                    val element = textField.opt(j)
                    if (element is JSONArray && isGetName(element)) {
                        textField.put(j, languageExpression(lang))
                    }
                }
            }
        }
        return root.toString()
    }

    private fun isGetName(expr: JSONArray): Boolean =
        expr.length() == 2 && expr.optString(0) == "get" && expr.optString(1) == "name"

    /** The `text-field` expression selecting the label for [lang], with fallbacks. */
    private fun languageExpression(lang: String): JSONArray = when (lang) {
        "native" -> get("name")
        "en" -> JSONArray().put("coalesce").put(get("name:en")).put(get("name"))
        else -> JSONArray().put("coalesce").put(get("name:$lang")).put(get("name:en")).put(get("name"))
    }

    private fun get(field: String): JSONArray = JSONArray().put("get").put(field)
}
