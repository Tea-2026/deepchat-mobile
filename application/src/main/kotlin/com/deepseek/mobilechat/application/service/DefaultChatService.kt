package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ChatService
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.EditResult
import com.deepseek.mobilechat.application.GenerationQueue
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SendResult
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree

/**
 * Default [ChatService].
 *
 * The send pipeline is fixed by docs/architecture.md §8.1:
 *
 * ```
 * persist user message -> move activeLeaf -> assemble context
 *   -> budget policy (confirmation, never silent truncation)
 *   -> persist STREAMING assistant placeholder -> enqueue generation
 * ```
 *
 * Two invariants matter for callers:
 *
 * - [send] returns as soon as the placeholder is enqueued
 *   ([SendResult.Started]); the stream is owned by the queue, so use cases
 *   never block on the network.
 * - A user message is only committed together with its assistant turn: every
 *   refusal before the placeholder exists (assembly error, budget rejection,
 *   confirmation request) rolls the provisional user message back. The caller
 *   owns the draft text and re-sends it when the turn can proceed; the tree
 *   therefore only ever holds accepted turns.
 *
 * The command surface is split into two focused collaborators:
 * [AssistantTurnGenerator] (regenerate / continue, both restart a stream) and
 * [ChatTreeEditor] (edit / switch / delete / pin, no I/O beyond the stores).
 *
 * All expected failures are [Outcome.Err]; program errors still throw.
 */
