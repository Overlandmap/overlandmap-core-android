package ch.overlandmap.map.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.markup.Markup
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.markup.rememberMarkupLinkHandler
import ch.overlandmap.map.ui.theme.contentTextStyle
import coil.compose.AsyncImage

/**
 * Full-screen reader for one sidebar article, loaded by [sidebarId]. A normal
 * navigation destination (not a Dialog): being a regular screen in the app's
 * own window, its Scaffold gets correct system-bar insets — the top bar sits
 * below the status bar and the content clears the navigation bar — with no
 * inset workarounds. Its description's markup links open in place: other
 * sidebars stack on the back stack via [onOpenSidebar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarScreen(
    sidebarId: String,
    onBack: () -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenSidebar: (sidebarId: String) -> Unit,
    onOpenShopPack: ((packId: String) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val lang = currentLanguage()
    val sidebar by produceState<Sidebar?>(initialValue = null, sidebarId) {
        value = app.libraryRepository.sidebarById(sidebarId)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        sidebar?.name(lang) ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (val current = sidebar) {
            null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> {
                val onLink = rememberMarkupLinkHandler(
                    trackPackId = current.trackPackId,
                    sourceItineraryId = null,
                    onOpenItinerary = onOpenItinerary,
                    onOpenSidebar = onOpenSidebar,
                    onOpenShopPack = onOpenShopPack,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // Scaffold's inner padding carries the status-bar (top
                        // bar) and navigation-bar (bottom) insets; the trailing
                        // 16dp is just breathing room below the last line.
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    current.titlePhotoUrl?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = current.titlePhotoCaption,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            contentScale = ContentScale.Crop,
                        )
                        current.titlePhotoCaption?.let { caption ->
                            MarkupText(
                                caption,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                onLinkClick = onLink,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    current.description(lang)?.let {
                        MarkupText(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            onLinkClick = onLink,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tap-preview shown when a markup link points to a sidebar: a very small
 * title-photo thumbnail on the left, the name and first lines on the right.
 * Tapping anywhere opens the full [SidebarScreen].
 */
@Composable
fun SidebarPreviewDialog(
    sidebar: Sidebar,
    lang: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = Modifier.clickable(onClick = onOpen),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                sidebar.titlePhotoUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sidebar.name(lang),
                        style = contentTextStyle(MaterialTheme.typography.titleSmall),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    sidebar.description(lang)?.let { desc ->
                        Text(
                            Markup.plainText(desc),
                            style = contentTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
