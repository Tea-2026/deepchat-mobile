package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.MotionVisibility
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Web-search provenance under a message: `参考 N 个网页`, collapsed by default,
 * expanding in place into the first few `title / hostname / snippet` rows.
 *
 * The [MessagePart.SearchResults] part is recorded on the *user* message (that
 * is where the search was triggered), so [onUserBubble] switches to an
 * on-accent treatment instead of the assistant's muted card. Both are compact
 * and capped in height so the composer never gets pushed off-screen.
 */
@Composable
fun SearchSourcesPanel(
    results: List<MessagePart.SearchResults>,
    onUserBubble: Boolean,
    modifier: Modifier = Modifier,
) {
    // De-dupe and drop URL-less hits before counting, so the header matches the
    // rows the list can actually show.
    val hits = remember(results) {
        searchSourceView(results.flatMap { it.hits }, showAll = true).visible
    }
    if (hits.isEmpty()) return

    val chatColors = LocalChatColors.current
    val uriHandler = LocalUriHandler.current
    var expanded by remember(hits) { mutableStateOf(false) }
    var showAll by remember(hits) { mutableStateOf(false) }
    val view = remember(hits, showAll) { searchSourceView(hits, showAll) }

    val titleColor = if (onUserBubble) chatColors.onAccent else MaterialTheme.colorScheme.onSurface
    val secondary = if (onUserBubble) {
        chatColors.onAccent.copy(alpha = 0.74f)
    } else {
        chatColors.mutedText
    }
    val accent = if (onUserBubble) chatColors.onAccent else MaterialTheme.colorScheme.primary
    val container = if (onUserBubble) {
        chatColors.onAccent.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = if (onUserBubble) null else BorderStroke(1.dp, chatColors.hairline)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.Springs.Light,
        label = "sourcesChevron",
    )

    Column(modifier = modifier) {
        Surface(shape = RoundedCornerShape(12.dp), color = container, border = border) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable { expanded = !expanded }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlobeIcon(modifier = Modifier.iconSize(14.dp), tint = secondary)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "参考 ${hits.size} 个网页",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (onUserBubble) chatColors.onAccent else chatColors.mutedText,
                    modifier = Modifier.weight(1f),
                )
                ChevronDownIcon(
                    modifier = Modifier
                        .iconSize(14.dp)
                        .rotate(chevronRotation),
                    tint = secondary,
                )
            }
        }

        MotionVisibility(
            visible = expanded,
            enter = Motion.enterFromTop(),
            exit = Motion.exitToTop(),
            label = "searchSources",
        ) {
            Column(
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                view.visible.forEachIndexed { index, hit ->
                    SearchSourceRow(
                        index = index,
                        title = sourceTitle(hit),
                        host = hostOf(hit.url),
                        snippet = oneLineSnippet(hit.snippet),
                        titleColor = titleColor,
                        hostColor = accent,
                        secondaryColor = secondary,
                        onOpen = { runCatching { uriHandler.openUri(hit.url) } },
                    )
                }
                if (view.hidden > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressable { showAll = true }
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "还有 ${view.hidden} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                        ChevronRightIcon(
                            modifier = Modifier.iconSize(12.dp),
                            tint = accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSourceRow(
    index: Int,
    title: String,
    host: String,
    snippet: String,
    titleColor: androidx.compose.ui.graphics.Color,
    hostColor: androidx.compose.ui.graphics.Color,
    secondaryColor: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onOpen)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = secondaryColor,
            modifier = Modifier.width(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = host,
                style = MaterialTheme.typography.labelSmall,
                color = hostColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snippet.isNotEmpty()) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
