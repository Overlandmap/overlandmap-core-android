package ch.overlandmap.map.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.map.BaseMapStyle
import ch.overlandmap.map.map.MapStyleOptions
import ch.overlandmap.map.map.MapboxStyleKind

/**
 * The itinerary map's style picker: a floating layers button that opens a menu
 * of the base styles (offline light/detailed, Mapbox, satellite) plus a
 * "Customize" entry opening a dialog for each style's options. Mapbox and
 * satellite are disabled until a Mapbox token is available.
 */
@Composable
fun MapStyleMenu(
    options: MapStyleOptions,
    hasMapboxToken: Boolean,
    onChange: (MapStyleOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var customizeOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            onClick = { menuOpen = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shadowElevation = 3.dp,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                Icons.Filled.Layers,
                contentDescription = stringResource(R.string.map_style),
                modifier = Modifier.padding(10.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            StyleItem(
                stringResource(R.string.style_offline_light),
                selected = options.base == BaseMapStyle.OFFLINE_LIGHT,
            ) { onChange(options.copy(base = BaseMapStyle.OFFLINE_LIGHT)); menuOpen = false }
            StyleItem(
                stringResource(R.string.style_offline_detailed),
                selected = options.base == BaseMapStyle.OFFLINE_DETAILED,
            ) { onChange(options.copy(base = BaseMapStyle.OFFLINE_DETAILED)); menuOpen = false }
            StyleItem(
                stringResource(R.string.style_mapbox),
                selected = options.base == BaseMapStyle.MAPBOX,
                enabled = hasMapboxToken,
            ) { onChange(options.copy(base = BaseMapStyle.MAPBOX)); menuOpen = false }
            StyleItem(
                stringResource(R.string.style_satellite),
                selected = options.base == BaseMapStyle.SATELLITE,
                enabled = hasMapboxToken,
            ) { onChange(options.copy(base = BaseMapStyle.SATELLITE)); menuOpen = false }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.customize)) },
                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                onClick = { menuOpen = false; customizeOpen = true },
            )
        }
    }

    if (customizeOpen) {
        CustomizeDialog(options, onChange = onChange, onDismiss = { customizeOpen = false })
    }
}

@Composable
private fun StyleItem(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        leadingIcon = {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        onClick = onClick,
    )
}

/** Per-style customization: offline hillshade/contour, Mapbox kind, satellite roads. */
@Composable
private fun CustomizeDialog(
    options: MapStyleOptions,
    onChange: (MapStyleOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customize)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle(stringResource(R.string.customize_offline))
                SwitchRow(stringResource(R.string.hillshade), options.hillshade) {
                    onChange(options.copy(hillshade = it))
                }
                SwitchRow(stringResource(R.string.contour_lines), options.contour) {
                    onChange(options.copy(contour = it))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.customize_mapbox))
                MapboxStyleKind.entries.forEach { kind ->
                    RadioRow(kind.displayName, options.mapboxKind == kind) {
                        onChange(options.copy(mapboxKind = kind))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SectionTitle(stringResource(R.string.customize_satellite))
                SwitchRow(stringResource(R.string.show_roads), options.satelliteRoads) {
                    onChange(options.copy(satelliteRoads = it))
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}
