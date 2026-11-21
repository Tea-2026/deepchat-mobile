package com.deepseek.mobilechat.data.remote.search

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.data.remote.FakeProviderProfileStore
import com.deepseek.mobilechat.data.remote.FakeSecretStore
import com.deepseek.mobilechat.data.remote.RecordingHttpCaller
import com.deepseek.mobilechat.data.remote.http.HttpResponse
import com.deepseek.mobilechat.data.remote.provider.arr
import com.deepseek.mobilechat.data.remote.provider.obj
import com.deepseek.mobilechat.data.remote.provider.parseJsonObject
import com.deepseek.mobilechat.data.remote.provider.str
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek server-side search over the Anthropic-format endpoint.
 *
 * Fixtures are literal recorded payloads; no test touches the network.
 */
class DeepSeekWebSearchTest {

    // -----------------------------------------------------------------------
    // Response parsing
    // -----------------------------------------------------------------------

    /** One snippet hit, one citation-fallback hit, one duplicate, one url-less item. */
    private val recordedResponse = """
        {
          "id": "msg_01",
          "type": "message",
          "role": "assistant",
          "content": [
            {"type": "server_tool_use", "id": "srvtoolu_01", "name": "web_search",
             "input": {"query": "kotlin coroutines"}},
            {"type": "web_search_tool_result", "tool_use_id": "srvtoolu_01", "content": [
              {"type": "web_search_result",
               "url": "https://kotlinlang.org/docs/coroutines-overview.html",
               "title": "Coroutines overview",
               "snippet": "Lightweight threads for Kotlin.",
               "page_age": "2025-06-11"},
              {"type": "web_search_result",
               "url": "https://example.test/guide",
               "title": "Kotlin guide"},
              {"type": "web_search_result",
               "url": "https://kotlinlang.org/docs/coroutines-overview.html",
               "title": "Duplicate entry",
               "snippet": "A duplicate must not replace the first hit."},
              {"type": "web_search_result",
               "title": "No url",
               "snippet": "An item without a url is skipped."}
            ]},
            {"type": "text", "text": "Kotlin coroutines are lightweight.",
             "citations": [
               {"url": "https://example.test/guide",
                "cited_text": "A practical guide to coroutines."},
               {"url": "https://kotlinlang.org/docs/coroutines-overview.html",
                "cited_text": "A citation must never override an existing snippet."}
             ]}
          ],
          "stop_reason": "end_turn"
        }
    """.trimIndent()

    @Test
    fun `maps snippets, falls back to citations, dedupes and skips url-less items`() {
        val hits = parseDeepSeekSearchResults(recordedResponse)

        assertEquals(
            listOf(
                SearchHit(
                    "Coroutines overview",
                    "https://kotlinlang.org/docs/coroutines-overview.html",
                    "Lightweight threads for Kotlin.",
                ),
                SearchHit(
                    "Kotlin guide",
                    "https://example.test/guide",
                    "A practical guide to coroutines.",
                ),
            ),
            hits,
        )
    }

    @Test
    fun `a response without a result block is a failure, not an empty result`() {
        val body = """
            {"id":"msg_02","type":"message","role":"assistant",
             "content":[{"type":"text","text":"I could not search."}],
             "stop_reason":"end_turn"}
        """.trimIndent()

        assertNull(parseDeepSeekSearchResults(body))
    }

    @Test
    fun `a malformed payload is a failure`() {
        assertNull(parseDeepSeekSearchResults("not json"))
        assertNull(parseDeepSeekSearchResults("""{"content":null}"""))
    }

    // -----------------------------------------------------------------------
    // Endpoint derivation
    // -----------------------------------------------------------------------

    @Test
    fun `endpoint derivation normalizes every documented base url shape`() {
        val expected = "https://api.deepseek.com/anthropic/v1/messages"

        assertEquals(expected, deepSeekSearchEndpoint("https://api.deepseek.com"))
        assertEquals(expected, deepSeekSearchEndpoint("https://api.deepseek.com/"))
        assertEquals(expected, deepSeekSearchEndpoint("https://api.deepseek.com/v1"))
        assertEquals(expected, deepSeekSearchEndpoint("https://api.deepseek.com/v1/"))
        assertEquals(expected, deepSeekSearchEndpoint("https://api.deepseek.com/anthropic"))
        assertEquals(expected, deepSeekSearchEndpoint("https://api.deepseek.com/anthropic/"))
    }

