@file:OptIn(ExperimentalMaterial3Api::class)

package com.deepseek.mobilechat.ui.screens.context

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.ui.components.DownIcon
import com.deepseek.mobilechat.ui.components.iconSize
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.animatedEnter
import com.deepseek.mobilechat.ui.screens.common.compactCount
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import java.util.Locale

/**
 * Visual pieces of the context debug panel: the budget bar, one bar per
 * context block, and the expandable raw-prompt rows. Split from the screen so
 * each file stays small and the animations stay next to the data they show.
 */

@Composable
internal fun BudgetCard(assembly: ContextAssembly) {
    val chatColors = LocalChatColors.current
    val budget = assembly.budget
    val over = budget.isOverBudget
    val target = budget.ratio.coerceIn(0f, 1f)
    var grown by remember(budget.used) { mutableStateOf(false) }
    LaunchedEffect(budget.used) { grown = true }
    val progress by animateFloatAsState(
        targetValue = if (grown) target else 0f,
        animationSpec = tween(Motion.Durations.Long, easing = Motion.Easings.Emphasized),
        label = "contextBudgetGrowth",
    )
    val barColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animatedEnter(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "预算",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${compactCount(budget.used)} / ${compactCount(budget.limit)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (over) MaterialTheme.colorScheme.error else chatColors.mutedText,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(chatColors.divider, CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(10.dp)
                        .background(barColor, CircleShape),
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = if (over) {
                    "超出预算 ${compactCount(budget.overflow)} tokens，发送前会触发上下文策略。"
                } else {
                    "剩余 ${compactCount(budget.remaining)} tokens（${(budget.ratio * 100).toInt()}% 已用）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (over) MaterialTheme.colorScheme.error else chatColors.mutedText,
            )
        }
    }
}

@Composable
internal fun BlockRow(
    kind: BlockKind,
    title: String,
    tokens: Int,
    share: Float,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val color = blockColor(kind)
    var grown by remember(kind, title) { mutableStateOf(false) }
    LaunchedEffect(kind, title) { grown = true }
    val progress by animateFloatAsState(
        targetValue = if (grown) share.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(Motion.Durations.Long, easing = Motion.Easings.Emphasized),
        label = "contextBlockGrowth",
    )
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = blockKindLabel(kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxs)
                    .height(6.dp)
                    .background(chatColors.divider, CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(6.dp)
                        .background(color, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "${compactCount(tokens)} · ${(share * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = chatColors.mutedText,
        )
    }
}

@Composable
internal fun PromptHeader(expanded: Boolean, count: Int, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.Durations.Medium, easing = Motion.Easings.Standard),
        label = "promptChevron",
    )
    TextButton(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
    ) {
        Text(
            text = if (expanded) "收起原始 prompt" else "展开原始 prompt（$count 条）",
            modifier = Modifier.weight(1f),
        )
        DownIcon(
            modifier = Modifier
                .iconSize(16.dp)
                .graphicsLayer { rotationZ = rotation },
            contentDescription = null,
        )
    }
}

@Composable
internal fun PromptRow(index: Int, message: PromptMessage, modifier: Modifier = Modifier) {
    val chatColors = LocalChatColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = roleLabel(message.role),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "${message.parts.size} 段",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
        MonospaceBlock(text = promptText(message))
    }
}

@Composable
internal fun MonospaceBlock(text: String) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxs),
        shape = RoundedCornerShape(10.dp),
        color = chatColors.codeBackground,
    ) {
        Text(
            text = text.ifBlank { "（空）" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "system"
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.TOOL -> "tool"
}

private fun blockKindLabel(kind: BlockKind): String = when (kind) {
    BlockKind.SYSTEM_PROMPT -> "系统"
    BlockKind.INJECTION -> "注入"
    BlockKind.COMPACTION -> "压缩"
    BlockKind.HISTORY -> "历史"
    BlockKind.SEARCH -> "搜索"
    BlockKind.FILE -> "文件"
    BlockKind.EPHEMERAL -> "临时"
    BlockKind.CURRENT_USER -> "提问"
}

@Composable
private fun blockColor(kind: BlockKind): Color {
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        BlockKind.SYSTEM_PROMPT -> scheme.primary
        BlockKind.INJECTION -> scheme.tertiary
        BlockKind.COMPACTION -> scheme.secondary
        BlockKind.HISTORY -> scheme.primary.copy(alpha = 0.5f)
        BlockKind.SEARCH -> scheme.tertiary.copy(alpha = 0.6f)
        BlockKind.FILE -> scheme.secondary.copy(alpha = 0.6f)
        BlockKind.EPHEMERAL -> scheme.tertiary.copy(alpha = 0.35f)
        BlockKind.CURRENT_USER -> scheme.primary.copy(alpha = 0.8f)
    }
}

/** Flattens the parts of one prompt message, the way the provider adapter sees it. */
private fun promptText(message: PromptMessage): String = buildString {
    message.parts.forEachIndexed { index, part ->
        if (index > 0) append("\n")
        append(
            when (part) {
                is MessagePart.Text -> part.text
                is MessagePart.Reasoning -> "[reasoning]\n${part.text}"
                is MessagePart.Image -> "[image ${part.attachmentId.raw}]"
                is MessagePart.FileRef -> "[file ${part.attachmentId.raw}]"
                is MessagePart.ContextBlock -> "[${part.title}]\n${part.body}"
                is MessagePart.SearchResults -> buildString {
                    append("[search] ")
                    append(part.query)
                    part.hits.forEach { hit ->
                        append("\n- ")
                        append(hit.title)
                        append(" ")
                        append(hit.url)
                    }
                }
                is MessagePart.ToolCall -> "[tool ${part.name}] ${part.argumentsJson}"
                is MessagePart.ToolResult -> "[tool result ${part.callId}] ${part.content}"
            },
        )
    }
}
