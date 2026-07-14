package ch.overlandmap.map.ui.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.LibraryRepository
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.ui.MapObjectPopup
import ch.overlandmap.map.ui.MapPopupKind
import ch.overlandmap.map.ui.MapPopupState
import ch.overlandmap.map.ui.VerticalSplit
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.zoomToPopupObject
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap

/** Shop tab: global map of all itineraries plus the list of track packs. */
@Composable
fun ShopScreen(
    onOpenPack: (String) -> Unit,
    onOpenItinerary: (documentId: String) -> Unit,
    viewModel: ShopViewModel = viewModel { ShopViewModel(overlandApp()) },
) {
    val state by viewModel.state.collectAsState()
    val owned by viewModel.ownedPackIds.collectAsState()
    val prices by viewModel.prices.collectAsState()
    val selectedPackId by viewModel.selectedPackId.collectAsState()
    val lang = currentLanguage()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val listState = rememberLazyListState()
    val library = (LocalContext.current.applicationContext as OverlandApp).libraryRepository
    val scope = rememberCoroutineScope()
    var popup by remember { mutableStateOf<MapPopupState?>(null) }

    // Bring the selected pack into view when it is picked on the map.
    LaunchedEffect(selectedPackId) {
        val index = state.packs.indexOfFirst { it.documentId == selectedPackId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    when {
        state.loading -> Centered { CircularProgressIndicator() }
        state.offline -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.shop_offline), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.retry)) }
            }
        }
        else -> VerticalSplit(
            top = {
                Box(modifier = Modifier.fillMaxSize()) {
                    GlobalItinerariesMap(
                        selectedPackId = selectedPackId,
                        onTrackTapped = { packId, itinerarySlug, position ->
                            viewModel.selectPack(packId)
                            scope.launch {
                                popup = shopTapPopup(
                                    library, state.packs, packId, itinerarySlug, position, lang,
                                )
                            }
                        },
                        onMapReady = { map = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    popup?.let { current ->
                        MapObjectPopup(
                            state = current,
                            onDismiss = { popup = null },
                            onZoom = { kind -> map?.let { zoomToPopupObject(it, kind) } },
                            onOpen = { kind ->
                                when (kind) {
                                    is MapPopupKind.OfItinerary ->
                                        onOpenItinerary(kind.itinerary.documentId)
                                    is MapPopupKind.Buy -> onOpenPack(kind.packId)
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            },
            bottom = {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.packs.size) { index ->
                        val pack = state.packs[index]
                        PackRow(
                            pack = pack,
                            lang = lang,
                            owned = pack.documentId in owned,
                            price = prices[pack.productId],
                            selected = pack.documentId == selectedPackId,
                            onClick = { onOpenPack(pack.documentId) },
                            onZoomToPack = {
                                viewModel.selectPack(pack.documentId)
                                map?.let { zoomToPack(it, pack) }
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * Resolves a tap on the shop map against the local library: a downloaded
 * itinerary gets the info popup, everything else the buy popup of its pack.
 */
private suspend fun shopTapPopup(
    library: LibraryRepository,
    packs: List<TrackPack>,
    packId: String,
    itinerarySlug: String?,
    position: Offset,
    lang: String,
): MapPopupState? {
    val packName = packs.firstOrNull { it.documentId == packId }?.name(lang)
    if (library.trackPack(packId) == null) {
        return MapPopupState(position, MapPopupKind.Buy(packId, packName, notInSample = false))
    }
    val itinerary = itinerarySlug?.let { library.itineraryBySlug(packId, it) }
    return when {
        itinerary == null -> null
        itinerary.isBuyable ->
            MapPopupState(position, MapPopupKind.Buy(packId, packName, notInSample = true))
        else -> MapPopupState(position, MapPopupKind.OfItinerary(itinerary))
    }
}

@Composable
private fun PackRow(
    pack: TrackPack,
    lang: String,
    owned: Boolean,
    price: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onZoomToPack: () -> Unit,
) {
    Card(
        border = if (selected) BorderStroke(1.dp, Color.Red) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The Play price (or owned state) rides on the pack's photo.
            Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) {
                AsyncImage(
                    model = pack.titlePhotoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                val overlay = if (owned) stringResource(R.string.purchased) else price
                if (!overlay.isNullOrEmpty()) {
                    Text(
                        overlay,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(vertical = 2.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(pack.name(lang), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.itinerary_count, pack.nbItineraries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onZoomToPack) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = stringResource(R.string.show_on_map),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
