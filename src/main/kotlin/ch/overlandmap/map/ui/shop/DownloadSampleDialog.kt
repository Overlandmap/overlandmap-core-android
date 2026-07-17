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
 * Asks what to download for a pack: a mandatory item (the free sample zip, or
 * the full purchased pack) plus the optional offline / hillshade / contour maps
 * as switches (on by default, off and disabled when the pack has no such
 * asset). Shows each size, the resulting total, and the free space on the
 * device. [onDownload] reports only the chosen *map* kinds — the mandatory item
 * is always downloaded, so the caller pairs it with its own download call.
 */
@Composable
fun DownloadAssetsDialog(
    title: String,
    mandatoryLabel: String,
    mandatoryAsset: Asset?,
    assets: Map<PackAssetKind, Asset>,
    onDismiss: () -> Unit,
    onDownload: (Set<PackAssetKind>) -> Unit,
    downloadEnabled: Boolean = true,
) {
    var offlineMap by remember { mutableStateOf(PackAssetKind.OFFLINE_MAP in assets) }
    var hillshade by remember { mutableStateOf(PackAssetKind.HILLSHADE in assets) }
    var contour by remember { mutableStateOf(PackAssetKind.CONTOUR in assets) }

    val selection = buildSet {
        if (offlineMap) add(PackAssetKind.OFFLINE_MAP)
        if (hillshade) add(PackAssetKind.HILLSHADE)
        if (contour) add(PackAssetKind.CONTOUR)
    }
    val totalBytes = (mandatoryAsset?.fileSizeBytes ?: 0L) +
        selection.sumOf { assets[it]?.fileSizeBytes ?: 0L }

    val context = LocalContext.current
    val freeBytes = remember { StatFs(context.filesDir.path).availableBytes }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                AssetRow(
                    label = mandatoryLabel,
                    asset = mandatoryAsset,
                    checked = true,
                    onChecked = null,
                    alwaysAvailable = true,
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
                enabled = downloadEnabled,
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
    alwaysAvailable: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            val size = when {
                asset != null -> readableSize(asset.fileSizeBytes)
                alwaysAvailable -> null // full pack: size resolved by the backend
                else -> stringResource(R.string.not_available)
            }
            size?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = alwaysAvailable || (asset != null && checked),
            onCheckedChange = onChecked,
            enabled = !alwaysAvailable && asset != null && onChecked != null,
        )
    }
}

/** Formats bytes the way storage is marketed: decimal MB / GB. */
private fun readableSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    else -> "${bytes / 1_000_000} MB"
}
