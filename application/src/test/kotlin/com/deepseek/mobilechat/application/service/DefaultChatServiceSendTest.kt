package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.SendResult
import com.deepseek.mobilechat.application.service.testutil.ChatHarness
import com.deepseek.mobilechat.application.service.testutil.FixedPlanStrategy
import com.deepseek.mobilechat.application.service.testutil.attachment
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.descriptor
import com.deepseek.mobilechat.application.service.testutil.preset
import com.deepseek.mobilechat.application.service.testutil.proposal
import com.deepseek.mobilechat.application.service.testutil.providerProfile
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChatServiceSendTest {

    private val cid = ConversationId("c1")

    @Test
    fun `send persists the user message and completes the assistant stream`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.profiles.put(providerProfile())
        harness.catalog.models = listOf(descriptor(inputPerMTok = 1.0, outputPerMTok = 2.0))
        harness.chat.events = listOf(
            GenerationEvent.Started(0L),
            GenerationEvent.TextDelta("Hello"),
            GenerationEvent.TextDelta(" world"),
            GenerationEvent.UsageReport(Usage(promptTokens = 100, completionTokens = 50)),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        val result = harness.service.send(cid, "Hi there").requireOk()
        assertTrue(result is SendResult.Started)
        val started = result as SendResult.Started
        advanceUntilIdle()

        val user = harness.messages.get(started.userMessageId)!!
        assertEquals(MessageRole.USER, user.role)
        assertEquals(MessageStatus.COMPLETE, user.status)
        assertEquals("Hi there", user.text)
        assertNull(user.parentId)
        assertEquals(1L, user.seq)

        val assistant = harness.messages.get(started.assistantMessageId)!!
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(started.userMessageId, assistant.parentId)
        assertEquals(1L, assistant.seq)
        assertEquals(MessageStatus.COMPLETE, assistant.status)
        assertEquals("Hello world", assistant.text)
        assertEquals(FinishReason.STOP, assistant.finishReason)
        assertNull(assistant.error)
        assertEquals(100, assistant.usage!!.promptTokens)
        assertEquals(50, assistant.usage!!.completionTokens)
        assertEquals(0.0002 * 7.2, assistant.usage!!.cost!!, 1e-12)
        assertEquals(harness.assembler.modelRef, assistant.modelInfo!!.ref)

        val conversation = harness.conversations.get(cid)!!
        assertEquals(started.assistantMessageId, conversation.activeLeafId)
        assertEquals("Hi there", conversation.title)
        assertEquals(harness.clock.nowMillis(), conversation.updatedAt)

        assertEquals(1, harness.chat.requests.size)
        val request = harness.chat.requests.single()
        assertEquals(harness.assembler.prompt, request.messages)
        assertEquals(harness.assembler.systemPrompt, request.systemPrompt)
        assertEquals(harness.assembler.modelRef, request.modelRef)
        assertEquals(harness.assembler.params, request.params)
        assertEquals(harness.assembler.thinkingLevel, request.thinkingLevel)
        assertEquals(listOf(cid), harness.assembler.calls)
    }

    @Test
    fun `over budget send asks for confirmation and writes no assistant message`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.assembler.limit = 100
        harness.assembler.used = 400
        harness.strategies["confirm-compaction"] = FixedPlanStrategy("confirm-compaction") { _, _ ->
            ContextPlan.RequiresConfirmation(proposal(summary = "condensed"))
        }

        val result = harness.service.send(cid, "please remember everything").requireOk()
        advanceUntilIdle()

        assertTrue(result is SendResult.NeedsConfirmation)
        val proposal = (result as SendResult.NeedsConfirmation).proposal
        assertEquals("condensed", proposal.summary)

        // The turn was not accepted: neither bubble exists yet, and the
        // conversation cursor is back where it was.
        assertTrue(harness.messages.list(cid).isEmpty())
        assertNull(harness.conversations.get(cid)!!.activeLeafId)
        assertTrue(harness.chat.requests.isEmpty())
    }

    @Test
    fun `confirming the compaction and re-sending does not duplicate the user message`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.assembler.limit = 100
        harness.assembler.used = 400
        harness.strategies["confirm-compaction"] = FixedPlanStrategy("confirm-compaction") { _, _ ->
            ContextPlan.RequiresConfirmation(proposal())
        }

        assertTrue(harness.service.send(cid, "long turn").requireOk() is SendResult.NeedsConfirmation)

        // The UI applies the compaction, then re-sends the same text.
        harness.assembler.used = 10
        val started = harness.service.send(cid, "long turn").requireOk() as SendResult.Started
        advanceUntilIdle()

        val messages = harness.messages.list(cid)
        assertEquals(2, messages.size)
        assertEquals(1, messages.count { it.role == MessageRole.USER })
        assertEquals(started.userMessageId, messages.first { it.role == MessageRole.USER }.id)
        assertEquals(started.assistantMessageId, harness.conversations.get(cid)!!.activeLeafId)
    }

    @Test
    fun `stream failure marks the assistant FAILED and keeps the error`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.chat.events = listOf(
            GenerationEvent.TextDelta("partial"),
            GenerationEvent.Failed(AppError.ProviderUnavailable("provider down")),
        )

        val started = harness.service.send(cid, "hi").requireOk() as SendResult.Started
        advanceUntilIdle()

        val assistant = harness.messages.get(started.assistantMessageId)!!
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("partial", assistant.text)
        assertEquals("provider down", assistant.error)
        assertEquals(FinishReason.ERROR, assistant.finishReason)
    }

    @Test
    fun `cancel stops the stream and keeps partial content`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.chat.events = listOf(GenerationEvent.TextDelta("partial answer"))
        harness.chat.hangAfter = 1

        val started = harness.service.send(cid, "hi").requireOk() as SendResult.Started
        runCurrent()
        harness.service.cancel(cid).requireOk()
        advanceUntilIdle()

        val assistant = harness.messages.get(started.assistantMessageId)!!
        assertEquals(MessageStatus.CANCELLED, assistant.status)
        assertEquals(FinishReason.CANCELLED, assistant.finishReason)
        assertEquals("partial answer", assistant.text)
    }

    @Test
    fun `attachments become image and file parts in order`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.attachments.put(attachment("a1", AttachmentKind.IMAGE))
        harness.attachments.put(attachment("a2", AttachmentKind.TEXT))

        val started = harness.service.send(
            conversationId = cid,
            text = "look at these",
            attachmentIds = listOf(AttachmentId("a1"), AttachmentId("a2")),
        ).requireOk() as SendResult.Started
        advanceUntilIdle()

        val user = harness.messages.get(started.userMessageId)!!
        assertEquals(
            listOf(
                MessagePart.Text("look at these"),
                MessagePart.Image(AttachmentId("a1")),
                MessagePart.FileRef(AttachmentId("a2")),
            ),
            user.parts,
        )
    }

    @Test
    fun `empty text without attachments is a validation error`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))

        val error = harness.service.send(cid, "   ").requireErr()

        assertTrue(error is AppError.Validation)
        assertTrue(harness.messages.list(cid).isEmpty())
    }

    @Test
    fun `missing attachment is rejected before anything is written`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))

        val error = harness.service.send(cid, "hi", listOf(AttachmentId("nope"))).requireErr()

        assertTrue(error is AppError.Validation)
        assertTrue(harness.messages.list(cid).isEmpty())
    }

    @Test
    fun `ephemeral injection is visible to the assembler and removed afterwards`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        val injectionsDuringAssembly = mutableListOf<Int>()
        harness.assembler.beforeAssemble = {
            injectionsDuringAssembly += harness.injections.state.value.size
        }

        harness.service.send(cid, "hi", ephemeralInjection = "this turn only").requireOk()
        advanceUntilIdle()

        assertEquals(listOf(1), injectionsDuringAssembly)
        assertTrue(harness.injections.state.value.isEmpty())
    }

    @Test
    fun `assembly failure is reported and no assistant message is created`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.assembler.failure = AppError.Validation("no active leaf")

        val error = harness.service.send(cid, "hi").requireErr()

        assertEquals("no active leaf", error.message)
        assertTrue(harness.messages.list(cid).none { it.role == MessageRole.ASSISTANT })
        assertTrue(harness.messages.list(cid).isEmpty())
    }

    @Test
    fun `draft checkpoints are written while streaming`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.settings.set(AppSettings(streamingCheckpointMillis = 300))
        harness.clock.autoAdvance(400)
        harness.chat.events = listOf(
            GenerationEvent.TextDelta("a"),
            GenerationEvent.TextDelta("b"),
            GenerationEvent.TextDelta("c"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        val started = harness.service.send(cid, "hi").requireOk() as SendResult.Started
        advanceUntilIdle()

        val writes = harness.messages.updates.filter { it.id == started.assistantMessageId }
        val drafts = writes.filter { it.status == MessageStatus.STREAMING }
        assertTrue("expected intermediate drafts, got ${drafts.size}", drafts.size >= 2)
        assertEquals("a", drafts.first().text)
        assertEquals("abc", drafts.last().text)
        assertEquals(MessageStatus.COMPLETE, writes.last().status)
        assertEquals("abc", writes.last().text)
    }

    @Test
    fun `a throwing transport marks the assistant FAILED`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.chat.throwOnStream = IllegalStateException("socket closed")

        val started = harness.service.send(cid, "hi").requireOk() as SendResult.Started
        advanceUntilIdle()

        val assistant = harness.messages.get(started.assistantMessageId)!!
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("socket closed", assistant.error)
        assertEquals(FinishReason.ERROR, assistant.finishReason)
    }

    @Test
    fun `the preset context strategy is the one that runs`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.presets.put(preset("p1", isDefault = true, contextStrategyId = "none"))
        val strategy = FixedPlanStrategy("none") { _, _ ->
            ContextPlan.Rejected(AppError.Validation("context exceeds budget"))
        }
        harness.strategies["none"] = strategy
        harness.assembler.limit = 10
        harness.assembler.used = 100

        val error = harness.service.send(cid, "too long").requireErr()

        assertEquals("context exceeds budget", error.message)
        assertEquals(1, strategy.planCalls)
        assertTrue(harness.messages.list(cid).none { it.role == MessageRole.ASSISTANT })
        assertTrue(harness.messages.list(cid).isEmpty())
    }

    @Test
    fun `a paused queue holds the assistant turn until resumed`() = runTest {
        val harness = ChatHarness(testScheduler)
        harness.conversations.put(conversation("c1"))
        harness.queue().pause()

        val started = harness.service.send(cid, "hi").requireOk() as SendResult.Started
        advanceUntilIdle()

        assertEquals(
            MessageStatus.STREAMING,
            harness.messages.get(started.assistantMessageId)!!.status,
        )
        assertTrue(harness.chat.requests.isEmpty())

        harness.queue().resume()
        advanceUntilIdle()

        assertEquals(
            MessageStatus.COMPLETE,
            harness.messages.get(started.assistantMessageId)!!.status,
        )
        assertEquals(1, harness.chat.requests.size)
    }
}
