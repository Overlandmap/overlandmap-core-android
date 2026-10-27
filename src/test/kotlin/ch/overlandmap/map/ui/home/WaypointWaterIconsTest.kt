package ch.overlandmap.map.ui.home

import ch.overlandmap.map.data.local.toRow
import ch.overlandmap.map.data.local.toModel
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.model.WaypointFlags
import ch.overlandmap.map.ui.WaypointMarkers
import org.junit.Assert.*
import org.junit.Test

class WaypointWaterIconsTest {
    @Test fun swimmingFieldSurvivesImportAndOfflineStorage() {
        val data = mapOf("name" to "Lake", "isSwimming" to true)
        val waypoint = Waypoint.fromFirestore("w", data).toRow().toModel()
        val step = ItineraryStep.fromFirestore("s", "i", data).toRow().toModel()
        assertTrue(waypoint.isSwimming)
        assertTrue(step.isSwimming)
        assertTrue(WaypointFlags.from(data).isSwimming)
        assertEquals("swimming", WaypointMarkers.makiFor(waypoint))
        assertEquals("swimming", WaypointMarkers.makiFor(step))
    }

    @Test fun waterfallGetsDedicatedIconAndExistingPriorityIsRetained() {
        val waterfall = Waypoint("w", "p", name = "Falls", isWaterfall = true)
        assertEquals("waterfall", WaypointMarkers.makiFor(waterfall))
        assertEquals("camera", WaypointMarkers.makiFor(waterfall.copy(isViewpoint = true)))
        assertEquals(WaypointMarkers.DEFAULT, WaypointMarkers.makiFor(waterfall.copy(isWaterfall = false)))
    }
}
