package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.application.log.testutil.FixedProposalStrategy
import com.deepseek.mobilechat.application.log.testutil.LogHarness
import com.deepseek.mobilechat.application.log.testutil.SwitchableBudgetLookup
import com.deepseek.mobilechat.application.log.testutil.assistantEvent
import com.deepseek.mobilechat.application.log.testutil.leafEvent
import com.deepseek.mobilechat.application.log.testutil.seedConversation
import com.deepseek.mobilechat.application.log.testutil.toolResultEvent
import com.deepseek.mobilechat.application.log.testutil.userEvent
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.SurfaceOp
import com.deepseek.mobilechat.domain.log.TranscriptNode
import com.deepseek.mobilechat.domain.log.TranscriptProjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mandatory gateway contract tests: every batch composition, every
 * fail-closed rule, every overlay invariant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChatGatewayTest {

    private suspend fun DefaultChatGateway.snapshotOf(
        conversationId: ConversationId,
    ): ChatSnapshot = snapshots(conversationId).first()

    // -----------------------------------------------------------------------
    // send
    // -----------------------------------------------------------------------

    @Test
    fun `send appends user and turn start in one batch before any port call`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.completion.events = listOf(
            GenerationEvent.TextDelta("hi"),
            GenerationEvent.Finished(FinishReason.STOP),
        )
        var appendsWhenPortCalled = -1
        harness.completion.beforeStream = { appendsWhenPortCalled = harness.logStore.appends.size }
        val gateway = harness.gateway()

        val outcome = gateway.send(conversationId, "hello")
        advanceUntilIdle()

        assertTrue(outcome is Outcome.Ok)
        assertEquals("the port must only see a durable turn", 1, appendsWhenPortCalled)
        val first = harness.logStore.appends.first()
        assertEquals(
            listOf(EventType.USER_MESSAGE, EventType.TURN_START),
            first.events.take(2).map { it.type },
        )
        // The very first turn also names the conversation (batch 3/3).
        assertEquals(EventType.SESSION_TITLE, first.events.last().type)
        assertEquals(conversationId, first.conversationId)
        // one command = one batch: the completion batch is the only second write
        assertEquals(2, harness.logStore.appends.size)
        assertEquals(MessageStatus.COMPLETE, gateway.snapshotOf(conversationId).messages.last().status)
    }

    @Test
    fun `a failed append means the completion port is never called`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.logStore.appendFailure = AppError.Storage("disk is full")
        val gateway = harness.gateway()

        val outcome = gateway.send(conversationId, "hello")
        advanceUntilIdle()

        assertTrue(outcome is Outcome.Err)
        assertEquals(AppError.Storage::class.java, (outcome as Outcome.Err).error.javaClass)
        assertEquals(0, harness.completion.requests.size)
        assertEquals(0, harness.logStore.appends.size)
        val snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.busy)
        assertNull(snapshot.overlay.streaming)
        assertEquals(0L, snapshot.version)
    }

    @Test
    fun `the first send also names a conversation that has no title yet`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.completion.events = listOf(GenerationEvent.Finished(FinishReason.STOP))
        val gateway = harness.gateway()

        gateway.send(conversationId, "first question")
        advanceUntilIdle()

        val first = harness.logStore.appends.first()
        assertEquals(
            listOf(EventType.USER_MESSAGE, EventType.TURN_START, EventType.SESSION_TITLE),
            first.events.map { it.type },
        )
        val title = first.events.last().data as EventData.SessionTitle
        assertEquals("first question", title.title)
        assertEquals("first question", gateway.snapshotOf(conversationId).title)
    }

    @Test
    fun `search hits stay in memory and never enter the log`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.search.hits = listOf(SearchHit("title", "https://example.com", "snippet"))
        harness.completion.events = listOf(GenerationEvent.Finished(FinishReason.STOP))
        val gateway = harness.gateway()

        gateway.send(conversationId, "query", search = true)
        advanceUntilIdle()

        val userData = harness.logStore.appends.first().events.first().data as EventData.UserMessage
        assertFalse(userData.parts.any { it is MessagePart.SearchResults })

        val request = harness.completion.requests.single()
        assertTrue(
            request.messages.any { message ->
                message.parts.filterIsInstance<MessagePart.ContextBlock>()
                    .any { it.source == BlockSource.SEARCH }
            },
        )
        // The sources survive as citations on the answer, not as prompt history.
        val assistant = harness.logStore.appends.last().events.first().data
        assertEquals(
            listOf(SearchHit("title", "https://example.com", "snippet")),
            (assistant as EventData.AssistantMessage).citations,
        )
    }

    // -----------------------------------------------------------------------
    // completion / failure / cancel
    // -----------------------------------------------------------------------

    @Test
    fun `completion appends assistant turn end leaf in one batch and clears the overlay`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.completion.events = listOf(
            GenerationEvent.TextDelta("hello back"),
            GenerationEvent.UsageReport(
                Usage(promptTokens = 11, completionTokens = 7, cachedPromptTokens = 3, cost = 0.5),
            ),
            GenerationEvent.Finished(FinishReason.STOP),
        )
        val gateway = harness.gateway()

        gateway.send(conversationId, "hello")
        advanceUntilIdle()

        assertEquals(2, harness.logStore.appends.size)
        val completion = harness.logStore.appends[1]
        assertEquals(
            listOf(EventType.ASSISTANT_MESSAGE, EventType.TURN_END, EventType.LEAF_CHANGED),
            completion.events.map { it.type },
        )
        val assistantData = completion.events[0].data as EventData.AssistantMessage
        assertEquals("hello back", assistantData.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text })
        assertEquals(11, assistantData.usage?.promptTokens)
        assertEquals(assistantData.messageId, completion.index?.activeLeafId)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(assistantData.messageId, snapshot.activeLeafId)
        assertNull(snapshot.overlay.streaming)
        assertNull(snapshot.overlay.busy)
        assertFalse(snapshot.messages.any { it.status == MessageStatus.STREAMING })
        assertEquals(UsageTotals(promptTokens = 11, cachedPromptTokens = 3, completionTokens = 7, costCny = 0.5, messageCount = 1), snapshot.totals)
        assertTrue(harness.drafts.deletes.contains(assistantData.messageId))
        assertEquals(0, harness.drafts.all().size)
    }

    @Test
    fun `a failed stream lands turn interrupted and clears busy and streaming`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.completion.events = listOf(
            GenerationEvent.TextDelta("partial"),
            GenerationEvent.Failed(AppError.Network("boom")),
        )
        val gateway = harness.gateway()

        gateway.send(conversationId, "hello")
        advanceUntilIdle()

        val last = harness.logStore.appends.last()
        assertEquals(
            listOf(EventType.TURN_INTERRUPTED, EventType.LEAF_CHANGED),
            last.events.map { it.type },
        )
        val interrupted = last.events[0].data as EventData.TurnInterrupted
        assertEquals("partial", interrupted.partialText)
        assertEquals("boom", interrupted.reason)

        val snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.busy)
        assertNull(snapshot.overlay.streaming)
        assertEquals(MessageStatus.CANCELLED, snapshot.messages.first { it.id == interrupted.messageId }.status)
    }

    @Test
    fun `cancel appends turn interrupted with the partial text and deletes the draft`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.completion.events = listOf(GenerationEvent.TextDelta("par"))
        harness.completion.hangAfter = 1
        val gateway = harness.gateway()

        gateway.send(conversationId, "hello")
        advanceUntilIdle()
        val streamingId = gateway.snapshotOf(conversationId).overlay.streaming?.messageId
        assertNotNull(streamingId)

        val outcome = gateway.cancel(conversationId)
        advanceUntilIdle()

        assertTrue(outcome is Outcome.Ok)
        val last = harness.logStore.appends.last()
        assertEquals(
            listOf(EventType.TURN_INTERRUPTED, EventType.LEAF_CHANGED),
            last.events.map { it.type },
        )
        val interrupted = last.events[0].data as EventData.TurnInterrupted
        assertEquals("par", interrupted.partialText)
        assertTrue(harness.drafts.deletes.contains(streamingId))
        assertEquals(0, harness.drafts.all().size)

        val snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.streaming)
        assertNull(snapshot.overlay.busy)
        assertEquals("par", snapshot.messages.first { it.id == streamingId }.text)
    }

    // -----------------------------------------------------------------------
    // regenerate (I10)
    // -----------------------------------------------------------------------

    @Test
    fun `regenerate keeps the log leaf, shows the old answer and a streaming sibling on the active path`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "question"),
            assistantEvent(2, "a1", "old answer", parentId = "u1"),
            leafEvent(3, "a1"),
        )
        harness.completion.events = listOf(GenerationEvent.TextDelta("new "))
        harness.completion.hangAfter = 1
        val gateway = harness.gateway()

        val outcome = gateway.regenerate(MessageId("a1"))
        advanceUntilIdle()

        assertTrue(outcome is Outcome.Ok)
        // I10: the log's leaf is untouched while the new answer is assembled.
        val logTranscript = TranscriptProjector.project(harness.logStore.logOf(conversationId))
        assertEquals(MessageId("a1"), logTranscript.activeLeafId)
        assertEquals(EventType.TURN_START, harness.logStore.appends.single().events.single().type)

        val snapshot = gateway.snapshotOf(conversationId)
        val streamingId = snapshot.overlay.streaming?.messageId
        assertNotNull(streamingId)
        assertEquals(streamingId, snapshot.activeLeafId)

        // The old answer is still in the transcript…
        assertTrue(snapshot.messages.any { it.id == MessageId("a1") && it.text == "old answer" })
        // …and the streaming sibling is a real row in both lists.
        val synthetic = snapshot.messages.single { it.id == streamingId }
        assertEquals(MessageStatus.STREAMING, synthetic.status)
        assertEquals(MessageId("u1"), synthetic.parentId)
        assertEquals("new ", synthetic.text)
        assertTrue(
            snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>()
                .any { it.message.id == streamingId },
        )
        // pathTo(activeLeafId) lands on the streaming row, not the old answer.
        assertEquals(
            listOf(MessageId("u1"), streamingId),
            MessageTree.from(snapshot.messages).pathTo(snapshot.activeLeafId).map { it.id },
        )

        gateway.cancel(conversationId)
        advanceUntilIdle()
    }

    @Test
    fun `after completion the synthetic row is gone and the leaf is the real answer`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "question"),
            assistantEvent(2, "a1", "old answer", parentId = "u1"),
            leafEvent(3, "a1"),
        )
        harness.completion.events = listOf(
            GenerationEvent.TextDelta("fresh answer"),
            GenerationEvent.Finished(FinishReason.STOP),
        )
        val gateway = harness.gateway()

        gateway.regenerate(MessageId("a1"))
        advanceUntilIdle()

        val snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.streaming)
        assertFalse(snapshot.messages.any { it.status == MessageStatus.STREAMING })
        assertTrue(snapshot.messages.any { it.id == MessageId("a1") && it.text == "old answer" })
        val fresh = snapshot.messages.first { it.role == MessageRole.ASSISTANT && it.text == "fresh answer" }
        assertEquals(fresh.id, snapshot.activeLeafId)
        // The new answer was appended as a sibling of a1, not a child.
        assertEquals(MessageId("u1"), fresh.parentId)
        assertEquals(MessageId("a1"), snapshot.messages.first { it.id == MessageId("a1") }.id)
    }

    // -----------------------------------------------------------------------
    // edit
    // -----------------------------------------------------------------------

    @Test
    fun `an in place edit replaces the row and starts a turn`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(conversationId, userEvent(1, "u1", "original"))
        harness.completion.events = listOf(GenerationEvent.Finished(FinishReason.STOP))
        val gateway = harness.gateway()

        val outcome = gateway.edit(MessageId("u1"), "edited")
        advanceUntilIdle()

        assertEquals(EditOutcome.InPlace, (outcome as Outcome.Ok).value)
        val first = harness.logStore.appends.first()
        assertEquals(
            listOf(EventType.USER_MESSAGE, EventType.TURN_START),
            first.events.map { it.type },
        )
        val replacement = first.events[0]
        assertEquals(SurfaceOp.Replace(1, 1), replacement.surfaceOp)
        assertEquals(listOf(1L), replacement.sourceEventSeqs)
        val data = replacement.data as EventData.UserMessage
        assertEquals(MessageId("u1"), data.messageId)
        assertEquals("edited", data.parts.filterIsInstance<MessagePart.Text>().single().text)

        val snapshot = gateway.snapshotOf(conversationId)
        val userRows = snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>()
            .filter { it.message.role == MessageRole.USER }
        assertEquals(1, userRows.size)
        assertEquals("edited", userRows.single().message.text)
        assertEquals(1, snapshot.messages.count { it.role == MessageRole.USER })
    }

    @Test
    fun `an edit with children creates a sibling and starts a turn`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "original"),
            assistantEvent(2, "a1", "answer", parentId = "u1"),
        )
        harness.completion.events = listOf(GenerationEvent.Finished(FinishReason.STOP))
        val gateway = harness.gateway()

        val outcome = gateway.edit(MessageId("u1"), "edited")
        val forked = (outcome as Outcome.Ok).value as EditOutcome.Forked
        // Before the reply starts, the fork is the active branch (read the log
        // directly so no scheduler hop can let the reply land first).
        assertEquals(
            forked.messageId,
            TranscriptProjector.project(harness.logStore.logOf(conversationId)).activeLeafId,
        )
        advanceUntilIdle()

        val first = harness.logStore.appends.first()
        assertEquals(
            listOf(EventType.USER_MESSAGE, EventType.LEAF_CHANGED, EventType.TURN_START),
            first.events.map { it.type },
        )
        val sibling = first.events[0].data as EventData.UserMessage
        assertEquals(forked.messageId, sibling.messageId)
        assertNull(sibling.parentId)
        assertEquals(forked.messageId, (first.events[1].data as EventData.LeafChanged).messageId)
        assertEquals(forked.messageId, first.index?.activeLeafId)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(2, snapshot.messages.count { it.role == MessageRole.USER })
        assertTrue(snapshot.messages.any { it.id == MessageId("u1") && it.text == "original" })
        assertTrue(snapshot.messages.any { it.id == forked.messageId && it.text == "edited" })
    }

    // -----------------------------------------------------------------------
    // switchBranch
    // -----------------------------------------------------------------------

    @Test
    fun `switch branch only appends leaf changed`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "question"),
            assistantEvent(2, "a1", "answer", parentId = "u1"),
            assistantEvent(3, "a2", "other answer", parentId = "u1"),
        )
        val gateway = harness.gateway()

        val outcome = gateway.switchBranch(MessageId("a1"))
        advanceUntilIdle()

        assertTrue(outcome is Outcome.Ok)
        val batch = harness.logStore.appends.single()
        assertEquals(listOf(EventType.LEAF_CHANGED), batch.events.map { it.type })
        assertEquals(MessageId("a1"), (batch.events.single().data as EventData.LeafChanged).messageId)
        assertEquals(MessageId("a1"), batch.index?.activeLeafId)
        assertEquals(MessageId("a1"), gateway.snapshotOf(conversationId).activeLeafId)
    }

    // -----------------------------------------------------------------------
    // compaction
    // -----------------------------------------------------------------------

    private fun LogHarness.seedFourTurns(conversationId: ConversationId) {
        seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
            userEvent(3, "u2", "q2", parentId = "a1"),
            assistantEvent(4, "a2", "r2", parentId = "u2"),
        )
    }

    @Test
    fun `compaction holds the text, confirm appends start stand in end and resends`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedFourTurns(conversationId)
        harness.budgetLookup = SwitchableBudgetLookup(5, 100_000)
        harness.strategies[ContextStrategyIds.CONFIRM_COMPACTION] =
            FixedProposalStrategy(MessageId("u1"), MessageId("a2"))
        harness.completion.events = listOf(
            GenerationEvent.TextDelta("reply"),
            GenerationEvent.Finished(FinishReason.STOP),
        )
        val gateway = harness.gateway()

        val refused = gateway.send(conversationId, "held text")
        advanceUntilIdle()

        assertTrue(refused is Outcome.Err)
        assertTrue((refused as Outcome.Err).error is AppError.BudgetExceeded)
        assertEquals(0, harness.logStore.appends.size)
        var snapshot = gateway.snapshotOf(conversationId)
        assertEquals("held text", snapshot.overlay.pendingUserText)
        assertNotNull(snapshot.overlay.compactionProposal)
        assertNull(snapshot.overlay.busy)

        val confirmed = gateway.confirmCompaction(conversationId)
        advanceUntilIdle()

        assertTrue(confirmed is Outcome.Ok)
        val compactionBatch = harness.logStore.appends.first()
        assertEquals(
            listOf(EventType.COMPACTION_START, EventType.USER_MESSAGE, EventType.COMPACTION_END),
            compactionBatch.events.map { it.type },
        )
        val standIn = compactionBatch.events[1]
        assertEquals(SurfaceOp.Replace(1, 4), standIn.surfaceOp)
        assertEquals(listOf(1L, 2L, 3L, 4L), standIn.sourceEventSeqs)
        val standInData = standIn.data as EventData.UserMessage
        assertEquals("压缩摘要", standInData.parts.filterIsInstance<MessagePart.Text>().single().text)
        assertNotNull(standInData.compactionId)
        // log.version was 4, so the stand-in is seq 6 and the closing event
        // must point at it.
        assertEquals(6L, (compactionBatch.events[2].data as EventData.CompactionEnd).summarySeq)

        // Resend happened: user + turn started, and the answer landed.
        assertTrue(harness.logStore.appends.size >= 3)
        assertEquals(
            listOf(EventType.USER_MESSAGE, EventType.TURN_START, EventType.SESSION_TITLE),
            harness.logStore.appends[1].events.map { it.type },
        )
        snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.compactionProposal)
        assertNull(snapshot.overlay.pendingUserText)
        assertNull(snapshot.overlay.busy)

        // I5: every covered message is still in the human transcript.
        val messageIds = snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>()
            .map { it.message.id }
        assertTrue(messageIds.containsAll(listOf(MessageId("u1"), MessageId("a1"), MessageId("u2"), MessageId("a2"))))
        assertEquals(1, snapshot.transcript.filterIsInstance<TranscriptNode.CompactionBar>().size)
        // The model-visible surface no longer contains them.
        val surface = SurfaceFolder.fold(harness.logStore.logOf(conversationId))
        assertFalse(surface.nodes.any { it.messageId() == MessageId("u1") })
        assertTrue(surface.nodes.any { it.compactionId != null })
    }

    @Test
    fun `dismiss restores the held text and writes nothing`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedFourTurns(conversationId)
        harness.budgetLookup = SwitchableBudgetLookup(5, 5)
        harness.strategies[ContextStrategyIds.CONFIRM_COMPACTION] =
            FixedProposalStrategy(MessageId("u1"), MessageId("a2"))
        val gateway = harness.gateway()

        assertTrue(gateway.send(conversationId, "held text") is Outcome.Err)
        advanceUntilIdle()
        assertEquals(0, harness.logStore.appends.size)

        val dismissed = gateway.dismissCompaction(conversationId)
        advanceUntilIdle()

        assertTrue(dismissed is Outcome.Ok)
        val snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.compactionProposal)
        assertEquals("held text", snapshot.overlay.pendingUserText)
        assertNull(snapshot.overlay.busy)
        assertEquals(0, harness.logStore.appends.size)
    }

    @Test
    fun `confirm without a proposal fails and leaves the overlay clean`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        val gateway = harness.gateway()

        val outcome = gateway.confirmCompaction(conversationId)

        assertTrue(outcome is Outcome.Err)
        assertEquals(0, harness.logStore.appends.size)
        val snapshot = gateway.snapshotOf(conversationId)
        assertNull(snapshot.overlay.busy)
        assertNull(snapshot.overlay.compactionProposal)
    }

    @Test
    fun `a compaction range never splits a tool use from its tool result`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "q"),
            assistantEvent(
                seq = 2,
                messageId = "a1",
                text = "",
                parentId = "u1",
                parts = listOf(
                    MessagePart.Text("let me look"),
                    MessagePart.ToolCall(callId = "call-1", name = "search", argumentsJson = "{}"),
                ),
            ),
            toolResultEvent(3, "call-1", "result"),
            userEvent(4, "u2", "next", parentId = "a1"),
        )
        harness.budgetLookup = SwitchableBudgetLookup(5, 100_000)
        // The strategy names only the assistant; I7 forces the tool result in.
        harness.strategies[ContextStrategyIds.CONFIRM_COMPACTION] =
            FixedProposalStrategy(MessageId("a1"), MessageId("a1"))
        val gateway = harness.gateway()

        assertTrue(gateway.send(conversationId, "held") is Outcome.Err)
        assertTrue(gateway.confirmCompaction(conversationId) is Outcome.Ok)
        advanceUntilIdle()

        val standIn = harness.logStore.appends.first().events[1]
        assertEquals(SurfaceOp.Replace(2, 3), standIn.surfaceOp)
        assertEquals(listOf(2L, 3L), standIn.sourceEventSeqs)
    }

    // -----------------------------------------------------------------------
    // overlay invariant (I11)
    // -----------------------------------------------------------------------

    @Test
    fun `no command leaves the overlay busy`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "question"),
            assistantEvent(2, "a1", "answer", parentId = "u1"),
        )
        harness.completion.events = listOf(GenerationEvent.Finished(FinishReason.STOP))
        val gateway = harness.gateway()

        suspend fun assertNotBusy(label: String) {
            advanceUntilIdle()
            val overlay = gateway.snapshotOf(conversationId).overlay
            assertNull("$label left busy set", overlay.busy)
            assertFalse("$label left searching set", overlay.searching)
        }

        gateway.send(conversationId, "hello")
        assertNotBusy("send")

        gateway.regenerate(MessageId("a1"))
        assertNotBusy("regenerate")

        gateway.edit(MessageId("u1"), "edited")
        assertNotBusy("edit")

        gateway.switchBranch(MessageId("a1"))
        assertNotBusy("switchBranch")

        gateway.cancel(conversationId)
        assertNotBusy("cancel")

        gateway.confirmCompaction(conversationId)
        assertNotBusy("confirmCompaction")

        gateway.dismissCompaction(conversationId)
        assertNotBusy("dismissCompaction")
    }

    // -----------------------------------------------------------------------
    // startup reconciliation
    // -----------------------------------------------------------------------

    @Test
    fun `startup reconciliation turns every draft into an interrupted turn`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(conversationId, userEvent(1, "u1", "question"))
        harness.drafts.seed(conversationId, MessageId("pending-1"), text = "half an answer")
        val gateway = harness.gateway()

        val settled = gateway.reconcile()
        advanceUntilIdle()

        assertEquals(listOf(MessageId("pending-1")), settled)
        val last = harness.logStore.events(conversationId).last()
        assertEquals(EventType.TURN_INTERRUPTED, last.type)
        val data = last.data as EventData.TurnInterrupted
        assertEquals(MessageId("pending-1"), data.messageId)
        assertEquals("half an answer", data.partialText)
        assertTrue(harness.drafts.all().isEmpty())

        // A second run is a no-op.
        assertEquals(emptyList<MessageId>(), gateway.reconcile())
        assertEquals(2, harness.logStore.events(conversationId).size)
    }

    // -----------------------------------------------------------------------
    // snapshots
    // -----------------------------------------------------------------------

    @Test
    fun `snapshots emit immediately and again for every append`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        // Hang the stream so each append is observed before the next one.
        harness.completion.events = listOf(GenerationEvent.TextDelta("x"))
        harness.completion.hangAfter = 1
        val gateway = harness.gateway()

        val seen = mutableListOf<ChatSnapshot>()
        val collector = launch {
            gateway.snapshots(conversationId).collect { seen += it }
        }
        advanceUntilIdle()

        assertEquals(1, seen.size)
        assertEquals(0L, seen.single().version)

        gateway.send(conversationId, "hello")
        advanceUntilIdle()

        assertTrue("expected an emission for the send batch", seen.size >= 2)
        // user + turn/start + session/title
        assertEquals(3L, seen.last().version)
        assertEquals(3, harness.logStore.events(conversationId).size)

        gateway.cancel(conversationId)
        advanceUntilIdle()

        assertTrue("expected an emission for the interrupted batch", seen.size >= 3)
        assertEquals(5L, seen.last().version)
        assertEquals(seen.map { it.version }.sorted(), seen.map { it.version })
        collector.cancel()
    }

    @Test
    fun `draft snapshot is empty and carries the sentinel id`() = runTest {
        val harness = LogHarness(this)
        val gateway = harness.gateway()

        val draft = gateway.draftSnapshot().first()

        assertEquals(DefaultChatGateway.DRAFT_CONVERSATION_ID, draft.conversationId)
        assertEquals(0L, draft.version)
        assertEquals("", draft.title)
        assertTrue(draft.messages.isEmpty())
        assertTrue(draft.transcript.isEmpty())
        assertEquals(UsageTotals(), draft.totals)
    }

    // -----------------------------------------------------------------------
    // edit / regenerate resolve the conversation from a message id
    // -----------------------------------------------------------------------

    @Test
    fun `a command addressed by message id finds its conversation`() = runTest {
        val harness = LogHarness(this)
        val first = seedConversation(harness.conversations, "c1")
        val second = ConversationId("c2")
        harness.conversations.put(conversation("c2", title = "second"))
        harness.seed(first, userEvent(1, "u1", "one"))
        harness.seed(second, userEvent(1, "u2", "two"), assistantEvent(2, "a2", "answer", parentId = "u2"))
        val gateway = harness.gateway()

        val outcome = gateway.switchBranch(MessageId("a2"))
        advanceUntilIdle()

        assertTrue(outcome is Outcome.Ok)
        assertEquals(1, harness.logStore.events(first).size)
        assertEquals(
            EventType.LEAF_CHANGED,
            harness.logStore.events(second).last().type,
        )
    }
}
