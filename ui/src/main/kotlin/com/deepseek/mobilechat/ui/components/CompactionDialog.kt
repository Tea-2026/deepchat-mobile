package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Budget overrun is a decision, not an error: the user is told exactly how many
 * messages get folded away and how many tokens that buys back before anything
 * is written. Rendered through `AlertDialog` so it arrives with the platform's
 * own enter/exit animation.
 */
@Composable
fun CompactionDialog(
    proposal: CompactionProposal?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (proposal == null) return
    val saved = (proposal.tokensBefore - proposal.tokensAfter).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "上下文超出预算",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    text = "将把 ${proposal.coveredCount} 条消息压缩成一条摘要，预计节省约 $saved tokens" +
                        "（${proposal.tokensBefore} → ${proposal.tokensAfter}）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "压缩记录可以撤销，原始消息不会被删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("压缩并继续")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
