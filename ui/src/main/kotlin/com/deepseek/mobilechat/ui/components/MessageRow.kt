package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.ui.chat.BranchPosition
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.MotionVisibility
import com.deepseek.mobilechat.ui.motion.longPressable
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.motion.shakeWhen
import com.deepseek.mobilechat.ui.theme.BubbleShapes
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing
import com.deepseek.mobilechat.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Identity of a slot inside a message row when the visible branch changes. */
private data class BranchSlot(val id: MessageId, val index: Int)

/**
 * One turn in the transcript (spec §3).
 *
 * **Assistant rows are unbubbled**: brand mark + model name + time on the first
 * line, an optional collapsible "思考 N 秒" drawer, then the body directly on
 * the page. Only the user's own text sits in an accent pill, right-aligned.
 *
 * Long-press never opens an inline action strip (that overflowed and clipped) —
 * it asks the screen for the bottom action panel via [onRequestActions].
 */
@Composable
fun MessageRow(
    message: Message,
    branch: BranchPosition?,
    resolveMessage: (MessageId) -> Message?,
    modelName: String,
    protocol: ProviderProtocol?,
    thinkingSeconds: Int?,
    activeProviderId: ProviderId?,
    editing: Boolean,
    onRequestActions: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
    onSwitchBranch: (MessageId) -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER

    // Shake only on the transition *into* the failed state; scrolling a failed
    // message back into view must not replay it.
    var statusSeen by remember(message.id) { mutableStateOf(message.status) }
    var shakeKey by remember(message.id) { mutableStateOf(0) }
    LaunchedEffect(message.status) {
        if (statusSeen != message.status) {
            statusSeen = message.status
            if (message.status == MessageStatus.FAILED) shakeKey++
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shakeWhen(trigger = if (shakeKey > 0) shakeKey else null)
            .padding(
                start = Spacing.lg,
                end = Spacing.lg,
                top = if (isUser) Spacing.sm else Spacing.lg,
                bottom = if (isUser) Spacing.xs else Spacing.md,
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        MotionVisibility(
            visible = message.pinned,
            enter = Motion.enter(),
            exit = Motion.exit(),
            label = "pinnedBadge",
        ) {
            PinnedBadge(modifier = Modifier.padding(bottom = Spacing.xxs))
        }

        if (isUser) {
            UserTurnHeader(message = message)
        } else {
            AssistantTurnHeader(
                message = message,
                modelName = modelName,
                // A reply produced by another provider must not wear this
                // provider's logo; the brand-name fallback takes over instead.
                protocol = headerProtocol(message, protocol, activeProviderId),
            )
        }

        if (!isUser) {
            val reasoning = remember(message.parts) { reasoningOf(message) }
            if (reasoning.isNotEmpty()) {
                ThinkingDrawer(
                    reasoning = reasoning,
                    seconds = thinkingSeconds,
                    streaming = message.status == MessageStatus.STREAMING,
                )
            }
        }

        AnimatedContent(
            targetState = editing,
            transitionSpec = { Motion.morph() },
            label = "editingSwap",
        ) { isEditing ->
            if (isEditing) {
                InlineMessageEditor(
                    initialText = message.text,
                    onCancel = onCancelEdit,
                    onSave = onSaveEdit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (isUser) {
                UserPill(
                    message = message,
                    branch = branch,
                    resolveMessage = resolveMessage,
                    onLongPress = onRequestActions,
                )
            } else {
                AssistantBody(
                    message = message,
                    branch = branch,
                    resolveMessage = resolveMessage,
                    onLongPress = onRequestActions,
                    onSwitchBranch = onSwitchBranch,
                    onRetry = onRetry,
                    onContinue = onContinue,
                )
            }
        }

        if (isUser && !editing) {
            Row(
                modifier = Modifier.padding(top = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                EditedBadge(message)
                if (branch != null) BranchSwitcher(branch = branch, onSwitch = onSwitchBranch)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------

/** `[avatar] 模型名   时间` — the first line of an assistant turn. */
@Composable
private fun AssistantTurnHeader(
    message: Message,
    modelName: String,
    protocol: ProviderProtocol?,
) {
    val chatColors = LocalChatColors.current
    val resolvedName = message.modelInfo?.ref?.modelId?.raw?.takeIf { it.isNotBlank() } ?: modelName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandAvatar(protocol = protocol, name = resolvedName, size = 18.dp)
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = resolvedName.ifBlank { "模型" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = rememberClock(message.createdAt),
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = chatColors.mutedText,
        )
    }
}

/** `时间 You [avatar]` — right aligned above the user's pill. */
@Composable
private fun UserTurnHeader(message: Message) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = Modifier.padding(bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = rememberClock(message.createdAt),
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = chatColors.mutedText,
        )
        Text(
            text = "You",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        UserAvatar(size = 18.dp)
    }
}

// ---------------------------------------------------------------------------
// Bodies
// ---------------------------------------------------------------------------

@Composable
private fun UserPill(
    message: Message,
    branch: BranchPosition?,
    resolveMessage: (MessageId) -> Message?,
    onLongPress: () -> Unit,
) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier
                .weight(1f, fill = false)
                .longPressable(onLongClick = onLongPress),
            shape = BubbleShapes.user,
            color = chatColors.userBubble,
        ) {
            AnimatedContent(
                targetState = BranchSlot(message.id, branch?.index ?: 0),
                transitionSpec = {
                    val delta = targetState.index - initialState.index
                    Motion.horizontalTravel(if (delta >= 0) 1 else -1)
                },
                label = "branchTravelUser",
            ) { slot ->
                val resolved = if (slot.id == message.id) {
                    message
                } else {
                    resolveMessage(slot.id) ?: message
                }
                Box(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                ) {
                    MessageContent(message = resolved, showReasoning = false)
                }
            }
        }
    }
}

@Composable
private fun AssistantBody(
    message: Message,
    branch: BranchPosition?,
    resolveMessage: (MessageId) -> Message?,
    onLongPress: () -> Unit,
    onSwitchBranch: (MessageId) -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .longPressable(onLongClick = onLongPress),
        ) {
            AnimatedContent(
                targetState = BranchSlot(message.id, branch?.index ?: 0),
                transitionSpec = {
                    val delta = targetState.index - initialState.index
                    Motion.horizontalTravel(if (delta >= 0) 1 else -1)
                },
                label = "branchTravelAssistant",
            ) { slot ->
                val resolved = if (slot.id == message.id) {
                    message
                } else {
                    resolveMessage(slot.id) ?: message
                }
                MessageContent(message = resolved, showReasoning = false)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (branch != null) BranchSwitcher(branch = branch, onSwitch = onSwitchBranch)
            MessageStatusFooter(
                message = message,
                onRetry = onRetry,
                onContinue = onContinue,
            )
            Spacer(modifier = Modifier.weight(1f))
            message.usage?.let { usage ->
                UsageFooter(
                    usage = usage,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
        }
    }
}

/**
 * 「思考 12 秒 ›」 — collapsible reasoning.
 *
 * [seconds] is measured by the ViewModel from the live stream; messages that
 * were already finished when they were loaded have no measurement, so the row
 * says 「思考过程」 instead of inventing a number.
 */
@Composable
fun ThinkingDrawer(
    reasoning: String,
    seconds: Int?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chatColors = LocalChatColors.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = Motion.Springs.Light,
        label = "thinkingChevron",
    )
    val label = when {
        streaming -> "正在思考…"
        seconds != null -> "思考 $seconds 秒"
        else -> "思考过程"
    }

    Column(modifier = modifier.padding(bottom = Spacing.sm)) {
        Row(
            modifier = Modifier
                .pressable { expanded = !expanded }
                .padding(vertical = Spacing.xxs)
                .semantics { contentDescription = "$label，点击${if (expanded) "收起" else "展开"}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
            Chevron(
                rotation = rotation,
                tint = chatColors.mutedText,
            )
        }
        MotionVisibility(
            visible = expanded,
            enter = Motion.enterFromTop(),
            exit = Motion.exitToTop(),
            label = "reasoning",
        ) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = chatColors.mutedText,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xxs),
            )
        }
    }
}

/** Small chevron drawn inline so the drawer label stays one Text run. */
@Composable
private fun Chevron(rotation: Float, tint: androidx.compose.ui.graphics.Color) {
    ChevronRightIcon(
        modifier = Modifier
            .size(13.dp)
            .rotate(rotation),
        tint = tint,
        contentDescription = null,
    )
}

// ---------------------------------------------------------------------------
// Meta
// ---------------------------------------------------------------------------

@Composable
private fun PinnedBadge(modifier: Modifier = Modifier) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        PinIcon(modifier = Modifier.iconSize(11.dp), tint = chatColors.mutedText)
        Text(
            text = "已置顶",
            style = MaterialTheme.typography.labelSmall,
            color = chatColors.mutedText,
        )
    }
}

