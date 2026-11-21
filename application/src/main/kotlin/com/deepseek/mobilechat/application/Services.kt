package com.deepseek.mobilechat.application

import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.FolderId
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Chat commands
// ---------------------------------------------------------------------------

sealed interface SendResult {
    data class Started(val userMessageId: MessageId, val assistantMessageId: MessageId) : SendResult
    data class NeedsConfirmation(val proposal: CompactionProposal) : SendResult
}

data class EditResult(val messageId: MessageId, val forked: Boolean)

interface ChatService {
    /**
     * Persists the user message, assembles context, and enqueues generation.
     * Returns [SendResult.NeedsConfirmation] instead of silently truncating when
     * the context exceeds budget and the active strategy demands confirmation.
     */
    suspend fun send(
        conversationId: ConversationId,
        text: String,
        attachmentIds: List<AttachmentId> = emptyList(),
        ephemeralInjection: String? = null,
        /**
         * Web results that were injected into this turn. They are stored on the
         * user message as a [com.deepseek.mobilechat.domain.MessagePart.SearchResults]
         * part so the answer can still show its sources after a restart.
         */
        searchHits: List<SearchHit> = emptyList(),
    ): Outcome<SendResult>

    suspend fun regenerate(messageId: MessageId): Outcome<Unit>

    /** In-place edit when the message is an active-path leaf, otherwise forks. */
    suspend fun editMessage(messageId: MessageId, text: String): Outcome<EditResult>

    /** Makes [messageId] the active leaf, switching the visible branch. */
    suspend fun switchToBranch(messageId: MessageId): Outcome<Unit>

    suspend fun deleteSubtree(messageId: MessageId): Outcome<Unit>

    suspend fun cancel(conversationId: ConversationId): Outcome<Unit>

    /**
     * Extends an assistant turn that stopped before a natural end: a
     * [com.deepseek.mobilechat.domain.FinishReason.LENGTH] truncation, or a
     * turn left interrupted/cancelled/errored (`CANCELLED` / `FAILED`).
     * The message is appended to in place; a completed natural reply is still
     * rejected.
     */
    suspend fun continueGeneration(messageId: MessageId): Outcome<Unit>

    suspend fun setPinned(messageId: MessageId, pinned: Boolean): Outcome<Unit>
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

interface ContextAssembler {
    /** Pure preview: what would be sent for the conversation's active leaf. */
    suspend fun assemble(conversationId: ConversationId): Outcome<ContextAssembly>
}

/**
 * Resolves the token budget for a model. Kept tiny on purpose: the first
 * version is a constant lookup, later it can be backed by a cached catalog.
 */
interface ModelBudgetLookup {
    fun contextWindow(modelRef: ModelRef): Int
}

interface ContextService {
    /** Pure preview: what would be sent for the current active leaf. */
    suspend fun assemble(conversationId: ConversationId): Outcome<ContextAssembly>

    suspend fun planCompaction(conversationId: ConversationId): Outcome<CompactionProposal>

    suspend fun applyCompaction(proposal: CompactionProposal): Outcome<Compaction>

    suspend fun undoCompaction(compactionId: CompactionId): Outcome<Unit>

    suspend fun addInjection(
        conversationId: ConversationId,
        content: String,
        scope: InjectionScope,
        anchorMessageId: MessageId? = null,
    ): Outcome<ContextInjection>

    suspend fun removeInjection(injectionId: InjectionId): Outcome<Unit>

    fun observeInjections(conversationId: ConversationId): Flow<List<ContextInjection>>

    fun observeCompactions(conversationId: ConversationId): Flow<List<Compaction>>
}

// ---------------------------------------------------------------------------
// Conversations
// ---------------------------------------------------------------------------

interface ConversationService {
    fun observeConversations(): Flow<List<Conversation>>

    fun search(query: String): Flow<List<Conversation>>

    suspend fun create(title: String = ""): Outcome<Conversation>

    suspend fun rename(id: ConversationId, title: String): Outcome<Unit>

    suspend fun delete(id: ConversationId): Outcome<Unit>

