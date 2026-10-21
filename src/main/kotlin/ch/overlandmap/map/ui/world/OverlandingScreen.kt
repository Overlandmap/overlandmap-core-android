package ch.overlandmap.map.ui.world

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.data.GeneralAssetState
import ch.overlandmap.map.model.ZoneType
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.VerticalSplit
import java.text.DateFormat
import java.util.Date

/**
 * The Overlanding tab (iOS `OverlandingScreen`): the country-borders map on top and, below it,
 * a detail pane that goes from the home screen to a country, a border and a border post while the
 * map stays visible. Free feature; works offline once its two assets are downloaded.
 */
@Composable
fun OverlandingScreen(
    onOpenSearch: () -> Unit,
    viewModel: OverlandingViewModel = viewModel { OverlandingViewModel(overlandApp()) },
) {
    val data by viewModel.data.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val panePath by viewModel.panePath.collectAsState()
    val showPosts by viewModel.showBorderPosts.collectAsState()
    val legendExpanded by viewModel.legendExpanded.collectAsState()
    val zoomRequest by viewModel.zoomRequest.collectAsState()
    val pointZoomRequest by viewModel.pointZoomRequest.collectAsState()
    val tilesRevision by viewModel.tilesRevision.collectAsState()
    val context = LocalContext.current

    BackHandler(enabled = panePath.isNotEmpty()) { viewModel.popPane() }

    VerticalSplit(
        splitKey = "overlanding",
        initialTopFraction = 0.55f,
        top = {
            Box(Modifier.fillMaxSize()) {
                OverlandingMap(
                    selection = selection,
                    showBorderPosts = showPosts,
                    dataRevision = tilesRevision,
                    initialCamera = viewModel.initialCamera,
                    zoomRequest = zoomRequest,
                    onZoomRequestHandled = viewModel::zoomHandled,
                    pointZoomRequest = pointZoomRequest,
                    onPointZoomHandled = viewModel::pointZoomHandled,
                    onCameraChanged = viewModel::saveCamera,
                    onSelect = viewModel::select,
                    modifier = Modifier.fillMaxSize(),
                )
                OverlandingLegend(
                    expanded = legendExpanded,
                    onExpandedChange = viewModel::setLegendExpanded,
                    showBorderPosts = showPosts,
                    onShowBorderPosts = viewModel::setShowBorderPosts,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
                MapToolbar(
                    canGoBack = panePath.isNotEmpty(),
                    backTitle = panePath.lastOrNull()?.backTitle,
                    zoomTarget = viewModel.zoomTarget(data),
                    shareUrl = viewModel.shareUrl(data),
                    onBack = { viewModel.popPane() },
                    onSearch = onOpenSearch,
                    onZoom = { target ->
                        when (target) {
                            is OverlandingViewModel.ZoomTarget.Bounds -> viewModel.zoomTo(target.bounds)
                            is OverlandingViewModel.ZoomTarget.Point -> viewModel.zoomToPoint(target.point)
                        }
                    },
                    onShare = { url ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                null,
                            )
                        )
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        },
        bottom = { DetailPane(viewModel) },
    )
}

@Composable
private fun MapToolbar(
    canGoBack: Boolean,
    backTitle: String?,
    zoomTarget: OverlandingViewModel.ZoomTarget?,
    shareUrl: String?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onZoom: (OverlandingViewModel.ZoomTarget) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (canGoBack) {
            ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, backTitle ?: stringResource(R.string.close), onBack)
        }
        ToolbarButton(Icons.Filled.Search, stringResource(R.string.search), onSearch)
        if (zoomTarget != null) {
            ToolbarButton(Icons.Filled.ZoomIn, stringResource(R.string.zoom)) { onZoom(zoomTarget) }
        }
        if (shareUrl != null) {
            ToolbarButton(Icons.Filled.Share, stringResource(R.string.share_link)) { onShare(shareUrl) }
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = description)
        }
    }
}

// ── Legend ──────────────────────────────────────────────────────────────────

