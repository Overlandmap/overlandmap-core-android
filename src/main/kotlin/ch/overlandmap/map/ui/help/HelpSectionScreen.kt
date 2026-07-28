package ch.overlandmap.map.ui.help

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.ui.currentLanguage
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch

/**
 * A section's tutorial carousel: the first page lists the section's features
 * (screenshot on top, then icon + title + subtitle rows), the following pages
 * are the screenshot-with-text steps. Dots track the position and a Continue
 * button advances (and closes on the last page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSectionScreen(
    sectionId: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lang = currentLanguage()
    val repo = remember { HelpRepository(context) }
    val data by produceState<Pair<HelpSection?, HelpContent>?>(initialValue = null, sectionId) {
        value = repo.sections().find { it.id == sectionId } to repo.content(sectionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.first?.title.textFor(lang).ifEmpty { stringResource(R.string.help) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        val content = data?.second
        if (content == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        // Page 0 is the features list; the rest are the tutorial pages.
        val pageCount = 1 + content.pages.size
        val pagerState = rememberPagerState(pageCount = { pageCount })
        val scope = rememberCoroutineScope()

        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                if (page == 0) {
                    FeaturesPage(data?.first?.image.orEmpty(), content.features, lang)
                } else {
                    TutorialPage(content.pages[page - 1], lang)
                }
            }
            PagerDots(count = pageCount, selected = pagerState.currentPage)
            val isLast = pagerState.currentPage >= pageCount - 1
            Button(
                onClick = {
                    if (isLast) onBack()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = HelpLink),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(if (isLast) R.string.help_done else R.string.help_continue))
            }
        }
    }
}

@Composable
private fun FeaturesPage(image: LocalizedText, features: List<HelpFeature>, lang: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        image.textFor(lang).takeIf { it.isNotEmpty() }?.let { Screenshot(it) }
        features.forEach { feature ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    helpIcon(feature.icon),
                    contentDescription = null,
                    tint = HelpLink,
                    modifier = Modifier.size(34.dp),
                )
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        feature.title.textFor(lang),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    feature.description.textFor(lang).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialPage(page: HelpPage, lang: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            page.title.textFor(lang),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        page.image.textFor(lang).takeIf { it.isNotEmpty() }?.let { Screenshot(it) }
        page.paragraphs.forEach { paragraph ->
            Text(
                paragraph.textFor(lang),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A screenshot with rounded corners and a drop shadow so it stands out. The box
 * is sized to the image's own aspect ratio (fit within the available width and
 * half the screen height), so the shadow hugs the photo, whatever its shape.
 */
@Composable
private fun Screenshot(url: String) {
    val shape = RoundedCornerShape(14.dp)
    val painter = rememberAsyncImagePainter(url)
    val intrinsic = painter.intrinsicSize
    val aspect = if (intrinsic.isSpecified && intrinsic.height > 0f) {
        intrinsic.width / intrinsic.height
    } else {
        16f / 9f // until the image loads and its size is known
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width: Dp
        val height: Dp
        if (maxWidth / aspect <= maxHeight) {
            width = maxWidth
            height = maxWidth / aspect
        } else {
            height = maxHeight
            width = maxHeight * aspect
        }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width = width, height = height)
                .shadow(elevation = 8.dp, shape = shape)
                .clip(shape),
        )
    }
}

@Composable
private fun PagerDots(count: Int, selected: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            val color = if (i == selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
