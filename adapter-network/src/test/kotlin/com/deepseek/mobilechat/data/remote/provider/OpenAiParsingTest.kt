package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ToolSpec
import com.deepseek.mobilechat.data.remote.testRequest
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiParsingTest {

    private val streamFixture = """
        data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

        data: {"choices":[{"delta":{"reasoning_content":"let me think"}}]}

        data: {"choices":[{"delta":{"content":"Hel"}}]}

        data: {"choices":[{"delta":{"content":"lo"}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}

        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]}}]}

        data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}

        data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"completion_tokens_details":{"reasoning_tokens":7}}}

        data: [DONE]

    """.trimIndent()

    /**
     * Recorded shape behind the crash report: the interrupted assistant turn
     * stored visible text plus a tool call, and — because this app never
     * executes tool calls — no `role:"tool"` answer followed it. Replaying that
     * history used to make DeepSeek answer
     * `HTTP 400: Invalid assistant message: content or tool_calls must be set`.
     */
    private val interruptedToolCallHistory = listOf(
        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("世卫是什么"))),
        PromptMessage(
            MessageRole.ASSISTANT,
            listOf(
                MessagePart.Text("世卫 +"),
                MessagePart.ToolCall("call_1", "web_search", """{"query":"世卫"}"""),
            ),
        ),
        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("继续"))),
    )

    private fun bodyJson(text: String): JsonObject = parseJsonObject(text)!!

    @Test
    fun `parses text reasoning tool calls finish and usage in order`() {
        val events = parseOpenAiStream(streamFixture)

        val expected = listOf(
            GenerationEvent.ReasoningDelta("let me think"),
            GenerationEvent.TextDelta("Hel"),
            GenerationEvent.TextDelta("lo"),
            GenerationEvent.ToolCallDelta("call_1", "get_weather", ""),
            GenerationEvent.ToolCallDelta("call_1", null, "{\"city\":"),
            GenerationEvent.ToolCallDelta("call_1", null, "\"Paris\"}"),
            GenerationEvent.Finished(FinishReason.TOOL_CALLS),
            GenerationEvent.UsageReport(Usage(promptTokens = 10, completionTokens = 20, reasoningTokens = 7)),
        )
        assertEquals(expected, events)
    }

    @Test
    fun `finish reason mapping covers the documented values`() {
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("stop"))
        assertEquals(FinishReason.LENGTH, mapOpenAiFinishReason("length"))
        assertEquals(FinishReason.TOOL_CALLS, mapOpenAiFinishReason("tool_calls"))
        assertEquals(FinishReason.CONTENT_FILTER, mapOpenAiFinishReason("content_filter"))
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("something_new"))
    }

    @Test
    fun `malformed chunk becomes a protocol failure instead of an exception`() {
        val events = parseOpenAiStream("data: {not json\n\n")

        assertEquals(1, events.size)
        assertTrue(events.single() is GenerationEvent.Failed)
    }

    @Test
    fun `provider error object becomes a protocol failure`() {
        val events = parseOpenAiStream("""data: {"error":{"message":"quota exhausted"}}""" + "\n\n")

        val failure = events.single() as GenerationEvent.Failed
        assertEquals("quota exhausted", failure.error.message)
    }

    @Test
    fun `stream without finish_reason still terminates`() {
        val events = parseOpenAiStream("""data: {"choices":[{"delta":{"content":"only text"}}]}""" + "\n\n")

        assertEquals(2, events.size)
        assertEquals(GenerationEvent.TextDelta("only text"), events[0])
        assertEquals(GenerationEvent.Finished(FinishReason.STOP), events[1])
    }

    // -----------------------------------------------------------------------
    // Request building
    // -----------------------------------------------------------------------

    @Test
    fun `request body carries params, reasoning effort, system prompt and tools`() {
        val request = testRequest(
            systemPrompt = "be brief",
            params = SamplingParams(temperature = 0.3, topP = 0.9, maxTokens = 256, stop = listOf("END")),
            thinkingLevel = ThinkingLevel.MAX,
            tools = listOf(ToolSpec("get_weather", "weather", """{"type":"object"}""")),
        )

        val body = bodyJson(buildOpenAiBody(request))

        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("max", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(0.3, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.9, body["top_p"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("END", body["stop"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            "true",
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content,
        )

        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("be brief", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `thinking level off omits reasoning_effort and unset params stay absent`() {
        val body = bodyJson(buildOpenAiBody(testRequest()))

        assertNull(body["reasoning_effort"])
        assertNull(body["temperature"])
        assertNull(body["top_p"])
        assertNull(body["max_tokens"])
        assertNull(body["stop"])
    }

    @Test
    fun `image part becomes a base64 data url`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02,
        )
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.USER,
                    listOf(
                        MessagePart.Text("what is this?"),
                        MessagePart.Image(AttachmentId("photo.png")),
                    ),
                ),
            ),
        )

        val body = bodyJson(buildOpenAiBody(request, resolveAttachment = { png }))
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val imageUrl = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(imageUrl.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `unavailable image degrades to a text marker`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Image(AttachmentId("gone.png")))),
            ),
        )

        val body = bodyJson(buildOpenAiBody(request))
        assertEquals(
            "[image attachment unavailable]",
            body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `assistant reasoning is not replayed by default`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.Reasoning("inner thought", "sig"), MessagePart.Text("answer")),
                ),
            ),
        )

        val message = bodyJson(buildOpenAiBody(request))["messages"]!!.jsonArray[0].jsonObject

        assertEquals("answer", message["content"]!!.jsonPrimitive.content)
        assertNull(message["reasoning_content"])
    }

    @Test
    fun `assistant reasoning can be replayed as reasoning_content field`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.Reasoning("inner thought"), MessagePart.Text("answer")),
                ),
            ),
        )

        val message = bodyJson(
            buildOpenAiBody(request, reasoningEcho = ReasoningEcho.REASONING_CONTENT_FIELD),
        )["messages"]!!.jsonArray[0].jsonObject

        assertEquals("answer", message["content"]!!.jsonPrimitive.content)
        assertEquals("inner thought", message["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant reasoning can be prefixed into content`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.Reasoning("thoughts"), MessagePart.Text("answer")),
                ),
            ),
        )

        val message = bodyJson(
            buildOpenAiBody(request, reasoningEcho = ReasoningEcho.CONTENT_PREFIX),
        )["messages"]!!.jsonArray[0].jsonObject

        assertEquals("thoughts\nanswer", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool results become one tool message per call`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.TOOL,
                    listOf(
                        MessagePart.ToolResult("call_1", true, "sunny"),
                        MessagePart.ToolResult("call_2", false, "timeout"),
                    ),
                ),
            ),
        )

        val messages = bodyJson(buildOpenAiBody(request))["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("tool", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[0].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("timeout", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant tool calls are replayed with their arguments`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(
                        MessagePart.Text("checking"),
                        MessagePart.ToolCall("call_1", "get_weather", """{"city":"Paris"}"""),
                    ),
                ),
                PromptMessage(
                    MessageRole.TOOL,
                    listOf(MessagePart.ToolResult("call_1", true, "sunny")),
                ),
            ),
        )

        val message = bodyJson(buildOpenAiBody(request))["messages"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", message["role"]!!.jsonPrimitive.content)
        assertEquals("checking", message["content"]!!.jsonPrimitive.content)
        val call = message["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals(
            "{\"city\":\"Paris\"}",
            call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `answered tool call without text uses an empty string instead of null content`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.ToolCall("call_1", "get_weather", """{"city":"Paris"}""")),
                ),
                PromptMessage(
                    MessageRole.TOOL,
                    listOf(MessagePart.ToolResult("call_1", true, "sunny")),
                ),
            ),
        )

        val message = bodyJson(buildOpenAiBody(request))["messages"]!!.jsonArray[0].jsonObject
        assertEquals("", message["content"]!!.jsonPrimitive.content)
        assertEquals("call_1", message["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `orphan assistant tool calls are stripped from the request body`() {
        val messages = bodyJson(
            buildOpenAiBody(testRequest(messages = interruptedToolCallHistory)),
        )["messages"]!!.jsonArray

        // The tool call is not sent (no tool result answers it), but the
        // assistant's visible text is preserved as a legal plain message.
        assertEquals(
            listOf("user", "assistant", "user"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        val assistant = messages[1].jsonObject
        assertEquals("世卫 +", assistant["content"]!!.jsonPrimitive.content)
        assertTrue(messages.none { it.jsonObject.containsKey("tool_calls") })
        assertTrue(messages.none { it.jsonObject["content"] == JsonNull })
    }

    @Test
    fun `tool-call-only assistant turn is dropped from the request body`() {
        val messages = bodyJson(
            buildOpenAiBody(
                testRequest(
                    messages = listOf(
                        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("search"))),
                        PromptMessage(
                            MessageRole.ASSISTANT,
                            listOf(
                                MessagePart.ToolCall("call_1", "web_search", """{"query":"x"}"""),
                            ),
                        ),
                        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("continue"))),
                    ),
                ),
            ),
        )["messages"]!!.jsonArray

        // After stripping the unanswered call nothing is left, so the turn is
        // omitted instead of becoming `content:null` with tool_calls.
        assertEquals(
            listOf("user", "user"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertTrue(messages.none { it.jsonObject["content"] == JsonNull })
    }

    @Test
    fun `empty assistant turn is dropped from the request body`() {
        val messages = bodyJson(
            buildOpenAiBody(
                testRequest(
                    messages = listOf(
                        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("tell me"))),
                        PromptMessage(MessageRole.ASSISTANT, emptyList()),
                        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("continue"))),
                    ),
                ),
            ),
        )["messages"]!!.jsonArray

        // An interrupted turn that never produced a delta has neither content
        // nor tool_calls; DeepSeek rejects that with the recorded 400 as well.
        assertEquals(
            listOf("user", "user"),
            messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertTrue(messages.none { it.jsonObject["content"] == JsonNull })
    }

    @Test
    fun `context and search parts are rendered as text rather than dropped`() {
        val request = testRequest(
            messages = listOf(
                PromptMessage(
                    MessageRole.USER,
                    listOf(
                        MessagePart.ContextBlock(
                            title = "Notes",
                            body = "remember this",
                            source = BlockSource.USER_INJECTED,
                        ),
                        MessagePart.SearchResults(
                            query = "kotlin",
                            hits = listOf(SearchHit("Kotlin", "https://kotlinlang.org", "language")),
                        ),
                    ),
                ),
            ),
        )

        val content = bodyJson(buildOpenAiBody(request))["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonPrimitive.content

        assertTrue(content.contains("Notes"))
        assertTrue(content.contains("remember this"))
        assertTrue(content.contains("https://kotlinlang.org"))
    }
}
