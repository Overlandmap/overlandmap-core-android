package ch.overlandmap.map.ui.markup

import ch.overlandmap.map.units.UnitFormat
import ch.overlandmap.map.units.UnitFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Parser for the markup used by descriptions and captions. Port of the iOS
 * `MarkupParser` (itself a port of `components/formatted_text.dart`), so both apps
 * read authored content the same way.
 *
 * Hyperlinks — `[shown](action)`; the action selects the target:
 * - `http://…` / `https://…`          web link
 * - `::T2`                            itinerary of the same pack
 * - `:Tajikistan:T2`                  itinerary of another pack
 * - `::T2:5` / `:::5`                 itinerary step (`:::5` = step 5 of the current one)
 * - `? sidebarName`                   sidebar article
 * - `! track`                         track
 * - `#geohash`                        point by geohash
 * - `@country/CHE`, `@border/<id>`, `@border_post/<id>`, `@zone/<id>`   world entity
 * - anything else                     waypoint by name
 * An empty action, or one that cannot be understood (an unknown `@kind`, a `:`
 * target without an itinerary id), is not a link: the label stays plain text.
 *
 * Inline formatting:
 * - `{^1230}` altitude, `{->km 123}` / `{->m 123}` distances, `{km}` unit name,
 *   `{°C 34}` temperature (also ranges like `20-30`) — converted to the user's units
 * - `{?term}` glossary term — the braces are dropped and the term shown as plain text
 * - `` `b`text` `` bold, `` `i`text` `` italic, `` `u`text` `` underline
 * - a bare `https://…` in the prose becomes a tappable web link
 * - `::Title::` at the start of a paragraph: a section title, only where the caller
 *   allows titles (sidebars). Elsewhere it is literal text.
 */
object Markup {

    private val LINK_RE = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val STYLE_RE = Regex("""`[biu]`([^`]*)`""")
    private val ELEVATION_RE = Regex("""\{\^([0-9.]+)\}""")
    private val DISTANCE_RE = Regex("""\{->[a-zA-Z]+ ([0-9.]+)\}""")
    private val TEMPERATURE_RE = Regex("""\{°[CF] ([0-9.]+)\}""")
    private val GLOSSARY_RE = Regex("""\{\?([^}]*)\}""")
    private val WORD_RE = Regex("""\{([a-zA-Z]+)\}""")

    /**
     * Removes the markup so the search index holds readable words rather than link syntax
     * (iOS `MarkupStripper`): `[label](target)` becomes `label`, `` `b`text` `` becomes `text`,
     * `{^1230}` / `{->km 123}` / `{°C 34}` become the bare number, and `{km}` / `{?term}` lose
     * their braces. Independent of the units the user has chosen: it only strips.
     */
    fun stripForSearch(text: String): String {
        if (text.isEmpty()) return ""
        var out = text
        out = LINK_RE.replace(out, "$1")
        out = STYLE_RE.replace(out, "$1")
        out = ELEVATION_RE.replace(out, "$1")
        out = DISTANCE_RE.replace(out, "$1")
        out = TEMPERATURE_RE.replace(out, "$1")
        out = GLOSSARY_RE.replace(out, "$1")
        out = WORD_RE.replace(out, "$1")
        return out.trim()
    }

