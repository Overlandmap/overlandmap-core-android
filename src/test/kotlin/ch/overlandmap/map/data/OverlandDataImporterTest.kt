package ch.overlandmap.map.data

import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.GlobalSetting
import ch.overlandmap.map.model.TravelAdvisoryLinks
import ch.overlandmap.map.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OverlandDataImporterTest {

    private val json = """
        {
          "countries": [
            {"id": "FRA", "name": "France", "iso_a2": "FR", "overlanding": 3,
             "translations": {"fr": "France"}, "bounding_box": [-5.0, 41.0, 9.0, 51.0]},
            {"documentId": "ESP", "name": "Spain"},
            {"name": "No id"}
          ],
          "borders": [
            {"id": "FRA-ESP", "name": "France / Spain", "country1": "FRA", "country2": "ESP",
             "border_posts": ["p1"]},
            {"id": "A-B", "name": "A / B", "is_open": 0}
          ],
          "border_posts": [
            {"id": "p1", "name": "Hendaye", "latitude": 43.35, "longitude": -1.77, "borderId": "FRA-ESP"}
          ],
          "zones": [
            {"id": "z1", "name": "Zone", "type": 2, "country": "FRA"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAllFourCollectionsAndSkipsItemsWithoutId() {
        val parsed = OverlandDataImporter.parse(json)
        assertEquals(listOf("FRA", "ESP"), parsed.countries.map { it.documentId })
        assertEquals(2, parsed.borders.size)
        assertEquals(1, parsed.posts.size)
        assertEquals(1, parsed.zones.size)
        assertEquals("France", parsed.countries[0].name("fr"))
        assertNotNull(parsed.countries[0].boundingBox)
        assertEquals(ZoneType.PERMIT_NEEDED, parsed.zones[0].zoneType)
        assertEquals(listOf("p1"), parsed.borders[0].borderPostIds)
        assertNotNull(parsed.posts[0].geohash)
    }

    @Test
    fun absentBorderOpenStateMeansOpen() {
        val parsed = OverlandDataImporter.parse(json)
        assertEquals(BorderOpenState.OPEN, parsed.borders[0].openState)
        assertEquals(BorderOpenState.CLOSED, parsed.borders[1].openState)
        assertEquals(BorderOpenState.OPEN, parsed.posts[0].openState)
    }

    @Test
    fun missingCollectionsParseAsEmpty() {
        val parsed = OverlandDataImporter.parse("{}")
        assertEquals(0, parsed.countries.size + parsed.borders.size + parsed.posts.size + parsed.zones.size)
    }

    @Test
    fun advisoryLinksUseOnlyTheRequestedLanguageAndSubstituteTheName() {
        val templates = mapOf(
            "gbr_en" to "https://gov.uk/foreign-travel-advice/{translation}",
            "usa_en" to "https://travel.state.gov/{Translation}.html",
            "gbr_fr" to "https://gov.uk/fr/{translation}",
            "ab_en" to "https://x/{translation}",
            "can_en" to "https://has space/{translation}",
        )
        val names = mapOf("GBR" to "United Kingdom", "USA" to "United States")
        val links = TravelAdvisoryLinks.make(templates, "en", "Chad") { names[it] }
        assertEquals(listOf("United Kingdom", "United States"), links.map { it.sourceName })
        assertEquals(
            listOf("https://gov.uk/foreign-travel-advice/chad", "https://travel.state.gov/Chad.html"),
            links.map { it.url },
        )
        assertNull(links.firstOrNull { it.sourceAlpha3 == "CAN" })
    }

    @Test
    fun globalSettingExposesOnlyStringEntriesAsTemplates() {
        val setting = GlobalSetting.fromFirestore(
            GlobalSetting.ADVISORY_URL_TEMPLATE_ID,
            mapOf("gbr_en" to "https://a/{translation}", "count" to 3),
        )
        assertEquals(mapOf("gbr_en" to "https://a/{translation}"), setting.advisoryUrlTemplates)
    }
}