    @Test
    fun `endpoint derivation keeps a self-hosted deepseek path prefix`() {
        assertEquals(
            "https://gateway.test/deepseek/anthropic/v1/messages",
            deepSeekSearchEndpoint("https://gateway.test/deepseek/v1"),
        )
    }

    // -----------------------------------------------------------------------
    // Adapter over a fake transport
    // -----------------------------------------------------------------------

    @Test
    fun `adapter posts the documented body with both key headers`() {
        val caller = RecordingHttpCaller { HttpResponse.json(recordedResponse) }
        val profile = deepSeekProfile(baseUrl = "https://api.deepseek.com/v1")
        val adapter = DeepSeekWebSearchAdapter(
            providers = FakeProviderProfileStore(profile),
            secrets = FakeSecretStore(mapOf(profile.secretRef to "sk-deepseek")),
            http = caller,
        )

        val outcome = runBlocking { adapter.search("kotlin coroutines", SearchOptions(maxResults = 5)) }

        val response = (outcome as Outcome.Ok).value
        assertEquals("kotlin coroutines", response.query)
        assertEquals(2, response.hits.size)

        val call = caller.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", call.url)
        assertEquals("sk-deepseek", call.headers["x-api-key"])
        assertEquals("Bearer sk-deepseek", call.headers["Authorization"])
        assertEquals("application/json", call.headers["Content-Type"])
        assertEquals("2023-06-01", call.headers["anthropic-version"])

        val body = parseJsonObject(call.body!!)!!
        assertEquals("deepseek-flash", body.str("model"))
        assertEquals("512", body.str("max_tokens"))
        assertEquals("disabled", body.obj("thinking")?.str("type"))

        val tool = body.arr("tools")!!.single().let { parseJsonObject(it.toString())!! }
        assertEquals("web_search_20250305", tool.str("type"))
        assertEquals("web_search", tool.str("name"))
        assertEquals("3", tool.str("max_uses"))

        val message = body.arr("messages")!!.single().let { parseJsonObject(it.toString())!! }
        assertEquals("user", message.str("role"))
        val textBlock = message.arr("content")!!.single().let { parseJsonObject(it.toString())!! }
        assertEquals("text", textBlock.str("type"))
        assertEquals(
            "Perform a web search for the query: kotlin coroutines",
            textBlock.str("text"),
        )
    }

    @Test
    fun `adapter honours the provider default model when set`() {
        val caller = RecordingHttpCaller { HttpResponse.json(recordedResponse) }
        val profile = deepSeekProfile(baseUrl = "https://api.deepseek.com", modelId = ModelId("deepseek-chat"))
        val adapter = DeepSeekWebSearchAdapter(
            FakeProviderProfileStore(profile),
            FakeSecretStore(mapOf(profile.secretRef to "k")),
            caller,
        )

        runBlocking { adapter.search("q") }

        assertEquals("deepseek-chat", parseJsonObject(caller.calls.single().body!!)!!.str("model"))
    }

    @Test
    fun `missing result block surfaces the search-did-not-fire protocol error`() {
        val caller = RecordingHttpCaller {
            HttpResponse.json("""{"content":[{"type":"text","text":"no search"}]}""")
        }
        val profile = deepSeekProfile()
        val adapter = DeepSeekWebSearchAdapter(
            FakeProviderProfileStore(profile),
            FakeSecretStore(mapOf(profile.secretRef to "k")),
            caller,
        )

        val error = (runBlocking { adapter.search("q") } as Outcome.Err).error

        assertTrue(error is AppError.ProviderProtocol)
        assertTrue(error.message.contains("did not fire"))
        assertTrue(error.message.contains("web_search_tool_result"))
    }

    @Test
    fun `http errors are normalized by requireSuccess`() {
        val caller = RecordingHttpCaller {
            HttpResponse.json("""{"error":{"message":"revoked"}}""", status = 401)
        }
        val profile = deepSeekProfile()
        val adapter = DeepSeekWebSearchAdapter(
            FakeProviderProfileStore(profile),
            FakeSecretStore(mapOf(profile.secretRef to "k")),
            caller,
        )

        val error = (runBlocking { adapter.search("q") } as Outcome.Err).error

        assertTrue(error is AppError.ProviderAuth)
    }

