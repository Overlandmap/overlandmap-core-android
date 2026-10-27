package ch.overlandmap.map.ui.home

import org.junit.Assert.*
import org.junit.Test

class StepHitTestingTest {
    @Test fun tappedStepWinsOverNeighborRegardlessOfDrawOrder() {
        val targets = listOf(StepTapTarget(12, 115.0, 100.0), StepTapTarget(11, 100.0, 100.0))
        assertEquals(11, nearestStepToTap(101.0, 100.0, targets))
        assertEquals(11, nearestStepToTap(101.0, 100.0, targets.reversed()))
        assertEquals(12, nearestStepToTap(114.0, 100.0, targets))
    }

    @Test fun selectedLayerDuplicatesDoNotBiasSelection() {
        val selected = StepTapTarget(10, 95.0, 110.0)
        assertEquals(11, nearestStepToTap(100.0, 100.0,
            listOf(selected, selected, StepTapTarget(11, 100.0, 100.0))))
    }

    @Test fun equidistantStepsHaveStableSelectionAndEmptyQueryHasNone() {
        val targets = listOf(StepTapTarget(12, 110.0, 100.0), StepTapTarget(11, 90.0, 100.0))
        assertEquals(nearestStepToTap(100.0, 100.0, targets), nearestStepToTap(100.0, 100.0, targets.reversed()))
        assertNull(nearestStepToTap(100.0, 100.0, emptyList()))
    }
}
