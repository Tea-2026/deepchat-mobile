package com.deepseek.mobilechat.domain

// ---------------------------------------------------------------------------
// Identifiers
// ---------------------------------------------------------------------------

@JvmInline
value class ConversationId(val raw: String)

@JvmInline
value class MessageId(val raw: String)

@JvmInline
value class AttachmentId(val raw: String)

@JvmInline
value class ProviderId(val raw: String)

@JvmInline
value class ModelId(val raw: String)

@JvmInline
value class PresetId(val raw: String)

@JvmInline
value class InjectionId(val raw: String)

@JvmInline
value class CompactionId(val raw: String)

@JvmInline
value class FolderId(val raw: String)

@JvmInline
value class TagId(val raw: String)

// ---------------------------------------------------------------------------
// Enumerations
// ---------------------------------------------------------------------------

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

enum class MessageStatus { STREAMING, COMPLETE, CANCELLED, FAILED }

enum class FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, ERROR, CANCELLED }

/**
 * Reasoning effort. Four levels so the picker can read Off / Low / High / Max.
 * Providers that expose fewer levels clamp (see the protocol adapters).
 * Stored as its [name] in the presets table.
 */
enum class ThinkingLevel { OFF, LOW, HIGH, MAX }

enum class ProviderProtocol { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

enum class AttachmentKind { TEXT, IMAGE, OTHER }

enum class InjectionScope { EPHEMERAL, PERSISTENT }

enum class CompactionKind { SUMMARY, MANUAL, MEMORY }

enum class BlockSource { SYSTEM, USER_INJECTED, SEARCH, FILE_EXTRACT, COMPACTION, MEMORY }

// ---------------------------------------------------------------------------
// Message content
// ---------------------------------------------------------------------------

data class Citation(val title: String, val url: String)

data class SearchHit(val title: String, val url: String, val snippet: String)

sealed interface MessagePart {
    data class Text(val text: String) : MessagePart

    data class Image(val attachmentId: AttachmentId) : MessagePart

    data class FileRef(val attachmentId: AttachmentId) : MessagePart

    /** Provider thinking block. [signature] must be preserved and echoed back verbatim. */
    data class Reasoning(val text: String, val signature: String? = null) : MessagePart

    data class ContextBlock(
        val title: String,
        val body: String,
        val source: BlockSource,
        val citations: List<Citation> = emptyList(),
    ) : MessagePart

    data class SearchResults(val query: String, val hits: List<SearchHit>) : MessagePart

    data class ToolCall(val callId: String, val name: String, val argumentsJson: String) : MessagePart

    data class ToolResult(val callId: String, val ok: Boolean, val content: String) : MessagePart
}

// ---------------------------------------------------------------------------
// Model descriptor / parameters
// ---------------------------------------------------------------------------

data class ModelRef(val providerId: ProviderId, val modelId: ModelId)

data class SamplingParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
)

/** Money unit a price is quoted in. The app displays everything in CNY. */
enum class Currency(val symbol: String) {
    USD("$"),
    CNY("¥"),
}

/**
 * Per-1M-token prices, quoted in [currency].
 *
 * [cachedInputPerMTok] is the discounted price for prompt tokens the provider
 * served from its context cache (DeepSeek bills those at ~1/50 of a miss).
 * `null` means "this provider does not price cache hits separately".
 */
data class ModelPricing(
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val cachedInputPerMTok: Double? = null,
    val currency: Currency = Currency.USD,
)

data class ModelCapabilities(
    val vision: Boolean = false,
    val tools: Boolean = false,
    val streaming: Boolean = true,
    val thinkingLevels: Set<ThinkingLevel> = emptySet(),
    val contextWindow: Int = 8192,
    val maxOutputTokens: Int = 4096,
)

data class ModelDescriptor(
    val id: ModelId,
    val displayName: String,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val pricing: ModelPricing? = null,
)

data class ModelInfo(
    val ref: ModelRef,
    val params: SamplingParams,
    val thinkingLevel: ThinkingLevel,
)

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
    /**
     * The slice of [promptTokens] the provider served from its cache. Billed at
     * the discounted cache-hit price, so it must be tracked separately or every
     * long conversation looks more expensive than it is.
     */
    val cachedPromptTokens: Int = 0,
    /** Estimated cost in **CNY**, written once when the message completes. */
    val cost: Double? = null,
)

// ---------------------------------------------------------------------------
// Message / conversation
// ---------------------------------------------------------------------------

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val parentId: MessageId?,
    val seq: Long,
    val role: MessageRole,
    val parts: List<MessagePart>,
    val status: MessageStatus,
    val createdAt: Long,
    val editedAt: Long? = null,
    val pinned: Boolean = false,
    val modelInfo: ModelInfo? = null,
    val usage: Usage? = null,
    val finishReason: FinishReason? = null,
    val error: String? = null,
) {
    val text: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    val hasImage: Boolean
        get() = parts.any { it is MessagePart.Image }
}

data class ConversationOverrides(
    val presetId: PresetId? = null,
    val modelRef: ModelRef? = null,
    val params: SamplingParams? = null,
    val thinkingLevel: ThinkingLevel? = null,
    val systemPrompt: String? = null,
)

data class Conversation(
    val id: ConversationId,
    val title: String,
    val activeLeafId: MessageId?,
    val folderId: FolderId? = null,
    val tagIds: Set<TagId> = emptySet(),
    val overrides: ConversationOverrides = ConversationOverrides(),
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

// ---------------------------------------------------------------------------
// Preset / provider / organisation
// ---------------------------------------------------------------------------

object ContextStrategyIds {
    const val NONE = "none"
    const val CONFIRM_COMPACTION = "confirm-compaction"
    const val DROP_OLDEST = "drop-oldest"
}

data class Preset(
    val id: PresetId,
    val name: String,
    val systemPrompt: String = "",
    val modelRef: ModelRef? = null,
    val params: SamplingParams = SamplingParams(),
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val contextStrategyId: String = ContextStrategyIds.CONFIRM_COMPACTION,
    val isDefault: Boolean = false,
)

data class ProviderProfile(
    val id: ProviderId,
    val name: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    /** Opaque key into [com.deepseek.mobilechat.application.port.SecretStore]. */
    val secretRef: String,
    val defaultModelId: ModelId? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val createdAt: Long,
)

data class Folder(val id: FolderId, val name: String, val order: Int = 0)

data class Tag(val id: TagId, val name: String, val color: Long = 0xFF6B7280)

object FolderTagLimits {
    const val MAX_TAGS_PER_CONVERSATION = 32
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

data class Attachment(
    val id: AttachmentId,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val sha256: String,
    val kind: AttachmentKind,
    val extractedText: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long,
)

// ---------------------------------------------------------------------------
// Context injection / compaction
// ---------------------------------------------------------------------------

data class ContextInjection(
    val id: InjectionId,
    val conversationId: ConversationId,
    val anchorMessageId: MessageId?,
    val scope: InjectionScope,
    val position: Int,
    val content: String,
    val createdAt: Long,
)

data class Compaction(
    val id: CompactionId,
    val conversationId: ConversationId,
    val fromMessageId: MessageId,
    val toMessageId: MessageId,
    val summary: String,
    val kind: CompactionKind,
    val createdAt: Long,
    val undoneAt: Long? = null,
)

// ---------------------------------------------------------------------------
// Budget
// ---------------------------------------------------------------------------

data class ContextBudget(val limit: Int, val used: Int) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val overflow: Int get() = (used - limit).coerceAtLeast(0)
    val isOverBudget: Boolean get() = used > limit
    val ratio: Float get() = if (limit <= 0) 0f else used.toFloat() / limit
}
