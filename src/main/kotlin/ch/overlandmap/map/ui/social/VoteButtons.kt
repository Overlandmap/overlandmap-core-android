package ch.overlandmap.map.ui.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Up and down vote buttons with their counts (iOS `DiscussionVoteButtons`). [userVote] is the
 * signed-in user's own vote (true = up, false = down, null = none) and is highlighted; tapping it
 * again removes it, tapping the other one switches it (see SocialRules.voteAction).
 */
@Composable
fun DiscussionVoteButtons(
    upCount: Int,
    downCount: Int,
    userVote: Boolean?,
    onVote: (up: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        VoteButton(true, upCount, userVote == true) { onVote(true) }
        VoteButton(false, downCount, userVote == false) { onVote(false) }
    }
}

@Composable
private fun VoteButton(up: Boolean, count: Int, selected: Boolean, onClick: () -> Unit) {
    val tint = if (up) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else MaterialTheme.colorScheme.outline),
    ) {
        Icon(
            when {
                up && selected -> Icons.Filled.ThumbUp
                up -> Icons.Outlined.ThumbUp
                selected -> Icons.Filled.ThumbDown
                else -> Icons.Outlined.ThumbDown
            },
            contentDescription = null,
            tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("  $count", color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