@Composable
private fun OverlandingLegend(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showBorderPosts: Boolean,
    onShowBorderPosts: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .then(if (expanded) Modifier.width(176.dp) else Modifier)
            .border(1.dp, Color(0xFF3478C7).copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onExpandedChange(!expanded) },
            ) {
                Text(stringResource(R.string.legend), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF3478C7),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                LegendSwatch(stringResource(R.string.open), Color(0xFF45A866))
                LegendSwatch(stringResource(R.string.restricted), Color(0xFFE7B844))
                LegendSwatch(stringResource(R.string.overlanding_unsafe), Color(0xFFD64545))
                LegendSwatch(stringResource(R.string.overlanding_forbidden), Color(0xFF252525))
                Text(
                    stringResource(R.string.overland_borders_heading),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LegendLine(stringResource(R.string.border_open), Color(0xFF45A866))
                LegendLine(stringResource(R.string.border_bilateral), Color(0xFF3478C7))
                LegendLine(stringResource(R.string.restricted), Color(0xFFE7A43D))
                LegendLine(stringResource(R.string.border_closed), Color(0xFFD64545))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.border_posts), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = showBorderPosts, onCheckedChange = onShowBorderPosts, modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LegendLine(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 28.dp, height = 3.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Detail pane ─────────────────────────────────────────────────────────────

@Composable
private fun DetailPane(vm: OverlandingViewModel) {
    val data by vm.data.collectAsState()
    val selection by vm.selection.collectAsState()
    val panePath by vm.panePath.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val templates by vm.advisoryTemplates.collectAsState()
    val lang = currentLanguage()
    val unavailable = stringResource(R.string.overland_data_unavailable)
    val borderFallback = stringResource(R.string.border)

    fun tab(key: String) = tabs[key] ?: 0

    Surface(Modifier.fillMaxSize()) {
        val top = panePath.lastOrNull()
        when {
            top is PaneDestination.Border -> {
                val border = data.border(top.borderId)
                if (border == null) UnavailableSelection(unavailable)
                else BorderDetail(
                    border, data, lang, tab("border:${border.documentId}"),
                    { vm.setTab("border:${border.documentId}", it) },
                    onOpenPost = { post ->
                        vm.openPost(post.documentId, border.documentId, data.borderTitle(border, lang, borderFallback))
                    },
                    onZoomPost = vm::zoomToPoint,
                )
            }
            top is PaneDestination.BorderPostOf -> {
                val post = data.post(top.postId)
                if (post == null) UnavailableSelection(unavailable)
                else BorderPostDetail(
                    post, data.border(top.borderId ?: post.borderId), data, lang,
                    tab("post:${post.documentId}"), { vm.setTab("post:${post.documentId}", it) },
                )
            }
            else -> when (val s = selection) {
                null -> OverlandHome(vm, data)
                is OverlandingSelection.OfCountry -> {
                    val country = data.country(s.id)
                    if (country == null) UnavailableSelection(unavailable)
                    else CountryDetail(
                        country, data, lang, templates,
                        tab("country:${country.documentId}"), { vm.setTab("country:${country.documentId}", it) },
                        onOpenBorder = { border -> vm.openBorder(border.documentId, country.name(lang)) },
                    )
                }
                is OverlandingSelection.OfBorder -> {
                    val border = data.border(s.id)
                    if (border == null) UnavailableSelection(unavailable)
                    else BorderDetail(
                        border, data, lang, tab("border:${border.documentId}"),
                        { vm.setTab("border:${border.documentId}", it) },
                        onOpenPost = { post ->
                            vm.openPost(post.documentId, border.documentId, data.borderTitle(border, lang, borderFallback))
                        },
                        onZoomPost = vm::zoomToPoint,
                    )
                }
                is OverlandingSelection.OfBorderPost -> {
                    val post = data.post(s.id)
                    if (post == null) UnavailableSelection(unavailable)
                    else BorderPostDetail(
                        post, data.border(post.borderId), data, lang,
                        tab("post:${post.documentId}"), { vm.setTab("post:${post.documentId}", it) },
                    )
                }
                is OverlandingSelection.OfZone -> {
                    val zone = data.zone(s.id)
                    if (zone == null) UnavailableSelection(unavailable)
                    else ZoneDetail(zone, data.country(zone.country), lang)
                }
            }
        }
    }
}

// ── Home ────────────────────────────────────────────────────────────────────

@Composable
private fun OverlandHome(vm: OverlandingViewModel, data: OverlandData) {
    val searching by vm.searching.collectAsState()
    val query by vm.query.collectAsState()
    val datasetState by vm.datasetState.collectAsState()
    val version by vm.datasetVersion.collectAsState()
    var showDisclaimer by remember { mutableStateOf(false) }
    val lang = currentLanguage()

    val zoneLabels = mapOf(
        ZoneType.CLOSED to stringResource(R.string.zone_closed),
        ZoneType.GUIDE_ESCORT_NEEDED to stringResource(R.string.zone_guide_escort),
        ZoneType.PERMIT_NEEDED to stringResource(R.string.zone_permit),
        ZoneType.SPECIAL to stringResource(R.string.zone_special),
        ZoneType.UNKNOWN to stringResource(R.string.unknown),
    )
    val postFallback = stringResource(R.string.search_kind_border_post)

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (searching) {
                TextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { vm.setSearching(false) }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    stringResource(R.string.overland_db),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.setSearching(true) }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
            }
        }

        if (searching) {
            val results = remember(query, data, lang) {
                OverlandSearch.search(query, data, lang, { zoneLabels[it.zoneType].orEmpty() }, postFallback)
            }
            SearchResults(results, query) { result ->
                vm.select(result.selection)
                vm.setSearching(false)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (data.countries.isEmpty()) DatasetStatus(datasetState, onRetry = { vm.retryDownload() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    version?.let { v ->
                        val date = v.dateMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                        Text(if (date != null) "v.${v.version} ($date)" else "v.${v.version}")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showDisclaimer = true }) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFFF9500))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.disclaimer), color = Color(0xFFFF9500))
                    }
                }
                ContinentProgress()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = Color(0xFF3478C7), modifier = Modifier.size(32.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.getting_started), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.start_by_clicking_map), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer)) },
            text = { Text(stringResource(R.string.database_disclaimer)) },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun DatasetStatus(state: GeneralAssetState, onRetry: () -> Unit) {
    when (state) {
        is GeneralAssetState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.overland_data_downloading))
            val fraction = state.fraction
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is GeneralAssetState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.overland_data_downloading))
        }
    }
}

