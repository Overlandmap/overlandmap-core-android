package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.NaturalOrder
import ch.overlandmap.map.model.idPostfix
import ch.overlandmap.map.model.idPrefix
import ch.overlandmap.map.model.sortedForDisplay
import ch.overlandmap.map.ui.markup.Markup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndSortTest {

    private fun itin(slug: String, free: Boolean = false) =
        Itinerary(documentId = "d$slug", trackPackId = "p", itineraryId = slug, name = slug, isFree = free)

    // ── Markup stripping for the index ──────────────────────────────────────

    @Test
    fun stripForSearchRemovesMarkup() {
        assertEquals("the pass", Markup.stripForSearch("the [pass](waypoint:abc)"))
        assertEquals("bold text", Markup.stripForSearch("`b`bold` text"))
        assertEquals("4035 m", Markup.stripForSearch("{^4035} m"))
        assertEquals("123 to go", Markup.stripForSearch("{->km 123} to go"))
        assertEquals("34 in July", Markup.stripForSearch("{°C 34} in July"))
        assertEquals("fuel", Markup.stripForSearch("{?fuel}"))
        assertEquals("km", Markup.stripForSearch("{km}"))
        assertEquals("", Markup.stripForSearch(""))
        assertEquals("trimmed", Markup.stripForSearch("  trimmed \n"))
    }

    @Test
    fun matchExpressionIsAPhraseWithPrefixOnTheLastWord() {
        assertEquals("\"Umling La*\"", FtsIndex.matchExpression("Umling La"))
        assertEquals("\"man*\"", FtsIndex.matchExpression("  man  "))
        // Operators in the input are neutralised.
        assertEquals("\"a b*\"", FtsIndex.matchExpression("\"a* b\"*"))
        assertNull(FtsIndex.matchExpression("  \"* "))
        assertEquals("\"leh*\"", FtsIndex.matchExpression("leh."))
    }

    @Test
    fun searchLimitAndTypesMatchIos() {
        assertEquals(100, FtsIndex.DEFAULT_LIMIT)
        assertEquals(3, SearchRepository.MIN_QUERY_LENGTH)
        assertEquals("ItineraryStep", FtsIndex.TYPE_STEP)
        assertEquals("ContributedWaypoint", FtsIndex.TYPE_CONTRIBUTED_WAYPOINT)
        assertEquals("BorderPost", FtsIndex.TYPE_BORDER_POST)
    }

    // ── Ranking ─────────────────────────────────────────────────────────────

    @Test
    fun rankBucketsPrefixThenContainsThenBody() {
        val hits = listOf("Road to Leh", "Lehigh bridge", "Manali", "Leh").map {
            SearchResult(FtsIndex.TYPE_WAYPOINT, it, it, "")
        }
        assertEquals(
            listOf("Lehigh bridge", "Leh", "Road to Leh", "Manali"),
            // Stable within a bucket: "Lehigh bridge" came before "Leh" in the index order.
            SearchRepository.rank(hits, "leh").map { it.name },
        )
    }

    @Test
    fun rankIgnoresCaseAndAccents() {
        val hits = listOf("Café du Nord", "Le Café", "Other").map { SearchResult("x", it, it, "") }
        assertEquals(listOf("Café du Nord", "Le Café", "Other"), SearchRepository.rank(hits, "CAFE").map { it.name })
        assertEquals("Le Café", SearchRepository.rank(listOf(SearchResult("x", "b", "Le Café", ""), SearchResult("x", "a", "Zed", "")), "cafe").first().name)
    }

    // ── Itinerary ordering ──────────────────────────────────────────────────

    @Test
    fun slugPartsSplitPrefixAndNumber() {
        assertEquals("Om", itin("Om5").idPrefix)
        assertEquals(5, itin("Om5").idPostfix)
        assertEquals("", itin("12").idPrefix)
        assertNull(itin("Om").idPostfix)
    }

    @Test
    fun itinerariesSortByPrefixThenNumber() {
        val sorted = listOf("L10", "K2", "K10", "L9", "K1", "Om", "K").map(::itin).sortedForDisplay()
        assertEquals(listOf("K1", "K2", "K10", "K", "L9", "L10", "Om"), sorted.map { it.itineraryId })
    }

    @Test
    fun freeItineraryComesFirstOnlyWhenAsked() {
        val list = listOf(itin("A2"), itin("B1", free = true), itin("A1"))
        assertEquals(listOf("A1", "A2", "B1"), list.sortedForDisplay().map { it.itineraryId })
        assertEquals(listOf("B1", "A1", "A2"), list.sortedForDisplay(freeFirst = true).map { it.itineraryId })
    }

    @Test
    fun naturalOrderComparesNumbersByValueAndIgnoresCase() {
        assertTrue(NaturalOrder.compare("K2", "K10") < 0)
        assertTrue(NaturalOrder.compare("a", "B") < 0)
        assertTrue(NaturalOrder.compare("é", "f") < 0)
        assertEquals(0, NaturalOrder.compare("K01", "k1"))
    }

    // ── Indexed itinerary text ──────────────────────────────────────────────

    @Test
    fun indexableDescriptionFollowsIosOrder() {
        val i = Itinerary(
            documentId = "i", trackPackId = "p", name = "n", description = "desc",
            translatedDesc = mapOf("fr" to "desc-fr"),
            highlights = "high", translatedHighlights = mapOf("fr" to "high-fr"),
            roadConditions = "road",
            titlePhotoCaption = "cap1", otherPhotoCaptions = listOf("cap2"),
        )
        assertEquals("desc\nhigh\nroad\ncap1\ncap2", i.indexableDescription("en"))
        assertEquals("desc-fr\nhigh-fr\nroad\ncap1\ncap2", i.indexableDescription("fr"))
        assertEquals("", Itinerary(documentId = "i", trackPackId = "p", name = "n").indexableDescription("en"))
        assertEquals("d", Itinerary(documentId = "i", trackPackId = "p", name = "n", description = "d").indexableDescription("en"))
    }
}
