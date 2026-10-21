package ch.overlandmap.map.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniversalLinkTest {

    @Test
    fun overlandEntitiesRoundTrip() {
        listOf(
            UniversalLink.Country("FRA") to "https://overlandmap.ch/country/FRA",
            UniversalLink.Border("FRA-ESP") to "https://overlandmap.ch/border/FRA-ESP",
            UniversalLink.BorderPost("abc123") to "https://overlandmap.ch/border_post/abc123",
            UniversalLink.Zone("z1") to "https://overlandmap.ch/zone/z1",
            UniversalLink.Waypoint("u0qj2") to "https://overlandmap.ch/waypoint/u0qj2",
        ).forEach { (link, url) ->
            assertEquals(url, link.url())
            assertEquals(link, UniversalLink.parse(url))
        }
    }

    @Test
    fun itineraryWithAndWithoutStep() {
        val plain = UniversalLink.Itinerary("ed", "Pamir", "P1")
        assertEquals("https://overlandmap.ch/itinerary/ed/Pamir/P1", plain.url())
        assertEquals(plain, UniversalLink.parse("https://overlandmap.ch/itinerary/ed/Pamir/P1"))

        val step = UniversalLink.Itinerary("ed", "Pamir", "P1", 3)
        assertEquals("https://overlandmap.ch/ed/Pamir/P1?step=3", step.url())
        assertEquals(step, UniversalLink.parse("https://overlandmap.ch/ed/Pamir/P1?step=3"))
    }

    @Test
    fun segmentsAreEncodedIndividually() {
        val link = UniversalLink.TrackPack("ed", "Great Silk/Road #1")
        val url = link.url()!!
        assertEquals("https://overlandmap.ch/trackpack/ed/Great%20Silk%2FRoad%20%231", url)
        assertEquals(link, UniversalLink.parse(url))
        assertEquals(UniversalLink.TrackPack("ed", "Été"), UniversalLink.parse(UniversalLink.TrackPack("ed", "Été").url()!!))
    }

    @Test
    fun emptySegmentsHaveNoLink() {
        assertNull(UniversalLink.TrackPack("", "Pamir").url())
    }

    @Test
    fun foreignOrMalformedLinksAreRejected() {
        assertNull(UniversalLink.parse("http://overlandmap.ch/country/FRA"))
        assertNull(UniversalLink.parse("https://example.com/country/FRA"))
        assertNull(UniversalLink.parse("https://overlandmap.ch/country"))
        assertNull(UniversalLink.parse("https://overlandmap.ch/country/FRA?step=1"))
        assertNull(UniversalLink.parse("https://overlandmap.ch/ed/Pamir/P1?step=-1"))
        assertNull(UniversalLink.parse("https://overlandmap.ch/ed/Pamir/P1?step=1&step=2"))
        assertNull(UniversalLink.parse("https://overlandmap.ch/country/%ZZ"))
        assertNull(UniversalLink.parse("not a url"))
    }
}
