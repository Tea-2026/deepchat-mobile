package com.deepseek.mobilechat.data.remote.search

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.data.remote.http.HttpByteResponse
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.http.HttpResponse
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Keyless Bing RSS search.
 *
 * The feed declares `encoding="utf-8"` but ships GB18030 for Chinese, so both
 * cases are recorded here against the byte-level transport.
 */
class BingRssSearchTest {

    private val chineseTitle = "Kotlin 协程"
    private val chineseSnippet = "轻量级线程，支持结构化并发。"

    // -----------------------------------------------------------------------
    // Charset decoding
    // -----------------------------------------------------------------------

    @Test
    fun `decoder accepts valid utf-8 and falls back to gb18030`() {
        assertEquals(chineseTitle, decodeSearchResponse(chineseTitle.toByteArray(Charsets.UTF_8)))
        assertEquals(chineseTitle, decodeSearchResponse(chineseTitle.toByteArray(Charset.forName("GB18030"))))
        assertEquals("plain ascii", decodeSearchResponse("plain ascii".toByteArray(Charsets.UTF_8)))
        assertEquals("", decodeSearchResponse(ByteArray(0)))
    }

    // -----------------------------------------------------------------------
    // Payload parsing
    // -----------------------------------------------------------------------

    @Test
    fun `rss items are mapped to title url snippet`() {
        val hits = parseBingRss(
            rssFeed(
                rssItem("First", "https://example.test/1", "one"),
                rssItem("Second", "https://example.test/2", "two"),
            ),
        )

        assertEquals(
            listOf(
                SearchHit("First", "https://example.test/1", "one"),
                SearchHit("Second", "https://example.test/2", "two"),
            ),
            hits,
        )
    }

    @Test
    fun `an item without a description has an empty snippet`() {
        val hits = parseBingRss(rssFeed(rssItem("No description", "https://example.test/x")))

        assertEquals(SearchHit("No description", "https://example.test/x", ""), hits.single())
    }

    @Test
    fun `markup and entities are stripped from title url and description`() {
        val hits = parseBingRss(
            rssFeed(
                rssItem(
                    title = "Kotlin &amp; &lt;b&gt;Coroutines&lt;/b&gt;",
                    link = "https://example.test/a?x=1&amp;y=2",
                    description = "&#x4E2D;&#25991; &quot;quoted&quot;",
                ),
            ),
        )

        assertEquals("Kotlin & Coroutines", hits.single().title)
        assertEquals("https://example.test/a?x=1&y=2", hits.single().url)
        assertEquals("中文 \"quoted\"", hits.single().snippet)
    }

    @Test
    fun `items without a link are skipped and duplicate links dedupe`() {
        val hits = parseBingRss(
            rssFeed(
                "<item><title>No link</title><description>dropped</description></item>",
                rssItem("First", "https://example.test/same", "one"),
                rssItem("Second", "https://example.test/same", "two"),
            ),
        )

        assertEquals(SearchHit("First", "https://example.test/same", "one"), hits.single())
    }

    @Test
    fun `non rss input yields no hits instead of throwing`() {
        assertTrue(parseBingRss("").isEmpty())
        assertTrue(parseBingRss("<html><body>blocked</body></html>").isEmpty())
    }

    // -----------------------------------------------------------------------
    // Adapter over a byte transport
    // -----------------------------------------------------------------------

    @Test
    fun `adapter decodes the gb18030 payload without trusting the declared charset`() {
        val xml = rssFeed(rssItem(chineseTitle, "https://example.test/gb", chineseSnippet))
        val bytes = xml.toByteArray(Charset.forName("GB18030"))
        val caller = RecordingByteCaller {
            HttpByteResponse(
                status = 200,
                // The declaration lies; it is here to prove the adapter ignores it.
                headers = mapOf("content-type" to "text/xml; charset=utf-8"),
                bytes = bytes,
            )
        }
        val adapter = BingRssSearchAdapter(caller)

        val response = (runBlocking { adapter.search("kotlin 协程") } as Outcome.Ok).value

        assertEquals(chineseTitle, response.hits.single().title)
        assertEquals(chineseSnippet, response.hits.single().snippet)
    }

    @Test
    fun `adapter decodes a genuine utf-8 payload`() {
        val xml = rssFeed(rssItem(chineseTitle, "https://example.test/utf8", chineseSnippet))
        val caller = RecordingByteCaller {
            HttpByteResponse(200, mapOf("content-type" to "text/xml; charset=utf-8"), xml.toByteArray(Charsets.UTF_8))
        }
        val adapter = BingRssSearchAdapter(caller)

        val response = (runBlocking { adapter.search("kotlin 协程") } as Outcome.Ok).value

        assertEquals(chineseTitle, response.hits.single().title)
        assertEquals(chineseSnippet, response.hits.single().snippet)
    }

    @Test
    fun `adapter is keyless and asks for the rss format with a mobile user agent`() {
        val caller = RecordingByteCaller { HttpByteResponse(200, bytes = ByteArray(0)) }
        val adapter = BingRssSearchAdapter(caller)

        assertTrue(runBlocking { adapter.isConfigured() })
        runBlocking { adapter.search("kotlin 协程", SearchOptions(maxResults = 3)) }

        val call = caller.calls.single()
        assertEquals("GET", call.method)
        assertEquals(
            "https://cn.bing.com/search?q=kotlin%20%E5%8D%8F%E7%A8%8B&format=rss",
            call.url,
        )
        assertNull(call.headers["Authorization"])
        assertNull(call.headers["x-api-key"])
        assertTrue(call.headers["User-Agent"].orEmpty().contains("Android"))
    }

    @Test
    fun `adapter trims hits to maxResults`() {
        val xml = rssFeed(
            rssItem("A", "https://example.test/a", "a"),
            rssItem("B", "https://example.test/b", "b"),
            rssItem("C", "https://example.test/c", "c"),
        )
        val caller = RecordingByteCaller { HttpByteResponse(200, bytes = xml.toByteArray(Charsets.UTF_8)) }
        val adapter = BingRssSearchAdapter(caller)

        val response = (runBlocking { adapter.search("q", SearchOptions(maxResults = 2)) } as Outcome.Ok).value

        assertEquals(listOf("A", "B"), response.hits.map { it.title })
    }

    // -----------------------------------------------------------------------

    private fun rssFeed(vararg items: String): String =
        """<?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel><title>Bing</title>""" +
            items.joinToString("") +
            "</channel></rss>"

    private fun rssItem(title: String, link: String, description: String? = null): String = buildString {
        append("<item><title>").append(title).append("</title>")
        append("<link>").append(link).append("</link>")
        if (description != null) append("<description>").append(description).append("</description>")
        append("</item>")
    }

    private data class RecordedCall(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
    )

    /** Byte-level transport fake: the RSS adapter never uses [execute]. */
    private class RecordingByteCaller(
        private val responder: (RecordedCall) -> HttpByteResponse,
    ) : HttpCaller {

        val calls: MutableList<RecordedCall> = mutableListOf()

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): HttpResponse = error("Bing RSS uses executeBytes")

        override fun executeBytes(
            method: String,
            url: String,
            headers: Map<String, String>,
        ): HttpByteResponse {
            val call = RecordedCall(method, url, headers)
            calls += call
            return responder(call)
        }
    }
}
