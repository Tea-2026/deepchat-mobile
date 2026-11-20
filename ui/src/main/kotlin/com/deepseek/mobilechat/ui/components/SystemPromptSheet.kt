package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * The per-conversation system prompt (spec §5).
 *
 * Written into `ConversationOverrides.systemPrompt` through the conversation
 * store — the same field the context assembler already reads, so nothing about
 * the contract changes.
 */
@Suppress("DEPRECATION")
@Composable
fun SystemPromptSheetContent(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalChatColors.current
    var text by rememberSaveable(initial) { mutableStateOf(initial) }

    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeading(
            title = "系统提示词",
            hint = "仅本会话 · 每次请求都会带上",
        )
        SheetHintCard(text = "留空则沿用预设的系统提示词。改动只影响这个会话，不会改到预设。")

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, chatColors.hairline),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 320.dp)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 16,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "例如：你是一个简洁、直接的技术助手……",
                                style = MaterialTheme.typography.bodyMedium,
                                color = chatColors.mutedText,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${text.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = chatColors.mutedText,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (initial.isNotEmpty()) {
                    PillButton(
                        label = "清空",
                        onClick = {
                            text = ""
                            onSave("")
                        },
                        primary = false,
                    )
                }
                PillButton(
                    label = "保存",
                    onClick = {
                        onSave(text.trim())
                        onDismiss()
                    },
                    primary = true,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
    }
}
