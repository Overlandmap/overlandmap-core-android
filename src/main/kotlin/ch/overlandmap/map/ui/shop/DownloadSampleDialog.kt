package ch.overlandmap.map.ui.shop

import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.model.Asset

/**
 * Asks what to download with the free sample itinerary: the zip itself is
 * mandatory, the offline / hillshade / contour maps are optional switches
 * (on by default, off and disabled when the pack has no such asset).
 * Shows each size, the resulting total, and the free space on the device.
 */
@Composable
fun DownloadSampleDialog(
    assets: Map<PackAssetKind, Asset>,
    onDismiss: () -> Unit,
    onDownload: (Set<PackAssetKind>) -> Unit,
) {
    var offlineMap by remember { mutableStateOf(PackAssetKind.OFFLINE_MAP in assets) }
    var hillshade by remember { mutableStateOf(PackAssetKind.HILLSHADE in assets) }
    var contour by remember { mutableStateOf(PackAssetKind.CONTOUR in assets) }

    val selection = buildSet {
        add(PackAssetKind.FREE_ITINERARY)
        if (offlineMap) add(PackAssetKind.OFFLINE_MAP)
        if (hillshade) add(PackAssetKind.HILLSHADE)
        if (contour) add(PackAssetKind.CONTOUR)
    }
    val totalBytes = selection.sumOf { assets[it]?.fileSizeBytes ?: 0L }

    val context = LocalContext.current
    val freeBytes = remember { StatFs(context.filesDir.path).availableBytes }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_sample)) },
        text = {
            Column {
                AssetRow(
                    label = stringResource(R.string.free_sample_itinerary),
                    asset = assets[PackAssetKind.FREE_ITINERARY],
                    checked = true,
                    onChecked = null,
                )
                AssetRow(
                    label = stringResource(R.string.offline_map),
                    asset = assets[PackAssetKind.OFFLINE_MAP],
                    checked = offlineMap,
                    onChecked = { offlineMap = it },
                )
                AssetRow(
                    label = stringResource(R.string.hillshade_map),
                    asset = assets[PackAssetKind.HILLSHADE],
                    checked = hillshade,
                    onChecked = { hillshade = it },
                )
                AssetRow(
                    label = stringResource(R.string.contour_map),
                    asset = assets[PackAssetKind.CONTOUR],
                    checked = contour,
                    onChecked = { contour = it },
                )
                Text(
                    stringResource(R.string.total_download_size, readableSize(totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.free_space_on_device, readableSize(freeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (totalBytes > freeBytes) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDownload(selection) },
                enabled = assets[PackAssetKind.FREE_ITINERARY] != null,
            ) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** One downloadable item: name and size, with a switch when it's optional. */
@Composable
private fun AssetRow(
    label: String,
    asset: Asset?,
    checked: Boolean,
    onChecked: ((Boolean) -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (asset == null) stringResource(R.string.not_available)
                else readableSize(asset.fileSizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = asset != null && checked,
            onCheckedChange = onChecked,
            enabled = asset != null && onChecked != null,
        )
    }
}

/** Formats bytes the way storage is marketed: decimal MB / GB. */
private fun readableSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    else -> "${bytes / 1_000_000} MB"
}
