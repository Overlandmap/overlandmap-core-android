package ch.overlandmap.map.ui.shop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.data.PackDownloadProgress
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.ui.MapSettingsButton
import ch.overlandmap.map.ui.VerticalSplit
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.mapActionButtonColors
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.overlandApp
import coil.compose.AsyncImage
import org.maplibre.android.maps.MapLibreMap
import java.text.DateFormat
import java.util.Date

/**
 * The hosting Activity behind a Compose `LocalContext`, unwrapping the
 * `ContextWrapper` chain. Compose may hand back a themed wrapper rather than
 * the Activity itself, and the Play billing flow needs the real Activity.
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Shop detail of one track pack: its tracks highlighted in red on the global
 * map (top pane), and three tabs below — description, itineraries, comments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    packId: String,
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
    // When set, a Settings action appears in the top bar. The tab-less
    // single-pack app uses it to reach Settings while this is the root screen.
    onOpenSettings: (() -> Unit)? = null,
    viewModel: PackDetailViewModel = viewModel(key = packId) {
        PackDetailViewModel(overlandApp(), packId)
    },
) {
    val state by viewModel.state.collectAsState()
    val owned by viewModel.owned.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()
    val prices by viewModel.prices.collectAsState()
    val selectedItineraryId by viewModel.selectedItineraryId.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val purchasing by viewModel.purchasing.collectAsState()
    val purchaseError by viewModel.purchaseError.collectAsState()
    val activity = LocalContext.current.findActivity()
    val lang = currentLanguage()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }

    // Frame the pack as soon as both the map and its bounds are known.
    LaunchedEffect(map, state.pack) {
        val pack = state.pack ?: return@LaunchedEffect
        map?.let { zoomToPack(it, pack) }
    }

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Root of a single-track-pack app when Settings is wired in (only the
    // single-pack root passes it): no title bar, no back button, map full-bleed
    // to the top edge, Settings floated on the map instead (see below).
    val isRoot = onOpenSettings != null

    // This screen owns the full display height: in landscape (and as a
    // single-pack root) the app bar is dropped so the map reaches the top edge.
    // In portrait it keeps its status-bar inset — under the status bar the back
    // arrow would be untappable (the system owns touches in that strip).
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (!landscape && !isRoot) {
                TopAppBar(
                    title = { Text(state.pack?.name(lang) ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            }
        },
    ) { padding ->
        val pack = state.pack
        if (state.loading || pack == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            VerticalSplit(
                modifier = Modifier.fillMaxSize(),
                top = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PackTracksMap(
                            packId = packId,
                            selectedItineraryId = selectedItineraryId,
                            onItineraryTapped = { id, _ ->
                                viewModel.selectItinerary(id)
                                tab = 1
                            },
                            onMapReady = { map = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                        MapActions(
                            state = state,
                            pack = pack,
                            lang = lang,
                            owned = owned,
                            price = prices[pack.productId],
                            purchasing = purchasing,
                            purchaseError = purchaseError,
                            downloading = downloadProgress != null,
                            onBuy = {
                                if (signedIn) activity?.let(viewModel::buy)
                                else showSignInDialog = true
                            },
                            onDownloadPack = viewModel::downloadPack,
                            onDownloadSample = { showDownloadDialog = true },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, bottom = 16.dp),
                        )
                        onOpenSettings?.let { open ->
                            MapSettingsButton(
                                onClick = open,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(12.dp),
                            )
                        }
                    }
                },
                bottom = {
                    // Landscape drops the app bar and puts the tabs beside the
                    // map, so inset them below the status bar (else the tab row
                    // falls under it, where the system owns touches).
                    Box(
                        modifier = if (landscape) Modifier.statusBarsPadding() else Modifier,
                    ) {
                        DetailTabs(
                            state = state,
                            pack = pack,
                            lang = lang,
                            tab = tab,
                            onTabChange = { tab = it },
                            selectedItineraryId = selectedItineraryId,
                            onSelectItinerary = viewModel::selectItinerary,
                        )
                    }
                },
            )
            // The background download of this pack, visible whichever tab is
            // open, while the rest of the app stays usable.
            downloadProgress?.let {
                DownloadOverlay(it, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    if (showDownloadDialog) {
        DownloadSampleDialog(
            assets = state.assets,
            onDismiss = { showDownloadDialog = false },
            onDownload = { kinds ->
                showDownloadDialog = false
                viewModel.startAssetDownload(kinds)
            },
        )
    }

    if (showSignInDialog) {
        AlertDialog(
            onDismissRequest = { showSignInDialog = false },
            title = { Text(stringResource(R.string.sign_in)) },
            text = { Text(stringResource(R.string.sign_in_to_purchase)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignInDialog = false
                    onOpenSignIn()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignInDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Progress (or error) of a pack's background download. */
