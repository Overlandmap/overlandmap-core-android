package ch.overlandmap.map.ui.home

internal data class StepTapTarget(val stepId: Int, val x: Double, val y: Double)

/** Rendered query results can contain neighbors and duplicate selected-layer features. */
internal fun nearestStepToTap(x: Double, y: Double, targets: List<StepTapTarget>): Int? =
    targets.minWithOrNull(compareBy<StepTapTarget> {
        val dx = it.x - x
        val dy = it.y - y
        dx * dx + dy * dy
    }.thenBy { it.stepId })?.stepId
