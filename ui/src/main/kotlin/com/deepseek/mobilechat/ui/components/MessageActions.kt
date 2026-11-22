package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * Long-press actions for one message (spec §3).
 *
 * The v1 strip lived *inside* the message column and scrolled horizontally,
 * which clipped on narrow screens and pushed the transcript around. This is a
 * vertical bottom-panel list instead: nothing overflows, every row is a full
 * tap target, and the destructive row is separated visually.
 *
 * Rendered inside the caller's `ModalBottomSheet`; it owns no container.
 */
@Composable
fun MessageActionSheetContent(
    message: Message,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onPin: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    val isAssistant = message.role == MessageRole.ASSISTANT
    val streaming = message.status == MessageStatus.STREAMING
    val canEdit = message.text.isNotBlank() && !streaming
    val canRegenerate = isAssistant && !streaming
    val canFork = !streaming

    Column(modifier = modifier.fillMaxWidth()) {
        MessagePreview(message = message)

        SheetAction(
            label = "复制",
            hint = if (message.text.isBlank()) "这条消息没有文字" else null,
            enabled = message.text.isNotBlank(),
            icon = { CopyIcon(modifier = Modifier.iconSize(18.dp)) },
            onClick = onCopy,
        )
        if (canEdit) {
            SheetAction(
                label = "编辑",
                hint = if (isAssistant) "保存后会另存为一个新分支" else null,
                icon = { EditIcon(modifier = Modifier.iconSize(18.dp)) },
                onClick = onEdit,
            )
        }
        if (canRegenerate) {
            SheetAction(
                label = "重新生成",
                icon = { RefreshIcon(modifier = Modifier.iconSize(18.dp)) },
                onClick = onRegenerate,
            )
        }
        // The label is read from the snapshot (`Message.pinned`), so the sheet can
        // never offer the wrong direction after another device or branch changed it.
        SheetAction(
            label = if (message.pinned) "取消置顶" else "置顶",
            icon = { PinIcon(modifier = Modifier.iconSize(18.dp)) },
            onClick = onPin,
        )
        if (canFork) {
            SheetAction(
                label = "从此分支",
                hint = "把这里设为当前对话的接续点",
                icon = { BranchIcon(modifier = Modifier.iconSize(18.dp)) },
                onClick = onFork,
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            shape = RoundedCornerShape(1.dp),
            color = chatColors.divider,
        ) {}

        // Destructive and immediate: the hint under the label is the confirmation,
        // so tapping it does not open a second dialog (spec §3).
        SheetAction(
            label = "删除",
            hint = "这条消息以及它之后的内容都会被删除",
            destructive = true,
            icon = { TrashIcon(modifier = Modifier.iconSize(18.dp)) },
            onClick = onDelete,
        )
    }
}

@Composable
private fun MessagePreview(message: Message) {
    val chatColors = LocalChatColors.current
    val preview = remember(message) {
        message.text.trim().replace(Regex("\\s+"), " ").take(120)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.sm),
    ) {
        Text(
            text = if (message.role == MessageRole.USER) "你的消息" else "助手回复",
            style = MaterialTheme.typography.labelSmall,
            color = chatColors.mutedText,
        )
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val chatColors = LocalChatColors.current
    val contentColor = when {
        !enabled -> chatColors.mutedText.copy(alpha = 0.45f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Surface(
            shape = CircleShape,
            color = if (destructive) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor,
                ) {
                    icon()
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * In-place editor shown instead of the message body. Saving either rewrites the
 * message (leaf) or forks it — the ViewModel decides, this is only the input.
 */
@Suppress("DEPRECATION")
@Composable
fun InlineMessageEditor(
    initialText: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    val chatColors = LocalChatColors.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = modifier) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .widthIn(min = 120.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 10,
            )
        }
        Row(
            modifier = Modifier.padding(top = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            TextButton(onClick = onCancel) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(
                    "保存",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        chatColors.mutedText
                    },
                )
            }
        }
    }
}
