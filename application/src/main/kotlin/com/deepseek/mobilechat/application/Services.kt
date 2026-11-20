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
    ): Outcome<SendResult>

    suspend fun regenerate(messageId: MessageId): Outcome<Unit>

    /** In-place edit when the message is an active-path leaf, otherwise forks. */
    suspend fun editMessage(messageId: MessageId, text: String): Outcome<EditResult>

    /** Makes [messageId] the active leaf, switching the visible branch. */
    suspend fun switchToBranch(messageId: MessageId): Outcome<Unit>

    suspend fun deleteSubtree(messageId: MessageId): Outcome<Unit>

    suspend fun cancel(conversationId: ConversationId): Outcome<Unit>

    /** Only legal after [com.deepseek.mobilechat.domain.FinishReason.LENGTH]. */
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
