package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.MotionVisibility
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.motion.rememberCaretAlpha
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Body of one message: optional reasoning drawer, markdown text (with the
 * streaming caret), and the terminal states a bubble can end in.
 */
@Composable
fun MessageContent(
    message: Message,
    modifier: Modifier = Modifier,
    /**
     * v2 assistant rows render the 「思考 N 秒」 drawer themselves (it belongs
     * next to the model name), so they pass `false` here. The default keeps the
     * inline drawer for any other caller.
     */
    showReasoning: Boolean = true,
) {
    val chatColors = LocalChatColors.current
    val streaming = message.status == MessageStatus.STREAMING
    // Only a streaming bubble needs the blink clock; every other message would
    // otherwise hold an infinite animation open forever.
    val caretAlpha = if (streaming) rememberCaretAlpha() else 1f
    val reasoning = remember(message.parts) {
        message.parts.filterIsInstance<MessagePart.Reasoning>()
            .joinToString("\n") { it.text }
            .trim()
    }
    val body = message.text
    val imageParts = remember(message.parts) {
        message.parts.filterIsInstance<MessagePart.Image>()
    }
    val fileParts = remember(message.parts) {
        message.parts.filterIsInstance<MessagePart.FileRef>()
    }
    val textColor = when (message.role) {
        MessageRole.USER -> chatColors.userBubbleText
        else -> chatColors.assistantBubbleText
    }

    Column(modifier = modifier) {
        if (showReasoning && reasoning.isNotEmpty()) {
            ReasoningDrawer(
                reasoning = reasoning,
                expandedByDefault = false,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }

        when {
            body.isNotEmpty() -> MarkdownText(
                text = body,
                color = textColor,
                streaming = streaming,
                caretAlpha = caretAlpha,
            )

            streaming -> TypingDots(modifier = Modifier.padding(vertical = Spacing.xxs))
        }

        if (imageParts.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = if (body.isEmpty()) Spacing.xxs else Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                imageParts.forEach { part ->
                    MessageAttachmentImage(attachmentId = part.attachmentId)
                }
            }
        }

        if (fileParts.isNotEmpty()) {
            Text(
                text = "含 ${fileParts.size} 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = if (message.role == MessageRole.USER) {
                    chatColors.userBubbleText.copy(alpha = 0.75f)
                } else {
                    chatColors.mutedText
                },
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        if (message.status == MessageStatus.CANCELLED && body.isEmpty()) {
            Text(
                text = "已停止生成",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
        }
    }
}

/** Assistant-only footer: error with retry, or truncation with continue. */
@Composable
fun MessageStatusFooter(
    message: Message,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val errorColor = MaterialTheme.colorScheme.error
    val failed = message.status == MessageStatus.FAILED
    val truncated = message.status == MessageStatus.COMPLETE &&
        message.finishReason == FinishReason.LENGTH
    MotionVisibility(
        visible = failed || truncated,
        modifier = modifier,
        enter = Motion.enter(),
        exit = Motion.exit(),
        label = "messageStatus",
    ) {
        if (failed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Spacer(
                    modifier = Modifier
                        .size(6.dp)
                        .drawBehind { drawCircle(color = errorColor) },
                )
                Text(
                    text = message.error?.takeIf { it.isNotBlank() } ?: "生成失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = errorColor,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onRetry) {
                    Text("重试", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else if (truncated) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = "输出被截断",
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
                TextButton(onClick = onContinue) {
                    Text("继续生成", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ReasoningDrawer(
    reasoning: String,
    expandedByDefault: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(expandedByDefault) }
    val chatColors = LocalChatColors.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .pressable { expanded = !expanded }
                .padding(vertical = Spacing.xxs, horizontal = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = if (expanded) "收起思考过程" else "查看思考过程",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
            ChevronToggle(expanded = expanded)
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

@Composable
private fun ChevronToggle(expanded: Boolean) {
    val chatColors = LocalChatColors.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 90f,
        animationSpec = Motion.Springs.Light,
        label = "chevron",
    )
    Spacer(
        modifier = Modifier
            .size(11.dp)
            .drawBehind {
                val stroke = 1.4.dp.toPx()
                val w = size.width
                val h = size.height
                rotate(rotation, pivot = center) {
                    drawLine(
                        color = chatColors.mutedText,
                        start = Offset(w * 0.18f, h * 0.34f),
                        end = Offset(w * 0.62f, h * 0.64f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = chatColors.mutedText,
                        start = Offset(w * 0.62f, h * 0.64f),
                        end = Offset(w * 0.18f, h * 0.94f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

/**
 * Three breathing dots for the gap between "request sent" and "first token".
 * One shared clock keeps the dots in step.
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = LocalChatColors.current.mutedText,
) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Durations.Typing, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typingPhase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val local = (phase - index).mod(3f)
            val alpha = when {
                local < 0.4f -> 0.3f + local * 1.75f
                local < 0.8f -> 1f - (local - 0.4f) * 1.75f
                else -> 0.3f
            }.coerceIn(0.25f, 1f)
            Spacer(
                modifier = Modifier
                    .size(5.dp)
                    .drawBehind { drawCircle(color = color.copy(alpha = alpha)) },
            )
        }
    }
}
