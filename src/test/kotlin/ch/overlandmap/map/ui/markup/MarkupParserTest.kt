package ch.overlandmap.map.ui.markup

import ch.overlandmap.map.units.UnitFormat
import ch.overlandmap.map.units.UnitFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Ported from iOS `MarkupParserTests`; the vectors are the same, the node model differs. */
class MarkupParserTest {
    private val metric = UnitFormatter(UnitFormat.METRIC, Locale.US)
    private val imperial = UnitFormatter(UnitFormat.IMPERIAL, Locale.US)

    private fun nodes(text: String, units: UnitFormatter = metric, titles: Boolean = false) =
        Markup.parse(text, units, titles).flatten()

    private fun plain(text: String, units: UnitFormatter = metric, titles: Boolean = false) =
        Markup.plainText(text, units, titles)

    private fun routes(text: String, units: UnitFormatter = metric) =
        nodes(text, units).filterIsInstance<MarkupNode.Link>().map { it.target }

    private fun itinerary(pack: String?, slug: String?, step: Int?) =
        MarkupLink.ItineraryRef(pack, slug, step)

    // ── Links ───────────────────────────────────────────────────────────────

    @Test
    fun parsesWaypointLink() {
        val text = "Go to [the spring](spring-doc-id) and refill."
        assertEquals("Go to the spring and refill.", plain(text))
        assertEquals(listOf<MarkupLink>(MarkupLink.WaypointRef("spring-doc-id")), routes(text))
    }

    @Test
    fun parsesWebLinks() {
        for (scheme in listOf("http", "https")) {
            assertEquals(
                listOf<MarkupLink>(MarkupLink.Url("$scheme://www.google.com")),
                routes("See [the site]($scheme://www.google.com)."),
            )
        }
    }

    @Test
    fun parsesItineraryLinks() {
        assertEquals(listOf<MarkupLink>(itinerary(null, "T2", null)), routes("Continue on [T2](::T2)."))
        assertEquals("Continue on T2.", plain("Continue on [T2](::T2)."))
        assertEquals(
            listOf<MarkupLink>(itinerary("Tajikistan", "T2", null)),
            routes("See [the Pamir](:Tajikistan:T2)."),
        )
        assertEquals(listOf<MarkupLink>(itinerary(null, "T2", 5)), routes("Turn off at [step 5](::T2:5)."))
    }

    @Test
    fun bareStepLinkIsAStepOfTheCurrentItinerary() {
        val link = routes("[go](:::11)").single()
        assertEquals(11, link.ownStepId)
        assertEquals(null, itinerary(null, "T2", 5).ownStepId)
    }

    @Test
    fun parsesSidebarTrackAndGeohashLinks() {
        assertEquals(
            listOf<MarkupLink>(MarkupLink.SidebarRef("Oman Geology")),
            routes("Read [about geology](? Oman Geology)."),
        )
        assertEquals(
            listOf<MarkupLink>(MarkupLink.TrackRef("track-abc")),
            routes("Follow [the main track](! track-abc)."),
        )
        assertEquals(
            listOf<MarkupLink>(MarkupLink.GeohashRef("tk0u461dw")),
            routes("Camp [here](#tk0u461dw)."),
        )
    }

    @Test
    fun parsesEntityLinks() {
        val cases = listOf(
            "@document-id" to MarkupLink.EntityRef(null, "document-id"),
            "@country/CHE" to MarkupLink.EntityRef(EntityKind.COUNTRY, "CHE"),
            "@border/che-ita" to MarkupLink.EntityRef(EntityKind.BORDER, "che-ita"),
            "@border_post/bp-12" to MarkupLink.EntityRef(EntityKind.BORDER_POST, "bp-12"),
            "@zone/z-3" to MarkupLink.EntityRef(EntityKind.ZONE, "z-3"),
        )
        for ((target, expected) in cases) {
            assertEquals(target, listOf<MarkupLink>(expected), routes("See [it]($target)."))
        }
    }

    @Test
    fun unknownEntityKindProducesNoLink() {
        assertTrue(routes("See [it](@planet/mars).").isEmpty())
        assertEquals("See it.", plain("See [it](@planet/mars)."))
        assertTrue(routes("See [it](@country/).").isEmpty())
        assertEquals(listOf<MarkupLink>(MarkupLink.EntityRef(null, "country")), routes("See [it](@country)."))
        assertTrue(routes("See [it](@).").isEmpty())
    }

