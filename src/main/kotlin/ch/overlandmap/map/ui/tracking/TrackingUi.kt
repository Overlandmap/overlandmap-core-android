package ch.overlandmap.map.ui.tracking

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.tracking.hasFineLocationPermission
import kotlinx.coroutines.delay

/** Whether the signed-in user has the `beta` entitlement that unlocks recording and navigation. */
@Composable
fun rememberIsBeta(): Boolean {
    val app = LocalContext.current.applicationContext as OverlandApp
    val entitlements by app.entitlements.collectAsState()
    return entitlements?.hasBeta == true
}

/**
 * Returns a function that runs an action once the position permission is granted: at once when it
 * already is, else after asking (and, on Android 13+, also for notifications, which carry the
 * recording and navigation controls while the app is in the background).
 */
@Composable
fun rememberWithLocationPermission(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocationPermission(context)
        val action = pending
        pending = null
        if (granted && action != null) action()
        else if (!granted) Toast.makeText(context, R.string.location_permission_needed, Toast.LENGTH_LONG).show()
    }
    return { action ->
        if (hasFineLocationPermission(context)) {
            action()
        } else {
            pending = action
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(permissions.toTypedArray())
        }
    }
}

/** A function that starts a recording (asking for permission first). */
@Composable
fun rememberStartRecording(): () -> Unit {
    val app = LocalContext.current.applicationContext as OverlandApp
    val withPermission = rememberWithLocationPermission()
    return { withPermission { app.trackRecorder.startRecording() } }
}

/**
 * The strip shown above the tab bar while a track is being recorded (beta): elapsed time and
 * distance, a button to drop a voice waypoint and one to stop (the iOS toolbar items).
 */
@Composable
fun ActiveRecordingBar(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val isBeta = rememberIsBeta()
    val recordings by app.trackRecorder.recordings.collectAsState()
    val currentId by app.trackRecorder.currentTrackId.collectAsState()
    val track = currentId?.let { id -> recordings.firstOrNull { it.id == id } }
    if (!isBeta || track == null) return

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(track.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    var addingWaypoint by remember { mutableStateOf(false) }
    val seconds = ((now - track.startDateMs) / 1000).coerceAtLeast(0)
    val elapsed = "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)

    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.size(10.dp).background(Color(0xFFD32F2F), CircleShape)) {}
            Text(
                "  ${stringResource(R.string.recording_notification_title)} · $elapsed · ${track.formattedDistance()}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { addingWaypoint = true }) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = stringResource(R.string.add_waypoint))
            }
            IconButton(onClick = { app.trackRecorder.stopRecording() }) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop), tint = Color(0xFFD32F2F))
            }
        }
    }
    if (addingWaypoint) VoiceWaypointSheet(onDismiss = { addingWaypoint = false })
}