    // Android's ICU regex engine (unlike the JVM's) rejects unescaped
    // literal `]` and `}`, so they are all escaped here.
    private val hyperlink = Regex("""\[([^\]]*)\]\(([^)]*)\)""")
    private val styled = Regex("""`([biu])`([^`]+)`""")
    private val title = Regex("""^::(.+)::""")
    private val itineraryAction = Regex("""^:(\w+)?:(\w+)?:?(-?\d+)?""")
    private val bareUrl = Regex("""https?://[^\s<>"]+""")
    private val urlTrailingPunctuation = setOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"', '»', '”', '’',
    )

    private val altitude = Regex("""\{\^ ?(\d+)\}""")
    private val distanceKm = Regex("""\{->km (\d+)\}""")
    private val distanceM = Regex("""\{->m (\d+)\}""")
    private val kmUnit = Regex("""\{km\}""")
    private val temperature = Regex("""\{°C ([\d\-\s]+)\}""")
    private val glossary = Regex("""\{\?([^}]*)\}""")

    private fun defaultUnits() = UnitFormatter(UnitFormat.METRIC, Locale.getDefault())

    /**
     * Non-empty paragraphs of the text, each a list of spans and links.
     * [allowsTitles] enables the `::Title::` paragraph form (sidebars only).
     */
    fun parse(
        text: String,
        units: UnitFormatter = defaultUnits(),
        allowsTitles: Boolean = false,
    ): List<List<MarkupNode>> =
        substituteUnits(text, units)
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { parseParagraph(it, allowsTitles) }

    /** The text with links and formatting stripped, for one-line previews. */
    fun plainText(
        text: String,
        units: UnitFormatter = defaultUnits(),
        allowsTitles: Boolean = false,
    ): String =
        parse(text, units, allowsTitles).joinToString("\n") { nodes ->
            nodes.joinToString("") {
                when (it) {
                    is MarkupNode.Span -> it.text
                    is MarkupNode.Link -> it.text
                }
            }
        }

    private fun parseParagraph(paragraph: String, allowsTitles: Boolean): List<MarkupNode> {
        val nodes = ArrayList<MarkupNode>()
        var rest = paragraph
        if (allowsTitles) {
            title.find(paragraph)?.let { match ->
                nodes += MarkupNode.Span(match.groupValues[1], MarkupStyle.TITLE)
                rest = paragraph.substring(match.range.last + 1).trim()
            }
        }
        nodes += parseLinksAndStyle(rest)
        return mergeAdjacent(autolinkBareUrls(nodes))
    }

    private fun parseLinksAndStyle(input: String): List<MarkupNode> {
        val nodes = ArrayList<MarkupNode>()
        var pos = 0
        for (match in hyperlink.findAll(input)) {
            if (match.range.first > pos) nodes += styledSpans(input.substring(pos, match.range.first))
            val label = match.groupValues[1]
            val target = parseTarget(match.groupValues[2])
            // A label may itself carry style markup, e.g. [`b`Bold`](::T2).
            val pieces = styledSpans(label).ifEmpty { listOf(MarkupNode.Span(label)) }
            for (piece in pieces) {
                val span = piece as MarkupNode.Span
                nodes += if (target == null) span else MarkupNode.Link(span.text, target, span.style)
            }
            pos = match.range.last + 1
        }
        if (pos < input.length) nodes += styledSpans(input.substring(pos))
        return nodes
    }

    private fun styledSpans(text: String): List<MarkupNode> {
        if (text.isEmpty()) return emptyList()
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

    /**
     * Turns a naked `https://…` in the prose into a web link. Only text that is not
     * already a link is considered, so authored link labels are left as written, and
     * the span's style is kept (a URL inside `` `b`…` `` stays bold). The text is never
     * rewritten, only split, so [plainText] is unaffected.
     */
    private fun autolinkBareUrls(nodes: List<MarkupNode>): List<MarkupNode> {
        if (nodes.none { it is MarkupNode.Span && it.text.contains("://") }) return nodes
        val out = ArrayList<MarkupNode>()
        for (node in nodes) {
            if (node !is MarkupNode.Span || !node.text.contains("://")) {
                out += node
                continue
            }
            val text = node.text
            var cursor = 0
            for (match in bareUrl.findAll(text)) {
                // Trailing punctuation belongs to the sentence, not the address:
                // "see https://a.example." must not link the full stop.
                var end = match.range.last + 1
                while (end > match.range.first && text[end - 1] in urlTrailingPunctuation) end--
                if (end <= match.range.first) continue
                if (cursor < match.range.first) {
                    out += MarkupNode.Span(text.substring(cursor, match.range.first), node.style)
                }
                val url = text.substring(match.range.first, end)
                out += MarkupNode.Link(url, MarkupLink.Url(url), node.style)
                cursor = end
            }
            if (cursor < text.length) out += MarkupNode.Span(text.substring(cursor), node.style)
        }
        return out
    }

    /** Collapses neighbours with identical attributes and drops empty spans. */
    private fun mergeAdjacent(nodes: List<MarkupNode>): List<MarkupNode> {
        val out = ArrayList<MarkupNode>()
        for (node in nodes) {
            if (node is MarkupNode.Span && node.text.isEmpty()) continue
            val last = out.lastOrNull()
            out += when {
                node is MarkupNode.Span && last is MarkupNode.Span && last.style == node.style ->
                    out.removeAt(out.lastIndex).let { last.copy(text = last.text + node.text) }
                node is MarkupNode.Link && last is MarkupNode.Link &&
                    last.target == node.target && last.style == node.style ->
                    out.removeAt(out.lastIndex).let { last.copy(text = last.text + node.text) }
                else -> node
            }
        }
        return out
    }

    /** The link an action string denotes, or null when it is not a usable link. */
    fun parseTarget(action: String): MarkupLink? {
        val a = action.trim()
        if (a.isEmpty()) return null
        if (a.startsWith("http://") || a.startsWith("https://")) return MarkupLink.Url(a)
        if (a.startsWith(":")) {
            // `:::N` is "step N of the current itinerary".
            if (a.startsWith(":::")) {
                a.drop(3).toIntOrNull()?.let { return MarkupLink.ItineraryRef(null, null, abs(it)) }
            }
            val match = itineraryAction.find(a) ?: return null
            val slug = match.groupValues[2].ifEmpty { null } ?: return null
            return MarkupLink.ItineraryRef(
                packName = match.groupValues[1].trim().ifEmpty { null },
                itineraryId = slug,
                stepId = match.groupValues[3].toIntOrNull()?.let(::abs),
            )
        }
        return when {
            a.startsWith("!") -> a.drop(1).trim().takeIf(String::isNotEmpty)?.let(MarkupLink::TrackRef)
            a.startsWith("?") -> a.drop(1).trim().takeIf(String::isNotEmpty)?.let(MarkupLink::SidebarRef)
            a.startsWith("#") -> a.drop(1).trim().takeIf(String::isNotEmpty)?.let(MarkupLink::GeohashRef)
            a.startsWith("@") -> entityRef(a.drop(1))
            else -> MarkupLink.WaypointRef(a)
        }
    }

    /** `country/CHE`, `border/x`, `border_post/x`, `zone/x`; null for anything else. */
    private fun entityRef(body: String): MarkupLink? {
        if (body.isNotBlank() && '/' !in body) return MarkupLink.EntityRef(null, body)
        val parts = body.split('/', limit = 2)
        if (parts.size != 2 || parts[1].isEmpty()) return null
        val kind = when (parts[0]) {
            "country" -> EntityKind.COUNTRY
            "border" -> EntityKind.BORDER
            "border_post" -> EntityKind.BORDER_POST
            "zone" -> EntityKind.ZONE
            else -> return null
        }
        return MarkupLink.EntityRef(kind, parts[1])
    }

    /**
     * Replaces the inline unit tokens with values in the user's units. Mirrors the iOS
     * substitution, including which setting each token follows (`{->m}` follows the
     * *distance* axis, `{°C}` the temperature axis).
     */
    fun substituteUnits(text: String, units: UnitFormatter): String = text
        .replace(altitude) { units.meters(it.groupValues[1].toDouble()) }
        .replace(distanceKm) { units.markupKilometers(it.groupValues[1].toDouble()) }
        .replace(distanceM) {
            val value = it.groupValues[1].toDouble()
            if (units.format.distanceMetric) "${Math.round(value)} m" else units.meters(value)
        }
        .replace(kmUnit) { units.distanceUnitWord }
        .replace(temperature) { units.markupTemperature(it.groupValues[1]) }
        // Glossary links are dropped, but the syntax is still consumed so raw braces
        // never reach the screen.
        .replace(glossary) { it.groupValues[1] }
}