    @Test
    fun unusableItineraryTargetsProduceNoLink() {
        assertTrue(routes("[x](:Tajikistan:)").isEmpty())
        assertTrue(routes("[x](:::abc)").isEmpty())
    }

    @Test
    fun emptyLinkTargetProducesNoRoute() {
        assertTrue(routes("See [this]().").isEmpty())
        assertEquals("See this.", plain("See [this]()."))
        assertTrue(routes("[x](! )").isEmpty())
    }

    @Test
    fun handlesSeveralLinksInOneParagraph() {
        val text = "From [A](::T1) via [B](::T2:3) to [C](#abc)."
        assertEquals("From A via B to C.", plain(text))
        assertEquals(3, routes(text).size)
    }

    // ── Bare URLs ───────────────────────────────────────────────────────────

    @Test
    fun linksBareUrlsWithoutRewritingText() {
        for (scheme in listOf("http", "https")) {
            val text = "Details at $scheme://example.com/visa now."
            assertEquals(listOf<MarkupLink>(MarkupLink.Url("$scheme://example.com/visa")), routes(text))
            assertEquals(text, plain(text))
        }
    }

    @Test
    fun trimsTrailingPunctuationFromBareUrls() {
        val text = "See https://example.com/a."
        assertEquals(listOf<MarkupLink>(MarkupLink.Url("https://example.com/a")), routes(text))
        assertEquals(text, plain(text))
        assertEquals(
            listOf<MarkupLink>(MarkupLink.Url("https://example.com/x")),
            routes("Info (https://example.com/x) here"),
        )
    }

    @Test
    fun doesNotDoubleLinkAuthoredWebLinks() {
        val text = "See [the site](https://example.com/page)."
        assertEquals(listOf<MarkupLink>(MarkupLink.Url("https://example.com/page")), routes(text))
        assertEquals("See the site.", plain(text))
    }

    @Test
    fun linksBareUrlAlongsideAuthoredLink() {
        assertEquals(
            listOf<MarkupLink>(itinerary(null, "T2", null), MarkupLink.Url("https://example.com/end")),
            routes("Go [T2](::T2) then https://example.com/end"),
        )
    }

    @Test
    fun keepsStyleOnAutolinkedUrl() {
        val link = nodes("`b`https://example.com`").filterIsInstance<MarkupNode.Link>().single()
        assertEquals(MarkupStyle.BOLD, link.style)
    }

    @Test
    fun linksEveryBareUrlInAParagraph() {
        val text = "a https://one.example b https://two.example c"
        assertEquals(2, routes(text).size)
        assertEquals(text, plain(text))
    }

    @Test
    fun leavesProseWithoutUrlsAlone() {
        val n = nodes("Just some ordinary prose about borders.")
        assertEquals(1, n.size)
        assertTrue(n.single() is MarkupNode.Span)
    }

    // ── Inline style ────────────────────────────────────────────────────────

    @Test
    fun parsesBoldItalicUnderline() {
        val text = "Normal `b`bold` `i`italic` `u`under` end."
        assertEquals("Normal bold italic under end.", plain(text))
        val spans = nodes(text).filterIsInstance<MarkupNode.Span>()
        assertEquals(MarkupStyle.BOLD, spans.first { it.text == "bold" }.style)
        assertEquals(MarkupStyle.ITALIC, spans.first { it.text == "italic" }.style)
        assertEquals(MarkupStyle.UNDERLINE, spans.first { it.text == "under" }.style)
    }

    @Test
    fun styleAndRouteSurviveInsideLinkLabels() {
        val link = nodes("Go [`b`now`](::T2).").filterIsInstance<MarkupNode.Link>().single()
        assertEquals("now", link.text)
        assertEquals(MarkupStyle.BOLD, link.style)
        assertEquals(itinerary(null, "T2", null), link.target)
    }

    // ── Units ───────────────────────────────────────────────────────────────

    @Test
    fun formatsAltitude() {
        assertEquals("Pass at 1,230 m.", plain("Pass at {^1230}."))
        assertEquals("Pass at 4,035 ft.", plain("Pass at {^1230}.", imperial))
        assertEquals("At 1,230 m.", plain("At {^ 1230}.")) // optional space
        assertEquals("16,404 ft", plain("{^5000}", imperial))
    }

