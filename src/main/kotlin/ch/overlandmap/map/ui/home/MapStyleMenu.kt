package ch.overlandmap.map.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.overlandmap.map.R
import ch.overlandmap.map.map.BaseMapStyle
import ch.overlandmap.map.map.MapStyleOptions
import ch.overlandmap.map.map.MapboxStyleKind
import ch.overlandmap.map.model.WaypointCategory

/**
 * The itinerary map's style picker — a floating layers button opening a bottom
 * sheet, matching the iOS design: a row of four thumbnail swatches (offline
 * light/detailed, Mapbox, satellite); style-specific option pills (hillshade +
 * contour for offline, roads for satellite, the four substyles for Mapbox); and
 * a "Waypoints" row of icon chips toggling which non-itinerary waypoint
 * categories are shown on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStyleMenu(
    options: MapStyleOptions,
    hasMapboxToken: Boolean,
    onChange: (MapStyleOptions) -> Unit,
    waypointFilter: Set<WaypointCategory>,
    onWaypointFilterChange: (Set<WaypointCategory>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Box(modifier = modifier) {
        Surface(
            onClick = { sheetOpen = true },
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
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = sheetState) {
            StyleSheetContent(
                options = options,
                hasMapboxToken = hasMapboxToken,
                onChange = onChange,
                waypointFilter = waypointFilter,
                onWaypointFilterChange = onWaypointFilterChange,
            )
        }
    }
}

@Composable
private fun StyleSheetContent(
    options: MapStyleOptions,
    hasMapboxToken: Boolean,
    onChange: (MapStyleOptions) -> Unit,
    waypointFilter: Set<WaypointCategory>,
    onWaypointFilterChange: (Set<WaypointCategory>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        // Row 1: the four base-style thumbnail swatches. The row adapts to the
        // screen width — the swatches shrink so all four fit without scrolling
        // on narrow screens, and are capped at [MAX_SWATCH] and centered on wide
        // ones.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val available = maxWidth - HORIZONTAL_PADDING * 2 - SWATCH_SPACING * 3
            val cell = (available / 4).coerceIn(MIN_SWATCH, MAX_SWATCH)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PADDING),
                horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING, Alignment.CenterHorizontally),
            ) {
                StyleSwatch(
                    label = stringResource(R.string.style_offline_light),
                    thumb = R.drawable.style_thumb_light,
                    size = cell,
                    selected = options.base == BaseMapStyle.OFFLINE_LIGHT,
                    onClick = { onChange(options.copy(base = BaseMapStyle.OFFLINE_LIGHT)) },
                )
                StyleSwatch(
                    label = stringResource(R.string.style_offline_detailed),
                    thumb = R.drawable.style_thumb_detailed,
                    size = cell,
                    selected = options.base == BaseMapStyle.OFFLINE_DETAILED,
                    onClick = { onChange(options.copy(base = BaseMapStyle.OFFLINE_DETAILED)) },
                )
                StyleSwatch(
                    label = stringResource(R.string.style_mapbox),
                    thumb = R.drawable.style_thumb_mapbox,
                    size = cell,
                    selected = options.base == BaseMapStyle.MAPBOX,
                    enabled = hasMapboxToken,
                    onClick = { onChange(options.copy(base = BaseMapStyle.MAPBOX)) },
                )
                StyleSwatch(
                    label = stringResource(R.string.style_satellite),
                    thumb = R.drawable.style_thumb_satellite,
                    size = cell,
                    selected = options.base == BaseMapStyle.SATELLITE,
                    enabled = hasMapboxToken,
                    onClick = { onChange(options.copy(base = BaseMapStyle.SATELLITE)) },
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // Row 2: options for the selected style.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (options.base) {
                BaseMapStyle.OFFLINE_LIGHT, BaseMapStyle.OFFLINE_DETAILED -> {
                    PillToggle(stringResource(R.string.hillshade), options.hillshade) {
                        onChange(options.copy(hillshade = !options.hillshade))
                    }
                    PillToggle(stringResource(R.string.contour), options.contour) {
                        onChange(options.copy(contour = !options.contour))
                    }
                }
                BaseMapStyle.SATELLITE -> {
                    PillToggle(stringResource(R.string.roads), options.satelliteRoads) {
                        onChange(options.copy(satelliteRoads = !options.satelliteRoads))
                    }
                }
                BaseMapStyle.MAPBOX -> {
                    // Only the four primary substyles are offered (the design's
                    // Streets / Outdoor / Light / Dark).
                    listOf(
                        MapboxStyleKind.STREETS,
                        MapboxStyleKind.OUTDOORS,
                        MapboxStyleKind.LIGHT,
                        MapboxStyleKind.DARK,
                    ).forEach { kind ->
                        PillToggle(kind.displayName, options.mapboxKind == kind) {
                            onChange(options.copy(mapboxKind = kind))
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // Row 3: the waypoint-category filter.
        Text(
            stringResource(R.string.waypoints),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WaypointChip(
                stringResource(R.string.wp_itinerary),
                R.drawable.marker_stroked,
                WaypointCategory.ITINERARY in waypointFilter,
            ) { toggle(waypointFilter, WaypointCategory.ITINERARY, onWaypointFilterChange) }
            WaypointChip(
                stringResource(R.string.wp_mountain_pass),
                R.drawable.mountain_pass,
                WaypointCategory.MOUNTAIN_PASS in waypointFilter,
            ) { toggle(waypointFilter, WaypointCategory.MOUNTAIN_PASS, onWaypointFilterChange) }
            WaypointChip(
                stringResource(R.string.wp_fuel),
                R.drawable.fuel_,
                WaypointCategory.FUEL in waypointFilter,
            ) { toggle(waypointFilter, WaypointCategory.FUEL, onWaypointFilterChange) }
            WaypointChip(
                stringResource(R.string.wp_police_checkpoint),
                R.drawable.checkpoint,
                WaypointCategory.POLICE_CHECKPOINT in waypointFilter,
            ) { toggle(waypointFilter, WaypointCategory.POLICE_CHECKPOINT, onWaypointFilterChange) }
            WaypointChip(
                stringResource(R.string.wp_viewpoint),
                R.drawable.camera,
                WaypointCategory.VIEWPOINT in waypointFilter,
            ) { toggle(waypointFilter, WaypointCategory.VIEWPOINT, onWaypointFilterChange) }
        }
    }
}

/** Flips [category] in [current] and reports the new set. */
private fun toggle(
    current: Set<WaypointCategory>,
    category: WaypointCategory,
    onChange: (Set<WaypointCategory>) -> Unit,
) {
    onChange(if (category in current) current - category else current + category)
}

@Composable
private fun StyleSwatch(
    label: String,
    thumb: Int,
    size: Dp,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Image(
            painter = painterResource(thumb),
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(10.dp))
                    else Modifier,
                ),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Layout metrics for the responsive style-swatch row. */
private val HORIZONTAL_PADDING = 16.dp
private val SWATCH_SPACING = 12.dp
private val MAX_SWATCH = 88.dp
private val MIN_SWATCH = 44.dp

@Composable
private fun PillToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun WaypointChip(label: String, iconRes: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    // No fixed width: each chip sizes to its own content (icon + label), so a
    // short label like "Fuel" stays compact and only "Police checkpoint" is
    // wide, rather than every chip matching the widest one.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .alpha(if (selected) 1f else 0.5f),
    ) {
        // The waypoint marker icons are raster PNGs (drawable-nodpi), so they
        // must be drawn with painterResource — vectorResource only parses XML
        // vectors and throws on a PNG.
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
