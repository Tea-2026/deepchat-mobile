package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree

/**
 * Restarts an assistant turn: [regenerate] forks a sibling, [continueGeneration]
 * extends a reply that stopped short of a natural end.
 *
 * Both share the same shape — point the conversation at the right leaf, assemble,
 * enforce the budget, then hand a streaming block to the scheduler — which is why
 * they live together and apart from [DefaultChatService]'s send pipeline.
 *
 * The budget cannot ask for confirmation through [com.deepseek.mobilechat.application.SendResult]
 * here, so an over-budget context is surfaced as [AppError.BudgetExceeded]
 * carrying the proposal; the UI confirms it and calls again. Every early exit
 * restores the previous `activeLeafId`, so a failed command never mutates the
 * visible branch.
 */
internal class AssistantTurnGenerator(
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
    private val contextAssembler: ContextAssembler,
    private val scheduler: GenerationScheduler,
    private val chatCompletion: ChatCompletionPort,
    private val clock: ClockPort,
    private val idPort: IdPort,
    private val resolver: ContextStrategyResolver,
    private val runner: ChatGenerationRunner,
) {

    suspend fun regenerate(messageId: MessageId): Outcome<Unit> {
        val message = messageStore.get(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        if (message.role != MessageRole.ASSISTANT) {
            return Outcome.Err(AppError.Validation("only assistant messages can be regenerated"))
        }
        val conversation = conversationStore.get(message.conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${message.conversationId.raw}"))
        val parentId = message.parentId
            ?: return Outcome.Err(AppError.Validation("cannot regenerate a root message"))
        val tree = MessageTree.from(messageStore.list(message.conversationId))

        // Assembly is defined against a leaf, so the conversation is pointed at
        // the parent for the duration of the request and restored on failure.
        conversationStore.upsert(conversation.copy(activeLeafId = parentId))
        val assembly = when (val outcome = contextAssembler.assemble(message.conversationId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> {
                conversationStore.upsert(conversation)
                return Outcome.Err(outcome.error)
            }
        }
        when (val allowed = budgetAllows(assembly, conversation)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> {
                conversationStore.upsert(conversation)
                return allowed
            }
        }

        val sibling = assistantPlaceholder(
            id = MessageId(idPort.newId()),
            conversationId = message.conversationId,
            parentId = parentId,
            seq = tree.nextSeq(parentId),
            createdAt = clock.nowMillis(),
            assembly = assembly,
        )
        messageStore.append(sibling)
        conversationStore.upsert(
            conversation.copy(activeLeafId = sibling.id, updatedAt = sibling.createdAt),
        )
        return submit(sibling, assembly, appendToExisting = false)
    }

    /**
     * Starts the assistant reply for a user message that has none.
     *
     * Two callers share this: the fork branch of `editMessage` (a new sibling
     * user message must not be a dead end) and the explicit "生成回复" recovery
     * for branches whose active path already ends at an unanswered user message
     * (data written before fork edits generated a reply). The message must be a
     * leaf; an existing child means the branch already has its answer.
     */
    suspend fun replyTo(userMessageId: MessageId): Outcome<Unit> {
        val message = messageStore.get(userMessageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${userMessageId.raw}"))
        if (message.role != MessageRole.USER) {
            return Outcome.Err(AppError.Validation("only user messages can be answered"))
        }
        if (message.status == MessageStatus.STREAMING) {
            return Outcome.Err(AppError.BranchConflict("message is already generating"))
        }
        val conversation = conversationStore.get(message.conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${message.conversationId.raw}"))
        val tree = MessageTree.from(messageStore.list(message.conversationId))
        if (!tree.isLeaf(userMessageId)) {
            return Outcome.Err(AppError.BranchConflict("message already has a reply"))
        }

        // The user message becomes the tail of the prompt, so it has to be the
        // leaf the context is assembled from.
        conversationStore.upsert(conversation.copy(activeLeafId = userMessageId))
        val assembly = when (val outcome = contextAssembler.assemble(message.conversationId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> {
                conversationStore.upsert(conversation)
                return Outcome.Err(outcome.error)
            }
        }
        when (val allowed = budgetAllows(assembly, conversation)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> {
                conversationStore.upsert(conversation)
                return allowed
            }
        }

        val assistant = assistantPlaceholder(
            id = MessageId(idPort.newId()),
            conversationId = message.conversationId,
            parentId = userMessageId,
            seq = tree.nextSeq(userMessageId),
            createdAt = clock.nowMillis(),
            assembly = assembly,
        )
        messageStore.append(assistant)
        conversationStore.upsert(
            conversation.copy(activeLeafId = assistant.id, updatedAt = assistant.createdAt),
        )
        return submit(assistant, assembly, appendToExisting = false)
    }

    suspend fun continueGeneration(messageId: MessageId): Outcome<Unit> {
        val message = messageStore.get(messageId)
            ?: return Outcome.Err(AppError.Validation("message not found: ${messageId.raw}"))
        if (message.role != MessageRole.ASSISTANT) {
            return Outcome.Err(AppError.Validation("only assistant messages can be continued"))
        }
        // A reply can be continued when it stopped before a natural end:
        // length-truncated, interrupted by a process death/cancel, or errored
        // after partial output. The context assembler drops any empty or
        // unanswered tool-call turn, so even an interrupted placeholder can be
        // resumed without poisoning the next request.
        val continuable = message.finishReason == FinishReason.LENGTH ||
            message.status == MessageStatus.CANCELLED ||
            message.status == MessageStatus.FAILED ||
            message.finishReason == FinishReason.CANCELLED ||
            message.finishReason == FinishReason.ERROR
        if (!continuable) {
            return Outcome.Err(
                AppError.Validation(
                    "continue is only available for a truncated, interrupted or failed reply",
                ),
            )
        }
        if (message.status == MessageStatus.STREAMING) {
            return Outcome.Err(AppError.BranchConflict("message is already generating"))
        }
        val conversation = conversationStore.get(message.conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${message.conversationId.raw}"))

        // The truncated reply becomes the tail of the prompt, so it has to be
        // the leaf the context is assembled from.
        conversationStore.upsert(conversation.copy(activeLeafId = messageId))
        val assembly = when (val outcome = contextAssembler.assemble(message.conversationId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> {
                conversationStore.upsert(conversation)
                return Outcome.Err(outcome.error)
            }
        }
        when (val allowed = budgetAllows(assembly, conversation)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> {
                conversationStore.upsert(conversation)
                return allowed
            }
        }

        messageStore.update(message.copy(status = MessageStatus.STREAMING, error = null))
        return submit(message, assembly, appendToExisting = true)
    }

    private suspend fun submit(
        message: Message,
        assembly: ContextAssembly,
        appendToExisting: Boolean,
    ): Outcome<Unit> {
        val request = requestForAssembly(assembly)
        return when (val submitted = scheduler.submit(message.conversationId, message.id) {
                runner.run(message.id, request, appendToExisting = appendToExisting)
            }) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> {
                messageStore.update(
                    message.copy(
                        status = MessageStatus.FAILED,
                        finishReason = FinishReason.ERROR,
                        error = submitted.error.message,
                    ),
                )
                Outcome.Err(submitted.error)
            }
        }
    }

    private suspend fun budgetAllows(
        assembly: ContextAssembly,
        conversation: Conversation,
    ): Outcome<Unit> {
        if (!assembly.budget.isOverBudget) return Outcome.Ok(Unit)
        val strategy = when (val resolved = resolver.resolve(conversation)) {
            is Outcome.Ok -> resolved.value
            is Outcome.Err -> return Outcome.Err(resolved.error)
        }
        return when (val plan = applyBudgetPolicy(assembly, strategy, chatCompletion)) {
            is ContextPlan.Fits -> Outcome.Ok(Unit)
            is ContextPlan.RequiresConfirmation -> Outcome.Err(
                AppError.BudgetExceeded(plan.proposal),
            )

            is ContextPlan.Rejected -> Outcome.Err(plan.error)
        }
    }
}
