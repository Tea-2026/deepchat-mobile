package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ToolSpec
import com.deepseek.mobilechat.data.remote.testRequest
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicParsingTest {

    private val streamFixture = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":12,"output_tokens":1}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

        event: content_block_start
        data: {"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"considering"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":1,"delta":{"type":"signature_delta","signature":"sig-abc"}}

        event: content_block_start
        data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"search"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"x\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":2}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":34}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()

    private fun bodyJson(text: String): JsonObject = parseJsonObject(text)!!

    @Test
    fun `parses text thinking signature tool call usage and stop reason`() {
        val events = parseAnthropicStream(streamFixture)

        val expected = listOf(
            GenerationEvent.TextDelta("Hi"),
            GenerationEvent.ReasoningDelta("considering"),
            GenerationEvent.ReasoningSignature("sig-abc"),
            GenerationEvent.ToolCallDelta("toolu_1", "search", ""),
            GenerationEvent.ToolCallDelta("toolu_1", null, "{\"q\":\"x\"}"),
            GenerationEvent.UsageReport(Usage(promptTokens = 12, completionTokens = 34)),
            GenerationEvent.Finished(FinishReason.TOOL_CALLS),
        )
        assertEquals(expected, events)
    }

    @Test
    fun `cache hits are counted inside the prompt total and reported separately`() {
        // Anthropic's input_tokens excludes the cache. A 90-token cache hit and
        // a 5-token cache write therefore mean 105 prompt tokens, of which 90
        // are billed at the discounted cache rate.
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":90,"cache_creation_input_tokens":5}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()

        val usage = parseAnthropicStream(stream)
            .filterIsInstance<GenerationEvent.UsageReport>()
            .single()
            .usage

        assertEquals(Usage(promptTokens = 105, completionTokens = 7, cachedPromptTokens = 90), usage)
    }

    @Test
    fun `stop reason mapping covers anthropic values`() {
        assertEquals(FinishReason.STOP, mapAnthropicStopReason("end_turn"))
        assertEquals(FinishReason.STOP, mapAnthropicStopReason("stop_sequence"))
        assertEquals(FinishReason.LENGTH, mapAnthropicStopReason("max_tokens"))
        assertEquals(FinishReason.TOOL_CALLS, mapAnthropicStopReason("tool_use"))
        assertEquals(FinishReason.CONTENT_FILTER, mapAnthropicStopReason("refusal"))
    }

    @Test
    fun `error event becomes a protocol failure`() {
        val events = parseAnthropicStream(
            "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}\n\n",
        )

        val failure = events.single() as GenerationEvent.Failed
        assertEquals("overloaded", failure.error.message)
    }

    @Test
    fun `stream that ends without message_stop still reports usage and finishes`() {
        val events = parseAnthropicStream(
            """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":3}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":5}}

            """.trimIndent(),
        )

        assertEquals(
            listOf(
                GenerationEvent.UsageReport(Usage(promptTokens = 3, completionTokens = 5)),
                GenerationEvent.Finished(FinishReason.LENGTH),
            ),
            events,
        )
    }

    // -----------------------------------------------------------------------
    // Request building
    // -----------------------------------------------------------------------

    @Test
    fun `thinking budget is mapped and temperature is omitted`() {
        val request = testRequest(
            systemPrompt = "be terse",
            params = SamplingParams(temperature = 0.7, topP = 0.8, maxTokens = 1024),
            thinkingLevel = ThinkingLevel.MAX,
        )

        val body = bodyJson(buildAnthropicBody(request))

        assertEquals("be terse", body["system"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals(8192, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        // max_tokens must stay above budget_tokens for the API to accept it.
        assertEquals(9216, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(body["temperature"])
        assertNull(body["top_p"])
        assertNull(body["stop_sequences"])
        assertNull(body["tools"])
    }

    @Test
    fun `thinking off keeps temperature and omits thinking`() {
        val request = testRequest(
            params = SamplingParams(temperature = 0.2, topP = 0.5, stop = listOf("DONE")),
        )

        val body = bodyJson(buildAnthropicBody(request))

        assertNull(body["thinking"])
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.2, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("DONE", body["stop_sequences"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `thinking budgets follow the documented table`() {
        assertEquals(1024, anthropicThinkingBudget(ThinkingLevel.LOW))
        assertEquals(4096, anthropicThinkingBudget(ThinkingLevel.HIGH))
        assertEquals(8192, anthropicThinkingBudget(ThinkingLevel.MAX))
        assertNull(anthropicThinkingBudget(ThinkingLevel.OFF))
    }

    @Test
    fun `system role prompt messages are folded into the top-level system field`() {
        val request = testRequest(
            systemPrompt = "top",
            messages = listOf(
                PromptMessage(MessageRole.SYSTEM, listOf(MessagePart.Text("from history"))),
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("hello"))),
            ),
        )

        val body = bodyJson(buildAnthropicBody(request))
        val messages = body["messages"]!!.jsonArray

        assertEquals("top\n\nfrom history", body["system"]!!.jsonPrimitive.content)
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant reasoning with signature is replayed as a thinking block first`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(
                        MessagePart.Text("answer"),
                        MessagePart.Reasoning("thought", "sig-123"),
                    ),
                ),
            ),
        )

        val blocks = bodyJson(buildAnthropicBody(request))["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonArray

        assertEquals("thinking", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("thought", blocks[0].jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("sig-123", blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("text", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant reasoning without signature is dropped`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.Reasoning("unsigned"), MessagePart.Text("answer")),
                ),
            ),
        )

        val blocks = bodyJson(buildAnthropicBody(request))["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonArray

        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("answer", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `consecutive same-role messages are merged`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.USER,
                    listOf(MessagePart.Text("first")),
                    sourceMessageId = MessageId("m1"),
                ),
                PromptMessage(
                    MessageRole.USER,
                    listOf(MessagePart.Text("second")),
                    sourceMessageId = MessageId("m2"),
                ),
            ),
        )

        val messages = bodyJson(buildAnthropicBody(request))["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals(2, messages[0].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `image part becomes a base64 image block`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val request = testRequest(
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Image(AttachmentId("shot.jpg")))),
            ),
        )

        val block = bodyJson(buildAnthropicBody(request, resolveAttachment = { jpeg }))["messages"]!!
            .jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject

        assertEquals("image", block["type"]!!.jsonPrimitive.content)
        val source = block["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertTrue(source["data"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `tool results and tool calls use anthropic blocks`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.ToolCall("toolu_1", "search", """{"q":"x"}""")),
                ),
                PromptMessage(
                    MessageRole.TOOL,
                    listOf(MessagePart.ToolResult("toolu_1", true, "ok")),
                ),
            ),
            tools = listOf(ToolSpec("search", "web search", """{"type":"object"}""")),
        )

        val body = bodyJson(buildAnthropicBody(request))
        val messages = body["messages"]!!.jsonArray

        val useBlock = messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", useBlock["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", useBlock["id"]!!.jsonPrimitive.content)
        assertEquals("x", useBlock["input"]!!.jsonObject["q"]!!.jsonPrimitive.content)

        val resultBlock = messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", resultBlock["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", resultBlock["tool_use_id"]!!.jsonPrimitive.content)

        assertEquals(
            "search",
            body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `orphan assistant tool_use is stripped from the anthropic request`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("世卫是什么"))),
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(
                        MessagePart.Text("世卫 +"),
                        MessagePart.ToolCall("toolu_1", "web_search", """{"query":"世卫"}"""),
                    ),
                ),
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("继续"))),
            ),
        )

        val messages = bodyJson(buildAnthropicBody(request))["messages"]!!.jsonArray

        // The unanswered tool_use block is removed, the visible text stays.
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        val blocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("世卫 +", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(
            messages.flatMap { it.jsonObject["content"]!!.jsonArray }.none {
                it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            },
        )
    }

    @Test
    fun `tool-call-only assistant turn is dropped from the anthropic request`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("search"))),
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.ToolCall("toolu_1", "web_search", """{"query":"x"}""")),
                ),
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("continue"))),
            ),
        )

        val messages = bodyJson(buildAnthropicBody(request))["messages"]!!.jsonArray

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(2, messages[0].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `empty assistant turn is dropped from the anthropic request`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("tell me"))),
                PromptMessage(MessageRole.ASSISTANT, emptyList()),
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("continue"))),
            ),
        )

        val messages = bodyJson(buildAnthropicBody(request))["messages"]!!.jsonArray

        // Anthropic would turn the empty turn into a `text:""` block; it is not
        // something the model can validate as a turn, so it is omitted too.
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(2, messages[0].jsonObject["content"]!!.jsonArray.size)
    }
}
