package ch.overlandmap.map.ui.tracking

import android.content.Intent
import android.text.util.Linkify
import android.widget.TextView
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.overlandmap.map.R
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.tracking.RecordedTrack
import ch.overlandmap.map.tracking.RecordedWaypoint
import ch.overlandmap.map.ui.home.CheckInButton
import ch.overlandmap.map.ui.home.CorrectionButton
import ch.overlandmap.map.ui.social.SocialTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordedWaypointSheet(track: RecordedTrack, waypoint: RecordedWaypoint, onDismiss: () -> Unit, onZoom: () -> Unit) {
    val context = LocalContext.current
    val target = SocialTarget(
        if (track.isImported) "" else waypoint.id, "", "",
        if (track.isImported) "imported_waypoint" else "waypoint",
        geohash = waypoint.geohash ?: ch.overlandmap.map.geo.Geohash.encode(waypoint.latitude, waypoint.longitude),
    )
    var confirmDelete by remember(waypoint.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row {
                Text(waypoint.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(importedWaypointDrawable(waypoint.symbol)), null, modifier = Modifier.size(32.dp))
            }
            if (waypoint.description.isNotBlank()) {
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                val linkColor = MaterialTheme.colorScheme.primary
                AndroidView(factory = { ctx -> TextView(ctx).apply { movementMethod = LinkMovementMethod.getInstance() } }, update = { view ->
                    view.text = waypoint.description
                    view.setTextColor(android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
                    view.setLinkTextColor(android.graphics.Color.argb(255, (linkColor.red * 255).toInt(), (linkColor.green * 255).toInt(), (linkColor.blue * 255).toInt()))
                    Linkify.addLinks(view, Linkify.WEB_URLS)
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onZoom) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_in)) }
                CheckInButton(target)
                CorrectionButton(target)
                IconButton(onClick = { shareRecordedTrackGpx(context, track.copy(points = emptyList(), waypoints = listOf(waypoint))) }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
            }
            if (track.isImported) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete)) },
        text = { Text(stringResource(R.string.delete_imported_waypoint_confirmation, waypoint.label)) },
        confirmButton = { TextButton(onClick = {
            (context.applicationContext as OverlandApp).trackRecorder.deleteImportedWaypoint(waypoint.id)
            confirmDelete = false
            onDismiss()
        }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordedTrackSheet(track: RecordedTrack, onDismiss: () -> Unit, onOpen: () -> Unit, onZoom: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(20.dp)) {
            Row {
                Text(track.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Route, null, tint = track.colorHex.toComposeColor())
            }
            if (track.description.isNotBlank()) Text(track.description, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 12.dp))
            IconButton(onClick = onZoom) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_in)) }
        }
    }
}

internal fun importedWaypointDrawable(symbol: String): Int = when (symbol) {
    "Campground", "Lodging", "Lodge" -> R.drawable.camp_site
    "Restaurant" -> R.drawable.import_restaurant
    "Gas Station" -> R.drawable.fuel_
    "Museum" -> R.drawable.historic_
    "Information" -> R.drawable.information_
    "Scenic Area" -> R.drawable.camera
    "ATV" -> R.drawable.import_wrench
    "Shopping Center" -> R.drawable.import_basket
    "Swimming Area" -> R.drawable.swimming
    "Drinking Water" -> R.drawable.waterfall
    else -> R.drawable.marker_stroked
}

internal fun importedWaypointBitmap(context: android.content.Context, symbol: String): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.density = android.util.DisplayMetrics.DENSITY_DEFAULT
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawCircle(20f, 20f, 19f, android.graphics.Paint(3).apply { color = android.graphics.Color.WHITE })
    androidx.core.content.ContextCompat.getDrawable(context, importedWaypointDrawable(symbol))?.mutate()?.let {
        it.setBounds(7, 7, 33, 33); it.draw(canvas)
    }
    return bitmap
}
