package ch.overlandmap.map.ui.tracking

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.overlandmap.map.R
import ch.overlandmap.map.bluetooth.SelectedLocationSource
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.navigation.NavigationStartChoice
import ch.overlandmap.map.navigation.TrackNavigationMath
import ch.overlandmap.map.tracking.LocationFix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One location-based entry flow shared by itineraries and recorded/imported tracks. */
@Composable
fun rememberNavigationStart(
    onStart: (NavigationStartChoice, LocationFix) -> Unit,
): (List<Coordinate>, Coordinate?, Coordinate?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberWithNavigationLocation()
    val start by rememberUpdatedState(onStart)
    var locating by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<Pair<Boolean, LocationFix>?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    if (locating) AlertDialog(
        onDismissRequest = { job?.cancel(); locating = false },
        title = { Text(stringResource(R.string.navigate)) },
        text = { CircularProgressIndicator() },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { job?.cancel(); locating = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (unavailable) AlertDialog(
        onDismissRequest = { unavailable = false },
        text = { Text(stringResource(R.string.search_location_unavailable)) },
        confirmButton = { TextButton(onClick = { unavailable = false }) { Text(stringResource(R.string.ok)) } },
    )
    choice?.let { (offTrack, fix) ->
        fun select(value: NavigationStartChoice) { choice = null; start(value, fix) }
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text(stringResource(R.string.navigate)) },
            text = {
                Column {
                    Text(stringResource(if (offTrack) R.string.navigation_get_to else R.string.navigation_choose_direction))
                    if (offTrack) {
                        TextButton(onClick = { select(NavigationStartChoice.FIRST) }) { Text(stringResource(R.string.navigation_first_step)) }
                        TextButton(onClick = { select(NavigationStartChoice.LAST) }) { Text(stringResource(R.string.navigation_last_step)) }
                        TextButton(onClick = { select(NavigationStartChoice.NEAREST) }) { Text(stringResource(R.string.navigation_nearest_point)) }
                    } else {
                        TextButton(onClick = { select(NavigationStartChoice.FORWARD) }) { Text(stringResource(R.string.forward)) }
                        TextButton(onClick = { select(NavigationStartChoice.REVERSE) }) { Text(stringResource(R.string.navigation_direction_reverse)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choice = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return { track, first, last ->
        permission {
            if (!locating) {
                locating = true
                job = scope.launch {
                    val source = SelectedLocationSource(context)
                    try {
                        val ready = CompletableDeferred<LocationFix>()
                        source.start(0f) { if (it.accuracyM >= 0) ready.complete(it) }
                        val fix = withTimeoutOrNull(20_000) { ready.await() }
                        if (fix == null) unavailable = true else {
                            val geometry = track.ifEmpty { listOfNotNull(first, last) }
                            val distance = withContext(Dispatchers.Default) {
                                TrackNavigationMath.closestTrackProjection(fix.coordinate, geometry)?.crossDistance
                                    ?: first?.let { fix.coordinate.distanceKm(it) * 1000 }
                                    ?: Double.POSITIVE_INFINITY
                            }
                            when {
                                distance > 1000 -> choice = true to fix
                                first != null && fix.coordinate.distanceKm(first) < 1 -> start(NavigationStartChoice.FORWARD, fix)
                                last != null && fix.coordinate.distanceKm(last) < 1 -> start(NavigationStartChoice.REVERSE, fix)
                                else -> choice = false to fix
                            }
                        }
                    } finally { source.stop(); locating = false }
                }
            }
        }
    }
}
