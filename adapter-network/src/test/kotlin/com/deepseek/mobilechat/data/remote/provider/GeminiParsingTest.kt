package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ToolSpec
import com.deepseek.mobilechat.data.remote.testRequest
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.FinishReason
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

class GeminiParsingTest {

    private val streamFixture = """
        data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}]}

        data: {"candidates":[{"content":{"parts":[{"text":"considering","thought":true}]}}]}

        data: {"candidates":[{"content":{"parts":[{"text":" world"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":9,"thoughtsTokenCount":3,"cachedContentTokenCount":2}}

    """.trimIndent()

    private fun bodyJson(text: String): JsonObject = parseJsonObject(text)!!

    @Test
    fun `parses text thought usage and finish reason`() {
        val events = parseGeminiStream(streamFixture)

        assertEquals(
            listOf(
                GenerationEvent.TextDelta("Hello"),
                GenerationEvent.ReasoningDelta("considering"),
                GenerationEvent.TextDelta(" world"),
                GenerationEvent.UsageReport(
                    Usage(
                        promptTokens = 5,
                        completionTokens = 9,
                        reasoningTokens = 3,
                        // Cached prompt tokens are a discounted slice of the prompt.
                        cachedPromptTokens = 2,
                    ),
                ),
                GenerationEvent.Finished(FinishReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `finish reason mapping covers gemini values`() {
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("STOP"))
        assertEquals(FinishReason.LENGTH, mapGeminiFinishReason("MAX_TOKENS"))
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("SAFETY"))
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("RECITATION"))
        assertEquals(FinishReason.ERROR, mapGeminiFinishReason("MALFORMED_FUNCTION_CALL"))
    }

    @Test
    fun `error object becomes a protocol failure`() {
        val events = parseGeminiStream(
            "data: {\"error\":{\"code\":429,\"message\":\"quota\"}}\n\n",
        )

        val failure = events.single() as GenerationEvent.Failed
        assertEquals("quota", failure.error.message)
    }

    @Test
    fun `blocked prompt becomes a content filter failure`() {
        val events = parseGeminiStream(
            "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n\n",
        )

        assertEquals(2, events.size)
        assertTrue(events[0] is GenerationEvent.Failed)
        assertEquals(GenerationEvent.Finished(FinishReason.CONTENT_FILTER), events[1])
    }

    @Test
    fun `stream without finishReason still emits usage and terminus`() {
        val events = parseGeminiStream(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":2}}\n\n",
        )

        assertEquals(
            listOf(
                GenerationEvent.TextDelta("partial"),
                GenerationEvent.UsageReport(Usage(promptTokens = 1, completionTokens = 2)),
                GenerationEvent.Finished(FinishReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `stream url carries model, alt sse and key`() {
        val url = geminiStreamUrl("https://generativelanguage.example/v1beta", "gemini-2.5-flash", "abc123")

        assertEquals(
            "https://generativelanguage.example/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=abc123",
            url,
        )
    }

    @Test
    fun `stream url keeps a plain base url untouched`() {
        val url = geminiStreamUrl("https://generativelanguage.example/", "gemini-pro", "k")

        assertEquals(
            "https://generativelanguage.example/v1beta/models/gemini-pro:streamGenerateContent?alt=sse&key=k",
            url,
        )
    }

    @Test
    fun `stream url encodes the key and strips a models prefix`() {
        val url = geminiStreamUrl("https://example.test/", "models/gemini-pro", "a b")

        assertTrue(url.endsWith("/v1beta/models/gemini-pro:streamGenerateContent?alt=sse&key=a%20b"))
    }

    // -----------------------------------------------------------------------
    // Request building
    // -----------------------------------------------------------------------

    @Test
    fun `request body maps params, roles and thinking budget`() {
        val request = testRequest(
            systemPrompt = "be nice",
            params = SamplingParams(temperature = 0.4, topP = 0.6, maxTokens = 512, stop = listOf("STOP!")),
            thinkingLevel = ThinkingLevel.MAX,
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("question"))),
                PromptMessage(MessageRole.ASSISTANT, listOf(MessagePart.Text("answer"))),
                PromptMessage(MessageRole.USER, listOf(MessagePart.Text("follow up"))),
            ),
        )

        val body = bodyJson(buildGeminiBody(request))
        val contents = body["contents"]!!.jsonArray

        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", contents[2].jsonObject["role"]!!.jsonPrimitive.content)

        val instruction = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals("be nice", instruction["text"]!!.jsonPrimitive.content)

        val config = body["generationConfig"]!!.jsonObject
        assertEquals(0.4, config["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.6, config["topP"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(512, config["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("STOP!", config["stopSequences"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(
            24576,
            config["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `thinking off omits thinkingConfig`() {
        val config = bodyJson(buildGeminiBody(testRequest()))["generationConfig"]!!.jsonObject

        assertNull(config["thinkingConfig"])
        assertNull(config["temperature"])
    }

    @Test
    fun `thinking budgets follow the documented table`() {
        assertEquals(1024, geminiThinkingBudget(ThinkingLevel.LOW))
        assertEquals(8192, geminiThinkingBudget(ThinkingLevel.HIGH))
        assertEquals(24576, geminiThinkingBudget(ThinkingLevel.MAX))
        assertNull(geminiThinkingBudget(ThinkingLevel.OFF))
    }

    @Test
    fun `image part becomes inline_data`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00)
        val request = testRequest(
            messages = listOf(
                PromptMessage(MessageRole.USER, listOf(MessagePart.Image(AttachmentId("x.png")))),
            ),
        )

        val part = bodyJson(buildGeminiBody(request, resolveAttachment = { png }))["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject

        val inline = part["inline_data"]!!.jsonObject
        assertEquals("image/png", inline["mime_type"]!!.jsonPrimitive.content)
        assertTrue(inline["data"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `tools become function declarations and history reasoning is not replayed`() {
        val request = testRequest(
            tools = listOf(ToolSpec("lookup", "look things up", """{"type":"object"}""")),
            messages = listOf(
                PromptMessage(
                    MessageRole.ASSISTANT,
                    listOf(MessagePart.Reasoning("hidden thought"), MessagePart.Text("visible")),
                ),
            ),
        )

        val body = bodyJson(buildGeminiBody(request))
        val declaration = body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!
            .jsonArray.single().jsonObject
        assertEquals("lookup", declaration["name"]!!.jsonPrimitive.content)

        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("visible", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }
}
