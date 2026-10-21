package ch.overlandmap.map.ui.tracking

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.tracking.RecordedTrack
import java.io.File

/** Writes the track to a GPX file in the cache and shares it through the system sheet (iOS `exportGPX`). */
fun shareRecordedTrackGpx(context: Context, track: RecordedTrack) {
    val stem = track.gpxName.replace(Regex("[^A-Za-z0-9-_]+"), "_").trim('_').ifEmpty { "track" }
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "$stem.gpx")
    file.writeText(track.toGpx())
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

/** Track colours offered by the picker. */
private val TRACK_COLORS = listOf(
    "#FF2D55", "#FF3B30", "#FF9500", "#FFCC00", "#34C759", "#00C7BE",
    "#30B0C7", "#007AFF", "#5856D6", "#AF52DE", "#A2845E", "#8E8E93",
)

/** A parsed `#RRGGBB`, for drawing. */
internal fun String.toComposeColor(): Color =
    runCatching { Color(android.graphics.Color.parseColor(toMapColor())) }.getOrDefault(Color(0xFFFF2D55))

/** The colour choice dialog (iOS `TrackColorPickerSheet`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackColorPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.track_color)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TRACK_COLORS.forEach { hex ->
                    Surface(
                        shape = CircleShape,
                        color = hex.toComposeColor(),
                        border = if (hex.equals(current, ignoreCase = true)) BorderStroke(3.dp, Color.Black) else null,
                        modifier = Modifier.size(44.dp).clickable { onPick(hex); onDismiss() },
                    ) {
                        if (hex.equals(current, ignoreCase = true)) Icon(Icons.Filled.Check, null, tint = Color.White)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Dialogs a track menu can open. */
enum class TrackDialog { COLOR, RENAME, DELETE }

/**
 * The actions on a track shared by the list rows and the viewer menu: show/hide, colour, rename,
 * simplify, share, delete. [dialog] hosts the colour/rename/delete dialogs.
 */
@Composable
fun TrackMenu(
    track: RecordedTrack,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShowOnMap: (() -> Unit)? = null,
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val recorder = (context.applicationContext as OverlandApp).trackRecorder
    var dialog by remember { mutableStateOf<TrackDialog?>(null) }
    val isRecording = recorder.currentTrackId.value == track.id

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onShowOnMap != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_on_map)) },
                onClick = { recorder.setDisplayed(track.id, true); onShowOnMap(); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(if (track.isDisplayed) R.string.hide else R.string.show)) },
            onClick = { recorder.toggleDisplayed(track.id); onDismiss() },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.color)) }, onClick = { dialog = TrackDialog.COLOR; onDismiss() })
        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { dialog = TrackDialog.RENAME; onDismiss() })
        if (!isRecording) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.simplify_track)) },
                onClick = { recorder.simplify(track.id, 10.0); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share_as_gpx)) },
            onClick = { shareRecordedTrackGpx(context, track); onDismiss() },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { dialog = TrackDialog.DELETE; onDismiss() })
    }

    when (dialog) {
        TrackDialog.COLOR -> TrackColorPickerDialog(
            current = track.colorHex,
            onPick = { recorder.updateColor(track.id, it) },
            onDismiss = { dialog = null },
        )
        TrackDialog.RENAME -> {
            var text by remember { mutableStateOf(track.name.orEmpty()) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.rename)) },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.name)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { recorder.rename(track.id, text); dialog = null }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        TrackDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(track.displayName) },
            confirmButton = {
                TextButton(onClick = { dialog = null; recorder.delete(track.id); onDeleted() }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> {}
    }
}
