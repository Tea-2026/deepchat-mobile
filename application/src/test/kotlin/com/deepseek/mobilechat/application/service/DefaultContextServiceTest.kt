package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.service.testutil.FakeChatCompletion
import com.deepseek.mobilechat.application.service.testutil.FakeClock
import com.deepseek.mobilechat.application.service.testutil.FakeCompactionStore
import com.deepseek.mobilechat.application.service.testutil.FakeContextAssembler
import com.deepseek.mobilechat.application.service.testutil.FakeConversationStore
import com.deepseek.mobilechat.application.service.testutil.FakeIdPort
import com.deepseek.mobilechat.application.service.testutil.FakeInjectionStore
import com.deepseek.mobilechat.application.service.testutil.FakeMessageStore
import com.deepseek.mobilechat.application.service.testutil.FakePresetStore
import com.deepseek.mobilechat.application.service.testutil.FixedPlanStrategy
import com.deepseek.mobilechat.application.service.testutil.SummarizingStrategy
import com.deepseek.mobilechat.application.service.testutil.assistantMessage
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.preset
import com.deepseek.mobilechat.application.service.testutil.proposal
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.application.service.testutil.userMessage
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.PresetId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultContextServiceTest {

    private val conversations = FakeConversationStore()
    private val messages = FakeMessageStore()
    private val injections = FakeInjectionStore()
    private val compactions = FakeCompactionStore()
    private val presets = FakePresetStore()
    private val assembler = FakeContextAssembler()
    private val chat = FakeChatCompletion()
    private val clock = FakeClock()
    private val ids = FakeIdPort("ctx")
    private val strategies = mutableMapOf<String, ContextStrategy>()

    private val service = DefaultContextService(
        contextAssembler = assembler,
        conversationStore = conversations,
        messageStore = messages,
        injectionStore = injections,
        compactionStore = compactions,
        presetStore = presets,
        chatCompletion = chat,
        clock = clock,
        idPort = ids,
        strategies = strategies,
    )

    private fun seedTree() {
        conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        messages.put(
            userMessage("u1", text = "hello", seq = 1),
            assistantMessage("a1", text = "hi", seq = 1, parentId = "u1"),
        )
    }

    @Test
    fun `assemble delegates to the injected assembler`() = runTest {
        val assembly = service.assemble(ConversationId("c1")).requireOk()

        assertEquals(ConversationId("c1"), assembly.conversationId)
        assertEquals(listOf(ConversationId("c1")), assembler.calls)
    }

    @Test
    fun `planCompaction refuses to plan when the context fits`() = runTest {
        seedTree()
        assembler.limit = 1_000
        assembler.used = 100

        val error = service.planCompaction(ConversationId("c1")).requireErr()

        assertTrue(error is AppError.Validation)
    }

    @Test
    fun `planCompaction asks the strategy and uses the streaming summarizer`() = runTest {
        seedTree()
        assembler.limit = 100
        assembler.used = 400
        strategies["confirm-compaction"] = SummarizingStrategy("confirm-compaction", "u1", "a1")
        chat.events = listOf(
            GenerationEvent.TextDelta("compressed "),
            GenerationEvent.TextDelta("summary"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        val plan = service.planCompaction(ConversationId("c1")).requireOk()

        assertEquals("compressed summary", plan.summary)
        assertEquals(MessageId("u1"), plan.fromMessageId)
        assertEquals(MessageId("a1"), plan.toMessageId)
        assertEquals(1, chat.requests.size)
        assertEquals("confirm-compaction", plan.strategyId)
        assertTrue(compactions.state.value.isEmpty())
    }

    @Test
    fun `planCompaction surfaces a rejecting strategy`() = runTest {
        seedTree()
        assembler.limit = 100
        assembler.used = 400
        strategies["confirm-compaction"] = FixedPlanStrategy("confirm-compaction") { _, _ ->
            ContextPlan.Rejected(AppError.Validation("nothing to compact"))
        }

        val error = service.planCompaction(ConversationId("c1")).requireErr()

        assertEquals("nothing to compact", error.message)
    }

    @Test
    fun `applyCompaction persists a summary record`() = runTest {
        seedTree()
        clock.setNow(7_500L)

        val compaction = service.applyCompaction(proposal()).requireOk()

        assertEquals(CompactionKind.SUMMARY, compaction.kind)
        assertEquals("summary", compaction.summary)
        assertEquals(7_500L, compaction.createdAt)
        assertEquals(listOf(compaction), compactions.state.value)
    }

    @Test
    fun `applyCompaction rejects a range outside the active path`() = runTest {
        seedTree()

        val error = service.applyCompaction(proposal(from = "missing")).requireErr()

        assertTrue(error is AppError.BranchConflict)
        assertTrue(compactions.state.value.isEmpty())
    }

    @Test
    fun `applyCompaction rejects a range containing a streaming message`() = runTest {
        conversations.put(conversation("c1", title = "chat", activeLeafId = "a1"))
        messages.put(
            userMessage("u1", text = "hello", seq = 1),
            assistantMessage(
                id = "a1",
                seq = 1,
                parentId = "u1",
                status = MessageStatus.STREAMING,
            ),
        )

        val error = service.applyCompaction(proposal()).requireErr()

        assertTrue(error is AppError.BranchConflict)
        assertTrue(compactions.state.value.isEmpty())
    }

    @Test
    fun `undoCompaction marks the record undone without deleting history`() = runTest {
        seedTree()
        val compaction = service.applyCompaction(proposal()).requireOk()

        service.undoCompaction(compaction.id).requireOk()

        assertNotEquals(null, compactions.state.value.single().undoneAt)
        assertEquals(2, messages.list(ConversationId("c1")).size)
    }

    @Test
    fun `undoCompaction is a no-op for an unknown id`() = runTest {
        service.undoCompaction(CompactionId("ghost")).requireOk()
        assertTrue(compactions.state.value.isEmpty())
    }

    @Test
    fun `injections get increasing positions and can be removed`() = runTest {
        conversations.put(conversation("c1"))

        val first = service.addInjection(
            conversationId = ConversationId("c1"),
            content = " remember this ",
            scope = InjectionScope.EPHEMERAL,
        ).requireOk()
        val second = service.addInjection(
            conversationId = ConversationId("c1"),
            content = "and this",
            scope = InjectionScope.PERSISTENT,
            anchorMessageId = MessageId("u1"),
        ).requireOk()

        assertEquals(0, first.position)
        assertEquals(1, second.position)
        assertEquals("remember this", first.content)
        assertEquals(MessageId("u1"), second.anchorMessageId)

        val observed = service.observeInjections(ConversationId("c1")).first()
        assertEquals(listOf(first.id, second.id), observed.map { it.id })

        service.removeInjection(first.id).requireOk()
        assertEquals(listOf(second.id), injections.state.value.map { it.id })

        val error = service.addInjection(
            conversationId = ConversationId("c1"),
            content = "   ",
            scope = InjectionScope.EPHEMERAL,
        ).requireErr()
        assertTrue(error is AppError.Validation)
    }

    @Test
    fun `addInjection rejects an unknown conversation`() = runTest {
        val error = service.addInjection(
            conversationId = ConversationId("ghost"),
            content = "x",
            scope = InjectionScope.PERSISTENT,
        ).requireErr()

        assertTrue(error is AppError.Validation)
    }

    @Test
    fun `unknown strategy id is unsupported`() = runTest {
        seedTree()
        assembler.limit = 100
        assembler.used = 400
        conversations.upsert(
            conversation("c1", title = "chat", activeLeafId = "a1").copy(
                overrides = ConversationOverrides(presetId = PresetId("p1")),
            ),
        )
        presets.put(preset("p1", contextStrategyId = "rolling-memory"))

        val error = service.planCompaction(ConversationId("c1")).requireErr()

        assertTrue(error is AppError.Unsupported)
    }

    @Test
    fun `compaction observation is scoped to the conversation`() = runTest {
        seedTree()
        service.applyCompaction(proposal()).requireOk()

        val observed = service.observeCompactions(ConversationId("c1")).first()
        assertEquals(1, observed.size)
        assertTrue(service.observeCompactions(ConversationId("other")).first().isEmpty())
    }
}
