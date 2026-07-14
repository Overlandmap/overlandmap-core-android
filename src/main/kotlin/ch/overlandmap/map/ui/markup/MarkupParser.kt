package ch.overlandmap.map.ui.markup

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parser for the markup used by descriptions and captions (port of
 * `components/formatted_text.dart`).
 *
 * Hyperlinks — `[shown](action)` where the action selects the target:
 * - `[waypoint](waypoint)`            waypoint by name (anything not matching below)
 * - `[text](http://…)`                web link
 * - `[..](::T2)`                      itinerary of the same pack
 * - `[..](:Tajikistan:T2)`            itinerary of another pack
 * - `[..](::T2:5)` / `[..](:::5)`     itinerary step
 * - `[..](? sidebarName)`             sidebar article
 * - `[..](! track)`                   track (TODO, like the Flutter app)
 * - `[..](#geohash)`                  point by geohash
 * - `[..](@objectId)`                 world entity (country, border, border post, zone)
 *
 * Inline formatting:
 * - `{^1230}` altitude, `{->km 123}` / `{->m 123}` distances, `{km}` unit
 *   name, `{°C 34}` temperature — converted to the user's units
 * - `{?term}` glossary term (TODO: glossary link; shows the term)
 * - `` `b`text` `` bold, `` `i`text` `` italic, `` `u`text` `` underline
 * - `::Title::` at the start of a line: section title (sidebars)
 */
object Markup {

    // Android's ICU regex engine (unlike the JVM's) rejects unescaped
    // literal `]` and `}`, so they are all escaped here.
    private val hyperlink = Regex("""\[([^\]]*)\]\(([^)]*)\)""")
    private val styled = Regex("""`([biu])`([^`]+)`""")
    private val title = Regex("""^::(.+)::""")
    private val itineraryAction = Regex("""^:(\w+)?:(\w+)?:?(-?\d+)?""")

    private val altitude = Regex("""\{\^ ?(\d+)\}""")
    private val distanceKm = Regex("""\{->km (\d+)\}""")
    private val distanceM = Regex("""\{->m (\d+)\}""")
    private val kmUnit = Regex("""\{km\}""")
    private val temperature = Regex("""\{°C ([\d\-\w]+)\}""")
    private val glossary = Regex("""\{\?([^}]*)\}""")

    /** Non-empty paragraphs of the text, each a list of spans and links. */
    fun parse(text: String, units: MarkupUnits): List<List<MarkupNode>> =
        substituteUnits(text, units)
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::parseParagraph)

    /** The text with links and formatting stripped, for one-line previews. */
    fun plainText(text: String, units: MarkupUnits = MarkupUnits()): String =
        parse(text, units).joinToString("\n") { nodes ->
            nodes.joinToString("") {
                when (it) {
                    is MarkupNode.Span -> it.text
                    is MarkupNode.Link -> it.text
                }
            }
        }

    private fun parseParagraph(paragraph: String): List<MarkupNode> {
        val nodes = ArrayList<MarkupNode>()
        var rest = paragraph
        title.find(paragraph)?.let { match ->
            nodes += MarkupNode.Span(match.groupValues[1].trim(), MarkupStyle.TITLE)
            rest = paragraph.substring(match.range.last + 1)
        }
        var pos = 0
        for (match in hyperlink.findAll(rest)) {
            if (match.range.first > pos) nodes += styledSpans(rest.substring(pos, match.range.first))
            nodes += MarkupNode.Link(match.groupValues[1], parseTarget(match.groupValues[2]))
            pos = match.range.last + 1
        }
        if (pos < rest.length) nodes += styledSpans(rest.substring(pos))
        return nodes
    }

    private fun styledSpans(text: String): List<MarkupNode> {
        val nodes = ArrayList<MarkupNode>()
        var pos = 0
        for (match in styled.findAll(text)) {
            if (match.range.first > pos) nodes += MarkupNode.Span(text.substring(pos, match.range.first))
            val style = when (match.groupValues[1]) {
                "b" -> MarkupStyle.BOLD
                "i" -> MarkupStyle.ITALIC
                "u" -> MarkupStyle.UNDERLINE
                else -> MarkupStyle.NORMAL
            }
            nodes += MarkupNode.Span(match.groupValues[2], style)
            pos = match.range.last + 1
        }
        if (pos < text.length) nodes += MarkupNode.Span(text.substring(pos))
        return nodes
    }

    fun parseTarget(action: String): MarkupLink {
        val a = action.trim()
        if (a.startsWith(":")) {
            itineraryAction.find(a)?.let { match ->
                return MarkupLink.ItineraryRef(
                    packName = match.groupValues[1].ifEmpty { null },
                    itineraryId = match.groupValues[2].ifEmpty { null },
                    stepId = match.groupValues[3].toIntOrNull()?.let(::abs),
                )
            }
        }
        return when {
            a.startsWith("?") -> MarkupLink.SidebarRef(a.drop(1).trim())
            a.startsWith("!") -> MarkupLink.TrackRef(a.drop(1).trim())
            a.startsWith("#") -> MarkupLink.GeohashRef(a.drop(1).trim())
            a.startsWith("@") -> MarkupLink.EntityRef(a.drop(1).trim())
            a.startsWith("http://") || a.startsWith("https://") -> MarkupLink.Url(a)
            else -> MarkupLink.WaypointRef(a)
        }
    }

    fun substituteUnits(text: String, units: MarkupUnits): String = text
        .replace(altitude) { if (units.useFeet) metersToFeet(it.groupValues[1]) else "${it.groupValues[1]} m" }
        .replace(distanceKm) { if (units.useMiles) kmToMiles(it.groupValues[1]) else "${it.groupValues[1]} km" }
        .replace(distanceM) { if (units.useMiles) metersToFeet(it.groupValues[1]) else "${it.groupValues[1]} m" }
        .replace(kmUnit) { if (units.useMiles) "miles" else "km" }
        // There is no separate temperature preference; miles users get °F.
        .replace(temperature) { if (units.useMiles) celsiusToFahrenheit(it.groupValues[1]) else "${it.groupValues[1]} °C" }
        // TODO: glossary links; for now the term shows as plain text.
        .replace(glossary) { it.groupValues[1] }

    private fun metersToFeet(text: String): String {
        val meters = text.toDoubleOrNull() ?: return text
        if (meters < 10) return "${(meters * 10 * 3.28084).roundToInt() / 10.0} ft"
        val feet = (meters * 3.28084).roundToInt()
        if (feet < 10_000) return "$feet ft"
        // Always a comma (like the Flutter app), whatever the device locale.
        return "%,d ft".format(Locale.US, feet)
    }

    private fun kmToMiles(text: String): String {
        val km = text.toDoubleOrNull() ?: return text
        return if (km > 10) "${(km * 0.621371).roundToInt()} mi"
        else "${(km * 10 * 0.621371).roundToInt() / 10.0} mi"
    }

    private fun celsiusToFahrenheit(text: String): String {
        val match = Regex("""(\d+)\s?-?\s?(\d*)""").find(text) ?: return text
        fun toF(celsius: String) = (celsius.toDouble() * 9 / 5 + 32).roundToInt()
        val low = toF(match.groupValues[1])
        val high = match.groupValues[2].takeIf(String::isNotEmpty)?.let(::toF)
        return if (high != null) "$low - $high°F" else "$low°F"
    }
}

/** Units the markup's inline metrics are rendered in. */
data class MarkupUnits(val useMiles: Boolean = false, val useFeet: Boolean = false)

enum class MarkupStyle { NORMAL, BOLD, ITALIC, UNDERLINE, TITLE }

sealed interface MarkupNode {
    data class Span(val text: String, val style: MarkupStyle = MarkupStyle.NORMAL) : MarkupNode
    data class Link(val text: String, val target: MarkupLink) : MarkupNode
}

/** What a `[shown](action)` link points at. */
sealed interface MarkupLink {
    data class Url(val url: String) : MarkupLink
    data class WaypointRef(val name: String) : MarkupLink

    /**
     * `(:pack:slug:step)` — all parts optional: no pack means the current
     * one, no slug the current itinerary, so `(:::5)` is "step 5 here".
     */
    data class ItineraryRef(val packName: String?, val itineraryId: String?, val stepId: Int?) : MarkupLink

    data class SidebarRef(val name: String) : MarkupLink
    data class TrackRef(val name: String) : MarkupLink
    data class GeohashRef(val geohash: String) : MarkupLink
    data class EntityRef(val objectId: String) : MarkupLink

    /** The step number when this is a bare step link like `(:::5)`. */
    val ownStepId: Int?
        get() = (this as? ItineraryRef)
            ?.takeIf { it.packName == null && it.itineraryId == null }
            ?.stepId
}
