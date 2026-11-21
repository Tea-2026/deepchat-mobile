package com.deepseek.mobilechat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * The compaction divider from `ChatSnapshot.transcript`.
 *
 * It is a *divider*, not a replacement: the covered messages stay above it (the
 * projection never deletes them), and the summary is collapsed by default so the
 * transcript still reads as a conversation. This row is part of the lazy list and
 * gets its own key, because it is not a branch slot.
 */
@Composable
internal fun CompactionDividerRow(
    row: TranscriptRow.CompactionDivider,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(row.compactionId.raw) { mutableStateOf(false) }
    val canExpand = row.summary.isNotBlank()
    val colors = LocalChatColors.current

    fun toggle() {
        if (canExpand) expanded = !expanded
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = colors.divider,
            )
            Text(
                text = "这里压缩了 ${row.coveredCount} 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = colors.mutedText,
                modifier = Modifier.clickable(enabled = canExpand) { toggle() },
            )
            if (canExpand) {
                Text(
                    text = if (expanded) "收起" else "摘要",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { toggle() },
                )
            }
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = colors.divider,
            )
        }
        AnimatedVisibility(visible = expanded && canExpand) {
            Column {
                Text(
                    text = row.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedText,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                val saved = row.tokensBefore
                    ?.let { before -> row.tokensAfter?.let { after -> before - after } }
                if (saved != null && saved > 0) {
                    Text(
                        text = "约节省 $saved tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.mutedText,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                }
            }
        }
    }
}
