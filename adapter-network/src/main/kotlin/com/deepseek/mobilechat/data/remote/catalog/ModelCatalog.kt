package com.deepseek.mobilechat.data.remote.catalog

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BalanceResult
import com.deepseek.mobilechat.application.ModelCatalogPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.http.ProviderHttpException
import com.deepseek.mobilechat.data.remote.http.guardedHttp
import com.deepseek.mobilechat.data.remote.http.requireSuccess
import com.deepseek.mobilechat.data.remote.provider.arr
import com.deepseek.mobilechat.data.remote.provider.double
import com.deepseek.mobilechat.data.remote.provider.geminiApiBase
import com.deepseek.mobilechat.data.remote.provider.joinUrl
import com.deepseek.mobilechat.data.remote.provider.obj
import com.deepseek.mobilechat.data.remote.provider.objOrNull
import com.deepseek.mobilechat.data.remote.provider.parseJsonObject
import com.deepseek.mobilechat.data.remote.provider.readSecret
import com.deepseek.mobilechat.data.remote.provider.str
import com.deepseek.mobilechat.domain.ModelCapabilities
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModelCatalogPort] implementation for the three supported protocols.
 *
 * Listing models doubles as the connection test: it is the cheapest
 * authenticated call every provider exposes. Pricing is not exposed by any of
 * the three `/models` endpoints, so [ModelDescriptor.pricing] stays null rather
 * than being invented.
 */
class ModelCatalog(
    private val secrets: SecretStore,
    private val http: HttpCaller,
) : ModelCatalogPort {

    override suspend fun listModels(profile: ProviderProfile): Outcome<List<ModelDescriptor>> =
        withContext(Dispatchers.IO) {
            val apiKey = when (val secret = readSecret(secrets, profile.secretRef, "provider \"${profile.name}\" API key")) {
                is Outcome.Ok -> secret.value
                is Outcome.Err -> return@withContext Outcome.Err(secret.error)
            }

            guardedHttp {
                val call = modelListCall(profile, apiKey)
                val response = http.execute(call.method, call.url, call.headers)
                response.requireSuccess()
                val body = response.use { it.bodyLines.joinToString("\n") }
                when (profile.protocol) {
                    ProviderProtocol.OPENAI_COMPATIBLE -> parseOpenAiModels(body)
                    ProviderProtocol.ANTHROPIC -> parseAnthropicModels(body)
                    ProviderProtocol.GEMINI -> parseGeminiModels(body)
                }
            }
        }

    override suspend fun testConnection(profile: ProviderProfile): Outcome<String> =
        when (val listed = listModels(profile)) {
            is Outcome.Ok -> Outcome.Ok("OK")
            is Outcome.Err -> Outcome.Err(listed.error)
        }

    override suspend fun balance(profile: ProviderProfile): BalanceResult =
        withContext(Dispatchers.IO) {
            if (profile.protocol != ProviderProtocol.OPENAI_COMPATIBLE ||
                !profile.baseUrl.contains("openrouter", ignoreCase = true)
            ) {
                return@withContext BalanceResult.Unsupported
            }
            val apiKey = when (val secret = readSecret(secrets, profile.secretRef, "OpenRouter API key")) {
                is Outcome.Ok -> secret.value
                is Outcome.Err -> return@withContext BalanceResult.Failed(secret.error)
            }
            val result = guardedHttp {
                val response = http.execute(
                    method = "GET",
                    url = openRouterCreditsUrl(profile.baseUrl),
                    headers = mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Accept" to "application/json",
                    ),
                )
                response.requireSuccess()
                val body = response.use { it.bodyLines.joinToString("\n") }
                parseOpenRouterCredits(body)
                    ?: throw ProviderHttpException(
                        AppError.ProviderProtocol("unexpected OpenRouter /credits payload"),
                    )
            }
            when (result) {
                is Outcome.Ok -> BalanceResult.Available(
                    amount = result.value.remaining,
                    currency = "USD",
                    label = "OpenRouter credits",
                )
                is Outcome.Err -> BalanceResult.Failed(result.error)
            }
        }
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

internal data class ModelListCall(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
)

