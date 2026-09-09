package ch.overlandmap.map.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.SatelliteDownloadProgress
import ch.overlandmap.map.ui.overlandApp

/**
 * Download manager: every local pack's assets grouped under a collapsible
 * pack row — itineraries, offline map, hillshade, contour — each with its
 * status and a download / retry / delete action, and the device's free space
 * plus the app's storage footprint at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadsViewModel = viewModel { DownloadsViewModel(overlandApp()) },
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val satellite by app.satelliteTileManager.progress.collectAsState()
    var breakdown by remember { mutableStateOf<StorageBreakdown?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.general.isNotEmpty()) {
                    item {
                        GeneralSection(
                            items = state.general,
                            onRetry = { viewModel.retryGeneral(it) },
                        )
                        HorizontalDivider()
                    }
                }
                if (satellite.isNotEmpty()) {
                    item {
                        SatelliteSection(
                            items = satellite,
                            onDelete = { app.satelliteTileManager.delete(it) },
                        )
                        HorizontalDivider()
                    }
                }
                items(state.packs, key = { it.packId }) { pack ->
                    PackSection(
                        pack = pack,
                        onDownload = { viewModel.download(pack, it) },
                        onDelete = { viewModel.delete(pack, it) },
                    )
                    HorizontalDivider()
                }
            }
            StorageFooter(
                freeSpaceBytes = state.freeSpaceBytes,
                appStorageBytes = state.appStorageBytes,
                onClick = { viewModel.computeStorageBreakdown { breakdown = it } },
            )
        }
    }

    breakdown?.let { data ->
        StorageBreakdownDialog(
            data = data,
            onDismiss = { breakdown = null },
            onReset = {
                breakdown = null
                viewModel.resetStorage { restartApp(context) }
            },
        )
    }
}

@Composable
private fun PackSection(
    pack: PackDownloads,
    onDownload: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit,
) {
    var expanded by remember(pack.packId) { mutableStateOf(true) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            pack.packName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
        )
    }
    if (expanded) {
        pack.items.forEach { item ->
            AssetRow(item, onDownload = { onDownload(item) }, onDelete = { onDelete(item) })
        }
    }
}

@Composable
private fun AssetRow(item: DownloadItem, onDownload: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(assetLabel(item.kind)), style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val status = item.status) {
                is DownloadStatus.Downloaded ->
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                is DownloadStatus.NotDownloaded ->
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.download)) }
                is DownloadStatus.Failed ->
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.retry)) }
                is DownloadStatus.Downloading -> Unit // progress bar below
            }
        }
        when (val status = item.status) {
            is DownloadStatus.Downloading -> {
                val fraction = status.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            is DownloadStatus.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
    }
}

@Composable
private fun SatelliteSection(
    items: Map<String, SatelliteDownloadProgress>,
    onDelete: (regionId: String) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.satellite_tiles),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        items.forEach { (regionId, item) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatBytes(item.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onDelete(regionId) }) {
                        Text(stringResource(R.string.delete))
                    }
                }
                when {
                    item.error != null -> Text(
                        item.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    !item.done -> LinearProgressIndicator(
                        progress = { item.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralSection(
    items: List<GeneralItem>,
    onRetry: (GeneralAssetKind) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.general_assets),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        items.forEach { item -> GeneralAssetRow(item, onRetry = { onRetry(item.kind) }) }
    }
}

@Composable
private fun GeneralAssetRow(item: GeneralItem, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(generalLabel(item.kind)), style = MaterialTheme.typography.bodyLarge)
                // Size is only known for the world map/relief; hidden ("—") for
                // fonts and sprites, whose sizes the asset store doesn't publish.
                if (item.sizeBytes > 0) {
                    Text(
                        formatBytes(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (item.status) {
                // General assets are required, not user-managed: no delete. A
                // present asset just reads "Downloaded".
                is DownloadStatus.Downloaded -> Text(
                    stringResource(R.string.downloaded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is DownloadStatus.NotDownloaded ->
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.download)) }
                is DownloadStatus.Failed ->
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                is DownloadStatus.Downloading -> Unit // progress bar below
            }
        }
        when (val status = item.status) {
            is DownloadStatus.Downloading -> {
                val fraction = status.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            is DownloadStatus.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
    }
}

@Composable
private fun StorageFooter(freeSpaceBytes: Long, appStorageBytes: Long, onClick: () -> Unit) {
    HorizontalDivider()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    ) {
        Text(
            stringResource(R.string.free_space_on_device, formatBytes(freeSpaceBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.app_storage_used, formatBytes(appStorageBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.tap_for_storage_details),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StorageBreakdownDialog(
    data: StorageBreakdown,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_storage)) },
            text = { Text(stringResource(R.string.reset_storage_confirm)) },
            confirmButton = {
                TextButton(onClick = onReset) {
                    Text(
                        stringResource(R.string.reset_storage),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_usage)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StorageRow(stringResource(R.string.itineraries), data.itinerariesBytes)
                StorageRow(stringResource(R.string.storage_photos), data.photosBytes)
                StorageRow(stringResource(R.string.offline_maps), data.offlineMapsBytes)
                StorageRow(stringResource(R.string.storage_cache), data.cacheBytes)
                HorizontalDivider()
                StorageRow(stringResource(R.string.storage_total), data.totalBytes, bold = true)
                StorageRow(stringResource(R.string.free_space), data.freeSpaceBytes)
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmReset = true }) {
                Text(
                    stringResource(R.string.reset_storage),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun StorageRow(label: String, bytes: Long, bold: Boolean = false) {
    val style = if (bold) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), style = style)
    }
}

/**
 * Restarts the app after a storage reset so [ch.overlandmap.map.OverlandApp]
 * re-runs its startup path: it recreates the database and re-fetches the
 * required general assets (world map, relief, fonts, sprites) from scratch.
 */
private fun restartApp(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

private fun assetLabel(kind: DownloadKind): Int = when (kind) {
    DownloadKind.ITINERARIES -> R.string.itineraries
    DownloadKind.OFFLINE_MAP -> R.string.offline_map
    DownloadKind.HILLSHADE -> R.string.hillshade_map
    DownloadKind.DEM -> R.string.relief_map
    DownloadKind.CONTOUR -> R.string.contour_map
}

private fun generalLabel(kind: GeneralAssetKind): Int = when (kind) {
    GeneralAssetKind.WORLD_MAP -> R.string.world_map
    GeneralAssetKind.WORLD_RELIEF -> R.string.world_relief
    GeneralAssetKind.FONTS -> R.string.map_fonts
    GeneralAssetKind.SPRITES -> R.string.map_icons
}

/** Human-readable byte size, base-1000 to match the assets' declared MB. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "%.1f GB".format(mb / 1000) else "%.0f MB".format(mb)
}
