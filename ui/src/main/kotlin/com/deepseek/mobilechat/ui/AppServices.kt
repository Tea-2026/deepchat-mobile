package com.deepseek.mobilechat.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ChatService
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextService
import com.deepseek.mobilechat.application.ConversationService
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.FolderTagStore
import com.deepseek.mobilechat.application.FileImportPort
import com.deepseek.mobilechat.application.GenerationQueue
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.PresetService
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.ProviderService
import com.deepseek.mobilechat.application.SearchService
import com.deepseek.mobilechat.application.TransferService

/**
 * The full set of capabilities the UI is allowed to touch.
 *
 * Implemented by the composition root (`:app`). Screens receive it as a
 * parameter; no screen constructs repositories or services itself.
 */
interface AppServices {
    val chat: ChatService
    val context: ContextService
    val conversations: ConversationService
    val presets: PresetService
    val providers: ProviderService
    val search: SearchService
    val transfer: TransferService
    val queue: GenerationQueue
    val conversationStore: ConversationStore
    val messageStore: MessageStore
    val attachmentStore: AttachmentStore
    val presetStore: PresetStore
    val providerStore: ProviderProfileStore
    val folderTagStore: FolderTagStore
    val contextAssembler: ContextAssembler
    val clock: ClockPort
    val fileImport: FileImportPort
}

val LocalAppServices = staticCompositionLocalOf<AppServices> {
    error("AppServices was not provided by the composition root")
}
