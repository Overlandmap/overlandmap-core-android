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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
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
                items(state.packs, key = { it.packId }) { pack ->
                    PackSection(
                        pack = pack,
                        onDownload = { viewModel.download(pack, it) },
                        onDelete = { viewModel.delete(pack, it) },
                    )
                    HorizontalDivider()
                }
            }
            StorageFooter(state.freeSpaceBytes, state.appStorageBytes)
        }
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
private fun StorageFooter(freeSpaceBytes: Long, appStorageBytes: Long) {
    HorizontalDivider()
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Text(
            stringResource(R.string.free_space_on_device, formatBytes(freeSpaceBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.app_storage_used, formatBytes(appStorageBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun assetLabel(kind: DownloadKind): Int = when (kind) {
    DownloadKind.ITINERARIES -> R.string.itineraries
    DownloadKind.OFFLINE_MAP -> R.string.offline_map
    DownloadKind.HILLSHADE -> R.string.hillshade_map
    DownloadKind.CONTOUR -> R.string.contour_map
}

/** Human-readable byte size, base-1000 to match the assets' declared MB. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "%.1f GB".format(mb / 1000) else "%.0f MB".format(mb)
}
