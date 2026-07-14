package ch.overlandmap.map.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

private const val MIN_FRACTION = 0.15f
private const val MAX_FRACTION = 0.85f

/**
 * Two panes separated by a draggable handle, so the user can give more room
 * to either one. The split follows the device orientation: stacked
 * top/bottom in portrait, side by side in landscape — where [top] becomes the
 * left pane and [bottom] the right. The split fraction survives configuration
 * changes.
 */
@Composable
fun VerticalSplit(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialTopFraction: Float = 0.45f,
) {
    var fraction by rememberSaveable { mutableFloatStateOf(initialTopFraction) }
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (landscape) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxHeight().weight(fraction)) { top() }
                SplitHandle(
                    orientation = Orientation.Horizontal,
                    onDrag = { deltaPx ->
                        fraction = (fraction + deltaPx / totalWidthPx)
                            .coerceIn(MIN_FRACTION, MAX_FRACTION)
                    },
                )
                Box(modifier = Modifier.fillMaxHeight().weight(1f - fraction)) { bottom() }
            }
        } else {
            val totalHeightPx = constraints.maxHeight.toFloat()
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().weight(fraction)) { top() }
                SplitHandle(
                    orientation = Orientation.Vertical,
                    onDrag = { deltaPx ->
                        fraction = (fraction + deltaPx / totalHeightPx)
                            .coerceIn(MIN_FRACTION, MAX_FRACTION)
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f - fraction)) { bottom() }
            }
        }
    }
}

/**
 * The drag bar between the panes. [orientation] is the drag axis: vertical
 * for a top/bottom split, horizontal for a left/right one.
 */
@Composable
private fun SplitHandle(orientation: Orientation, onDrag: (Float) -> Unit) {
    val vertical = orientation == Orientation.Vertical
    Box(
        modifier = Modifier
            .then(
                if (vertical) Modifier.fillMaxWidth().height(20.dp)
                else Modifier.fillMaxHeight().width(20.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                orientation = orientation,
                state = rememberDraggableState(onDrag),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (vertical) Modifier.size(width = 36.dp, height = 4.dp)
                    else Modifier.size(width = 4.dp, height = 36.dp)
                )
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}
