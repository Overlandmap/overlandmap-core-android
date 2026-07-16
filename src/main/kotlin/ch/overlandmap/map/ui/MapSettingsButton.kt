package ch.overlandmap.map.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R

/**
 * The shared background for the action buttons that ride on a track pack's map
 * (Purchase, Download sample, …). A single plain light-gray so they all look
 * the same — including the disabled "Purchase not available", which keeps this
 * solid gray instead of going transparent.
 */
@Composable
internal fun mapActionButtonColors(): ButtonColors {
    val gray = Color(0xFFE0E0E0)
    return ButtonDefaults.buttonColors(
        containerColor = gray,
        contentColor = Color(0xFF1C1B1F),
        disabledContainerColor = gray,
        disabledContentColor = Color(0xFF6E6E6E),
    )
}

/**
 * A circular Settings button floated over the map. Single-track-pack root
 * screens drop the top app bar (it's the root — no title or back needed), so
 * this keeps Settings reachable from the map's top-right corner.
 */
@Composable
internal fun MapSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shadowElevation = 3.dp,
        modifier = modifier.size(44.dp),
    ) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.tab_settings),
            modifier = Modifier.padding(10.dp),
        )
    }
}