@Composable
private fun ContinentProgress() {
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9500)
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ContinentRow(stringResource(R.string.asia), "✔", green, stringResource(R.string.complete))
            ContinentRow(stringResource(R.string.africa), "⏳", orange, stringResource(R.string.coming_soon))
            ContinentRow(stringResource(R.string.americas), "⏳", orange, stringResource(R.string.coming_soon))
            ContinentRow(stringResource(R.string.europe), "✔", green, stringResource(R.string.not_planned))
        }
    }
}

@Composable
private fun ContinentRow(name: String, icon: String, color: Color, result: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, modifier = Modifier.widthIn(min = 105.dp))
        Text(icon, color = color, modifier = Modifier.width(24.dp))
        Spacer(Modifier.weight(1f))
        Text(result)
    }
}

@Composable
private fun SearchResults(results: List<OverlandSearchResult>, query: String, onPick: (OverlandSearchResult) -> Unit) {
    if (query.isBlank() || results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (query.isNotBlank()) Text(query, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { result ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onPick(result) }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                    val flag = flagEmoji(result.isoA2)
                    when {
                        flag.isNotEmpty() -> Text(flag, style = MaterialTheme.typography.titleLarge)
                        result.kind == OverlandResultKind.BORDER ->
                            Icon(Icons.Filled.Timeline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        else -> Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column {
                    Text(result.title)
                    Text(
                        result.subtitle ?: when (result.kind) {
                            OverlandResultKind.COUNTRY -> stringResource(R.string.search_kind_country)
                            OverlandResultKind.BORDER -> stringResource(R.string.search_kind_border)
                            OverlandResultKind.BORDER_POST -> stringResource(R.string.search_kind_border_post)
                            OverlandResultKind.ZONE -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
