package ch.overlandmap.map.ui.home

import ch.overlandmap.map.data.local.toModel
import ch.overlandmap.map.data.local.toRow
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryDifficulty
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.OpenKind
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.units.UnitFormat
import ch.overlandmap.map.units.UnitFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SidebarTableTest {

    private val labels = object : SidebarLabels {
        override fun openKind(kind: OpenKind) = "kind:${kind.raw}"
        override fun difficulty(difficulty: ItineraryDifficulty) = "diff:${difficulty.raw}"
        override fun offroad(percent: Int?) = "off:$percent"
    }
    private val units = UnitFormatter(UnitFormat.METRIC, Locale.US)

    private fun sidebar(vararg extra: Pair<String, Any?>) = Sidebar.fromFirestore(
        "sb1",
        mapOf("trackPackId" to "p1", "name" to "Passes") + extra,
    )

    private fun wp(id: String, name: String, ele: Int?, vararg extra: Pair<String, Any?>) = Waypoint.fromFirestore(
        id,
        mapOf<String, Any?>("trackPackId" to "p1", "name" to name, "ele" to ele) + extra,
    )

    private val columns = listOf(
        mapOf("type" to "text", "field" to "name", "title" to mapOf("en" to "Name", "fr" to "Nom")),
        mapOf("type" to "elevation", "title" to mapOf("en" to "Altitude")),
        mapOf("type" to "text", "field" to "country", "title" to mapOf("en" to "Country")),
    )

    @Test
    fun sidebarExposesItsTableDefinition() {
        val sb = sidebar(
            "hasMap" to true,
            "tableColumns" to columns,
            "tableNames" to """{"en":"High passes","fr":"Cols"}""",
            "tableEntity" to "Waypoint",
            "tableFilter" to "isMountainPass",
            "tableSort" to mapOf("field" to "ele", "direction" to "desc"),
        )
        assertTrue(sb.hasMap)
        assertTrue(sb.hasTable)
        assertEquals(3, sb.tableColumns!!.size)
        assertEquals("High passes", sb.tableNames!!["en"])
        assertEquals("isMountainPass", sb.tableFilter)
        assertEquals("ele" to true, sb.tableSort)
        assertEquals(listOf("Nom", "", ""), SidebarTable.columnTitles(sb, "fr"))
        assertFalse(sidebar().hasTable)
        assertFalse(sidebar().hasMap)
        assertNull(sidebar().tableSort)
    }

    @Test
    fun filteredWaypointsAreSortedAndFormatted() {
        val sb = sidebar("tableColumns" to columns, "tableFilter" to "isMountainPass", "tableSort" to mapOf("field" to "ele", "direction" to "desc"))
        val waypoints = listOf(
            wp("a", "Low pass", 3000, "isMountainPass" to true, "country" to "Tajikistan"),
            wp("b", "High pass", 4600, "isMountainPass" to true, "country" to "Kyrgyzstan"),
            wp("c", "Hotel", 2000, "hasHotel" to true),
        )
        val rows = SidebarTable.build(sb, waypoints, emptyList(), emptyList(), units, "en", labels)
        assertEquals(listOf("b", "a"), rows.map { it.id })
        assertEquals("High pass", rows[0].cells[0].text)
        assertEquals(CellLink.OwnEntity, rows[0].cells[0].link)
        assertEquals("4,600 m", rows[0].cells[1].text)
        assertEquals("Kyrgyzstan", rows[0].cells[2].text)
        assertEquals(4600, rows[0].elevation)
    }

    @Test
    fun withoutAFilterRowsAreThoseMarkedWithTheSidebar() {
        val sb = sidebar("tableColumns" to columns)
        val waypoints = listOf(
            wp("a", "Mine", 1, "sidebarDocId" to "sb1"),
            wp("b", "Other sidebar", 1, "sidebarDocId" to "sb2"),
            wp("c", "Unmarked", 1),
        )
        val rows = SidebarTable.build(sb, waypoints, emptyList(), emptyList(), units, "en", labels)
        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test
    fun waypointEntityAlsoTakesMatchingSteps() {
        val sb = sidebar("tableColumns" to columns, "tableFilter" to "hasFuel")
        val step = ItineraryStep.fromFirestore("s1", "i1", mapOf("trackPackId" to "p1", "id" to 4, "name" to "Fuel stop", "hasFuel" to true))
        val rows = SidebarTable.build(sb, listOf(wp("a", "Pump", 10, "hasFuel" to true)), listOf(step), emptyList(), units, "en", labels)
        assertEquals(listOf("a", "s1"), rows.map { it.id })
        // "ItineraryStep" restricts the table to steps.
        val onlySteps = SidebarTable.build(
            sidebar("tableColumns" to columns, "tableFilter" to "hasFuel", "tableEntity" to "ItineraryStep"),
            listOf(wp("a", "Pump", 10, "hasFuel" to true)), listOf(step), emptyList(), units, "en", labels,
        )
        assertEquals(listOf("s1"), onlySteps.map { it.id })
    }

    @Test
    fun itineraryRowsCarryDifficultyOffroadAndALinkedBounds() {
        val sb = sidebar(
            "tableEntity" to "Itinerary",
            "tableColumns" to listOf(
                mapOf("type" to "itineraryId", "title" to mapOf("en" to "Id")),
                mapOf("type" to "difficulty", "title" to mapOf("en" to "Difficulty")),
                mapOf("type" to "offroadPercent", "title" to mapOf("en" to "Road")),
                mapOf("type" to "distance", "field" to "km", "title" to mapOf("en" to "Distance")),
            ),
        )
        val itin = Itinerary.fromFirestore(
            "i1",
            mapOf(
                "trackPackId" to "p1", "itineraryId" to "K4", "name" to "Karakol", "difficulty" to "hard",
                "offroadPercent" to 75, "latMin" to 40.0, "latMax" to 41.0, "lonMin" to 70.0, "lonMax" to 71.0,
                "sidebarDocId" to "sb1", "km" to 120.0,
            ),
        )
        val rows = SidebarTable.build(sb, emptyList(), emptyList(), listOf(itin), units, "en", labels)
        val row = rows.single()
        assertEquals("K4", row.cells[0].text)
        assertEquals(CellLink.Itinerary("i1"), row.cells[0].link)
        assertEquals("diff:hard", row.cells[1].text)
        assertEquals(CellTone.WARNING, row.cells[1].tone)
        assertEquals("off:75", row.cells[2].text)
        assertEquals(CellTone.WARNING, row.cells[2].tone)
        assertEquals("120 km", row.cells[3].text)
        assertEquals(40.0, row.bounds!!.latMin, 0.0)
        assertEquals(itin, row.itinerary)
    }

    @Test
    fun unknownColumnTypesGiveEmptyCellsAndTranslationsResolve() {
        val sb = sidebar(
            "tableFilter" to "isMountainPass",
            "tableColumns" to listOf(
                mapOf("type" to "mystery"),
                mapOf("type" to "translated", "field" to "note", "title" to mapOf("en" to "Note")),
                mapOf("type" to "openKind", "title" to mapOf("en" to "Status")),
            ),
        )
        val w = wp("a", "Pass", 1, "isMountainPass" to true, "note" to mapOf("fr" to "Fermé", "en" to "Closed"), "openKind" to "closed")
        val row = SidebarTable.build(sb, listOf(w), emptyList(), emptyList(), units, "fr", labels).single()
        assertEquals("", row.cells[0].text)
        assertEquals("Fermé", row.cells[1].text)
        assertEquals("kind:closed", row.cells[2].text)
        assertEquals(CellTone.BAD, row.cells[2].tone)
    }

    @Test
    fun filterAcceptsMakiNamesAndSpellings() {
        val pass = wp("a", "Pass", 1, "isMountainPass" to true)
        assertTrue(SidebarTable.flagMatches(pass, pass.extra, null, "mountain_pass"))
        assertTrue(SidebarTable.flagMatches(pass, pass.extra, "fuel", "FUEL"))
        assertFalse(SidebarTable.flagMatches(pass, pass.extra, null, "hasFuel"))
        assertFalse(SidebarTable.flagMatches(pass, pass.extra, null, "nonsense"))
    }

    @Test
    fun extrasSurviveTheRoomRows() {
        val sb = sidebar("hasMap" to true, "tableColumns" to columns, "tableFilter" to "hasFuel", "otherPhotoCaptions" to listOf("A", "B"))
        val back = sb.copy(localOtherPhotoPaths = listOf("/x/1.jpg")).toRow().toModel()
        assertTrue(back.hasMap)
        assertEquals(3, back.tableColumns!!.size)
        assertEquals("hasFuel", back.tableFilter)
        assertEquals(listOf("A", "B"), back.otherPhotoCaptions)
        assertEquals(listOf("/x/1.jpg"), back.localOtherPhotoPaths)

        val w = wp("a", "Pass", 5, "sidebarDocId" to "sb1", "country" to "Chad").toRow().toModel()
        assertEquals("sb1", w.extra["sidebarDocId"])
        assertEquals("Chad", w.extra["country"])
        val itin = Itinerary.fromFirestore("i1", mapOf("trackPackId" to "p1", "name" to "N", "editorId" to "ed", "steps" to listOf(mapOf("a" to 1))))
        val itinBack = itin.toRow().toModel()
        assertEquals("ed", itinBack.extra["editorId"])
        // Nested arrays (the export's embedded steps) are not kept.
        assertFalse(itinBack.extra.containsKey("steps"))
    }
}
