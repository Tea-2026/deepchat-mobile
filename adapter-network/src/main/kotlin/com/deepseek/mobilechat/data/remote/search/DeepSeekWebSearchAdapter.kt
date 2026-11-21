package com.deepseek.mobilechat.data.remote.search

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.http.ProviderHttpException
import com.deepseek.mobilechat.data.remote.http.guardedHttp
import com.deepseek.mobilechat.data.remote.http.requireSuccess
import com.deepseek.mobilechat.data.remote.provider.arr
import com.deepseek.mobilechat.data.remote.provider.objOrNull
import com.deepseek.mobilechat.data.remote.provider.parseJsonObject
import com.deepseek.mobilechat.data.remote.provider.str
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DeepSeek's own server-side web search, reached through its Anthropic-format
 * endpoint (`POST {root}/anthropic/v1/messages`).
 *
 * No search credential is involved: the request reuses the API key of the
 * DeepSeek provider profile the user already configured, and the `web_search`
 * tool runs on DeepSeek's side. The answer is billed as ordinary model tokens;
 * the search tool calls themselves are free (as of 2026-11-18).
 *
 * The adapter reads the profile on every call instead of capturing it at
 * construction time, so adding or re-keying a DeepSeek provider takes effect
 * without rebuilding the object graph.
 */
class DeepSeekWebSearchAdapter(
    private val providers: ProviderProfileStore,
    private val secrets: SecretStore,
    private val http: HttpCaller,
    /** Test seam; production derives the URL from the profile's base URL. */
    private val endpointOverride: String? = null,
) : SearchPort {

    override suspend fun isConfigured(): Boolean = resolveCredential() != null

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
        withContext(Dispatchers.IO) {
            val (profile, apiKey) = resolveCredential()
                ?: return@withContext Outcome.Err(
                    AppError.SecretMissing(
                        "DeepSeek web search needs a DeepSeek provider with an API key",
                    ),
                )

            val endpoint = endpointOverride ?: deepSeekSearchEndpoint(profile.baseUrl)
            val model = profile.defaultModelId?.raw?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

            guardedHttp {
                val response = http.execute(
                    method = "POST",
                    url = endpoint,
                    headers = mapOf(
                        // DeepSeek reads `x-api-key`; the bearer header is accepted
                        // as well and keeps proxies that strip custom headers happy.
                        "x-api-key" to apiKey,
                        "Authorization" to "Bearer $apiKey",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "anthropic-version" to "2023-06-01",
                    ),
                    body = buildDeepSeekSearchRequest(model, query),
                )
                response.requireSuccess()
                val body = response.use { it.bodyLines.joinToString("\n") }
                val hits = parseDeepSeekSearchResults(body)
                    ?: throw ProviderHttpException(
                        AppError.ProviderProtocol(
                            "DeepSeek web search did not fire: the response has no " +
                                "web_search_tool_result block",
                        ),
                    )
                SearchResponse(query, hits.take(options.maxResults.coerceAtLeast(1)))
            }
        }

    /**
     * The first DeepSeek profile that actually has a key. Kept separate from
     * [isConfigured] so a profile without a secret never turns into a request.
     */
    private suspend fun resolveCredential(): Pair<ProviderProfile, String>? {
        val profiles = try {
            providers.observeAll().first()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            emptyList()
        }
        for (profile in profiles) {
            if (!isDeepSeekProfile(profile)) continue
            val key = try {
                secrets.get(profile.secretRef)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                null
            }
            if (!key.isNullOrBlank()) return profile to key
        }
        return null
    }

    companion object {
        const val DEFAULT_MODEL: String = "deepseek-flash"
        const val MAX_USES: Int = 3
    }
}

// ---------------------------------------------------------------------------
// Endpoint derivation
// ---------------------------------------------------------------------------

/** True when [profile] points at DeepSeek, matched on the URL host. */
internal fun isDeepSeekProfile(profile: ProviderProfile): Boolean {
    val host = runCatching { java.net.URI(profile.baseUrl).host }.getOrNull()
    return (host ?: profile.baseUrl).contains("deepseek", ignoreCase = true)
}

/**
 * `https://api.deepseek.com` (also `.../v1`, `.../anthropic`, and a trailing
 * slash) all resolve to `https://api.deepseek.com/anthropic/v1/messages`.
 *
 * The `/v1` and `/anthropic` suffixes are the two shapes a chat provider base
 * URL can take, and appending the search path to either would produce
 * `/v1/anthropic/v1/messages`.
 */
internal fun deepSeekSearchEndpoint(baseUrl: String): String {
    var root = baseUrl.trim().trimEnd('/')
    while (true) {
        root = when {
            root.endsWith("/v1") -> root.removeSuffix("/v1")
            root.endsWith("/anthropic") -> root.removeSuffix("/anthropic")
            else -> break
        }
    }
    return "$root/anthropic/v1/messages"
}

// ---------------------------------------------------------------------------
// Request
// ---------------------------------------------------------------------------

/**
 * The Anthropic-format request that makes DeepSeek run one server-side search.
 * `thinking` is disabled so the whole token budget goes to the search summary,
 * and `max_tokens` is small on purpose: only the result blocks are consumed.
 */
internal fun buildDeepSeekSearchRequest(model: String, query: String): String =
    buildJsonObject {
        put("model", model)
        put("max_tokens", 512)
        put("thinking", buildJsonObject { put("type", "disabled") })
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", DeepSeekWebSearchAdapter.MAX_USES)
                    },
                )
            },
        )
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "Perform a web search for the query: $query")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

// ---------------------------------------------------------------------------
// Response parsing (pure, fixture-testable)
// ---------------------------------------------------------------------------

/**
 * Maps the Anthropic-format response to search hits.
 *
 * Returns `null` — not an empty list — when the response contains no
 * `web_search_tool_result` block: that means the model answered without ever
 * invoking the search tool, which is a failure rather than "zero results".
 *
 * Snippet resolution: a result's own `snippet` wins; otherwise the
 * `cited_text` of the first `text` citation with the same URL is used. URLs are
 * deduplicated on first occurrence and empty URLs are skipped.
 */
internal fun parseDeepSeekSearchResults(json: String): List<SearchHit>? {
    val root = parseJsonObject(json) ?: return null
    val blocks = root.arr("content")?.mapNotNull { it.objOrNull() } ?: return null

    if (blocks.none { it.str("type") == "web_search_tool_result" }) return null

    val citationsByUrl = mutableMapOf<String, String>()
    for (block in blocks) {
        if (block.str("type") != "text") continue
        val citations = block.arr("citations") ?: continue
        for (element in citations) {
            val citation = element.objOrNull() ?: continue
            val url = citation.str("url")?.takeIf { it.isNotBlank() } ?: continue
            val citedText = citation.str("cited_text") ?: continue
            citationsByUrl.putIfAbsent(url, citedText)
        }
    }

    val seenUrls = mutableSetOf<String>()
    val hits = mutableListOf<SearchHit>()
    for (block in blocks) {
        if (block.str("type") != "web_search_tool_result") continue
        val results = block.arr("content") ?: continue
        for (element in results) {
            val result = element.objOrNull() ?: continue
            if (result.str("type") != "web_search_result") continue
            val url = result.str("url")?.takeIf { it.isNotBlank() } ?: continue
            if (!seenUrls.add(url)) continue
            hits += SearchHit(
                title = result.str("title").orEmpty().ifBlank { url },
                url = url,
                snippet = result.str("snippet")?.takeIf { it.isNotBlank() }
                    ?: citationsByUrl[url]
                    ?: "",
            )
        }
    }
    return hits
}
