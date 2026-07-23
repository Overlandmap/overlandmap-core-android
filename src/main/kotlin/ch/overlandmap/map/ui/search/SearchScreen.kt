package ch.overlandmap.map.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Signpost
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.data.SearchResult
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.MapObjectPopup
import ch.overlandmap.map.ui.MapPopupKind
import ch.overlandmap.map.ui.MapPopupState
import ch.overlandmap.map.ui.overlandApp
import kotlinx.coroutines.launch

/**
 * Full-screen full-text search (the multi-pack app's Home button). Field, then
 * results; a tap opens the matching viewer, with waypoints shown as their popup
 * here (no live map, so both popup actions open the waypoint's itinerary).
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenPack: (packId: String) -> Unit,
    onOpenSidebar: (sidebarId: String) -> Unit,
    onOpenWorld: () -> Unit,
    viewModel: SearchViewModel = viewModel { SearchViewModel(overlandApp()) },
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val scope = rememberCoroutineScope()
    var waypoint by remember { mutableStateOf<Waypoint?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = viewModel::setQuery,
            leading = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            },
        )
        HorizontalDivider()
        SearchResultsList(
            results = results,
            query = query,
            minQueryLength = viewModel.minQueryLength,
            modifier = Modifier.fillMaxSize(),
        ) { result ->
            when (result.type) {
                FtsIndex.TYPE_ITINERARY -> onOpenItinerary(result.documentId, null)
                FtsIndex.TYPE_STEP ->
                    result.itineraryDocumentId?.let { onOpenItinerary(it, result.stepId) }
                FtsIndex.TYPE_TRACK_PACK -> onOpenPack(result.documentId)
                FtsIndex.TYPE_SIDEBAR -> onOpenSidebar(result.documentId)
                FtsIndex.TYPE_WAYPOINT ->
                    scope.launch { waypoint = viewModel.waypoint(result.documentId) }
                FtsIndex.TYPE_COUNTRY,
                FtsIndex.TYPE_BORDER,
                FtsIndex.TYPE_BORDER_POST -> onOpenWorld()
            }
        }
    }

    waypoint?.let { wp ->
        val openItinerary = {
            waypoint = null
            wp.itineraryId?.let { onOpenItinerary(it, null) }
            Unit
        }
        MapObjectPopup(
            state = MapPopupState(position = null, kind = MapPopupKind.OfWaypoint(wp)),
            onDismiss = { waypoint = null },
            onZoom = { openItinerary() },
            onOpen = { openItinerary() },
        )
    }
}

/**
 * Search embedded as a screen tab (the single-pack app's track-pack screen):
 * just the field and results, no top bar. [onResultClick] lets the host react —
 * e.g. anchor the tapped object's popup over the map on the other half.
 */
@Composable
fun SearchTabContent(
    onResultClick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel { SearchViewModel(overlandApp()) },
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        SearchField(query = query, onQueryChange = viewModel::setQuery)
        HorizontalDivider()
        SearchResultsList(
            results = results,
            query = query,
            minQueryLength = viewModel.minQueryLength,
            modifier = Modifier.fillMaxSize(),
            onResultClick = onResultClick,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    leading: @Composable (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        leading?.invoke()
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cancel))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    query: String,
    minQueryLength: Int,
    modifier: Modifier = Modifier,
    onResultClick: (SearchResult) -> Unit,
) {
    if (query.trim().length >= minQueryLength && results.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.search_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
            items(results, key = { it.type + it.documentId }) { result ->
                SearchRow(result) { onResultClick(result) }
            }
        }
    }
}

@Composable
private fun SearchRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = iconFor(result.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                titleFor(result),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            if (result.snippet.isNotBlank()) {
                Text(
                    result.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** "L9 1. Umling La" for a step, "L9 Manali - Leh" for an itinerary (slug in red). */
private fun titleFor(result: SearchResult) = buildAnnotatedString {
    val red = SpanStyle(color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
    val slug = result.itinerarySlug?.takeIf { it.isNotBlank() }
    when (result.type) {
        FtsIndex.TYPE_STEP -> {
            if (slug != null) {
                withStyle(red) { append(slug) }
                append(" ")
            }
            result.stepId?.let { append("$it. ") }
            append(result.name)
        }
        FtsIndex.TYPE_ITINERARY -> {
            if (slug != null) {
                withStyle(red) { append(slug) }
                append(" ")
            }
            append(result.name)
        }
        else -> append(result.name)
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    FtsIndex.TYPE_ITINERARY -> Icons.Filled.Map
    FtsIndex.TYPE_STEP -> Icons.Filled.Signpost
    FtsIndex.TYPE_WAYPOINT -> Icons.Filled.LocationOn
    FtsIndex.TYPE_SIDEBAR -> Icons.AutoMirrored.Filled.Article
    FtsIndex.TYPE_TRACK_PACK -> Icons.Filled.Layers
    FtsIndex.TYPE_COUNTRY -> Icons.Filled.Public
    FtsIndex.TYPE_BORDER, FtsIndex.TYPE_BORDER_POST -> Icons.Filled.Flag
    else -> Icons.Filled.Place
}
