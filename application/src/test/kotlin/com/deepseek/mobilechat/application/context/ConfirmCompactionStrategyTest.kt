package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ContextBlock
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.context.strategy.ConfirmCompactionStrategy
import com.deepseek.mobilechat.application.context.testutil.testAssembly
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmCompactionStrategyTest {

    private val estimator = HeuristicTokenEstimator()
    private val strategy = ConfirmCompactionStrategy(estimator)

    private fun historyBlock(index: Int, tokens: Int = 10): ContextBlock = ContextBlock(
        id = "history:m$index",
        kind = BlockKind.HISTORY,
        title = "历史消息",
        tokens = tokens,
        messageIds = listOf(MessageId("m$index")),
    )

    private fun currentBlock(): ContextBlock = ContextBlock(
        id = "current",
        kind = BlockKind.CURRENT_USER,
        title = "当前消息",
        tokens = 4,
        messageIds = listOf(MessageId("now")),
    )

    @Test
    fun `proposes the oldest compactable range and keeps the newest six messages`() = runTest {
        val blocks = (1..10).map { historyBlock(it) } + currentBlock()
        val assembly = testAssembly(blocks = blocks, limit = 100, used = 200)
        var captured: List<PromptMessage> = emptyList()

        val plan = strategy.plan(assembly, assembly.budget) { messages ->
            captured = messages
            "summary"
        }

        val proposal = (plan as ContextPlan.RequiresConfirmation).proposal
        assertEquals(MessageId("m1"), proposal.fromMessageId)
        assertEquals(MessageId("m4"), proposal.toMessageId)
        assertEquals(4, proposal.coveredCount)
        assertEquals(40, proposal.tokensBefore)
        assertEquals(listOf("m1", "m2", "m3", "m4"), captured.map { it.sourceMessageId?.raw })
        // 160 kept tokens + summary message: 4 role overhead + 6 for the
        // "已压缩的 4 条消息" title + 2 for the "summary" body
        assertEquals(172, proposal.tokensAfter)
        assertEquals("summary", proposal.summary)
        assertEquals(ContextStrategyIds.CONFIRM_COMPACTION, proposal.strategyId)
        assertEquals(strategy.id, proposal.strategyId)
    }

    @Test
    fun `rejects when fewer than two messages can be compacted`() = runTest {
        val seven = testAssembly(blocks = (1..7).map { historyBlock(it) }, limit = 100, used = 200)
        var summarizeCalled = false

        val plan = strategy.plan(seven, seven.budget) {
            summarizeCalled = true
            "summary"
        }

        assertTrue(plan is ContextPlan.Rejected)
        assertEquals("nothing to compact", (plan as ContextPlan.Rejected).error.message)
        assertFalse(summarizeCalled)

        val none = testAssembly(blocks = listOf(currentBlock()), limit = 100, used = 120)
        assertTrue(strategy.plan(none, none.budget) { "summary" } is ContextPlan.Rejected)
        assertTrue(strategy.plan(testAssembly(), testAssembly().budget) { "summary" } is ContextPlan.Rejected)
    }

    @Test
    fun `pinned messages are left out of the proposal range`() = runTest {
        val pinnedAware = ConfirmCompactionStrategy(estimator, isPinned = { it.raw == "m2" })
        val assembly = testAssembly(
            blocks = (1..10).map { historyBlock(it) },
            limit = 100,
            used = 200,
        )

        val plan = pinnedAware.plan(assembly, assembly.budget) { "summary" }

        val proposal = (plan as ContextPlan.RequiresConfirmation).proposal
        assertEquals(MessageId("m1"), proposal.fromMessageId)
        assertEquals(MessageId("m4"), proposal.toMessageId)
        assertEquals(3, proposal.coveredCount)
        assertEquals(30, proposal.tokensBefore)
    }

    @Test
    fun `current user message is never part of the compacted range`() = runTest {
        val blocks = (1..10).map { historyBlock(it) } + currentBlock()
        val assembly = testAssembly(blocks = blocks, limit = 100, used = 200)

        val plan = strategy.plan(assembly, assembly.budget) { "summary" }

        val proposal = (plan as ContextPlan.RequiresConfirmation).proposal
        assertTrue(proposal.coveredCount <= 4)
        assertEquals(MessageId("m4"), proposal.toMessageId)
    }

    @Test
    fun `keep recent window is configurable`() = runTest {
        val shorterWindow = ConfirmCompactionStrategy(estimator, keepRecentMessages = 2)
        val assembly = testAssembly(
            blocks = (1..4).map { historyBlock(it) },
            limit = 100,
            used = 200,
        )

        val plan = shorterWindow.plan(assembly, assembly.budget) { "summary" }

        val proposal = (plan as ContextPlan.RequiresConfirmation).proposal
        assertEquals(MessageId("m1"), proposal.fromMessageId)
        assertEquals(MessageId("m2"), proposal.toMessageId)
        assertEquals(2, proposal.coveredCount)
    }
}