internal fun modelListCall(profile: ProviderProfile, apiKey: String): ModelListCall = when (profile.protocol) {
    ProviderProtocol.OPENAI_COMPATIBLE -> ModelListCall(
        method = "GET",
        url = joinUrl(profile.baseUrl, "models"),
        headers = profile.extraHeaders + mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
        ),
    )

    ProviderProtocol.ANTHROPIC -> ModelListCall(
        method = "GET",
        url = joinUrl(profile.baseUrl, "v1/models"),
        headers = profile.extraHeaders + mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
            "Accept" to "application/json",
        ),
    )

    ProviderProtocol.GEMINI -> {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8").replace("+", "%20")
        ModelListCall(
            method = "GET",
            url = joinUrl(geminiApiBase(profile.baseUrl), "v1beta/models?key=$key"),
            headers = profile.extraHeaders + mapOf("Accept" to "application/json"),
        )
    }
}

/** OpenRouter's credits endpoint sits next to `/chat/completions`, i.e. under `/api/v1`. */
internal fun openRouterCreditsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return if (trimmed.contains("/api/v1", ignoreCase = true)) "$trimmed/credits"
    else "$trimmed/api/v1/credits"
}

// ---------------------------------------------------------------------------
// Payload parsing (pure, fixture-testable)
// ---------------------------------------------------------------------------

/**
 * Defaults advertised for a listed model. The `/models` endpoints do not
 * describe capabilities, so these are conservative modern values; callers that
 * know better (presets, user overrides) win.
 */
internal fun defaultCapabilities(): ModelCapabilities = ModelCapabilities(
    vision = true,
    tools = true,
    streaming = true,
    thinkingLevels = setOf(
        ThinkingLevel.OFF,
        ThinkingLevel.LOW,
        ThinkingLevel.HIGH,
        ThinkingLevel.MAX,
    ),
    contextWindow = 128_000,
    maxOutputTokens = 8_192,
)

internal fun modelDescriptor(id: String): ModelDescriptor = ModelDescriptor(
    id = ModelId(id.removePrefix("models/")),
    displayName = id.removePrefix("models/"),
    capabilities = defaultCapabilities(),
    pricing = null,
)

/** OpenAI-compatible: `{"data":[{"id":"gpt-4o"}, ...]}`. */
internal fun parseOpenAiModels(json: String): List<ModelDescriptor> {
    val root = parseJsonObject(json) ?: return emptyList()
    return root.arr("data")
        ?.mapNotNull { it.objOrNull()?.str("id")?.takeIf { id -> id.isNotBlank() } }
        ?.distinct()
        ?.map(::modelDescriptor)
        .orEmpty()
}

/** Anthropic: `{"data":[{"id":"claude-...","display_name":"..."}]}`. */
internal fun parseAnthropicModels(json: String): List<ModelDescriptor> {
    val root = parseJsonObject(json) ?: return emptyList()
    return root.arr("data")
        ?.mapNotNull { it.objOrNull()?.str("id")?.takeIf { id -> id.isNotBlank() } }
        ?.distinct()
        ?.map(::modelDescriptor)
        .orEmpty()
}

/** Gemini: `{"models":[{"name":"models/gemini-2.5-flash", ...}]}`. */
internal fun parseGeminiModels(json: String): List<ModelDescriptor> {
    val root = parseJsonObject(json) ?: return emptyList()
    return root.arr("models")
        ?.mapNotNull { it.objOrNull()?.str("name")?.takeIf { name -> name.isNotBlank() } }
        ?.distinct()
        ?.map(::modelDescriptor)
        .orEmpty()
}

internal data class OpenRouterCredits(val totalCredits: Double, val usedCredits: Double) {
    val remaining: Double get() = (totalCredits - usedCredits).coerceAtLeast(0.0)
}

/** OpenRouter: `{"data":{"total_credits":10.0,"total_usage":1.5}}` (older builds: `used`). */
internal fun parseOpenRouterCredits(json: String): OpenRouterCredits? {
    val data = parseJsonObject(json)?.obj("data") ?: return null
    val total = data.double("total_credits") ?: data.double("total") ?: return null
    val used = data.double("used")
        ?: data.double("total_usage")
        ?: data.double("used_credits")
        ?: 0.0
    return OpenRouterCredits(totalCredits = total, usedCredits = used)
}
