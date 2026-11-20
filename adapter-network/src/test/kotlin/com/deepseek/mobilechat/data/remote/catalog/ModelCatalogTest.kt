package com.deepseek.mobilechat.data.remote.catalog

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BalanceResult
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.data.remote.FakeSecretStore
import com.deepseek.mobilechat.data.remote.RecordingHttpCaller
import com.deepseek.mobilechat.data.remote.http.HttpResponse
import com.deepseek.mobilechat.data.remote.testProfile
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private val catalogWith = { caller: RecordingHttpCaller, secrets: FakeSecretStore ->
        ModelCatalog(secrets, caller)
    }

    // -----------------------------------------------------------------------
    // Payload parsing
    // -----------------------------------------------------------------------

    @Test
    fun `openai model list uses data ids`() {
        val models = parseOpenAiModels("""{"object":"list","data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"},{"id":"gpt-4o"}]}""")

        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models.map { it.id.raw })
        assertEquals("gpt-4o", models.first().displayName)
        assertNull(models.first().pricing)
        assertTrue(models.first().capabilities.streaming)
    }

    @Test
    fun `anthropic model list uses data ids`() {
        val models = parseAnthropicModels(
            """{"data":[{"type":"model","id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"}]}""",
        )

        assertEquals(ModelId("claude-sonnet-4-5"), models.single().id)
        assertEquals("claude-sonnet-4-5", models.single().displayName)
    }

    @Test
    fun `gemini model list strips the models prefix`() {
        val models = parseGeminiModels(
            """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash"}]}""",
        )

        assertEquals(ModelId("gemini-2.5-flash"), models.single().id)
        assertEquals("gemini-2.5-flash", models.single().displayName)
    }

    @Test
    fun `malformed model payloads yield an empty list rather than an exception`() {
        assertTrue(parseOpenAiModels("nope").isEmpty())
        assertTrue(parseAnthropicModels("""{"data":null}""").isEmpty())
        assertTrue(parseGeminiModels("""{}""").isEmpty())
    }

    // -----------------------------------------------------------------------
    // listModels / testConnection over a fake transport
    // -----------------------------------------------------------------------

    @Test
    fun `openai listModels calls the models endpoint with bearer auth`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"data":[{"id":"gpt-4o"}]}""") }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "sk")))

        val outcome = runBlocking {
            catalog.listModels(testProfile(ProviderProtocol.OPENAI_COMPATIBLE, baseUrl = "https://api.example.test/v1"))
        }

        assertEquals(ModelId("gpt-4o"), (outcome as Outcome.Ok).value.single().id)
        val call = caller.calls.single()
        assertEquals("GET", call.method)
        assertEquals("https://api.example.test/v1/models", call.url)
        assertEquals("Bearer sk", call.headers["Authorization"])
    }

    @Test
    fun `anthropic listModels sends the version header`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"data":[{"id":"claude-x"}]}""") }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "sk-ant")))

        val outcome = runBlocking {
            catalog.listModels(testProfile(ProviderProtocol.ANTHROPIC, baseUrl = "https://anthropic.test"))
        }

        assertEquals("claude-x", (outcome as Outcome.Ok).value.single().id.raw)
        val call = caller.calls.single()
        assertEquals("https://anthropic.test/v1/models", call.url)
        assertEquals("sk-ant", call.headers["x-api-key"])
        assertEquals("2023-06-01", call.headers["anthropic-version"])
    }

    @Test
    fun `gemini listModels puts the key in the query`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"models":[{"name":"models/gemini-pro"}]}""") }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "g-key")))

        val outcome = runBlocking {
            catalog.listModels(testProfile(ProviderProtocol.GEMINI, baseUrl = "https://gemini.test"))
        }

        assertEquals("gemini-pro", (outcome as Outcome.Ok).value.single().id.raw)
        assertEquals("https://gemini.test/v1beta/models?key=g-key", caller.calls.single().url)
    }

    @Test
    fun `missing secret is reported without a request`() {
        val caller = RecordingHttpCaller { error("must not be called") }
        val catalog = catalogWith(caller, FakeSecretStore())

        val outcome = runBlocking { catalog.listModels(testProfile(ProviderProtocol.OPENAI_COMPATIBLE)) }

        assertTrue((outcome as Outcome.Err).error is AppError.SecretMissing)
    }

    @Test
    fun `testConnection returns OK when the model list succeeds`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"data":[{"id":"m"}]}""") }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "sk")))

        val result = runBlocking { catalog.testConnection(testProfile(ProviderProtocol.OPENAI_COMPATIBLE)) }

        assertEquals("OK", (result as Outcome.Ok).value)
    }

    @Test
    fun `testConnection propagates provider errors`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"error":"bad"}""", status = 401) }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "sk")))

        val result = runBlocking { catalog.testConnection(testProfile(ProviderProtocol.OPENAI_COMPATIBLE)) }

        assertTrue((result as Outcome.Err).error is AppError.ProviderAuth)
    }

    // -----------------------------------------------------------------------
    // balance
    // -----------------------------------------------------------------------

    @Test
    fun `balance is unsupported for non openrouter profiles`() {
        val caller = RecordingHttpCaller { error("must not be called") }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "sk")))

        val result = runBlocking { catalog.balance(testProfile(ProviderProtocol.OPENAI_COMPATIBLE)) }

        assertEquals(BalanceResult.Unsupported, result)
        assertTrue(caller.calls.isEmpty())
    }

    @Test
    fun `balance is unsupported for anthropic and gemini`() {
        val caller = RecordingHttpCaller { error("must not be called") }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "sk")))

        assertEquals(
            BalanceResult.Unsupported,
            runBlocking { catalog.balance(testProfile(ProviderProtocol.ANTHROPIC)) },
        )
        assertEquals(
            BalanceResult.Unsupported,
            runBlocking { catalog.balance(testProfile(ProviderProtocol.GEMINI)) },
        )
    }

    @Test
    fun `openrouter balance subtracts used from total credits`() {
        val caller = RecordingHttpCaller {
            HttpResponse.json("""{"data":{"total_credits":10.0,"total_usage":1.5}}""")
        }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "or-key")))

        val result = runBlocking {
            catalog.balance(
                testProfile(
                    ProviderProtocol.OPENAI_COMPATIBLE,
                    baseUrl = "https://openrouter.ai/api/v1",
                ),
            )
        }

        val available = result as BalanceResult.Available
        assertEquals(8.5, available.amount, 0.0001)
        assertEquals("USD", available.currency)

        val call = caller.calls.single()
        assertEquals("https://openrouter.ai/api/v1/credits", call.url)
        assertEquals("Bearer or-key", call.headers["Authorization"])
    }

    @Test
    fun `openrouter credits parsing accepts the used field alias`() {
        val credits = parseOpenRouterCredits("""{"data":{"total_credits":5,"used":2}}""")!!

        assertEquals(5.0, credits.totalCredits, 0.0)
        assertEquals(2.0, credits.usedCredits, 0.0)
        assertEquals(3.0, credits.remaining, 0.0)
        assertNull(parseOpenRouterCredits("""{"data":{}}"""))
    }

    @Test
    fun `openrouter balance failure is reported as Failed`() {
        val caller = RecordingHttpCaller { HttpResponse.json("""{"error":"nope"}""", status = 401) }
        val catalog = catalogWith(caller, FakeSecretStore(mapOf("provider.test" to "or-key")))

        val result = runBlocking {
            catalog.balance(
                testProfile(ProviderProtocol.OPENAI_COMPATIBLE, baseUrl = "https://openrouter.ai/api/v1"),
            )
        }

        assertTrue((result as BalanceResult.Failed).error is AppError.ProviderAuth)
    }
}