    suspend fun moveToFolder(id: ConversationId, folderId: FolderId?): Outcome<Unit>

    suspend fun setTags(id: ConversationId, tagIds: Set<TagId>): Outcome<Unit>

    suspend fun setModelOverride(
        conversationId: ConversationId,
        modelRef: ModelRef?,
        params: SamplingParams?,
        thinkingLevel: ThinkingLevel?,
    ): Outcome<Unit>

    suspend fun applyPreset(conversationId: ConversationId, presetId: PresetId?): Outcome<Unit>

    /** Ensures the conversation has a non-empty title; generates one when possible. */
    suspend fun ensureTitle(conversationId: ConversationId): Outcome<Unit>
}

// ---------------------------------------------------------------------------
// Presets / providers
// ---------------------------------------------------------------------------

interface PresetService {
    fun observe(): Flow<List<Preset>>
    suspend fun save(preset: Preset): Outcome<Unit>
    suspend fun delete(id: PresetId): Outcome<Unit>
    suspend fun setDefault(id: PresetId): Outcome<Unit>
}

interface ProviderService {
    fun observe(): Flow<List<ProviderProfile>>
    suspend fun upsert(profile: ProviderProfile, apiKey: String? = null): Outcome<Unit>
    suspend fun delete(id: ProviderId): Outcome<Unit>
    suspend fun test(profile: ProviderProfile): Outcome<String>
    suspend fun listModels(profile: ProviderProfile): Outcome<List<ModelDescriptor>>
    suspend fun balance(profile: ProviderProfile): BalanceResult
}

// ---------------------------------------------------------------------------
// Search / transfer
// ---------------------------------------------------------------------------

interface SearchService {
    suspend fun webSearch(query: String): Outcome<SearchResponse>
}

interface TransferService {
    suspend fun exportConversations(
        conversationIds: List<ConversationId>,
        format: ExportFormat,
        targetUri: String,
        includeAttachments: Boolean,
    ): Outcome<Unit>

    suspend fun importConversations(sourceUri: String): Outcome<Int>
}

// ---------------------------------------------------------------------------
// Generation queue
// ---------------------------------------------------------------------------

enum class QueueState { QUEUED, RUNNING, PAUSED }

data class QueueItem(
    val conversationId: ConversationId,
    val messageId: MessageId,
    val state: QueueState,
    val enqueuedAt: Long,
)

interface GenerationQueue {
    fun observe(): Flow<List<QueueItem>>
    fun pause()
    fun resume()
    suspend fun cancel(conversationId: ConversationId)
}

// ---------------------------------------------------------------------------
// Usage / spend
// ---------------------------------------------------------------------------

/**
 * Token and money totals. [costCny] is an estimate and is always in CNY: a
 * provider that quotes in USD is converted when the price is resolved, so no
 * caller ever has to know which one it is talking about.
 */
data class UsageTotals(
    val promptTokens: Long = 0,
    val cachedPromptTokens: Long = 0,
    val completionTokens: Long = 0,
    val costCny: Double = 0.0,
    val messageCount: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    operator fun plus(other: UsageTotals): UsageTotals = UsageTotals(
        promptTokens = promptTokens + other.promptTokens,
        cachedPromptTokens = cachedPromptTokens + other.cachedPromptTokens,
        completionTokens = completionTokens + other.completionTokens,
        costCny = costCny + other.costCny,
        messageCount = messageCount + other.messageCount,
    )
}

data class UsageDay(val dayStartMillis: Long, val totals: UsageTotals)

data class UsageByModel(val providerId: String, val modelId: String, val totals: UsageTotals)

/** Everything the usage page needs, assembled in one pass. */
data class UsageReport(
    val windowDays: Int,
    val totals: UsageTotals,
    /** Oldest day first, with empty days already filled in for the chart. */
    val days: List<UsageDay>,
    /** Costliest model first. */
    val byModel: List<UsageByModel>,
)

interface UsageService {
    suspend fun report(windowDays: Int = 30): Outcome<UsageReport>

    suspend fun conversationTotals(conversationId: ConversationId): UsageTotals
}