enum class MarkupStyle { NORMAL, BOLD, ITALIC, UNDERLINE, TITLE }

sealed interface MarkupNode {
    data class Span(val text: String, val style: MarkupStyle = MarkupStyle.NORMAL) : MarkupNode
    data class Link(
        val text: String,
        val target: MarkupLink,
        val style: MarkupStyle = MarkupStyle.NORMAL,
    ) : MarkupNode
}

/** The kinds of world entity a typed `@kind/id` link can name. */
enum class EntityKind { COUNTRY, BORDER, BORDER_POST, ZONE }

/** What a `[shown](action)` link points at. */
sealed interface MarkupLink {
    data class Url(val url: String) : MarkupLink
    data class WaypointRef(val name: String) : MarkupLink

    /**
     * `(:pack:slug:step)` — pack and step optional: no pack means the current one. A
     * missing slug is only valid for a bare step link like `(:::5)`.
     */
    data class ItineraryRef(val packName: String?, val itineraryId: String?, val stepId: Int?) : MarkupLink

    data class SidebarRef(val name: String) : MarkupLink
    data class TrackRef(val name: String) : MarkupLink
    data class GeohashRef(val geohash: String) : MarkupLink
    /** A null kind resolves a bare `@documentId` across all world collections. */
    data class EntityRef(val kind: EntityKind?, val id: String) : MarkupLink

    /** The step number when this is a bare step link like `(:::5)`. */
    val ownStepId: Int?
        get() = (this as? ItineraryRef)
            ?.takeIf { it.packName == null && it.itineraryId == null }
            ?.stepId
}
