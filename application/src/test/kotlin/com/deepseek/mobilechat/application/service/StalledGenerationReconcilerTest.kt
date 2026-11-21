package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.service.testutil.ChatHarness
import com.deepseek.mobilechat.application.service.testutil.FakeConversationStore
import com.deepseek.mobilechat.application.service.testutil.FakeMessageStore
import com.deepseek.mobilechat.application.service.testutil.assistantMessage
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.application.service.testutil.userMessage
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StalledGenerationReconcilerTest {

    @Test
    fun `streaming rows are settled to an interrupted terminal state`() = runTest {
        val conversations = FakeConversationStore()
        val messages = FakeMessageStore()
        conversations.put(conversation("c1", title = "one", activeLeafId = "a1"))
        conversations.put(conversation("c2", title = "two", activeLeafId = "b1"))
        messages.put(
            userMessage("u1", conversationId = "c1", text = "tell me", seq = 1),
            assistantMessage(
                "a1",
                conversationId = "c1",
                text = "世卫 +",
                seq = 1,
                parentId = "u1",
                status = MessageStatus.STREAMING,
            ).copy(finishReason = null),
            userMessage("u2", conversationId = "c2", text = "another", seq = 1),
            assistantMessage(
                "b1",
                conversationId = "c2",
                text = "",
                seq = 1,
                parentId = "u2",
                status = MessageStatus.STREAMING,
                parts = emptyList(),
            ).copy(finishReason = null),
            assistantMessage(
                "done",
                conversationId = "c1",
                text = "finished",
                seq = 2,
                parentId = "a1",
                status = MessageStatus.COMPLETE,
                finishReason = FinishReason.STOP,
            ),
        )

        val reconciler = StalledGenerationReconciler(conversations, messages)
        val settled = reconciler.reconcile()

        assertEquals(setOf(MessageId("a1"), MessageId("b1")), settled.toSet())

        val first = messages.get(MessageId("a1"))!!
        assertEquals(MessageStatus.CANCELLED, first.status)
        assertEquals(FinishReason.CANCELLED, first.finishReason)
        assertEquals(StalledGenerationReconciler.INTERRUPTED_ERROR, first.error)
        // Partial output survives so the transcript keeps what the user saw.
        assertEquals("世卫 +", first.text)

        val empty = messages.get(MessageId("b1"))!!
        assertEquals(MessageStatus.CANCELLED, empty.status)
        assertEquals(StalledGenerationReconciler.INTERRUPTED_ERROR, empty.error)

        val completed = messages.get(MessageId("done"))!!
        assertEquals(MessageStatus.COMPLETE, completed.status)
        assertEquals(FinishReason.STOP, completed.finishReason)
        assertNull(completed.error)
    }

    @Test
    fun `reconciliation is idempotent`() = runTest {
        val conversations = FakeConversationStore()
        val messages = FakeMessageStore()
        conversations.put(conversation("c1", activeLeafId = "a1"))
        messages.put(
            assistantMessage(
                "a1",
                conversationId = "c1",
                text = "partial",
                seq = 1,
                status = MessageStatus.STREAMING,
            ).copy(finishReason = null),
        )

        val reconciler = StalledGenerationReconciler(conversations, messages)

        assertEquals(listOf(MessageId("a1")), reconciler.reconcile())
        assertTrue(reconciler.reconcile().isEmpty())
        assertEquals(1, messages.updates.size)
    }

    @Test
    fun `a settled interrupted turn can be continued`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        harness.messages.put(
            userMessage("u1", text = "tell me", seq = 1),
            assistantMessage(
                "a1",
                text = "part one",
                seq = 1,
                parentId = "u1",
                status = MessageStatus.STREAMING,
            ).copy(finishReason = null),
        )

        StalledGenerationReconciler(harness.conversations, harness.messages).reconcile()
        harness.chat.events = listOf(
            GenerationEvent.TextDelta(" and part two"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        harness.service.continueGeneration(MessageId("a1")).requireOk()
        advanceUntilIdle()

        val continued = harness.messages.get(MessageId("a1"))!!
        assertEquals("part one and part two", continued.text)
        assertEquals(MessageStatus.COMPLETE, continued.status)
        assertNull(continued.error)
    }
}