    @Test
    fun `no deepseek provider is SecretMissing and never calls the transport`() {
        val caller = RecordingHttpCaller { error("must not be called") }
        val adapter = DeepSeekWebSearchAdapter(
            providers = FakeProviderProfileStore(),
            secrets = FakeSecretStore(),
            http = caller,
        )

        val error = (runBlocking { adapter.search("q") } as Outcome.Err).error

        assertTrue(error is AppError.SecretMissing)
        assertTrue(caller.calls.isEmpty())
    }

    @Test
    fun `a deepseek provider without a key is SecretMissing`() {
        val caller = RecordingHttpCaller { error("must not be called") }
        val profile = deepSeekProfile()
        val adapter = DeepSeekWebSearchAdapter(
            FakeProviderProfileStore(profile),
            FakeSecretStore(),
            caller,
        )

        assertFalse(runBlocking { adapter.isConfigured() })
        assertTrue((runBlocking { adapter.search("q") } as Outcome.Err).error is AppError.SecretMissing)
        assertTrue(caller.calls.isEmpty())
    }

    @Test
    fun `the endpoint override replaces derivation`() {
        val caller = RecordingHttpCaller { HttpResponse.json(recordedResponse) }
        val profile = deepSeekProfile()
        val adapter = DeepSeekWebSearchAdapter(
            FakeProviderProfileStore(profile),
            FakeSecretStore(mapOf(profile.secretRef to "k")),
            caller,
            endpointOverride = "https://mock.test/anthropic/v1/messages",
        )

        runBlocking { adapter.search("q") }

        assertEquals("https://mock.test/anthropic/v1/messages", caller.calls.single().url)
    }

    // -----------------------------------------------------------------------
    // isConfigured
    // -----------------------------------------------------------------------

    @Test
    fun `isConfigured requires a deepseek host and a key`() {
        val deepSeek = deepSeekProfile(secretRef = "provider.deepseek")
        val other = deepSeekProfile(secretRef = "provider.other", baseUrl = "https://api.openai.com")
        val secrets = FakeSecretStore(
            mapOf("provider.deepseek" to "k", "provider.other" to "sk-other"),
        )

        assertTrue(
            runBlocking {
                DeepSeekWebSearchAdapter(FakeProviderProfileStore(deepSeek), secrets, RecordingHttpCaller { error("no") })
                    .isConfigured()
            },
        )
        assertFalse(
            runBlocking {
                DeepSeekWebSearchAdapter(FakeProviderProfileStore(other), secrets, RecordingHttpCaller { error("no") })
                    .isConfigured()
            },
        )
        assertFalse(
            runBlocking {
                DeepSeekWebSearchAdapter(FakeProviderProfileStore(deepSeek), FakeSecretStore(), RecordingHttpCaller { error("no") })
                    .isConfigured()
            },
        )
    }

    @Test
    fun `a non-deepseek provider is not used even when it has a key`() {
        val caller = RecordingHttpCaller { error("must not be called") }
        val other = deepSeekProfile(baseUrl = "https://api.example.test", secretRef = "p.other")
        val adapter = DeepSeekWebSearchAdapter(
            FakeProviderProfileStore(other),
            FakeSecretStore(mapOf("p.other" to "sk")),
            caller,
        )

        assertFalse(runBlocking { adapter.isConfigured() })
        assertTrue((runBlocking { adapter.search("q") } as Outcome.Err).error is AppError.SecretMissing)
        assertTrue(caller.calls.isEmpty())
    }

    // -----------------------------------------------------------------------

    private fun deepSeekProfile(
        baseUrl: String = "https://api.deepseek.com",
        secretRef: String = "provider.deepseek",
        modelId: ModelId? = null,
    ): ProviderProfile = ProviderProfile(
        id = ProviderId("deepseek"),
        name = "DeepSeek",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        secretRef = secretRef,
        defaultModelId = modelId,
        createdAt = 0L,
    )
}
