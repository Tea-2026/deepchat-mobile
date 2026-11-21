package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.service.testutil.FakeTokenEstimator
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SessionEvent
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.SurfaceOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceContextBuilderTest {

    private val estimator = FakeTokenEstimator()
    private val modelRef = ModelRef(ProviderId("provider-1"), ModelId("model-1"))
    private val lookup = object : ModelBudgetLookup {
        override fun contextWindow(modelRef: ModelRef): Int = 10_000
    }
    private val builder = SurfaceContextBuilder(estimator, lookup)

    private val settings = SurfaceContextBuilder.TurnSettings(
        modelRef = modelRef,
        params = SamplingParams(temperature = 0.2),
        thinkingLevel = ThinkingLevel.LOW,
        systemPrompt = "SYS",
    )

    private fun log(vararg events: SessionEvent) =
        SurfaceFolder.fold(ConversationLog(ConversationId("c1"), events.toList()))

    private fun event(
        seq: Long,
        type: EventType,
        data: EventData,
    ): SessionEvent = SessionEvent(
        seq = seq,
        eventId = "e$seq",
        type = type,
        time = seq,
        data = data,
        surfaceOp = SurfaceOp.Append,
    )

    @Test
    fun `surface nodes become prompt messages in order with the current turn at the end`() {
        val surface = log(
            event(
                1,
                EventType.USER_MESSAGE,
                EventData.UserMessage(MessageId("u1"), null, listOf(MessagePart.Text("one"))),
            ),
            event(
                2,
                EventType.ASSISTANT_MESSAGE,
                EventData.AssistantMessage(
                    messageId = MessageId("a1"),
                    parentId = MessageId("u1"),
                    parts = listOf(
                        MessagePart.Text("let me search"),
                        MessagePart.ToolCall("call-1", "search", "{}"),
                    ),
                ),
            ),
            event(3, EventType.TOOL_RESULT, EventData.ToolResult("call-1", "tool output")),
            event(
                4,
                EventType.DEVELOPER_MESSAGE,
                EventData.DeveloperMessage("remember this", kind = "injection"),
            ),
            event(
                5,
                EventType.USER_MESSAGE,
                EventData.UserMessage(MessageId("u2"), MessageId("a1"), listOf(MessagePart.Text("two"))),
            ),
        )

        val search = PromptMessage(
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text("search block")),
        )
        val current = PromptMessage(
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text("three")),
            sourceMessageId = MessageId("u3"),
        )

        val assembly = builder.build(
            conversationId = ConversationId("c1"),
            settings = settings,
            surface = surface,
            searchBlock = search,
            currentUser = current,
        )

        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.TOOL,
                MessageRole.SYSTEM,
                MessageRole.USER,
                MessageRole.USER,
                MessageRole.USER,
            ),
            assembly.prompt.map { it.role },
        )
        // The tool result keeps the tool_use pairing.
        val toolPart = assembly.prompt[2].parts.single() as MessagePart.ToolResult
        assertEquals("call-1", toolPart.callId)
        assertTrue(toolPart.ok)
        assertEquals("tool output", toolPart.content)
        // System prompt is carried separately, never duplicated in the list.
        assertEquals("SYS", assembly.systemPrompt)
        assertEquals(1, assembly.blocks.count { it.kind == BlockKind.SYSTEM_PROMPT && it.id == "system" })
        // Ephemeral search comes before the current turn's user message.
        assertTrue(assembly.prompt[5] === search)
        assertTrue(assembly.prompt[6] === current)
        assertEquals(MessageId("u3"), assembly.leafId)
        // Budget = sum of the block estimates, and the window comes from the lookup.
        assertEquals(10_000, assembly.budget.limit)
        assertEquals(assembly.blocks.sumOf { it.tokens }, assembly.budget.used)
        assertTrue(assembly.blocks.any { it.kind == BlockKind.SEARCH })
        assertTrue(assembly.blocks.any { it.kind == BlockKind.CURRENT_USER && it.messageIds == listOf(MessageId("u3")) })
    }

    @Test
    fun `settings resolve overrides before preset before fallback`() {
        val preset = Preset(
            id = PresetId("p1"),
            name = "p1",
            systemPrompt = "preset",
            modelRef = ModelRef(ProviderId("provider-2"), ModelId("model-2")),
            params = SamplingParams(temperature = 0.9),
            thinkingLevel = ThinkingLevel.HIGH,
        )

        val fallback = ModelRef(ProviderId("provider-9"), ModelId("model-9"))
        val fromPreset = builder.settings(ConversationOverrides(), preset, fallback)
        assertNotNull(fromPreset)
        assertEquals(preset.modelRef, fromPreset!!.modelRef)
        assertEquals("preset", fromPreset.systemPrompt)
        assertEquals(ThinkingLevel.HIGH, fromPreset.thinkingLevel)

        val fromFallback = builder.settings(ConversationOverrides(), null, fallback)!!
        assertEquals(fallback, fromFallback.modelRef)
        assertNull(fromFallback.systemPrompt)

        val overridden = builder.settings(
            ConversationOverrides(
                modelRef = ModelRef(ProviderId("provider-3"), ModelId("model-3")),
                systemPrompt = "override",
            ),
            preset,
            fallback,
        )
        assertEquals("override", overridden!!.systemPrompt)
        assertEquals(ModelRef(ProviderId("provider-3"), ModelId("model-3")), overridden.modelRef)

        assertNull(builder.settings(ConversationOverrides(), null, null))
    }
}
