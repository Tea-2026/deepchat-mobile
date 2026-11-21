package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.service.testutil.ChatHarness
import com.deepseek.mobilechat.application.service.testutil.assistantMessage
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.application.service.testutil.userMessage
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChatServiceBranchTest {

    private val cid = ConversationId("c1")
    private val u1 = MessageId("u1")
    private val a1 = MessageId("a1")

    private fun harnessWithFirstTurn(scheduler: TestCoroutineScheduler): ChatHarness {
        val harness = ChatHarness(scheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        harness.messages.put(
            userMessage("u1", text = "original", seq = 1),
            assistantMessage("a1", text = "old answer", seq = 1, parentId = "u1"),
        )
        return harness
    }

    // -----------------------------------------------------------------------
    // Regenerate
    // -----------------------------------------------------------------------

    @Test
    fun `regenerate creates a sibling and leaves the old reply untouched`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)
        var leafDuringAssembly: MessageId? = null
        harness.assembler.beforeAssemble = {
            leafDuringAssembly = harness.conversations.get(cid)?.activeLeafId
        }

        harness.service.regenerate(a1).requireOk()
        advanceUntilIdle()

        assertEquals(u1, leafDuringAssembly)
        val all = harness.messages.list(cid)
        val children = all.filter { it.parentId == u1 }
        assertEquals(2, children.size)

        val old = all.first { it.id == a1 }
        assertEquals("old answer", old.text)
        assertEquals(1L, old.seq)
        assertEquals(MessageStatus.COMPLETE, old.status)

        val fresh = children.first { it.id != a1 }
        assertEquals(MessageRole.ASSISTANT, fresh.role)
        assertEquals(2L, fresh.seq)
        assertEquals(MessageStatus.COMPLETE, fresh.status)
        assertEquals(fresh.id, harness.conversations.get(cid)!!.activeLeafId)
    }

    @Test
    fun `regenerate refuses a user message that already has a reply`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        val error = harness.service.regenerate(u1).requireErr()

        assertTrue(error is AppError.BranchConflict)
        assertEquals(2, harness.messages.list(cid).size)
    }

    // -----------------------------------------------------------------------
    // Edit
    // -----------------------------------------------------------------------

    @Test
    fun `editing a leaf updates it in place and keeps attachments`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "u1"))
        harness.messages.put(
            userMessage(
                id = "u1",
                seq = 1,
                parts = listOf(
                    MessagePart.Text("old"),
                    MessagePart.Image(AttachmentId("a1")),
                ),
            ),
        )
        harness.clock.setNow(5_000L)

        val result = harness.service.editMessage(u1, "  edited text  ").requireOk()

        assertFalse(result.forked)
        assertEquals(u1, result.messageId)
        val updated = harness.messages.get(u1)!!
        assertEquals(
            listOf(MessagePart.Text("edited text"), MessagePart.Image(AttachmentId("a1"))),
            updated.parts,
        )
        assertEquals(5_000L, updated.editedAt)
        assertEquals(1, harness.messages.list(cid).size)
        assertEquals(u1, harness.conversations.get(cid)!!.activeLeafId)
    }

    @Test
    fun `editing a message with children forks into a new user sibling and starts its reply`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        val result = harness.service.editMessage(u1, "forked text").requireOk()
        advanceUntilIdle()

        assertTrue(result.forked)
        assertNotEquals(u1, result.messageId)

        val sibling = harness.messages.get(result.messageId)!!
        assertEquals(MessageRole.USER, sibling.role)
        assertNull(sibling.parentId)
        assertEquals(2L, sibling.seq)
        assertEquals(listOf(MessagePart.Text("forked text")), sibling.parts)
        assertEquals(MessageStatus.COMPLETE, sibling.status)

        // The new branch is answered immediately: an assistant placeholder is
        // appended under the sibling and handed to the queue.
        val reply = harness.messages.list(cid).single { it.parentId == sibling.id }
        assertEquals(MessageRole.ASSISTANT, reply.role)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals(reply.id, harness.conversations.get(cid)!!.activeLeafId)
        assertEquals(1, harness.chat.requests.size)

        val original = harness.messages.get(u1)!!
        assertEquals("original", original.text)
        assertNull(original.editedAt)
        assertEquals(4, harness.messages.list(cid).size)
    }

    @Test
    fun `regenerate on an unanswered user leaf starts its reply`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "u1"))
        harness.messages.put(userMessage("u1", text = "never answered", seq = 1))

        harness.service.regenerate(u1).requireOk()
        advanceUntilIdle()

        val reply = harness.messages.list(cid).single { it.parentId == u1 }
        assertEquals(MessageRole.ASSISTANT, reply.role)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals(reply.id, harness.conversations.get(cid)!!.activeLeafId)
        assertEquals(1, harness.chat.requests.size)
    }

    @Test
    fun `editing an assistant message is rejected`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        val error = harness.service.editMessage(a1, "nope").requireErr()

        assertTrue(error is AppError.Validation)
        assertEquals(2, harness.messages.list(cid).size)
    }

    // -----------------------------------------------------------------------
    // Branch switching
    // -----------------------------------------------------------------------

    @Test
    fun `switchToBranch moves the active leaf`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        harness.service.switchToBranch(u1).requireOk()

        assertEquals(u1, harness.conversations.get(cid)!!.activeLeafId)
        assertTrue(harness.service.switchToBranch(MessageId("missing")) is Outcome.Err)
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    @Test
    fun `deleting the active leaf falls back to its parent`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        harness.service.deleteSubtree(a1).requireOk()

        assertEquals(listOf(u1), harness.messages.list(cid).map { it.id })
        assertEquals(u1, harness.conversations.get(cid)!!.activeLeafId)
    }

    @Test
    fun `deleting a leaf falls back to the newest remaining sibling`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a2"))
        harness.messages.put(
            userMessage("u1", seq = 1),
            assistantMessage("a1", text = "first", seq = 1, parentId = "u1"),
            assistantMessage("a2", text = "second", seq = 2, parentId = "u1"),
        )

        harness.service.deleteSubtree(MessageId("a2")).requireOk()

        val remaining = harness.messages.list(cid).map { it.id }
        assertEquals(listOf(u1, MessageId("a1")), remaining)
        assertEquals(MessageId("a1"), harness.conversations.get(cid)!!.activeLeafId)
    }

    @Test
    fun `a message that is still generating cannot be deleted`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        harness.messages.put(
            userMessage("u1", seq = 1),
            assistantMessage(
                id = "a1",
                seq = 1,
                parentId = "u1",
                status = MessageStatus.STREAMING,
            ),
        )

        val error = harness.service.deleteSubtree(a1).requireErr()

        assertTrue(error is AppError.BranchConflict)
        assertEquals(2, harness.messages.list(cid).size)
        assertEquals(a1, harness.conversations.get(cid)!!.activeLeafId)
    }

    // -----------------------------------------------------------------------
    // Continue / pin
    // -----------------------------------------------------------------------

    @Test
    fun `continueGeneration appends to the same message`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        harness.messages.put(
            userMessage("u1", text = "tell me", seq = 1),
            assistantMessage(
                id = "a1",
                text = "part one",
                seq = 1,
                parentId = "u1",
                finishReason = FinishReason.LENGTH,
            ),
        )
        harness.chat.events = listOf(
            GenerationEvent.TextDelta(" and part two"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        harness.service.continueGeneration(a1).requireOk()
        advanceUntilIdle()

        val continued = harness.messages.get(a1)!!
        assertEquals("part one and part two", continued.text)
        assertEquals(MessageStatus.COMPLETE, continued.status)
        assertEquals(FinishReason.STOP, continued.finishReason)
        assertEquals(2, harness.messages.list(cid).size)
    }

    @Test
    fun `continueGeneration only accepts length truncated replies`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        val error = harness.service.continueGeneration(a1).requireErr()

        assertTrue(error is AppError.Validation)
        assertTrue(harness.chat.requests.isEmpty())
    }

    @Test
    fun `continueGeneration resumes an interrupted message`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        harness.messages.put(
            userMessage("u1", text = "tell me", seq = 1),
            assistantMessage(
                id = "a1",
                text = "part one",
                seq = 1,
                parentId = "u1",
                status = MessageStatus.CANCELLED,
                finishReason = FinishReason.CANCELLED,
            ).copy(error = "生成已中断：应用在生成过程中退出"),
        )
        harness.chat.events = listOf(
            GenerationEvent.TextDelta(" and part two"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        harness.service.continueGeneration(a1).requireOk()
        advanceUntilIdle()

        val continued = harness.messages.get(a1)!!
        assertEquals("part one and part two", continued.text)
        assertEquals(MessageStatus.COMPLETE, continued.status)
        assertNull(continued.error)
    }

    @Test
    fun `continueGeneration resumes a failed message after partial output`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        harness.messages.put(
            userMessage("u1", text = "tell me", seq = 1),
            assistantMessage(
                id = "a1",
                text = "part one",
                seq = 1,
                parentId = "u1",
                status = MessageStatus.FAILED,
                finishReason = FinishReason.ERROR,
            ).copy(error = "HTTP 400: Invalid assistant message"),
        )
        harness.chat.events = listOf(
            GenerationEvent.TextDelta(" and part two"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        harness.service.continueGeneration(a1).requireOk()
        advanceUntilIdle()

        val continued = harness.messages.get(a1)!!
        assertEquals("part one and part two", continued.text)
        assertEquals(MessageStatus.COMPLETE, continued.status)
        assertNull(continued.error)
    }

    @Test
    fun `setPinned toggles the flag`() = runTest {
        val harness = harnessWithFirstTurn(testScheduler)

        harness.service.setPinned(u1, true).requireOk()
        assertTrue(harness.messages.get(u1)!!.pinned)

        harness.service.setPinned(u1, false).requireOk()
        assertFalse(harness.messages.get(u1)!!.pinned)
    }
}
