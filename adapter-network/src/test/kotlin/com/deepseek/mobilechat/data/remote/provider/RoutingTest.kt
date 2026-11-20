package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.data.remote.FakeProviderProfileStore
import com.deepseek.mobilechat.data.remote.FakeSecretStore
import com.deepseek.mobilechat.data.remote.RecordingHttpCaller
import com.deepseek.mobilechat.data.remote.sseResponse
import com.deepseek.mobilechat.data.remote.testProfile
import com.deepseek.mobilechat.data.remote.testRequest
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.ProviderProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    private val openAiSse = """
        data: {"choices":[{"delta":{"content":"Hi"}}]}

        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

        data: [DONE]

    """.trimIndent()

    private val anthropicSse = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":4}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Yo"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()

    private val geminiSse = """
        data: {"candidates":[{"content":{"parts":[{"text":"Hey"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1}}

    """.trimIndent()

    @Test
    fun `unknown profile is reported as a validation failure`() {
        val port = RoutingChatCompletionPort(
            profiles = FakeProviderProfileStore(),
            secrets = FakeSecretStore(),
            http = RecordingHttpCaller { error("must not be called") },
        )

        val events = runBlocking { port.stream(testRequest()).toList() }

        assertEquals(1, events.size)
        val failed = events.single() as GenerationEvent.Failed
        assertTrue(failed.error is AppError.Validation)
    }

    @Test
    fun `missing secret is reported before any request`() {
        val port = RoutingChatCompletionPort(
            profiles = FakeProviderProfileStore(testProfile(ProviderProtocol.OPENAI_COMPATIBLE)),
            secrets = FakeSecretStore(),
            http = RecordingHttpCaller { error("must not be called") },
        )

        val events = runBlocking { port.stream(testRequest()).toList() }

        val failed = events.single() as GenerationEvent.Failed
        assertTrue(failed.error is AppError.SecretMissing)
    }

    @Test
    fun `openai compatible profile routes to chat completions with bearer auth`() {
        val caller = RecordingHttpCaller { sseResponse(openAiSse) }
        val port = RoutingChatCompletionPort(
            profiles = FakeProviderProfileStore(
                testProfile(ProviderProtocol.OPENAI_COMPATIBLE, baseUrl = "https://api.example.test/v1"),
            ),
            secrets = FakeSecretStore(mapOf("provider.test" to "sk-test")),
            http = caller,
        )

        val events = runBlocking { port.stream(testRequest()).toList() }

        assertTrue(events[0] is GenerationEvent.Started)
        assertEquals(GenerationEvent.TextDelta("Hi"), events[1])
        assertEquals(GenerationEvent.Finished(FinishReason.STOP), events.last())

        val call = caller.calls.single()
        val body = call.body.orEmpty()
        assertEquals("POST", call.method)
        assertEquals("https://api.example.test/v1/chat/completions", call.url)
        assertEquals("Bearer sk-test", call.headers["Authorization"])
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun `anthropic profile routes to messages with x-api-key and version header`() {
        val caller = RecordingHttpCaller { sseResponse(anthropicSse) }
        val port = RoutingChatCompletionPort(
            profiles = FakeProviderProfileStore(
                testProfile(ProviderProtocol.ANTHROPIC, baseUrl = "https://api.anthropic.test"),
            ),
            secrets = FakeSecretStore(mapOf("provider.test" to "sk-ant")),
            http = caller,
        )

        val events = runBlocking { port.stream(testRequest()).toList() }

        assertTrue(events.any { it is GenerationEvent.TextDelta && it.text == "Yo" })
        assertEquals(GenerationEvent.Finished(FinishReason.STOP), events.last())

        val call = caller.calls.single()
        assertEquals("https://api.anthropic.test/v1/messages", call.url)
        assertEquals("sk-ant", call.headers["x-api-key"])
        assertEquals("2023-06-01", call.headers["anthropic-version"])
    }

    @Test
    fun `gemini profile routes to streamGenerateContent with the key in the query`() {
        val caller = RecordingHttpCaller { sseResponse(geminiSse) }
        val port = RoutingChatCompletionPort(
            profiles = FakeProviderProfileStore(
                testProfile(ProviderProtocol.GEMINI, baseUrl = "https://gemini.example"),
            ),
            secrets = FakeSecretStore(mapOf("provider.test" to "goog-key")),
            http = caller,
        )

        val events = runBlocking { port.stream(testRequest()).toList() }

        assertTrue(events.any { it is GenerationEvent.TextDelta && it.text == "Hey" })
        assertEquals(GenerationEvent.Finished(FinishReason.STOP), events.last())

        val call = caller.calls.single()
        assertTrue(call.url.startsWith("https://gemini.example/v1beta/models/test-model:streamGenerateContent?alt=sse"))
        assertTrue(call.url.endsWith("key=goog-key"))
    }

    @Test
    fun `attachment resolver reaches the adapter image path`() {
        val caller = RecordingHttpCaller { sseResponse(openAiSse) }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val port = RoutingChatCompletionPort(
            profiles = FakeProviderProfileStore(testProfile(ProviderProtocol.OPENAI_COMPATIBLE)),
            secrets = FakeSecretStore(mapOf("provider.test" to "sk-test")),
            resolveAttachment = { png },
            http = caller,
        )

        val request = testRequest(
            messages = listOf(
                com.deepseek.mobilechat.application.PromptMessage(
                    com.deepseek.mobilechat.domain.MessageRole.USER,
                    listOf(com.deepseek.mobilechat.domain.MessagePart.Image(com.deepseek.mobilechat.domain.AttachmentId("img.png"))),
                ),
            ),
        )

        runBlocking { port.stream(request).toList() }

        assertTrue(caller.calls.single().body!!.contains("data:image/png;base64,"))
    }
}
