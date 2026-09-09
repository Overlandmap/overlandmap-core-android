package ch.overlandmap.map.ui.home

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.GpsFormat
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.WaypointMarkers
import ch.overlandmap.map.ui.currentLanguage

private val CheckInGreen = Color(0xFF2E7D32)
private val CorrectionRed = Color(0xFFE53935)

/**
 * Bottom sheet shown when the user taps a waypoint on the map. Displays:
 * - Title: waypoint name
 * - Subtitle: GPS coordinates
 * - Maki icon (type indicator)
 * - Action row: zoom (black), check-in (green), correction (red), share (black)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointBottomSheet(
    waypoint: Waypoint,
    onDismiss: () -> Unit,
    onZoom: () -> Unit,
    onCheckIn: () -> Unit,
    onCorrection: () -> Unit,
) {
    val lang = currentLanguage()
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val gpsFormat by app.userPreferences.gpsFormat.collectAsState(initial = GpsFormat.DD)
    val useFeet by app.userPreferences.useFeet.collectAsState(initial = false)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showShareDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header: name/coordinates on the left, maki icon on the right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        waypoint.name(lang),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val lat = waypoint.lat
                    val lon = waypoint.lon
                    if (lat != null && lon != null) {
                        val coords = buildString {
                            append(UserPreferences.formatCoordinates(lat, lon, gpsFormat))
                            waypoint.ele?.let { ele ->
                                append(", alt. ")
                                append(UserPreferences.formatElevationM(ele, useFeet))
                            }
                        }
                        Text(
                            coords,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Image(
                    painter = painterResource(WaypointMarkers.drawableFor(waypoint)),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }

            // Description below
            waypoint.description(lang)?.let { desc ->
                Spacer(Modifier.height(12.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDismiss(); onZoom() }) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom_in))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDismiss(); onCheckIn() }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.check_in), tint = CheckInGreen)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDismiss(); onCorrection() }) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.corrections), tint = CorrectionRed)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showShareDialog = true }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }

    if (showShareDialog) {
        ShareWaypointDialog(
            waypoint = waypoint,
            onDismiss = { showShareDialog = false; onDismiss() },
        )
    }
}

@Composable
private fun ShareWaypointDialog(waypoint: Waypoint, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lang = currentLanguage()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share)) },
        text = {
            Column {
                // Google Maps
                TextButton(onClick = {
                    val lat = waypoint.lat ?: return@TextButton
                    val lon = waypoint.lon ?: return@TextButton
                    val uri = "geo:$lat,$lon?q=$lat,$lon(${waypoint.name(lang)})".toUri()
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        // Fallback: open in browser
                        val webUri = "https://www.google.com/maps/search/?api=1&query=$lat,$lon".toUri()
                        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                    }
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_google_maps))
                }
                // OsmAnd (only if installed)
                val osmandInstalled = remember {
                    listOf("net.osmand.plus", "net.osmand").any { pkg ->
                        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
                    }
                }
                if (osmandInstalled) {
                    TextButton(onClick = {
                        val lat = waypoint.lat ?: return@TextButton
                        val lon = waypoint.lon ?: return@TextButton
                        val name = waypoint.name(lang)
                        val uri = "osmand.navigation://show_map?lat=$lat&lon=$lon&name=${android.net.Uri.encode(name)}".toUri()
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        runCatching { context.startActivity(intent) }
                        onDismiss()
                    }) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.share_osmand))
                    }
                }
                // GPX
                TextButton(onClick = {
                    val lat = waypoint.lat ?: return@TextButton
                    val lon = waypoint.lon ?: return@TextButton
                    val gpx = buildString {
                        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                        append("<gpx version=\"1.1\" creator=\"OverlandMap\">\n")
                        append("  <wpt lat=\"$lat\" lon=\"$lon\">\n")
                        append("    <name>${waypoint.name(lang).xmlEscape()}</name>\n")
                        waypoint.ele?.let { append("    <ele>$it</ele>\n") }
                        append("  </wpt>\n")
                        append("</gpx>\n")
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_TEXT, gpx)
                        putExtra(Intent.EXTRA_SUBJECT, "${waypoint.name(lang)}.gpx")
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Terrain, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_as_gpx))
                }
                // Link
                TextButton(onClick = {
                    val url = "https://overlandmap.ch/waypoint/${waypoint.documentId}"
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_link))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun String.xmlEscape(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
