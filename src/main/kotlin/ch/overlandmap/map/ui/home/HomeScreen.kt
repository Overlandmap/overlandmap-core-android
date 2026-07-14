package ch.overlandmap.map.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.ui.PhotoGridTile
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.overlandApp

/**
 * Home tab: "Last used" (the five most recently opened itineraries) above
 * "Local Library" (the downloaded track packs), both as photo grids.
 * Long-pressing a pack offers to delete it.
 */
@Composable
fun HomeScreen(
    onOpenItinerary: (String) -> Unit,
    onOpenPack: (String) -> Unit,
    viewModel: HomeViewModel = viewModel { HomeViewModel(overlandApp()) },
) {
    val packs by viewModel.packs.collectAsState()
    val lastUsed by viewModel.lastUsed.collectAsState()
    var packToDelete by remember { mutableStateOf<TrackPack?>(null) }
    val lang = currentLanguage()

    if (packs.isEmpty()) {
        EmptyLibrary()
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (lastUsed.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle(stringResource(R.string.last_used))
            }
            lastUsed.forEach { itinerary ->
                item(key = "itin-${itinerary.documentId}") {
                    PhotoGridTile(
                        photoUrl = itinerary.titlePhotoUrl,
                        label = itinerary.name(lang),
                        freeBanner = itinerary.isFree,
                        onClick = { onOpenItinerary(itinerary.documentId) },
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionTitle(stringResource(R.string.local_library))
        }
        packs.forEach { pack ->
            item(key = "pack-${pack.documentId}") {
                PhotoGridTile(
                    photoUrl = pack.titlePhotoUrl,
                    label = pack.name(lang),
                    freeBanner = pack.isFreeSample,
                    updateBadge = pack.needsUpdate,
                    onClick = { onOpenPack(pack.documentId) },
                    onLongClick = { packToDelete = pack },
                )
            }
        }
    }

    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text(pack.name(lang)) },
            text = { Text(stringResource(R.string.library_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePack(pack.documentId)
                    packToDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { packToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyLibrary() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(180.dp).padding(bottom = 24.dp),
            )
            Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.library_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
