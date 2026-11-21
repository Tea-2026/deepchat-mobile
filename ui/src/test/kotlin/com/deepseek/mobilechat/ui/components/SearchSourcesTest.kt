package com.deepseek.mobilechat.ui.components

import com.deepseek.mobilechat.domain.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the `参考 N 个网页` panel; no Compose involved. */
class SearchSourcesTest {

    private fun hit(
        title: String = "标题",
        url: String = "https://example.test/a",
        snippet: String = "摘要",
    ) = SearchHit(title = title, url = url, snippet = snippet)

    @Test
    fun `hostname is extracted from common url shapes`() {
        assertEquals("kotlinlang.org", hostOf("https://kotlinlang.org/docs"))
        assertEquals("example.com", hostOf("https://www.example.com:8080/a?b=1#c"))
        assertEquals("a.test", hostOf("http://user:pass@a.test/x"))
        assertEquals("example.test", hostOf("example.test/path"))
        assertEquals("", hostOf("   "))
    }

    @Test
    fun `more than five hits keep the first five and count the rest`() {
        val hits = (1..7).map { hit(url = "https://example.test/$it") }
        val view = searchSourceView(hits)
        assertEquals(5, view.visible.size)
        assertEquals(2, view.hidden)
        assertEquals("https://example.test/1", view.visible.first().url)
    }

    @Test
    fun `expanding shows every hit`() {
        val hits = (1..7).map { hit(url = "https://example.test/$it") }
        val view = searchSourceView(hits, showAll = true)
        assertEquals(7, view.visible.size)
        assertEquals(0, view.hidden)
    }

    @Test
    fun `hits without a url are dropped and duplicate urls collapse`() {
        val hits = listOf(
            hit(title = "一", url = "https://a.test/1"),
            hit(title = "重复", url = "https://a.test/1"),
            hit(title = "空", url = "  "),
            hit(title = "二", url = "https://b.test/2"),
        )
        val view = searchSourceView(hits)
        assertEquals(2, view.visible.size)
        assertEquals(listOf("一", "二"), view.visible.map { it.title })
        assertEquals(0, view.hidden)
    }

    @Test
    fun `empty input produces an empty view`() {
        val view = searchSourceView(emptyList())
        assertTrue(view.visible.isEmpty())
        assertEquals(0, view.hidden)
    }

    @Test
    fun `blank title falls back to the hostname`() {
        assertEquals("example.test", sourceTitle(hit(title = "  ", url = "https://example.test/x")))
        assertEquals("标题", sourceTitle(hit()))
    }

    @Test
    fun `snippet is flattened to one line`() {
        assertEquals("第一行 第二行", oneLineSnippet("第一行\n第二行"))
        assertEquals("a b", oneLineSnippet("  a \n\n b  "))
    }

    @Test
    fun `long snippet is truncated with an ellipsis`() {
        val snippet = "字".repeat(400)
        val result = oneLineSnippet(snippet, maxChars = 50)
        assertEquals(51, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `short snippet is left alone`() {
        assertEquals("很短的摘要", oneLineSnippet("很短的摘要"))
    }
}
