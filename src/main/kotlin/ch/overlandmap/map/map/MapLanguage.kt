package ch.overlandmap.map.map

import android.util.Log
import ch.overlandmap.map.data.UserPreferences
import com.google.gson.JsonArray
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.types.Formatted

/**
 * Applies the map-language preference to a freshly loaded style: every symbol
 * layer whose `text-field` is the plain `{name}` token gets it replaced by a
 * coalesce over the translated name properties. `native` keeps the style as
 * authored (each place labelled in its local language).
 */
fun applyMapLanguage(style: Style, language: String) {
    if (language == UserPreferences.MAP_LANGUAGE_NATIVE) return
    val name: Expression = if (language == "en") {
        coalesce(get("name:en"), get("name"))
    } else {
        coalesce(get("name:$language"), get("name:en"), get("name"))
    }
    var replaced = 0
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        if (usesNameToken(layer)) {
            layer.setProperties(textField(name))
            replaced++
        }
    }
    Log.d("MapLanguage", "Labels in '$language' on $replaced layers")
}

private fun usesNameToken(layer: SymbolLayer): Boolean {
    // Read the raw value: the getter's generic type is a lie for layers whose
    // text-field is an expression (a gson JsonArray at runtime).
    val field: PropertyValue<*> = try {
        layer.textField
    } catch (_: Exception) {
        return false
    }
    return when (val raw = field.value) {
        is String -> raw == "{name}"
        is Formatted ->
            raw.formattedSections.size == 1 && raw.formattedSections[0].text == "{name}"
        // The style's `"text-field": "{name}"` comes back from the core as
        // this canonical format expression.
        is JsonArray -> raw.toString() == NAME_TOKEN_EXPRESSION
        else -> false
    }
}

private const val NAME_TOKEN_EXPRESSION = """["format",["to-string",["get","name"]],{}]"""
