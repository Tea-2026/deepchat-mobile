package com.deepseek.mobilechat.data.remote.search

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.http.guardedHttp
import com.deepseek.mobilechat.data.remote.http.requireSuccess
import com.deepseek.mobilechat.data.remote.provider.arr
import com.deepseek.mobilechat.data.remote.provider.readSecret
import com.deepseek.mobilechat.data.remote.provider.obj
import com.deepseek.mobilechat.data.remote.provider.objOrNull
import com.deepseek.mobilechat.data.remote.provider.parseJsonObject
import com.deepseek.mobilechat.data.remote.provider.str
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-app web search adapters.
 *
 * All three providers expose a different shape and auth header but the same
 * concept, so each adapter only maps `{title, url, snippet}` and normalizes
 * failures. The API keys are looked up in [SecretStore] by the documented
 * refs; a missing key is [AppError.SecretMissing], never an empty search.
 */

internal const val TAVILY_SECRET_REF = "search.tavily"
internal const val BRAVE_SECRET_REF = "search.brave"
internal const val SERPER_SECRET_REF = "search.serper"

/** Tavily: `POST https://api.tavily.com/search`. */
class TavilySearchAdapter(
    private val secrets: SecretStore,
    private val http: HttpCaller,
    private val endpoint: String = "https://api.tavily.com/search",
) : SearchPort {

    override suspend fun isConfigured(): Boolean = secrets.get(TAVILY_SECRET_REF)?.isNotBlank() == true

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
        withContext(Dispatchers.IO) {
            val apiKey = when (val secret = readSecret(secrets, TAVILY_SECRET_REF, "Tavily API key")) {
                is Outcome.Ok -> secret.value
                is Outcome.Err -> return@withContext Outcome.Err(secret.error)
            }
            guardedHttp {
                val response = http.execute(
                    method = "POST",
                    url = endpoint,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        // Tavily accepts the key in the body; the bearer header is
                        // also supported and keeps proxies from logging the body key.
                        "Authorization" to "Bearer $apiKey",
                    ),
                    body = buildTavilyRequest(query, options.maxResults, apiKey),
                )
                response.requireSuccess()
                val body = response.use { it.bodyLines.joinToString("\n") }
                SearchResponse(query, parseTavilyResults(body))
            }
        }

    companion object {
        const val SECRET_REF: String = TAVILY_SECRET_REF
    }
}

/** Brave Search: `GET https://api.search.brave.com/res/v1/web/search`. */
class BraveSearchAdapter(
    private val secrets: SecretStore,
    private val http: HttpCaller,
    private val endpoint: String = "https://api.search.brave.com/res/v1/web/search",
) : SearchPort {

    override suspend fun isConfigured(): Boolean = secrets.get(BRAVE_SECRET_REF)?.isNotBlank() == true

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
        withContext(Dispatchers.IO) {
            val apiKey = when (val secret = readSecret(secrets, BRAVE_SECRET_REF, "Brave Search API key")) {
                is Outcome.Ok -> secret.value
                is Outcome.Err -> return@withContext Outcome.Err(secret.error)
            }
            guardedHttp {
                val response = http.execute(
                    method = "GET",
                    url = braveSearchUrl(endpoint, query, options.maxResults),
                    headers = mapOf(
                        "X-Subscription-Token" to apiKey,
                        "Accept" to "application/json",
                    ),
                )
                response.requireSuccess()
                val body = response.use { it.bodyLines.joinToString("\n") }
                SearchResponse(query, parseBraveResults(body))
            }
        }

    companion object {
        const val SECRET_REF: String = BRAVE_SECRET_REF
    }
}