    @Test
    fun formatsKilometres() {
        assertEquals("Drive 123 km.", plain("Drive {->km 123}."))
        assertEquals("Drive 76 mi.", plain("Drive {->km 123}.", imperial))
        assertEquals("3.1 mi", plain("{->km 5}", imperial))
    }

    @Test
    fun formatsMetres() {
        assertEquals("Walk 123 m.", plain("Walk {->m 123}."))
        assertEquals("Walk 404 ft.", plain("Walk {->m 123}.", imperial))
    }

    @Test
    fun substitutesTheBareUnitWord() {
        assertEquals("about 12 km", plain("about 12 {km}"))
        assertEquals("about 12 miles", plain("about 12 {km}", imperial))
    }

    @Test
    fun formatsTemperatureOnItsOwnAxis() {
        assertEquals("Up to 34 °C.", plain("Up to {°C 34}."))
        assertEquals("Up to 93°F.", plain("Up to {°C 34}.", imperial))
        assertEquals("68 - 86°F", plain("{°C 20-30}", imperial))
        // Miles with Celsius must stay Celsius (the old Android code tied them together).
        val milesButCelsius = UnitFormatter(UnitFormat(distanceMetric = false), Locale.US)
        assertEquals("34 °C", plain("{°C 34}", milesButCelsius))
    }

    @Test
    fun unitAxesAreIndependent() {
        val mixed = UnitFormatter(UnitFormat(heightMetric = false), Locale.US)
        assertEquals("4,035 ft then 123 km at 34 °C", plain("{^1230} then {->km 123} at {°C 34}", mixed))
    }

    @Test
    fun stripsGlossaryMarkupWithoutLinking() {
        assertEquals("A washboard road.", plain("A {?washboard} road."))
        assertTrue(routes("A {?washboard} road.").isEmpty())
    }

    // ── Titles ──────────────────────────────────────────────────────────────

    @Test
    fun parsesTitleOnlyWhenAllowed() {
        val n = nodes("::Geology::\nOman sits on ophiolite.", titles = true)
        val first = n.first() as MarkupNode.Span
        assertEquals(MarkupStyle.TITLE, first.style)
        assertEquals("Geology", first.text)
        assertTrue(plain("::Geology::\nOman sits on ophiolite.", titles = true).contains("Oman sits on ophiolite."))
    }

    @Test
    fun titleMarkupIsLiteralOutsideSidebars() {
        val n = nodes("::Geology::\nrest")
        assertTrue(n.filterIsInstance<MarkupNode.Span>().none { it.style == MarkupStyle.TITLE })
        assertTrue(plain("::Geology::\nrest").contains("::Geology::"))
    }

    @Test
    fun everyParagraphOfASidebarCanHaveATitle() {
        val paragraphs = Markup.parse("::One::\nbody\n::Two::\nmore", metric, allowsTitles = true)
        assertEquals(4, paragraphs.size)
        assertEquals(MarkupStyle.TITLE, (paragraphs[2].first() as MarkupNode.Span).style)
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(Markup.parse("", metric).isEmpty())
    }

    @Test
    fun plainTextPassesThroughUnchanged() {
        val text = "Just an ordinary sentence, with punctuation — and a dash."
        assertEquals(text, plain(text))
    }

    @Test
    fun plainTextStripsFormattingAfterUnitSubstitution() {
        assertEquals("See the pass at 1,230 m.", plain("See [`b`the pass`](::T2) at {^1230}."))
    }

    @Test
    fun malformedMarkupDegradesToVisibleText() {
        val cases = listOf(
            "An [unclosed link(::T2)",
            "A ] stray bracket",
            "Unbalanced `b`bold",
            "{^notanumber}",
            "{->km }",
        )
        for (input in cases) assertTrue("input $input produced nothing", plain(input).isNotEmpty())
    }

    @Test
    fun mergesAdjacentUnstyledSpans() {
        assertEquals(1, nodes("one two three").size)
    }

    @Test
    fun paragraphsAreSplitOnNewlinesAndBlanksDropped() {
        assertEquals(2, Markup.parse("one\n\n  \ntwo", metric).size)
    }
}