@Composable
private fun EditedBadge(message: Message) {
    val chatColors = LocalChatColors.current
    if (message.editedAt != null) {
        Text(
            text = "已编辑",
            style = MaterialTheme.typography.labelSmall,
            color = chatColors.mutedText,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
    }
}

/** `‹ 2/3 ›` — the label itself slides when the branch changes. */
@Composable
fun BranchSwitcher(
    branch: BranchPosition,
    onSwitch: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    Row(
        modifier = modifier.padding(horizontal = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        BranchArrow(
            enabled = branch.canGoPrevious,
            content = {
                ChevronLeftIcon(
                    modifier = Modifier.iconSize(13.dp),
                    tint = if (branch.canGoPrevious) chatColors.mutedText else chatColors.mutedText.copy(alpha = 0.3f),
                )
            },
            onClick = { branch.previousId?.let(onSwitch) },
        )
        AnimatedContent(
            targetState = branch.index to branch.count,
            transitionSpec = {
                val delta = targetState.first - initialState.first
                Motion.horizontalTravel(if (delta >= 0) 1 else -1, distanceFraction = 3)
            },
            label = "branchLabel",
        ) { state ->
            Text(
                text = "${state.first + 1}/${state.second}",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = chatColors.mutedText,
                textAlign = TextAlign.Center,
            )
        }
        BranchArrow(
            enabled = branch.canGoNext,
            content = {
                ChevronRightIcon(
                    modifier = Modifier.iconSize(13.dp),
                    tint = if (branch.canGoNext) chatColors.mutedText else chatColors.mutedText.copy(alpha = 0.3f),
                )
            },
            onClick = { branch.nextId?.let(onSwitch) },
        )
    }
}

@Composable
private fun BranchArrow(
    enabled: Boolean,
    content: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(22.dp)
            .pressable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (enabled) 1f else 0.4f),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** `1,234 tokens · $0.0021` — only rendered when the provider reported usage. */
@Composable
fun UsageFooter(usage: Usage, modifier: Modifier = Modifier) {
    val chatColors = LocalChatColors.current
    val total = usage.promptTokens + usage.completionTokens
    val label = remember(usage) {
        buildString {
            append(formatCount(total))
            append(" tokens")
            if (usage.reasoningTokens > 0) {
                append("（思考 ")
                append(formatCount(usage.reasoningTokens))
                append("）")
            }
            usage.costUsd?.let {
                append(" · ")
                append(formatCost(it))
            }
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.tabular(),
        color = chatColors.mutedText,
        textAlign = TextAlign.End,
        modifier = modifier.widthIn(max = 220.dp),
    )
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

@Composable
private fun rememberClock(atMillis: Long): String {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    return remember(atMillis) { if (atMillis <= 0L) "" else formatter.format(Date(atMillis)) }
}

/**
 * The brand mark to print above an assistant turn: the conversation's protocol
 * only when the reply came from that same provider.
 */
private fun headerProtocol(
    message: Message,
    protocol: ProviderProtocol?,
    activeProviderId: ProviderId?,
): ProviderProtocol? {
    val replyProvider = message.modelInfo?.ref?.providerId ?: return protocol
    return if (replyProvider == activeProviderId) protocol else null
}

/** The provider's thinking text, joined for the collapsible drawer. */
internal fun reasoningOf(message: Message): String = message.parts
    .filterIsInstance<MessagePart.Reasoning>()
    .joinToString("\n") { it.text }
    .trim()

private fun formatCount(value: Int): String {
    val digits = value.toString()
    if (digits.length <= 3) return digits
    val out = StringBuilder()
    digits.forEachIndexed { index, char ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
        out.append(char)
    }
    return out.toString()
}

private fun formatCost(cost: Double): String = when {
    cost <= 0.0 -> "$0"
    cost < 0.0001 -> "<$0.0001"
    cost < 1.0 -> "$" + String.format(Locale.US, "%.4f", cost)
    else -> "$" + String.format(Locale.US, "%.2f", cost)
}