/** Serper (Google): `POST https://google.serper.dev/search`. */
class SerperSearchAdapter(
    private val secrets: SecretStore,
    private val http: HttpCaller,
    private val endpoint: String = "https://google.serper.dev/search",
) : SearchPort {

    override suspend fun isConfigured(): Boolean = secrets.get(SERPER_SECRET_REF)?.isNotBlank() == true

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
        withContext(Dispatchers.IO) {
            val apiKey = when (val secret = readSecret(secrets, SERPER_SECRET_REF, "Serper API key")) {
                is Outcome.Ok -> secret.value
                is Outcome.Err -> return@withContext Outcome.Err(secret.error)
            }
            guardedHttp {
                val response = http.execute(
                    method = "POST",
                    url = endpoint,
                    headers = mapOf(
                        "X-API-KEY" to apiKey,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                    body = buildSerperRequest(query, options.maxResults),
                )
                response.requireSuccess()
                val body = response.use { it.bodyLines.joinToString("\n") }
                SearchResponse(query, parseSerperResults(body))
            }
        }

    companion object {
        const val SECRET_REF: String = SERPER_SECRET_REF
    }
}

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

internal fun buildTavilyRequest(query: String, maxResults: Int, apiKey: String): String =
    buildJsonObject {
        put("api_key", apiKey)
        put("query", query)
        put("max_results", maxResults.coerceIn(1, 20))
    }.toString()

internal fun buildSerperRequest(query: String, maxResults: Int): String =
    buildJsonObject {
        put("q", query)
        put("num", maxResults.coerceIn(1, 100))
    }.toString()

internal fun braveSearchUrl(endpoint: String, query: String, maxResults: Int): String {
    val q = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    val separator = if (endpoint.contains('?')) '&' else '?'
    return "$endpoint${separator}q=$q&count=${maxResults.coerceIn(1, 20)}"
}

// ---------------------------------------------------------------------------
// Response parsing (pure, fixture-testable)
// ---------------------------------------------------------------------------

/** Tavily: `{"results":[{"title","url","content"}]}`. */
internal fun parseTavilyResults(json: String): List<SearchHit> {
    val root = parseJsonObject(json) ?: return emptyList()
    return root.arr("results").orEmpty().mapNotNull { element ->
        val result = element.objOrNull() ?: return@mapNotNull null
        val url = result.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SearchHit(
            title = result.str("title").orEmpty().ifBlank { url },
            url = url,
            snippet = result.str("content") ?: result.str("snippet").orEmpty(),
        )
    }
}

/** Brave: `{"web":{"results":[{"title","url","description"}]}}` with HTML-marked snippets. */
internal fun parseBraveResults(json: String): List<SearchHit> {
    val root = parseJsonObject(json) ?: return emptyList()
    val results = root.obj("web")?.arr("results") ?: return emptyList()
    return results.mapNotNull { element ->
        val result = element.objOrNull() ?: return@mapNotNull null
        val url = result.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SearchHit(
            title = cleanSearchText(result.str("title").orEmpty()).ifBlank { url },
            url = url,
            snippet = cleanSearchText(result.str("description").orEmpty()),
        )
    }
}

/** Serper: `{"organic":[{"title","link","snippet"}]}`. */
internal fun parseSerperResults(json: String): List<SearchHit> {
    val root = parseJsonObject(json) ?: return emptyList()
    return root.arr("organic").orEmpty().mapNotNull { element ->
        val result = element.objOrNull() ?: return@mapNotNull null
        val url = result.str("link")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SearchHit(
            title = cleanSearchText(result.str("title").orEmpty()).ifBlank { url },
            url = url,
            snippet = cleanSearchText(result.str("snippet").orEmpty()),
        )
    }
}

// ---------------------------------------------------------------------------
// Text normalization shared by the search adapters
// ---------------------------------------------------------------------------

private val HTML_TAG = Regex("<[^>]+>")
private val CDATA_BLOCK = Regex("<!\\[CDATA\\[(.*?)]]>", setOf(RegexOption.DOT_MATCHES_ALL))
private val NUMERIC_ENTITY = Regex("&#(?:[xX]([0-9a-fA-F]+)|([0-9]+));")
private val NAMED_ENTITY = Regex("&([a-zA-Z][a-zA-Z0-9]*);")

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
)

/**
 * Decodes the XML/HTML entities the search APIs put in their text fields
 * (`&amp;`, `&quot;`, `&#39;`, `&#x2014;`, …). A single replace pass means an
 * entity that decodes to `&` is never decoded twice.
 */
internal fun decodeSearchEntities(raw: String): String {
    val named = NAMED_ENTITY.replace(raw) { match ->
        NAMED_ENTITIES[match.groupValues[1].lowercase()] ?: match.value
    }
    return NUMERIC_ENTITY.replace(named) { match ->
        val code = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
            ?: match.groupValues[2].toIntOrNull()
        if (code != null && code in 1..0x10FFFF && code !in 0xD800..0xDFFF) {
            String(Character.toChars(code))
        } else {
            ""
        }
    }
}

/** Removes `<b>`-style markup and unwraps CDATA sections. */
internal fun stripSearchMarkup(raw: String): String =
    HTML_TAG.replace(CDATA_BLOCK.replace(raw) { it.groupValues[1] }, "")

/**
 * Search APIs mark query terms with markup and escape their text; plain text is
 * what the prompt wants. Entities are decoded before markup is stripped so
 * escaped tags (`&lt;b&gt;`) disappear as tags rather than showing as text.
 */
internal fun cleanSearchText(raw: String): String =
    stripSearchMarkup(decodeSearchEntities(raw)).trim()
