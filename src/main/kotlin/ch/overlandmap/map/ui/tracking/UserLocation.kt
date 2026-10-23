package ch.overlandmap.map.ui.tracking

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.tracking.AndroidLocationSource
import ch.overlandmap.map.tracking.hasFineLocationPermission

/**
 * The device position, for showing a dot on a map: null until a fix arrives or when the position
 * permission is refused. It asks for the permission once when the screen opens, and follows the GPS
 * only while the screen is on top (started and stopped with the lifecycle).
 */
@Composable
fun rememberUserLocation(): State<Coordinate?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val location = remember { mutableStateOf<Coordinate?>(null) }
    var granted by remember { mutableStateOf(hasFineLocationPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    DisposableEffect(granted, lifecycle) {
        val source = ch.overlandmap.map.bluetooth.SelectedLocationSource(context) { location.value = null }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> source.start(2f) { fix -> if (fix.accuracyM >= 0) location.value = fix.coordinate }
                Lifecycle.Event.ON_STOP -> source.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            source.start(2f) { fix -> if (fix.accuracyM >= 0) location.value = fix.coordinate }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            source.stop()
        }
    }
    return location
}
