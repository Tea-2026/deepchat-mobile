package com.deepseek.mobilechat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.ui.AppServices
import com.deepseek.mobilechat.ui.components.AdvancedParamsSheetContent
import com.deepseek.mobilechat.ui.components.AttachmentSheetContent
import com.deepseek.mobilechat.ui.components.MessageActionSheetContent
import com.deepseek.mobilechat.ui.components.ModelPickerSheetContent
import com.deepseek.mobilechat.ui.components.SystemPromptSheetContent
import com.deepseek.mobilechat.ui.motion.sheetEnter
import com.deepseek.mobilechat.ui.motion.sheetExit
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import kotlinx.coroutines.launch

/**
 * Which half-screen panel is open, if any. One value at a time, so the
 * "keyboard and panel are mutually exclusive" rule (spec §5) is a property of
 * the state rather than of five separate booleans.
 */
sealed interface ChatPanel {
    data object Attach : ChatPanel
    data object Model : ChatPanel
    data object Advanced : ChatPanel
    data object Prompt : ChatPanel
    data class Actions(val messageId: MessageId) : ChatPanel
}

/**
 * The single bottom-sheet host.
 *
 * Every panel is a branch of the same sheet: switching from "attach" to
 * "advanced parameters" cross-fades inside one already-open surface instead of
 * closing and reopening a dialog (which used to be a visible hard cut).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanelHost(
    panel: ChatPanel?,
    services: AppServices,
    state: ChatUiState,
    onDismiss: () -> Unit,
    onAttachUri: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onSelectModel: (ModelRef, String) -> Unit,
    onSaveParams: (SamplingParams) -> Unit,
    onResetParams: () -> Unit,
    onSaveSystemPrompt: (String) -> Unit,
    onManageProviders: () -> Unit,
    onCopyMessage: (Message) -> Unit,
    onEditMessage: (MessageId) -> Unit,
    onRegenerateMessage: (MessageId) -> Unit,
    onPinMessage: (MessageId) -> Unit,
    onForkMessage: (MessageId) -> Unit,
    onDeleteMessage: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == null) return

    val chatColors = LocalChatColors.current
    // The new state factory starts Hidden and only knows fully-expanded, which
    // is exactly the behaviour these panels want (they are short lists).
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()

    // The state starts Hidden (that is what the non-deprecated factory asks
    // for); make sure the surface is actually shown. Calling show() when the
    // sheet is already expanding is a no-op, so this is a guard, not a second
    // animation.
    LaunchedEffect(sheetState) {
        runCatching { sheetState.show() }
    }

    // Play the sheet's own exit animation before dropping it from composition.
    fun closeAnimated() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { closeAnimated() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = chatColors.scrim,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = chatColors.divider,
            )
        },
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = panel,
            transitionSpec = {
                // Spec §1: panels arrive in 280ms and leave in 220ms. Clipping
                // is off so a taller panel does not get its edges cut off while
                // the surface grows.
                ContentTransform(
                    targetContentEnter = sheetEnter(),
                    initialContentExit = sheetExit(),
                    targetContentZIndex = 0f,
                    sizeTransform = SizeTransform(clip = false),
                )
            },
            label = "chatPanel",
        ) { current ->
            when (current) {
                ChatPanel.Attach -> AttachmentSheetContent(
                    onPickPhoto = { photo ->
                        onAttachUri(photo.uri)
                        onDismiss()
                    },
                    onTakePhoto = {
                        onDismiss()
                        onTakePhoto()
                    },
                    onPickImage = {
                        onDismiss()
                        onPickImage()
                    },
                    onPickFile = {
                        onDismiss()
                        onPickFile()
                    },
                    onOpenAdvanced = onOpenAdvanced,
                    onOpenSystemPrompt = onOpenSystemPrompt,
                )

                ChatPanel.Model -> ModelPickerSheetContent(
                    services = services,
                    currentRef = state.activeModel.ref,
                    onSelect = { ref, name ->
                        onSelectModel(ref, name)
                        closeAnimated()
                    },
                    onManageProviders = {
                        onDismiss()
                        onManageProviders()
                    },
                )

                ChatPanel.Advanced -> AdvancedParamsSheetContent(
                    initial = state.params,
                    onApply = onSaveParams,
                    onReset = onResetParams,
                    onDismiss = onDismiss,
                )

                ChatPanel.Prompt -> SystemPromptSheetContent(
                    initial = state.systemPrompt,
                    onSave = onSaveSystemPrompt,
                    onDismiss = onDismiss,
                )

                is ChatPanel.Actions -> {
                    val message = state.messages.firstOrNull { it.id == current.messageId }
                    if (message == null) {
                        // The message disappeared (deleted/branch switched) while
                        // the panel was open: close instead of showing an empty sheet.
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    } else {
                        MessageActionSheetContent(
                            message = message,
                            onCopy = {
                                onCopyMessage(message)
                                onDismiss()
                            },
                            onEdit = {
                                onEditMessage(message.id)
                                onDismiss()
                            },
                            onRegenerate = {
                                onRegenerateMessage(message.id)
                                onDismiss()
                            },
                            onPin = {
                                onPinMessage(message.id)
                                onDismiss()
                            },
                            onFork = {
                                onForkMessage(message.id)
                                onDismiss()
                            },
                            onDelete = {
                                onDeleteMessage(message.id)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
        Spacer(
            modifier = Modifier
                .navigationBarsPadding()
                .height(8.dp),
        )
    }
}
