package com.deepseek.mobilechat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown subset is hand-written, so the block splitter gets real tests:
 * it has to stay stable while text arrives token by token (unterminated
 * fences are normal, not an error case).
 */
class MarkdownParseTest {

    @Test
    fun `plain text becomes one paragraph`() {
        val blocks = parseMarkdown("你好，世界")
        assertEquals(1, blocks.size)
        assertEquals("你好，世界", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `blank lines split paragraphs but single newlines do not`() {
        val blocks = parseMarkdown("第一段\n还是第一段\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段\n还是第一段", (blocks[0] as MarkdownBlock.Paragraph).text)
        assertEquals("第二段", (blocks[1] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `fenced code block keeps language and body`() {
        val source = "说明如下：\n```kotlin\nval x = 1\n```\n结束"
        val blocks = parseMarkdown(source)
        assertEquals(3, blocks.size)
        assertEquals("说明如下：", (blocks[0] as MarkdownBlock.Paragraph).text)
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertEquals("结束", (blocks[2] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `unterminated fence during streaming still yields a code block`() {
        val blocks = parseMarkdown("```python\nprint(1)\nprint(2")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.Code
        assertEquals("python", code.language)
        assertEquals("print(1)\nprint(2", code.code)
    }

    @Test
    fun `inline markers do not break block splitting`() {
        val blocks = parseMarkdown("用 **粗体** 和 `code` 混排")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("用 **粗体** 和 `code` 混排", paragraph.text)
    }

    @Test
    fun `empty input produces no blocks`() {
        assertTrue(parseMarkdown("").isEmpty())
    }

    @Test
    fun `code block content is not treated as markdown`() {
        val blocks = parseMarkdown("```\n**not bold**\n```")
        assertEquals(1, blocks.size)
        assertEquals("**not bold**", (blocks[0] as MarkdownBlock.Code).code)
    }
}