@Composable
internal fun DownloadOverlay(progress: PackDownloadProgress, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val label = when {
                progress.error != null -> progress.error
                progress.done -> stringResource(R.string.downloaded)
                progress.fraction == null -> stringResource(R.string.installing)
                else -> stringResource(R.string.downloading) + " " + progress.message
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (progress.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.error == null) {
                val fraction = progress.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The action buttons riding on the map: "Download" when the pack is
 * purchased but not yet on the device, or — not purchased — the sample
 * download stacked above "Purchase {name} for {price}" (the Play price
 * resolved at runtime from the product ID; without a price the button is
 * disabled as "Purchase not available"). A spinning wheel replaces them
 * while a purchase is being processed.
 */
@Composable
private fun MapActions(
    state: PackDetailState,
    pack: TrackPack,
    lang: String,
    owned: Boolean,
    price: String?,
    purchasing: Boolean,
    purchaseError: String?,
    downloading: Boolean,
    onBuy: () -> Unit,
    onDownloadPack: () -> Unit,
    onDownloadSample: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        purchaseError?.let {
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 3.dp) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        val buttonColors = mapActionButtonColors()
        when {
            downloading -> Unit // the progress overlay is already showing
            purchasing -> CircularProgressIndicator()
            owned && !state.isLocal -> Button(onClick = onDownloadPack, colors = buttonColors) {
                Text(stringResource(R.string.download))
            }
            !owned -> {
                // The sample: the pack's free itinerary, downloadable
                // without purchase.
                val sample = state.itineraries.firstOrNull { it.isFree }
                val hasZip = PackAssetKind.FREE_ITINERARY in state.assets
                if (sample != null && hasZip) {
                    val downloaded = sample.documentId in state.downloadedItineraryIds
                    Button(onClick = onDownloadSample, enabled = !downloaded, colors = buttonColors) {
                        Text(
                            stringResource(
                                if (downloaded) R.string.downloaded else R.string.download_sample
                            )
                        )
                    }
                }
                if (price.isNullOrBlank()) {
                    Button(onClick = {}, enabled = false, colors = buttonColors) {
                        Text(stringResource(R.string.purchase_not_available))
                    }
                } else {
                    Button(onClick = onBuy, colors = buttonColors) {
                        Text(stringResource(R.string.purchase_for, pack.name(lang), price))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTabs(
    state: PackDetailState,
    pack: TrackPack,
    lang: String,
    tab: Int,
    onTabChange: (Int) -> Unit,
    selectedItineraryId: String?,
    onSelectItinerary: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(
                selected = tab == 0,
                onClick = { onTabChange(0) },
                text = { Text(pack.name(lang), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            Tab(
                selected = tab == 1,
                onClick = { onTabChange(1) },
                text = { Text(stringResource(R.string.itineraries)) },
            )
            Tab(
                selected = tab == 2,
                onClick = { onTabChange(2) },
                text = { Text(stringResource(R.string.comments)) },
            )
        }
        when (tab) {
            0 -> DescriptionTab(state = state, pack = pack, lang = lang)
            1 -> ItinerariesTab(state, lang, selectedItineraryId, onSelectItinerary)
            2 -> CommentsTab(state.comments, lang)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DescriptionTab(
    state: PackDetailState,
    pack: TrackPack,
    lang: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // The shop has nothing local to route links to, so they stay inert.
        pack.description(lang)?.let {
            MarkupText(
                it,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ItinerariesTab(
    state: PackDetailState,
    lang: String,
    selectedItineraryId: String?,
    onSelect: (String?) -> Unit,
) {
    val gridState = rememberLazyGridState()

    // Bring the selected itinerary into view when it is picked on the map.
    LaunchedEffect(selectedItineraryId) {
        val index = state.itineraries.indexOfFirst {
            it.itineraryId.isNotEmpty() && it.itineraryId == selectedItineraryId
        }
        if (index >= 0) gridState.animateScrollToItem(index)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = gridState,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.itineraries.size) { index ->
            val itinerary = state.itineraries[index]
            ItineraryTile(
                itinerary = itinerary,
                lang = lang,
                selected = itinerary.itineraryId.isNotEmpty() &&
                    itinerary.itineraryId == selectedItineraryId,
                onClick = { onSelect(itinerary.itineraryId.ifEmpty { null }) },
            )
        }
    }
}

@Composable
private fun ItineraryTile(
    itinerary: Itinerary,
    lang: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        border = if (selected) BorderStroke(1.dp, Color.Red) else null,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column {
            Box {
                AsyncImage(
                    model = itinerary.titlePhotoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                    contentScale = ContentScale.Crop,
                )
                if (itinerary.isFree) {
                    Text(
                        stringResource(R.string.free),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(6.dp)
                            .background(Color.Black, MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                itinerary.name(lang),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun CommentsTab(comments: List<Comment>, lang: String) {
    if (comments.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.no_comments),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(comments.size) { index ->
            val comment = comments[index]
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.userName ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    comment.createdAt?.let {
                        Text(
                            dateFormat.format(Date(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                comment.rating?.takeIf { it > 0 }?.let {
                    Text("★".repeat(it), color = MaterialTheme.colorScheme.tertiary)
                }
                Text(comment.content(lang), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

