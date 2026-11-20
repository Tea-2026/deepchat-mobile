package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search block is what the model reads, and it is the only thing the user
 * paid a network round-trip for — its shape is worth pinning down.
 */
class SearchInjectionTest {

    private fun hit(title: String, url: String, snippet: String) = SearchHit(title, url, snippet)

    @Test
    fun `no hits still produces a readable block`() {
        val text = formatSearchInjection("kotlin coroutines", emptyList())

        assertEquals(
            "【联网搜索结果：kotlin coroutines】\n" +
                "（没有找到相关结果）\n" +
                "以上结果来自网络搜索，请核对来源。",
            text,
        )
    }

    @Test
    fun `hits are numbered, capped at five, and snippets are truncated`() {
        val longSnippet = "字".repeat(500)
        val hits = (1..7).map { index ->
            hit("标题$index", "https://example.com/$index", if (index == 1) longSnippet else "摘要$index")
        }

        val text = formatSearchInjection("查询", hits)
        val lines = text.lines()

        assertEquals("【联网搜索结果：查询】", lines.first())
        assertTrue(text.endsWith("以上结果来自网络搜索，请核对来源。"))
        // Only the first five hits survive.
        (1..5).forEach { index -> assertTrue("缺少第 $index 条", text.contains("$index. 标题$index")) }
        assertFalse(text.contains("6. 标题6"))
        assertFalse(text.contains("7. 标题7"))
        // Indented url on its own line.
        assertTrue(lines.contains("   https://example.com/1"))
        // Snippet truncated to 200 chars plus the ellipsis, no more.
        val snippetLine = lines.first { it.startsWith("   字") }
        assertEquals("   ".length + SearchSnippetLimit + 1, snippetLine.length)
        assertTrue(snippetLine.endsWith("…"))
    }

    @Test
    fun `special characters survive and line breaks do not break the layout`() {
        val hits = listOf(
            hit(
                title = "A <b>\"quoted\"</b> & 🚀 title",
                url = "https://example.com/search?q=c%2B%2B&lang=zh#top",
                snippet = "第一行\n第二行\t带制表符  —— 还有 emoji 🧪",
            ),
        )

        val text = formatSearchInjection("c++ & kotlin", hits)

        assertTrue(text.contains("1. A <b>\"quoted\"</b> & 🚀 title"))
        assertTrue(text.contains("   https://example.com/search?q=c%2B%2B&lang=zh#top"))
        // Newlines/tabs collapsed into single spaces, so each hit stays 3 lines.
        assertTrue(text.contains("   第一行 第二行 带制表符 —— 还有 emoji 🧪"))
        assertFalse(text.lines().any { it.contains("\t") })
        assertEquals(3 + 2, text.lines().size) // header + 3 hit lines + footer
    }

    @Test
    fun `empty title falls back to the url and empty snippet leaves no blank line`() {
        val text = formatSearchInjection("q", listOf(hit("   ", "https://only.url", "")))

        assertTrue(text.contains("1. https://only.url"))
        assertFalse(text.lines().any { it.isBlank() })
    }

    @Test
    fun `failure note is a single line`() {
        assertEquals("联网搜索失败：网络不可用，请检查连接", searchFailureInjection(" 网络不可用，请检查连接 "))
        assertEquals("联网搜索失败：未知错误", searchFailureInjection("   "))
    }

    @Test
    fun `manual injection and search context are joined, blanks dropped`() {
        assertEquals("A\n\nB", mergeInjections("A", null, "  ", "B"))
        assertEquals("A", mergeInjections(" A "))
        assertNull(mergeInjections(null, "  "))
    }
}
