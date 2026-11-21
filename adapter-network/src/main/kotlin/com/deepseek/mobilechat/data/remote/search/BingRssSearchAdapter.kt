package com.deepseek.mobilechat.data.remote.search

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.http.guardedHttp
import com.deepseek.mobilechat.data.remote.http.requireSuccess
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.net.URLEncoder

/**
 * Keyless default search: Bing's RSS output
 * (`GET https://cn.bing.com/search?q=...&format=rss`).
 *
 * It needs no credential and no scraping: the feed is a plain XML document.
 * Two quirks are handled here:
 *
 *  - the feed declares `encoding="utf-8"` but ships GB18030 bytes for Chinese
 *    payloads, so [decodeSearchResponse] sniffs the bytes instead of trusting
 *    the declaration;
 *  - titles/descriptions carry HTML markup and entities, so [parseBingRss]
 *    runs them through [cleanSearchText] (tags stripped, entities decoded).
 */
class BingRssSearchAdapter(
    private val http: HttpCaller,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : SearchPort {

    /** Always available: this source has no credential at all. */
    override suspend fun isConfigured(): Boolean = true

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
        withContext(Dispatchers.IO) {
            guardedHttp {
                val response = http.executeBytes(
                    method = "GET",
                    url = bingRssUrl(endpoint, query),
                    headers = mapOf(
                        "Accept" to "application/rss+xml, application/xml;q=0.9, */*;q=0.8",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                        "User-Agent" to MOBILE_USER_AGENT,
                    ),
                )
                response.requireSuccess()
                val body = decodeSearchResponse(response.bytes)
                SearchResponse(query, parseBingRss(body).take(options.maxResults.coerceIn(1, 50)))
            }
        }

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://cn.bing.com/search"

        /** A normal Android Chrome UA; Bing serves the RSS variant to any client. */
        const val MOBILE_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

// ---------------------------------------------------------------------------
// Request
// ---------------------------------------------------------------------------

internal fun bingRssUrl(endpoint: String, query: String): String {
    val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    val separator = if (endpoint.contains('?')) '&' else '?'
    return "$endpoint${separator}q=$q&format=rss"
}

// ---------------------------------------------------------------------------
// Charset sniffing
// ---------------------------------------------------------------------------

/**
 * Decodes a search payload whose declared charset cannot be trusted.
 *
 * Valid UTF-8 is taken at face value (the payload really is UTF-8). Anything
 * else is decoded as GB18030, which is the charset Bing actually emits for
 * Chinese; a strict UTF-8 decode reports a malformed sequence instead of
 * silently substituting U+FFFD, so the fallback is only reached for genuinely
 * non-UTF-8 bytes.
 */
internal fun decodeSearchResponse(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val utf8 = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (t: CharacterCodingException) {
        null
    }
    if (utf8 != null) return utf8.removePrefix("\uFEFF")
    val gb = try {
        String(bytes, Charset.forName("GB18030"))
    } catch (t: Throwable) {
        String(bytes, Charsets.UTF_8)
    }
    return gb.removePrefix("\uFEFF")
}

// ---------------------------------------------------------------------------
// Response parsing (pure, fixture-testable)
// ---------------------------------------------------------------------------

private val ITEM_BLOCK = Regex(
    "<item\\b[^>]*>(.*?)</item>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val TITLE_TAG = itemTag("title")
private val LINK_TAG = itemTag("link")
private val DESCRIPTION_TAG = itemTag("description")

private fun itemTag(name: String): Regex = Regex(
    "<$name\\b[^>]*>(.*?)</$name>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/**
 * Parses `<rss><channel><item>…</item></channel></rss>` into hits.
 *
 * Bing escapes markup inside `<description>`, so the text is entity-decoded
 * before tags are stripped: `&lt;b&gt;Kotlin&lt;/b&gt;` must end up as
 * "Kotlin", not as literal angle brackets. Items without a link are skipped;
 * duplicate links keep their first occurrence.
 */
internal fun parseBingRss(xml: String): List<SearchHit> {
    if (xml.isBlank()) return emptyList()
    return ITEM_BLOCK.findAll(xml)
        .mapNotNull { match ->
            val block = match.groupValues[1]
            val link = cleanSearchText(xmlTagValue(block, LINK_TAG))
            if (link.isBlank()) return@mapNotNull null
            SearchHit(
                title = cleanSearchText(xmlTagValue(block, TITLE_TAG)).ifBlank { link },
                url = link,
                snippet = cleanSearchText(xmlTagValue(block, DESCRIPTION_TAG)),
            )
        }
        .distinctBy { it.url }
        .toList()
}

private fun xmlTagValue(block: String, tag: Regex): String =
    tag.find(block)?.groupValues?.get(1).orEmpty()
