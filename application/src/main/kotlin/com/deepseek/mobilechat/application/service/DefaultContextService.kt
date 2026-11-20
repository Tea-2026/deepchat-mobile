package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.application.ContextAssembler
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextService
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree
import kotlinx.coroutines.flow.Flow

/**
 * Default [ContextService].
 *
 * Compaction stays a two-step, user-confirmed operation (§4.4):
 *
 * 1. [planCompaction] assembles, checks the budget and asks the configured
 *    strategy for a proposal. Nothing is written.
 * 2. [applyCompaction] re-validates the proposal against the *current* tree
 *    (range still on the active path, no STREAMING message inside it) and only
 *    then persists a [Compaction]. That re-validation is what makes a stale
 *    proposal safe: the tree may have changed while the dialog was open.
 *
 * [undoCompaction] only marks the record undone; history was never deleted, so
 * assembly immediately sees the covered messages again.
 */
class DefaultContextService(
    private val contextAssembler: ContextAssembler,
    private val conversationStore: ConversationStore,
    private val messageStore: MessageStore,
    private val injectionStore: InjectionStore,
    private val compactionStore: CompactionStore,
    private val presetStore: PresetStore,
    private val chatCompletion: ChatCompletionPort,
    private val clock: ClockPort,
    private val idPort: IdPort,
    strategies: Map<String, ContextStrategy>,
) : ContextService {

    private val resolver = ContextStrategyResolver(presetStore, strategies)

    override suspend fun assemble(conversationId: ConversationId): Outcome<ContextAssembly> =
        contextAssembler.assemble(conversationId)

    override suspend fun planCompaction(conversationId: ConversationId): Outcome<CompactionProposal> {
        val conversation = conversationStore.get(conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
        val assembly = when (val outcome = contextAssembler.assemble(conversationId)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return Outcome.Err(outcome.error)
        }
        if (!assembly.budget.isOverBudget) {
            return Outcome.Err(AppError.Validation("context fits the budget, nothing to compact"))
        }
        val strategy = when (val resolved = resolver.resolve(conversation)) {
            is Outcome.Ok -> resolved.value
            is Outcome.Err -> return Outcome.Err(resolved.error)
        }
        return when (val plan = applyBudgetPolicy(assembly, strategy, chatCompletion)) {
            is ContextPlan.RequiresConfirmation -> Outcome.Ok(plan.proposal)
            is ContextPlan.Fits -> Outcome.Err(
                AppError.Validation("context fits the budget, nothing to compact"),
            )

            is ContextPlan.Rejected -> Outcome.Err(plan.error)
        }
    }

    override suspend fun applyCompaction(proposal: CompactionProposal): Outcome<Compaction> {
        val conversation = conversationStore.get(proposal.conversationId)
            ?: return Outcome.Err(
                AppError.Validation("conversation not found: ${proposal.conversationId.raw}"),
            )
        val tree = MessageTree.from(messageStore.list(proposal.conversationId))
        val path = tree.pathTo(conversation.activeLeafId)
        val fromIndex = path.indexOfFirst { it.id == proposal.fromMessageId }
        val toIndex = path.indexOfFirst { it.id == proposal.toMessageId }
        if (fromIndex < 0 || toIndex < 0) {
            return Outcome.Err(
                AppError.BranchConflict("compaction range is not on the active path"),
            )
        }
        val range = if (fromIndex <= toIndex) {
            path.subList(fromIndex, toIndex + 1)
        } else {
            path.subList(toIndex, fromIndex + 1)
        }
        if (range.any { it.status == MessageStatus.STREAMING }) {
            return Outcome.Err(
                AppError.BranchConflict("cannot compact a message that is still generating"),
            )
        }
        val compaction = Compaction(
            id = CompactionId(idPort.newId()),
            conversationId = proposal.conversationId,
            fromMessageId = proposal.fromMessageId,
            toMessageId = proposal.toMessageId,
            summary = proposal.summary,
            kind = CompactionKind.SUMMARY,
            createdAt = clock.nowMillis(),
        )
        compactionStore.add(compaction)
        return Outcome.Ok(compaction)
    }

    override suspend fun undoCompaction(compactionId: CompactionId): Outcome<Unit> {
        compactionStore.markUndone(compactionId, clock.nowMillis())
        return Outcome.Ok(Unit)
    }

    override suspend fun addInjection(
        conversationId: ConversationId,
        content: String,
        scope: InjectionScope,
        anchorMessageId: MessageId?,
    ): Outcome<ContextInjection> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Err(AppError.Validation("injection content must not be empty"))
        }
        conversationStore.get(conversationId)
            ?: return Outcome.Err(AppError.Validation("conversation not found: ${conversationId.raw}"))
        val injection = ContextInjection(
            id = InjectionId(idPort.newId()),
            conversationId = conversationId,
            anchorMessageId = anchorMessageId,
            scope = scope,
            position = nextPosition(conversationId),
            content = trimmed,
            createdAt = clock.nowMillis(),
        )
        injectionStore.add(injection)
        return Outcome.Ok(injection)
    }

    override suspend fun removeInjection(injectionId: InjectionId): Outcome<Unit> {
        injectionStore.remove(injectionId)
        return Outcome.Ok(Unit)
    }

    override fun observeInjections(conversationId: ConversationId): Flow<List<ContextInjection>> =
        injectionStore.observe(conversationId)

    override fun observeCompactions(conversationId: ConversationId): Flow<List<Compaction>> =
        compactionStore.observe(conversationId)

    private suspend fun nextPosition(conversationId: ConversationId): Int {
        val existing = injectionStore.list(conversationId)
        return (existing.maxOfOrNull { it.position } ?: -1) + 1
    }
}
