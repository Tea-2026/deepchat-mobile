package com.deepseek.mobilechat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.ui.chat.ActiveModel
import com.deepseek.mobilechat.ui.chat.AttachmentDraft
import com.deepseek.mobilechat.ui.motion.Motion
import com.deepseek.mobilechat.ui.motion.MotionVisibility
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.ShapeTokens
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * The composer (spec §4).
 *
 * Top to bottom: staged attachment chips → the growing input → the control bar
 * (＋ / model + reasoning chip / web toggle …… advanced / system prompt / send).
 *
 * The reasoning *slider* is not on the bar: the chip opens a bubble above itself
 * that holds it ([ModelThinkingChip]), so the bar stays at four elements.
 *
 * The draft text is *controlled* by the caller (it lives in the ViewModel so a
 * half-written message survives switching conversations), everything else here
 * is presentation. The composer never decides to send by itself: send/stop is a
 * three-state morph owned by [SendStopButton].
 */
@Suppress("DEPRECATION")
@Composable
fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    isSearching: Boolean,
    isSending: Boolean,
    pendingAttachments: List<Attachment>,
    attachmentBusy: Boolean,
    searchEnabled: Boolean,
    onToggleSearch: () -> Unit,
    model: ActiveModel,
    thinkingLevel: ThinkingLevel,
    onOpenAttachSheet: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onThinkingLevelChange: (ThinkingLevel) -> Unit,
    onRemoveAttachment: (AttachmentId) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onInputFocused: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val chatColors = LocalChatColors.current
    val canSend = AttachmentDraft.canSend(text, pendingAttachments) &&
        !isGenerating && !isSearching && !isSending

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = chatColors.composerBackground,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            MotionVisibility(
                visible = pendingAttachments.isNotEmpty() || attachmentBusy,
                enter = Motion.enterFromTop(),
                exit = Motion.exitToTop(),
                label = "attachmentStrip",
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(
                        items = pendingAttachments,
                        key = { attachment -> attachment.id.raw },
                    ) { attachment ->
                        StagedAttachmentChip(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (attachmentBusy) {
                        item(key = "attachment-busy") {
                            AttachmentBusyChip(modifier = Modifier.animateItem())
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.Composer,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, chatColors.hairline.copy(alpha = 0.9f)),
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        end = Spacing.sm,
                        top = Spacing.md,
                        bottom = Spacing.sm,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Tapping the empty area of the tray starts typing.
                            .padding(end = Spacing.sm),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = "发消息…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = chatColors.mutedText,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) onInputFocused()
                                },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            minLines = 1,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default,
                            ),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachButton(onClick = onOpenAttachSheet)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            ModelThinkingChip(
                                model = model,
                                level = thinkingLevel,
                                onOpenModelPicker = onOpenModelPicker,
                                onLevelChange = onThinkingLevelChange,
                                // The only elastic control on the row: it takes
                                // whatever is left and ellipsises past that.
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            SearchToggle(
                                enabled = searchEnabled,
                                searching = isSearching,
                                onToggle = onToggleSearch,
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AnimatedContent(
                            targetState = isSending,
                            transitionSpec = { Motion.morph(initialScale = 0.6f, targetScale = 0.6f) },
                            label = "sendSlot",
                        ) { sending ->
                            if (sending) {
                                Box(
                                    modifier = Modifier.size(40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                SendStopButton(
                                    isGenerating = isGenerating,
                                    isSearching = isSearching,
                                    enabled = canSend,
                                    onClick = {
                                        if (isGenerating || isSearching) onStop() else onSend()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
