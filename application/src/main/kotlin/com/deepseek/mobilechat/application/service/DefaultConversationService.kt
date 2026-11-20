package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.application.ConversationService
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.FolderTagLimits
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Default [ConversationService].
 *
 * [ensureTitle] is intentionally best-effort and side-effect free on failure:
 * it tries a cheap model call, falls back to the first 16 characters of the
 * first user message, and never lets a provider problem escape into the send
 * flow (docs/architecture.md §11.2). Deleting a conversation cascades through
 * messages, injections and attachments; compactions have no hard delete in the
 * port, so they are marked undone instead of being left active.
 */
class DefaultConversationService(
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
    private val injectionStore: InjectionStore,
    private val compactionStore: CompactionStore,
    private val attachmentStore: AttachmentStore,
    private val presetStore: PresetStore,
    private val providerProfileStore: ProviderProfileStore,
    private val chatCompletion: ChatCompletionPort,
    private val clock: ClockPort,
    private val idPort: IdPort,
) : ConversationService {

    override fun observeConversations(): Flow<List<Conversation>> = conversationStore.observeAll()

    override fun search(query: String): Flow<List<Conversation>> = conversationStore.search(query.trim())

    override suspend fun create(title: String): Outcome<Conversation> {
        val now = clock.nowMillis()
        val conversation = Conversation(
            id = ConversationId(idPort.newId()),
            title = title.trim().ifEmpty { DEFAULT_CONVERSATION_TITLE },
            activeLeafId = null,
            createdAt = now,
            updatedAt = now,
        )
        conversationStore.upsert(conversation)
        return Outcome.Ok(conversation)
    }

    override suspend fun rename(id: ConversationId, title: String): Outcome<Unit> {
        val conversation = conversationStore.get(id)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${id.raw}"))
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Err(AppError.Validation("title must not be empty"))
        }
        conversationStore.upsert(conversation.copy(title = trimmed, updatedAt = clock.nowMillis()))
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: ConversationId): Outcome<Unit> {
        conversationStore.get(id)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${id.raw}"))

        val messageIds = messageStore.list(id).map { it.id }.toSet()
        if (messageIds.isNotEmpty()) messageStore.remove(messageIds)
        injectionStore.list(id).forEach { injectionStore.remove(it.id) }
        val now = clock.nowMillis()
        compactionStore.list(id)
            .filter { it.undoneAt == null }
            .forEach { compactionStore.markUndone(it.id, now) }
        attachmentStore.observe(id).first().forEach { attachmentStore.delete(it.id) }

        conversationStore.delete(id)
        return Outcome.Ok(Unit)
    }

    override suspend fun moveToFolder(id: ConversationId, folderId: FolderId?): Outcome<Unit> {
        val conversation = conversationStore.get(id)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${id.raw}"))
        conversationStore.upsert(conversation.copy(folderId = folderId, updatedAt = clock.nowMillis()))
        return Outcome.Ok(Unit)
    }

    override suspend fun setTags(id: ConversationId, tagIds: Set<TagId>): Outcome<Unit> {
        val conversation = conversationStore.get(id)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${id.raw}"))
        if (tagIds.size > FolderTagLimits.MAX_TAGS_PER_CONVERSATION) {
            return Outcome.Err(
                AppError.Validation(
                    "a conversation can carry at most " +
                        "${FolderTagLimits.MAX_TAGS_PER_CONVERSATION} tags",
                ),
            )
        }
        conversationStore.upsert(conversation.copy(tagIds = tagIds, updatedAt = clock.nowMillis()))
        return Outcome.Ok(Unit)
    }

    override suspend fun setModelOverride(
        conversationId: ConversationId,
        modelRef: ModelRef?,
        params: SamplingParams?,
        thinkingLevel: ThinkingLevel?,
    ): Outcome<Unit> {
        val conversation = conversationStore.get(conversationId)
            ?: return Outcome.Err(
                AppError.Validation("conversation not found: ${conversationId.raw}"),
            )
        val overrides = conversation.overrides.copy(
            modelRef = modelRef,
            params = params,
            thinkingLevel = thinkingLevel,
        )
        conversationStore.upsert(
            conversation.copy(overrides = overrides, updatedAt = clock.nowMillis()),
        )
        return Outcome.Ok(Unit)
    }

    /**
     * Binding a preset is an explicit "use this preset" action, so it also
     * clears the per-conversation model overrides — otherwise the documented
     * priority (§3.4.9, conversation override > preset) would silently keep the
     * old model and the preset would appear not to work.
     */
    override suspend fun applyPreset(conversationId: ConversationId, presetId: PresetId?): Outcome<Unit> {
        val conversation = conversationStore.get(conversationId)
            ?: return Outcome.Err(
                AppError.Validation("conversation not found: ${conversationId.raw}"),
            )
        val overrides = conversation.overrides.copy(
            presetId = presetId,
            modelRef = null,
            params = null,
            thinkingLevel = null,
            systemPrompt = null,
        )
        conversationStore.upsert(
            conversation.copy(overrides = overrides, updatedAt = clock.nowMillis()),
        )
        return Outcome.Ok(Unit)
    }

    override suspend fun ensureTitle(conversationId: ConversationId): Outcome<Unit> {
        val conversation = conversationStore.get(conversationId)
            ?: return Outcome.Err(
                AppError.Validation("conversation not found: ${conversationId.raw}"),
            )
        val messages = messageStore.list(conversationId)
        val firstUser = messages
            .filter { it.role == MessageRole.USER }
            .minWithOrNull(compareBy({ it.seq }, { it.createdAt }))
        val sourceText = firstUser?.text?.trim().orEmpty()
        if (sourceText.isEmpty()) return Outcome.Ok(Unit)
        if (!isPlaceholderTitle(conversation.title, sourceText)) return Outcome.Ok(Unit)

        val generated = generateTitle(sourceText)
        val title = generated ?: fallbackConversationTitle(sourceText)
        if (title.isEmpty()) return Outcome.Ok(Unit)
        conversationStore.upsert(conversation.copy(title = title, updatedAt = clock.nowMillis()))
        return Outcome.Ok(Unit)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private suspend fun generateTitle(sourceText: String): String? {
        val modelRef = resolveDefaultModelRef(presetStore, providerProfileStore) ?: return null
        val request = GenerationRequest(
            modelRef = modelRef,
            systemPrompt = TITLE_SYSTEM_PROMPT,
            messages = listOf(
                PromptMessage(
                    role = MessageRole.USER,
                    parts = listOf(MessagePart.Text(sourceText.take(MAX_TITLE_SOURCE_CHARS))),
                ),
            ),
            params = SamplingParams(),
            thinkingLevel = ThinkingLevel.OFF,
            stream = true,
        )
        val answer = StringBuilder()
        try {
            chatCompletion.stream(request).collect { event ->
                if (event is GenerationEvent.TextDelta) answer.append(event.text)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        return sanitizeGeneratedTitle(answer.toString())
    }
}
