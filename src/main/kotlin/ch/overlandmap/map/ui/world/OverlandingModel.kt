package ch.overlandmap.map.ui.world

import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.NaturalOrder
import ch.overlandmap.map.model.Zone

/** What is selected on the Overlanding map (iOS `OverlandingMapSelection`). Country ids are alpha-3 codes. */
sealed interface OverlandingSelection {
    val id: String

    data class OfCountry(override val id: String) : OverlandingSelection
    data class OfBorder(override val id: String) : OverlandingSelection
    data class OfBorderPost(override val id: String) : OverlandingSelection
    data class OfZone(override val id: String) : OverlandingSelection

    /** `country:FRA`, the form persisted between launches (as on iOS). */
    fun encoded(): String = when (this) {
        is OfCountry -> "country:$id"
        is OfBorder -> "border:$id"
        is OfBorderPost -> "borderPost:$id"
        is OfZone -> "zone:$id"
    }

    companion object {
        fun decode(stored: String?): OverlandingSelection? {
            val parts = stored?.split(':', limit = 2) ?: return null
            if (parts.size != 2 || parts[1].isEmpty()) return null
            return when (parts[0]) {
                "country" -> OfCountry(parts[1])
                "border" -> OfBorder(parts[1])
                "borderPost" -> OfBorderPost(parts[1])
                "zone" -> OfZone(parts[1])
                else -> null
            }
        }
    }
}

/** The map's saved viewpoint; the default shows the whole world (iOS `OverlandingCameraPosition.world`). */
data class OverlandingCamera(val lat: Double, val lon: Double, val zoom: Double) {
    fun encoded() = "$lat,$lon,$zoom"

    companion object {
        val WORLD = OverlandingCamera(lat = 18.0, lon = 15.0, zoom = 1.15)

        fun decode(stored: String?): OverlandingCamera? {
            val p = stored?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: return null
            return if (p.size == 3) OverlandingCamera(p[0], p[1], p[2]) else null
        }
    }
}

/** A drill-down inside the detail pane: the map stays put while the pane goes country → border → post. */
sealed interface PaneDestination {
    val backTitle: String

    data class Border(val borderId: String, override val backTitle: String) : PaneDestination
    data class BorderPostOf(
        val postId: String,
        val borderId: String?,
        override val backTitle: String,
    ) : PaneDestination
}

enum class OverlandResultKind { COUNTRY, BORDER, BORDER_POST, ZONE }

/** One row of the home pane's search. */
data class OverlandSearchResult(
    val id: String,
    val title: String,
    val kind: OverlandResultKind,
    /** Zone type label for zones; unused otherwise. */
    val subtitle: String?,
    val isoA2: String?,
    val selection: OverlandingSelection,
)

/** Everything the Overlanding screens read, loaded from Room. */
data class OverlandData(
    val countries: List<Country> = emptyList(),
    val borders: List<CountryBorder> = emptyList(),
    val posts: List<BorderPost> = emptyList(),
    val zones: List<Zone> = emptyList(),
) {
    /** A country by alpha-3 code or document id (they are the same; case-insensitive). */
    fun country(code: String?): Country? = code?.let { c ->
        countries.firstOrNull {
            it.adm0A3.equals(c, ignoreCase = true) || it.documentId.equals(c, ignoreCase = true)
        }
    }

    fun border(id: String?) = borders.firstOrNull { it.documentId == id }
    fun post(id: String?) = posts.firstOrNull { it.documentId == id }
    fun zone(id: String?) = zones.firstOrNull { it.documentId == id }

    /** The land borders touching [country]. */
    fun bordersOf(country: Country): List<CountryBorder> {
        val code = country.adm0A3 ?: country.documentId
        return borders.filter { it.country1 == code || it.country2 == code }
    }

    /** "France / Spain" from the two countries' names; falls back to the border's own name. */
    fun borderTitle(border: CountryBorder, lang: String, fallback: String): String {
        val names = listOf(border.country1, border.country2)
            .mapNotNull { country(it)?.name(lang)?.takeIf { n -> n.isNotEmpty() } }
        return if (names.size == 2) names.joinToString(" / ") else border.name.ifEmpty { fallback }
    }
}

/** The home pane's search over countries, borders, posts and zones (iOS `OverlandingScreen.searchResults`). */
object OverlandSearch {

    fun search(
        query: String,
        data: OverlandData,
        lang: String,
        zoneTypeLabel: (Zone) -> String,
        borderPostFallback: String,
    ): List<OverlandSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        fun matches(vararg values: String?) = values.any { it != null && it.contains(q, ignoreCase = true) }

        val countries = data.countries.mapNotNull { c ->
            val name = c.name(lang)
            if (!matches(name, c.adm0A3, c.isoA2)) return@mapNotNull null
            OverlandSearchResult(
                "country-${c.documentId}", name, OverlandResultKind.COUNTRY, null, c.isoA2,
                OverlandingSelection.OfCountry(c.adm0A3 ?: c.documentId),
            )
        }
        val borders = data.borders.mapNotNull { b ->
            val name = b.name.ifEmpty { listOfNotNull(b.country1, b.country2).joinToString(" / ") }
            if (!matches(name, b.documentId)) return@mapNotNull null
            OverlandSearchResult(
                "border-${b.documentId}", name, OverlandResultKind.BORDER, null, null,
                OverlandingSelection.OfBorder(b.documentId),
            )
        }
        val posts = data.posts.mapNotNull { p ->
            val name = p.name.ifEmpty { borderPostFallback }
            if (!matches(name, p.documentId, p.geohash)) return@mapNotNull null
            OverlandSearchResult(
                "post-${p.documentId}", name, OverlandResultKind.BORDER_POST, null, null,
                OverlandingSelection.OfBorderPost(p.documentId),
            )
        }
        val zones = data.zones.mapNotNull { z ->
            if (!matches(z.name, z.documentId, z.comment(lang))) return@mapNotNull null
            OverlandSearchResult(
                "zone-${z.documentId}", z.name, OverlandResultKind.ZONE, zoneTypeLabel(z), null,
                OverlandingSelection.OfZone(z.documentId),
            )
        }
        return (countries + borders + posts + zones).sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
    }
}
