package com.deepseek.mobilechat.application.context.strategy

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.CompactionProposal
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.application.context.HeuristicTokenEstimator
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole

/**
 * Default context strategy: never truncate silently.
 *
 * When the assembly does not fit, the strategy proposes a concrete compaction
 * range and lets the UI ask the user. Selection walks the active path from its
 * oldest end:
 *
 * - only [BlockKind.HISTORY] blocks are candidates, so system prompts,
 *   injections, compaction summaries and the current user message are out;
 * - the newest [keepRecentMessages] history messages are always kept;
 * - `pinned` messages are skipped through [isPinned] (a snapshot predicate, so
 *   the strategy stays I/O free) — history blocks carrying any pinned message
 *   are not compacted. The assembler independently guarantees that pinned
 *   messages are never replaced by a compaction;
 * - fewer than [MIN_COMPACTABLE_MESSAGES] remaining candidates means there is
 *   nothing worth compacting, which is an expected rejection.
 *
 * The strategy always returns [ContextPlan.RequiresConfirmation] or
 * [ContextPlan.Rejected]; it performs no budget check of its own because the
 * caller only invokes it on the over-budget path.
 *
 * `tokensBefore` is the sum of the covered blocks (as measured by the
 * assembler), `tokensAfter` is the summary message estimate plus everything
 * that is kept, so the UI can show the net saving before asking.
 */
class ConfirmCompactionStrategy(
    private val tokenEstimator: TokenEstimatorPort = HeuristicTokenEstimator(),
    private val isPinned: (MessageId) -> Boolean = { false },
    private val keepRecentMessages: Int = DEFAULT_KEEP_RECENT_MESSAGES,
) : ContextStrategy {

    override val id: String = ContextStrategyIds.CONFIRM_COMPACTION

    override suspend fun plan(
        assembly: ContextAssembly,
        budget: ContextBudget,
        summarize: suspend (List<PromptMessage>) -> String,
    ): ContextPlan {
        val historyBlocks = assembly.blocks.filter { it.kind == BlockKind.HISTORY }
        val candidates = historyBlocks
            .dropLast(keepRecentMessages.coerceAtLeast(0))
            .filterNot { block -> block.messageIds.any(isPinned) }

        if (candidates.size < MIN_COMPACTABLE_MESSAGES) {
            return ContextPlan.Rejected(AppError.Validation("nothing to compact"))
        }

        val coveredIds = candidates.flatMap { it.messageIds }.toSet()
        val firstMessageId = candidates.first().messageIds.firstOrNull()
            ?: return ContextPlan.Rejected(AppError.Validation("nothing to compact"))
        val lastMessageId = candidates.last().messageIds.lastOrNull()
            ?: return ContextPlan.Rejected(AppError.Validation("nothing to compact"))

        val coveredPrompt = assembly.prompt.filter { it.sourceMessageId in coveredIds }
        val summary = summarize(coveredPrompt)

        val tokensBefore = candidates.sumOf { it.tokens }
        val summaryMessage = PromptMessage(
            role = MessageRole.USER,
            parts = listOf(
                MessagePart.ContextBlock(
                    title = "已压缩的 ${coveredIds.size} 条消息",
                    body = summary,
                    source = BlockSource.COMPACTION,
                ),
            ),
        )
        val tokensAfter = (budget.used - tokensBefore).coerceAtLeast(0) +
            tokenEstimator.estimate(summaryMessage)

        return ContextPlan.RequiresConfirmation(
            CompactionProposal(
                conversationId = assembly.conversationId,
                fromMessageId = firstMessageId,
                toMessageId = lastMessageId,
                coveredCount = coveredIds.size,
                summary = summary,
                tokensBefore = tokensBefore,
                tokensAfter = tokensAfter,
                strategyId = id,
            ),
        )
    }

    companion object {
        /** Number of newest history messages that are never compacted. */
        const val DEFAULT_KEEP_RECENT_MESSAGES: Int = 6

        /** Below this, the proposal is not worth asking the user about. */
        const val MIN_COMPACTABLE_MESSAGES: Int = 2
    }
}
