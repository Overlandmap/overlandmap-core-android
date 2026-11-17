package ch.overlandmap.map.ui.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/** A flag of the trip being edited, at a coordinate of the map. */
internal data class PlanFlagItem(
    val id: String,
    val coordinate: LatLng,
    /** Zero-based position in the trip. */
    val index: Int,
    val color: Color,
)

private val FlagGreen = Color(0xFF22C55E)
private val FlagRed = Color(0xFFEF4444)
private val FlagInk = Color(0xFF1F1F1F)

/** First flag green, last red, the others white (as on iOS and the web map). */
internal fun flagColor(index: Int, count: Int): Color = when (index) {
    0 -> FlagGreen
    count - 1 -> FlagRed
    else -> Color.White
}

// The flag is drawn 32 × 38 dp; the touch area is a little larger. The foot of the pole sits on the
// flag's coordinate.
private val TouchWidth = 40.dp
private val TouchHeight = 44.dp
private val DrawX = 4.dp
private val DrawY = 2.dp
private val FootX = 9.dp // DrawX + the pole's centre
private val FootY = 38.dp // DrawY + the pole's bottom

private const val HOLD_MILLIS = 500L

private enum class TouchOutcome { RELEASED, DRAGGING }

/**
 * The flags of a trip, laid over the map and positioned from the map's projection. Only flags of a
 * trip being edited react to touches.
 *
 * One gesture tells the two intents apart: a press held for half a second without moving asks to
 * delete the flag ([onLongPress]); moving first drags it to re-route ([onDragChange] while moving,
 * [onDrop] on release). After a long press the finger's movement is ignored.
 *
 * [cameraTick] changes whenever the camera moves, so the flags are projected again. It is a
 * [State] read here, so that only this overlay recomposes while the camera moves.
 */
@Composable
internal fun PlanFlagsOverlay(
    map: MapLibreMap?,
    cameraTick: State<Int>,
    flags: List<PlanFlagItem>,
    editable: Boolean,
    onLongPress: (PlanFlagItem) -> Unit,
    onDragChange: (PlanFlagItem, Offset) -> Unit,
    onDrop: (PlanFlagItem, Offset) -> Unit,
) {
    val readyMap = map ?: return
    // Reading the tick makes this recompose, and so project again, while the camera moves.
    if (cameraTick.value < 0) return
    val footX = with(LocalDensity.current) { FootX.roundToPx() }
    val footY = with(LocalDensity.current) { FootY.roundToPx() }
    Box(Modifier.fillMaxSize()) {
        for (flag in flags) {
            key(flag.id) {
                val screen = readyMap.projection.toScreenLocation(flag.coordinate)
                PlanFlagMarker(
                    number = flag.index + 1,
                    color = flag.color,
                    editable = editable,
                    modifier = Modifier.offset { IntOffset(screen.x.roundToInt() - footX, screen.y.roundToInt() - footY) },
                    onLongPress = { onLongPress(flag) },
                    onDragChange = { onDragChange(flag, it) },
                    onDrop = { onDrop(flag, it) },
                )
            }
        }
    }
}

@Composable
private fun PlanFlagMarker(
    number: Int,
    color: Color,
    editable: Boolean,
    modifier: Modifier,
    onLongPress: () -> Unit,
    onDragChange: (Offset) -> Unit,
    onDrop: (Offset) -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    val haptic = LocalHapticFeedback.current
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentDragChange by rememberUpdatedState(onDragChange)
    val currentDrop by rememberUpdatedState(onDrop)

    // The touch area stays where the flag is; only the drawing follows the finger, so the
    // translation read from the pointer is not distorted by the movement.
    val touch = if (!editable) Modifier else Modifier.pointerInput(Unit) {
        val slop = 8.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val outcome = withTimeoutOrNull(HOLD_MILLIS) {
                var result: TouchOutcome? = null
                while (result == null) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    result = when {
                        change == null || !change.pressed -> TouchOutcome.RELEASED
                        (change.position - down.position).getDistance() > slop -> TouchOutcome.DRAGGING
                        else -> null
                    }
                }
                result
            }
            when (outcome) {
                null -> {
                    // Held without moving: ask to delete, then ignore the finger until it lifts.
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentLongPress()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
                TouchOutcome.RELEASED -> Unit
                TouchOutcome.DRAGGING -> {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        val translation = change.position - down.position
                        if (!change.pressed) {
                            drag = Offset.Zero
                            currentDrop(translation)
                            break
                        }
                        drag = translation
                        currentDragChange(translation)
                    }
                    drag = Offset.Zero
                }
            }
        }
    }

    Box(modifier.size(TouchWidth, TouchHeight).then(touch)) {
        Box(
            Modifier
                .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                .offset(DrawX, DrawY)
                .size(32.dp, 38.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = 1.dp.toPx()
                drawRect(FlagInk, topLeft = Offset(3.5f * unit, 3f * unit), size = Size(3f * unit, 33f * unit))
                val cloth = Path().apply {
                    // A rectangle with a notch on its free side.
                    moveTo(6f * unit, 3f * unit)
                    lineTo(29f * unit, 3f * unit)
                    lineTo(29f * unit - 23f * unit * 0.26f, 12f * unit)
                    lineTo(29f * unit, 21f * unit)
                    lineTo(6f * unit, 21f * unit)
                    close()
                }
                drawPath(cloth, color)
                drawPath(cloth, FlagInk, style = Stroke(width = 1.5f * unit))
            }
            Box(
                Modifier.offset(6.dp, 3.dp).size(17.dp, 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$number",
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
