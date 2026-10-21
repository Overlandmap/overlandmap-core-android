package ch.overlandmap.map.ui.world

import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlandingModelTest {

    private val data = OverlandData(
        countries = listOf(
            Country(documentId = "FRA", name = "France", isoA2 = "FR", adm0A3 = "FRA", translations = mapOf("fr" to "France")),
            Country(documentId = "ESP", name = "Spain", isoA2 = "ES", adm0A3 = "ESP", translations = mapOf("fr" to "Espagne")),
        ),
        borders = listOf(CountryBorder(documentId = "FRA-ESP", name = "x", country1 = "FRA", country2 = "ESP")),
        posts = listOf(BorderPost(documentId = "p1", name = "Hendaye", geohash = "ezzx")),
        zones = listOf(Zone(documentId = "z1", name = "Wakhan", type = 2)),
    )

    @Test
    fun selectionEncodingRoundTrips() {
        listOf(
            OverlandingSelection.OfCountry("FRA"), OverlandingSelection.OfBorder("FRA-ESP"),
            OverlandingSelection.OfBorderPost("p:1"), OverlandingSelection.OfZone("z1"),
        ).forEach { assertEquals(it, OverlandingSelection.decode(it.encoded())) }
        assertNull(OverlandingSelection.decode(null))
        assertNull(OverlandingSelection.decode("country:"))
        assertNull(OverlandingSelection.decode("planet:Mars"))
        assertNull(OverlandingSelection.decode("nonsense"))
    }

    @Test
    fun cameraEncodingRoundTripsAndDefaultsToTheWorld() {
        val camera = OverlandingCamera(41.5, 69.25, 6.5)
        assertEquals(camera, OverlandingCamera.decode(camera.encoded()))
        assertNull(OverlandingCamera.decode("1,2"))
        assertNull(OverlandingCamera.decode("a,b,c"))
        assertEquals(18.0, OverlandingCamera.WORLD.lat, 0.0)
        assertEquals(1.15, OverlandingCamera.WORLD.zoom, 0.0)
    }

    @Test
    fun countriesAreFoundByAlpha3IgnoringCase() {
        assertEquals("France", data.country("fra")?.name)
        assertNull(data.country("XXX"))
        assertNull(data.country(null))
    }

    @Test
    fun borderTitleJoinsTheLocalizedCountryNames() {
        val border = data.borders.single()
        assertEquals("France / Spain", data.borderTitle(border, "en", "Border"))
        assertEquals("France / Espagne", data.borderTitle(border, "fr", "Border"))
        val unknown = CountryBorder(documentId = "q", name = "", country1 = "AAA", country2 = "BBB")
        assertEquals("Border", data.borderTitle(unknown, "en", "Border"))
    }

    @Test
    fun bordersOfACountryTouchEitherSide() {
        assertEquals(1, data.bordersOf(data.countries[0]).size)
        assertEquals(1, data.bordersOf(data.countries[1]).size)
    }

    @Test
    fun searchMatchesNamesCodesGeohashesAndSortsByTitle() {
        fun titles(q: String) = OverlandSearch.search(q, data, "en", { "type" }, "Post").map { it.title }
        assertEquals(listOf("France"), titles("fra").filter { it == "France" })
        assertEquals(listOf("Hendaye"), titles("ezz"))
        assertEquals(listOf("Spain"), titles("ES").filter { it == "Spain" })
        assertEquals(listOf("Wakhan"), titles("wakh"))
        assertEquals(emptyList<String>(), titles("   "))
        assertEquals(titles("a").sorted(), titles("a").sortedWith(ch.overlandmap.map.model.NaturalOrder))
    }

    @Test
    fun zoneResultsCarryTheirTypeLabel() {
        val result = OverlandSearch.search("wakh", data, "en", { "Permit required" }, "Post").single()
        assertEquals("Permit required", result.subtitle)
        assertEquals(OverlandingSelection.OfZone("z1"), result.selection)
    }
}
