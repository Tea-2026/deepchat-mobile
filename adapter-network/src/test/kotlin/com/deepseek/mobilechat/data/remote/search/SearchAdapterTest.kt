package com.deepseek.mobilechat.data.remote.search

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.data.remote.FakeSecretStore
import com.deepseek.mobilechat.data.remote.RecordingHttpCaller
import com.deepseek.mobilechat.data.remote.http.HttpResponse
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAdapterTest {

    // -----------------------------------------------------------------------
    // Payload parsing
    // -----------------------------------------------------------------------

    @Test
    fun `tavily results are mapped to title url snippet`() {
        val hits = parseTavilyResults(
            """
            {"query":"kotlin","results":[
              {"title":"Kotlin","url":"https://kotlinlang.org","content":"A modern language","score":0.9},
              {"title":"No url","content":"dropped"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, hits.size)
        assertEquals(SearchHit("Kotlin", "https://kotlinlang.org", "A modern language"), hits.single())
    }

    @Test
    fun `brave results strip html highlighting and unescape entities`() {
        val hits = parseBraveResults(
            """
            {"web":{"results":[
              {"title":"Kotlin <strong>coroutines</strong>","url":"https://example.test/a","description":"use &quot;suspend&quot; &amp; enjoy"}
            ]}}
            """.trimIndent(),
        )

        assertEquals("Kotlin coroutines", hits.single().title)
        assertEquals("use \"suspend\" & enjoy", hits.single().snippet)
    }

    @Test
    fun `serper organic results are mapped and missing blocks yield an empty list`() {
        val hits = parseSerperResults(
            """
            {"organic":[{"title":"Docs","link":"https://example.test/docs","snippet":"reference"}]}
            """.trimIndent(),
        )

        assertEquals(SearchHit("Docs", "https://example.test/docs", "reference"), hits.single())
        assertTrue(parseSerperResults("""{"organic":[]}""").isEmpty())
        assertTrue(parseTavilyResults("not json").isEmpty())
    }

    // -----------------------------------------------------------------------
    // Adapters over a fake transport
    // -----------------------------------------------------------------------

    @Test
    fun `tavily adapter posts the key and query and maps hits`() {
        val caller = RecordingHttpCaller {
            HttpResponse.json("""{"results":[{"title":"T","url":"https://t.test","content":"S"}]}""")
        }
        val adapter = TavilySearchAdapter(FakeSecretStore(mapOf(TAVILY_SECRET_REF to "tv-key")), caller)

        val outcome = runBlocking { adapter.search("news", SearchOptions(maxResults = 3)) }

        val response = (outcome as Outcome.Ok).value
        assertEquals("news", response.query)
        assertEquals(SearchHit("T", "https://t.test", "S"), response.hits.single())

        val call = caller.calls.single()
        val body = call.body.orEmpty()
        assertEquals("POST", call.method)
        assertEquals("https://api.tavily.com/search", call.url)
        assertTrue(body.contains("\"api_key\":\"tv-key\""))
        assertTrue(body.contains("\"query\":\"news\""))
        assertTrue(body.contains("\"max_results\":3"))
        assertEquals("Bearer tv-key", call.headers["Authorization"])
    }

    @Test
    fun `tavily adapter reports a missing key instead of searching`() {
        val adapter = TavilySearchAdapter(FakeSecretStore(), RecordingHttpCaller { error("must not be called") })

        val outcome = runBlocking { adapter.search("q") }

        assertTrue((outcome as Outcome.Err).error is AppError.SecretMissing)
    }

    @Test
    fun `brave adapter sends the subscription token and encodes the query`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"web":{"results":[]}}""") }
        val adapter = BraveSearchAdapter(FakeSecretStore(mapOf(BRAVE_SECRET_REF to "br-key")), caller)

        val outcome = runBlocking { adapter.search("a b", SearchOptions(maxResults = 4)) }

        assertTrue(outcome is Outcome.Ok)
        val call = caller.calls.single()
        assertEquals("GET", call.method)
        assertTrue(call.url.startsWith("https://api.search.brave.com/res/v1/web/search?q=a%20b&count=4"))
        assertEquals("br-key", call.headers["X-Subscription-Token"])
    }

    @Test
    fun `brave adapter reports a missing key`() {
        val adapter = BraveSearchAdapter(FakeSecretStore(), RecordingHttpCaller { error("must not be called") })

        assertTrue((runBlocking { adapter.search("q") } as Outcome.Err).error is AppError.SecretMissing)
    }

    @Test
    fun `serper adapter sends the api key header`() {
        val caller = RecordingHttpCaller {
            HttpResponse.json("""{"organic":[{"title":"S","link":"https://s.test","snippet":"snip"}]}""")
        }
        val adapter = SerperSearchAdapter(FakeSecretStore(mapOf(SERPER_SECRET_REF to "sp-key")), caller)

        val outcome = runBlocking { adapter.search("q", SearchOptions(maxResults = 2)) }

        assertEquals(SearchHit("S", "https://s.test", "snip"), (outcome as Outcome.Ok).value.hits.single())
        val call = caller.calls.single()
        assertEquals("sp-key", call.headers["X-API-KEY"])
        assertTrue(call.body!!.contains("\"num\":2"))
    }

    @Test
    fun `serper adapter reports a missing key`() {
        val adapter = SerperSearchAdapter(FakeSecretStore(), RecordingHttpCaller { error("must not be called") })

        assertTrue((runBlocking { adapter.search("q") } as Outcome.Err).error is AppError.SecretMissing)
    }

    @Test
    fun `http failures are normalized into Outcome errors`() {
        val caller = RecordingHttpCaller {
            throw com.deepseek.mobilechat.data.remote.http.ProviderHttpException(AppError.ProviderRateLimit(9L))
        }
        val adapter = TavilySearchAdapter(FakeSecretStore(mapOf(TAVILY_SECRET_REF to "tv-key")), caller)

        val error = (runBlocking { adapter.search("q") } as Outcome.Err).error as AppError.ProviderRateLimit
        assertEquals(9L, error.retryAfterSeconds)
    }

    @Test
    fun `http status errors from a no-throw transport are normalized too`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"error":"revoked"}""", status = 401) }
        val adapter = SerperSearchAdapter(FakeSecretStore(mapOf(SERPER_SECRET_REF to "k")), caller)

        assertTrue((runBlocking { adapter.search("q") } as Outcome.Err).error is AppError.ProviderAuth)
    }
}
