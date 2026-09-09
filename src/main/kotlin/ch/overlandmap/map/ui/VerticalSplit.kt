package ch.overlandmap.map.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import kotlinx.coroutines.launch

private const val MIN_FRACTION = 0.15f
private const val MAX_FRACTION = 0.85f
private const val MAXIMIZED_TOP = 0.92f
private const val MAXIMIZED_BOTTOM = 0.08f

/**
 * Two panes separated by a draggable handle, so the user can give more room
 * to either one. The split follows the device orientation: stacked
 * top/bottom in portrait, side by side in landscape — where [top] becomes the
 * left pane and [bottom] the right.
 *
 * A single tap on the divider maximizes whichever half is larger (or restores
 * the previous ratio if already maximized). The last used ratio is persisted
 * in DataStore under [splitKey] (when non-null) and restored on next open.
 */
@Composable
fun VerticalSplit(
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialTopFraction: Float = 0.45f,
    splitKey: String? = null,
    collapseBottom: Boolean = false,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()

    // Load the persisted fraction (or use the default).
    val persistedFraction = remember(splitKey) {
        splitKey?.let { app.userPreferences.splitFractionNow(it) } ?: initialTopFraction
    }

    var fraction by rememberSaveable { mutableFloatStateOf(persistedFraction) }
    // The ratio to restore when un-maximizing.
    var savedFraction by rememberSaveable { mutableFloatStateOf(persistedFraction) }
    var maximized by rememberSaveable { mutableStateOf(false) }

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Persist fraction changes.
    LaunchedEffect(fraction) {
        if (!maximized && splitKey != null) {
            app.userPreferences.setSplitFraction(splitKey, fraction)
        }
    }

    fun onDrag(deltaPx: Float, totalPx: Float) {
        maximized = false
        fraction = (fraction + deltaPx / totalPx).coerceIn(MIN_FRACTION, MAX_FRACTION)
    }

    fun onTap() {
        if (maximized) {
            // Restore
            fraction = savedFraction
            maximized = false
            splitKey?.let { key -> scope.launch { app.userPreferences.setSplitFraction(key, fraction) } }
        } else {
            // Maximize whichever half is larger
            savedFraction = fraction
            fraction = if (fraction >= 0.5f) MAXIMIZED_TOP else MAXIMIZED_BOTTOM
            maximized = true
        }
    }

    // Move the same panes between the Row (landscape) and Column (portrait)
    // layouts so their state — open dialogs, scroll position, the map view —
    // survives a rotation instead of being torn down and rebuilt at the new
    // call site. (Requires the Activity to handle orientation config changes.)
    val currentTop by rememberUpdatedState(top)
    val currentBottom by rememberUpdatedState(bottom)
    val topPane = remember { movableContentOf { currentTop() } }
    val bottomPane = remember { movableContentOf { currentBottom() } }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (collapseBottom) {
            // Full-screen top pane: the same movable content, so the map view
            // isn't torn down and rebuilt on toggle.
            Box(modifier = Modifier.fillMaxSize()) { topPane() }
        } else if (landscape) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxHeight().weight(fraction)) { topPane() }
                SplitHandle(
                    orientation = Orientation.Horizontal,
                    onDrag = { deltaPx -> onDrag(deltaPx, totalWidthPx) },
                    onTap = ::onTap,
                )
                Box(modifier = Modifier.fillMaxHeight().weight(1f - fraction)) { bottomPane() }
            }
        } else {
            val totalHeightPx = constraints.maxHeight.toFloat()
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().weight(fraction)) { topPane() }
                SplitHandle(
                    orientation = Orientation.Vertical,
                    onDrag = { deltaPx -> onDrag(deltaPx, totalHeightPx) },
                    onTap = ::onTap,
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f - fraction)) { bottomPane() }
            }
        }
    }
}

/**
 * The drag bar between the panes. [orientation] is the drag axis: vertical
 * for a top/bottom split, horizontal for a left/right one. Tapping it calls
 * [onTap] to maximize/restore.
 */
@Composable
private fun SplitHandle(orientation: Orientation, onDrag: (Float) -> Unit, onTap: () -> Unit) {
    val vertical = orientation == Orientation.Vertical
    Box(
        modifier = Modifier
            .then(
                if (vertical) Modifier.fillMaxWidth().height(32.dp)
                else Modifier.fillMaxHeight().width(32.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
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
