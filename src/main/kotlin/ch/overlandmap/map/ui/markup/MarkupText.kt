package ch.overlandmap.map.ui.markup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import ch.overlandmap.map.OverlandApp

/** Same red as the Flutter app's textLinkColor. */
private val LinkColor = Color(0xFFF44336)

/**
 * Renders a description or caption written in the app's markup (see [Markup]):
 * inline metrics in the user's units, bold/italic/underline, titles, and
 * links. Tapped links report the parsed target and the shown text to
 * [onLinkClick] — typically a [MarkupLinkHost]'s handler; null renders them
 * as plain (colored but inert) text, for contexts without navigation.
 */
@Composable
fun MarkupText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onLinkClick: ((link: MarkupLink, shownText: String) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val useMiles by app.userPreferences.useMiles.collectAsState(initial = false)
    val useFeet by app.userPreferences.useFeet.collectAsState(initial = false)
    val units = MarkupUnits(useMiles, useFeet)
    val paragraphs = remember(text, units) { Markup.parse(text, units) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paragraphs.forEach { nodes -> MarkupParagraph(nodes, style, onLinkClick) }
    }
}

@Composable
private fun MarkupParagraph(
    nodes: List<MarkupNode>,
    style: TextStyle,
    onLinkClick: ((MarkupLink, String) -> Unit)?,
) {
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        nodes.forEach { node ->
            when (node) {
                is MarkupNode.Span -> withStyle(spanStyleFor(node.style, style)) { append(node.text) }
                is MarkupNode.Link -> appendLink(node, onLinkClick, inlineContent)
            }
        }
    }
    Text(annotated, style = style, inlineContent = inlineContent)
}

private fun AnnotatedString.Builder.appendLink(
    node: MarkupNode.Link,
    onLinkClick: ((MarkupLink, String) -> Unit)?,
    inlineContent: MutableMap<String, InlineTextContent>,
) {
    val body: AnnotatedString.Builder.() -> Unit = {
        if (node.text.isNotEmpty()) {
            withStyle(SpanStyle(color = LinkColor)) { append(node.text) }
        }
        // A bare step link `(:::5)` also shows the map's numbered circle.
        node.target.ownStepId?.let { stepId ->
            if (node.text.isNotEmpty()) append(' ')
            val key = "step-badge-$stepId"
            appendInlineContent(key, "($stepId)")
            inlineContent[key] = stepBadge(stepId)
        }
    }
    if (onLinkClick == null) {
        body()
    } else {
        withLink(
            LinkAnnotation.Clickable(
                tag = "markup",
                styles = TextLinkStyles(),
                linkInteractionListener = { onLinkClick(node.target, node.text) },
            ),
            body,
        )
    }
}

/** Red circle with the white step number, like the map's step markers. */
private fun stepBadge(stepId: Int) = InlineTextContent(
    Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter)
) {
    Box(
        modifier = Modifier.fillMaxSize().background(LinkColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stepId.toString(),
            color = Color.White,
            fontSize = if (stepId < 100) 10.sp else 8.sp,
        )
    }
}

private fun spanStyleFor(style: MarkupStyle, base: TextStyle): SpanStyle = when (style) {
    MarkupStyle.NORMAL -> SpanStyle()
    MarkupStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    MarkupStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    MarkupStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    MarkupStyle.TITLE -> SpanStyle(
        fontWeight = FontWeight.Bold,
        fontSize = if (base.fontSize.isSpecified) base.fontSize * 1.3 else 18.sp,
    )
}