class DefaultChatService(
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
    private val injectionStore: InjectionStore,
    private val contextAssembler: ContextAssembler,
    private val scheduler: GenerationScheduler,
    private val chatCompletion: ChatCompletionPort,
    private val clock: ClockPort,
    private val idPort: IdPort,
    @Suppress("unused") private val tokenEstimator: TokenEstimatorPort,
    private val settingsStore: SettingsStore,
    private val modelCatalog: ModelCatalogCache,
    private val providerProfileStore: ProviderProfileStore,
    private val presetStore: PresetStore,
    private val attachmentStore: AttachmentStore,
    strategies: Map<String, ContextStrategy>,
) : ChatService {

    private val registry = GenerationRegistry()
    private val resolver = ContextStrategyResolver(presetStore, strategies)
    private val runner = ChatGenerationRunner(
        messageStore = messageStore,
        chatCompletion = chatCompletion,
        clock = clock,
        settingsStore = settingsStore,
        modelCatalog = modelCatalog,
        providerProfileStore = providerProfileStore,
        registry = registry,
    )
    private val turns = AssistantTurnGenerator(
        conversationStore = conversationStore,
        messageStore = messageStore,
        contextAssembler = contextAssembler,
        scheduler = scheduler,
        chatCompletion = chatCompletion,
        clock = clock,
        idPort = idPort,
        resolver = resolver,
        runner = runner,
    )
    private val editor = ChatTreeEditor(
        messageStore = messageStore,
        conversationStore = conversationStore,
        clock = clock,
        idPort = idPort,
    )

    override suspend fun send(
        conversationId: ConversationId,
        text: String,
        attachmentIds: List<AttachmentId>,
        ephemeralInjection: String?,
    ): Outcome<SendResult> {
        val conversation = conversationStore.get(conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
        if (text.isBlank() && attachmentIds.isEmpty()) {
            return Outcome.Err(AppError.Validation("message has neither text nor attachments"))
        }
        val parts = when (val built = buildUserParts(text, attachmentIds)) {
            is Outcome.Ok -> built.value
            is Outcome.Err -> return Outcome.Err(built.error)
        }

        val existing = messageStore.list(conversationId)
        val tree = MessageTree.from(existing)
        val parentId = conversation.activeLeafId
        if (parentId != null && existing.none { it.id == parentId }) {
            // Tree rule 2: a message may never point at a parent outside the
            // conversation. Refuse before writing anything.
            return Outcome.Err(
                AppError.Validation("active leaf not found: ${parentId.raw}"),
            )
        }
        val now = clock.nowMillis()
        val userMessage = Message(
            id = MessageId(idPort.newId()),
            conversationId = conversationId,
            parentId = parentId,
            seq = tree.nextSeq(parentId),
            role = MessageRole.USER,
            parts = parts,
            status = MessageStatus.COMPLETE,
            createdAt = now,
        )
        messageStore.append(userMessage)

        var current = conversation.copy(
            activeLeafId = userMessage.id,
            updatedAt = now,
            title = conversation.title.ifBlank { temporaryConversationTitle(text) },
        )
        conversationStore.upsert(current)

        val assembly = when (val outcome = assemble(conversationId, ephemeralInjection)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> {
                rollbackProvisionalUser(conversation, userMessage)
                return Outcome.Err(outcome.error)
            }
        }

        if (assembly.budget.isOverBudget) {
            val strategy = when (val resolved = resolver.resolve(current)) {
                is Outcome.Ok -> resolved.value
                is Outcome.Err -> {
                    rollbackProvisionalUser(conversation, userMessage)
                    return Outcome.Err(resolved.error)
                }
            }
            when (val plan = applyBudgetPolicy(assembly, strategy, chatCompletion)) {
                is ContextPlan.Fits -> Unit
                is ContextPlan.RequiresConfirmation -> {
                    // The turn is not accepted yet, and the caller re-sends the
                    // same text after applying the compaction (see
                    // ChatViewModel.confirmCompaction). Roll the provisional
                    // user message and the conversation cursor back so nothing
                    // is duplicated and the tree only holds accepted turns; the
                    // text itself stays in the caller's pending draft.
                    rollbackProvisionalUser(conversation, userMessage)
                    return Outcome.Ok(SendResult.NeedsConfirmation(plan.proposal))
                }

                is ContextPlan.Rejected -> {
                    rollbackProvisionalUser(conversation, userMessage)
                    return Outcome.Err(plan.error)
                }
            }
        }

        val seq = MessageTree.from(existing + userMessage).nextSeq(userMessage.id)
        val assistant = assistantPlaceholder(
            id = MessageId(idPort.newId()),
            conversationId = conversationId,
            parentId = userMessage.id,
            seq = seq,
            createdAt = clock.nowMillis(),
            assembly = assembly,
        )
        messageStore.append(assistant)
        current = current.copy(activeLeafId = assistant.id, updatedAt = assistant.createdAt)
        conversationStore.upsert(current)

        return when (val submitted = scheduler.submit(conversationId, assistant.id) {
                runner.run(assistant.id, requestForAssembly(assembly), appendToExisting = false)
            }) {
            is Outcome.Ok -> Outcome.Ok(SendResult.Started(userMessage.id, assistant.id))
            is Outcome.Err -> {
                messageStore.update(
                    assistant.copy(
                        status = MessageStatus.FAILED,
                        finishReason = FinishReason.ERROR,
                        error = submitted.error.message,
                    ),
                )
                Outcome.Err(submitted.error)
            }
        }
    }

    override suspend fun regenerate(messageId: MessageId): Outcome<Unit> =
        turns.regenerate(messageId)

    override suspend fun continueGeneration(messageId: MessageId): Outcome<Unit> =
        turns.continueGeneration(messageId)

    override suspend fun editMessage(messageId: MessageId, text: String): Outcome<EditResult> =
        editor.editMessage(messageId, text)

    override suspend fun switchToBranch(messageId: MessageId): Outcome<Unit> =
        editor.switchToBranch(messageId)

    override suspend fun deleteSubtree(messageId: MessageId): Outcome<Unit> =
        editor.deleteSubtree(messageId)

    override suspend fun setPinned(messageId: MessageId, pinned: Boolean): Outcome<Unit> =
        editor.setPinned(messageId, pinned)

    override suspend fun cancel(conversationId: ConversationId): Outcome<Unit> {
        // Queued items only exist inside the concrete queue; the scheduling
        // seam does not expose them. When the scheduler is the queue (the
        // normal wiring) its items are dropped as well.
        (scheduler as? GenerationQueue)?.cancel(conversationId)
        val alreadyStopping = registry.cancelConversation(conversationId)
        messageStore.list(conversationId)
            .filter { it.status == MessageStatus.STREAMING && it.id !in alreadyStopping }
            .forEach { message ->
                messageStore.update(
                    message.copy(
                        status = MessageStatus.CANCELLED,
                        finishReason = FinishReason.CANCELLED,
                    ),
                )
            }
        return Outcome.Ok(Unit)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private suspend fun buildUserParts(
        text: String,
        attachmentIds: List<AttachmentId>,
    ): Outcome<List<MessagePart>> {
        val parts = ArrayList<MessagePart>(attachmentIds.size + 1)
        if (text.isNotBlank()) parts += MessagePart.Text(text)
        attachmentIds.forEach { id ->
            val attachment = attachmentStore.get(id)
                ?: return Outcome.Err(AppError.Validation("attachment not found: ${id.raw}"))
            parts += when (attachment.kind) {
                AttachmentKind.IMAGE -> MessagePart.Image(attachmentId = id)
                else -> MessagePart.FileRef(attachmentId = id)
            }
        }
        return Outcome.Ok(parts)
    }

    /**
     * Assembles the context for this turn. An ephemeral injection is written
     * before assembly and removed immediately after, so it reaches exactly one
     * request instead of leaking into every later turn.
     */
    private suspend fun assemble(
        conversationId: ConversationId,
        ephemeralInjection: String?,
    ): Outcome<ContextAssembly> {
        if (ephemeralInjection.isNullOrBlank()) return contextAssembler.assemble(conversationId)
        val injection = ContextInjection(
            id = InjectionId(idPort.newId()),
            conversationId = conversationId,
            anchorMessageId = null,
            scope = InjectionScope.EPHEMERAL,
            position = nextInjectionPosition(conversationId),
            content = ephemeralInjection,
            createdAt = clock.nowMillis(),
        )
        injectionStore.add(injection)
        return try {
            contextAssembler.assemble(conversationId)
        } finally {
            injectionStore.remove(injection.id)
        }
    }

    private suspend fun nextInjectionPosition(conversationId: ConversationId): Int =
        (injectionStore.list(conversationId).maxOfOrNull { it.position } ?: -1) + 1

    /**
     * Removes the provisional user message and restores the conversation
     * cursor when the turn was refused before any assistant placeholder
     * existed. A user message is therefore only ever persisted together with
     * the assistant turn it belongs to, which keeps "no reply" and "no message"
     * the same state for the UI.
     */
    private suspend fun rollbackProvisionalUser(
        conversation: Conversation,
        userMessage: Message,
    ) {
        messageStore.remove(setOf(userMessage.id))
        conversationStore.upsert(conversation)
    }
}
