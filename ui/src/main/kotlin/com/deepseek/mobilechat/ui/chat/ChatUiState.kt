package com.deepseek.mobilechat.ui.chat

import androidx.compose.runtime.Immutable
import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel

/**
 * Where the currently visible message sits inside its sibling group. Built by
 * the ViewModel so the UI never has to walk the message tree itself.
 */
@Immutable
data class BranchPosition(
    val index: Int,
    val count: Int,
    val previousId: MessageId?,
    val nextId: MessageId?,
) {
    val canGoPrevious: Boolean get() = previousId != null
    val canGoNext: Boolean get() = nextId != null
    val label: String get() = "${index + 1}/$count"
}

/**
 * What the composer's model chip and the transcript headers should show.
 *
 * Resolved by the ViewModel (which is the only place that knows about provider
 * profiles), so no composable has to guess a brand from a model id.
 */
@Immutable
data class ActiveModel(
    val ref: ModelRef? = null,
    val providerName: String = "",
    val displayName: String = "默认模型",
    val protocol: ProviderProtocol? = null,
) {
    val hasModel: Boolean get() = ref != null
}

@Immutable
data class ChatUiState(
    /** `null` while the conversation is an unsaved draft (spec §2). */
    val conversationId: ConversationId? = null,
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val branches: Map<MessageId, BranchPosition> = emptyMap(),
    val injections: List<ContextInjection> = emptyList(),
    val isGenerating: Boolean = false,
    /** A web search is running for the message the user just sent. */
    val isSearching: Boolean = false,
    /** "联网" toggle in the composer; off by default so nothing leaves the device unasked. */
    val searchEnabled: Boolean = false,
    val compactionProposal: CompactionProposal? = null,
    val title: String = "新对话",
    val subtitle: String = "",
    /** Attachments staged for the next send; cleared once the message starts. */
    val pendingAttachments: List<Attachment> = emptyList(),
    /** A file is being imported/saved right now (picker result in flight). */
    val attachmentBusy: Boolean = false,
    // ---- v2 draft conversation -------------------------------------------
    /** The composer's text. Lives here so it survives switching conversations. */
    val draftText: String = "",
    /** First send of a draft is in flight (conversation is being created). */
    val isSending: Boolean = false,
    val activeModel: ActiveModel = ActiveModel(),
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val params: SamplingParams = SamplingParams(),
    /** The conversation carries explicit sampling overrides. */
    val paramsOverridden: Boolean = false,
    val systemPrompt: String = "",
    /** Measured thinking time per message, filled live by the ViewModel. */
    val thinkingSeconds: Map<MessageId, Int> = emptyMap(),
    /**
     * A conversation is selected but its first store emission has not arrived.
     * Without this the screen flashes "这个会话还是空的" for one frame every time
     * the user picks a conversation from the drawer.
     */
    val isLoadingConversation: Boolean = false,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** True until the first message of a new conversation has been accepted. */
    val isDraft: Boolean get() = conversationId == null

    val canSend: Boolean
        get() = draftText.isNotBlank() || pendingAttachments.isNotEmpty()

    /** True while anything is in flight (generation, search, draft creation). */
    val isBusy: Boolean get() = isGenerating || isSearching || isSending
}

/**
 * Off / Low / High / Max — the level name in the top-bar subtitle.
 *
 * English on purpose: the product owner asked for the stop names to be English
 * everywhere they are *shown* ("思考 Max", not "思考 最高"), matching the slider
 * and the chip. The Chinese wording survives only in the TalkBack description
 * (`thinkingDescription`), where a screen reader needs the app's language.
 */
fun thinkingLabel(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.OFF -> "Off"
    ThinkingLevel.LOW -> "Low"
    ThinkingLevel.HIGH -> "High"
    ThinkingLevel.MAX -> "Max"
}

/** Turns a domain error into something a person can act on. */
fun AppError.toUserMessage(): String = when (this) {
    is AppError.Validation -> message.ifBlank { "参数不合法" }
    is AppError.BranchConflict -> "分支状态已变化，请重试"
    is AppError.BudgetExceeded -> "上下文超出预算，需要先压缩"
    is AppError.ProviderAuth -> "密钥无效或权限不足，请到设置中检查"
    is AppError.ProviderRateLimit -> buildString {
        append("请求过于频繁")
        if (retryAfterSeconds != null) append("，约 $retryAfterSeconds 秒后可重试")
    }

    is AppError.ProviderProtocol -> "模型返回的内容无法解析"
    is AppError.ProviderUnavailable -> "模型服务暂时不可用"
    is AppError.Network -> "网络不可用，请检查连接"
    is AppError.Timeout -> "请求超时，请重试"
    is AppError.Storage -> "本地存储读写失败"
    is AppError.SecretMissing -> "缺少 API Key，请先到设置里配置"
    is AppError.Unsupported -> "当前模型不支持该操作"
    is AppError.Unknown -> message.ifBlank { "发生未知错误" }
}
