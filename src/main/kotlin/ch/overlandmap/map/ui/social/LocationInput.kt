package ch.overlandmap.map.ui.social

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.social.CoordinateParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** A coordinate as the text the form keeps: plain decimals, which [CoordinateParser] always reads back. */
fun Coordinate.asInputText(): String = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

/**
 * The location field of the correction and shared-waypoint forms (iOS "Add location"): a text
 * field for typed or pasted coordinates, with a menu to fill it from the current position, the
 * GPS tags of a photo, or the clipboard. Problems are reported through [error].
 */
@Composable
fun LocationInput(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    onProblem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    fun useCurrentLocation() {
        scope.launch {
            val here = currentPosition(context)
            if (here != null) onValueChange(here.asInputText()) else onProblem(context.getString(R.string.cannot_get_current_location))
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useCurrentLocation() else onProblem(context.getString(R.string.location_denied_msg))
    }

    fun readPhoto(uri: Uri) {
        val position = photoPosition(context, uri)
        if (position != null) onValueChange(position.asInputText()) else onProblem(context.getString(R.string.no_gps_in_exif))
    }

    // Photos keep their GPS tags only for apps allowed to read the media location.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readPhoto(uri)
    }
    val mediaLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        photoPicker.launch("image/*")
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.location)) },
            placeholder = { Text(stringResource(R.string.gps_coordinates)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.add_location))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.use_current_location)) },
                onClick = {
                    menuOpen = false
                    if (hasLocationPermission(context)) useCurrentLocation()
                    else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.extract_position_from_photo)) },
                onClick = {
                    menuOpen = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        mediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    } else {
                        photoPicker.launch("image/*")
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.paste)) },
                onClick = {
                    menuOpen = false
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    val parsed = CoordinateParser.parse(clip)
                    if (parsed != null) onValueChange(parsed.asInputText()) else if (clip.isNotBlank()) onValueChange(clip)
                },
            )
        }
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The device's current position (GPS, else network), or null when it can't be had. */
@Suppress("MissingPermission")
private suspend fun currentPosition(context: Context): Coordinate? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    for (provider in providers) {
        val location = suspendCancellableCoroutine<Location?> { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager, provider, signal, ContextCompat.getMainExecutor(context),
            ) { cont.resume(it) }
        } ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        if (location != null) return Coordinate(location.latitude, location.longitude)
    }
    return null
}

/** The GPS position stored in a photo's EXIF tags, or null when it has none. */
private fun photoPosition(context: Context, picked: Uri): Coordinate? = runCatching {
    var uri = picked
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        uri = runCatching { MediaStore.setRequireOriginal(picked) }.getOrDefault(picked)
    }
    val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: context.contentResolver.openInputStream(picked)
    stream?.use {
        val out = FloatArray(2)
        if (android.media.ExifInterface(it).getLatLong(out)) Coordinate(out[0].toDouble(), out[1].toDouble()) else null
    }
}.getOrNull()
