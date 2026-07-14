package ch.overlandmap.map.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import coil.compose.AsyncImage

/**
 * One tile of a photo grid: 4:3 thumbnail with the name below, and a
 * diagonal "Free" ribbon across the top-left corner when [freeBanner].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGridTile(
    photoUrl: String?,
    label: String,
    modifier: Modifier = Modifier,
    freeBanner: Boolean = false,
    /** Shows the little clock badge: a newer version exists online. */
    updateBadge: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(MaterialTheme.shapes.medium),
            ) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                if (freeBanner) FreeRibbon(Modifier.align(Alignment.TopStart))
                if (updateBadge) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** Diagonal "Free" banner crossing the top-left corner of a thumbnail. */
@Composable
private fun FreeRibbon(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.free).uppercase(),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .offset(x = (-24).dp, y = 10.dp)
            .rotate(-45f)
            .background(Color(0xCCD32F2F))
            .padding(horizontal = 28.dp, vertical = 2.dp),
    )
}
