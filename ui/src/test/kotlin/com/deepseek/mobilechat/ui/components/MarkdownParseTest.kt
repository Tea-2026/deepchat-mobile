package com.deepseek.mobilechat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown renderer is hand-written, so the pure block/inline splitter gets
 * real tests. The streaming cases matter most: text arrives one token at a time,
 * so unterminated fences, emphasis markers and table rows are normal input, not
 * errors.
 */
class MarkdownParseTest {

    private fun MarkdownBlock.plainText(): String = when (this) {
        is MarkdownBlock.Paragraph -> inlinesText(inlines)
        is MarkdownBlock.Heading -> inlinesText(inlines)
        is MarkdownBlock.Code -> code
        else -> ""
    }

    @Test
    fun `plain text becomes one paragraph`() {
        val blocks = parseMarkdown("你好，世界")
        assertEquals(1, blocks.size)
        assertEquals("你好，世界", blocks[0].plainText())
    }

    @Test
    fun `blank lines split paragraphs but single newlines do not`() {
        val blocks = parseMarkdown("第一段\n还是第一段\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段\n还是第一段", blocks[0].plainText())
        assertEquals("第二段", blocks[1].plainText())
    }

    @Test
    fun `fenced code block keeps language and body`() {
        val source = "说明如下：\n```kotlin\nval x = 1\n```\n结束"
        val blocks = parseMarkdown(source)
        assertEquals(3, blocks.size)
        assertEquals("说明如下：", blocks[0].plainText())
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertEquals("结束", blocks[2].plainText())
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
    fun `indented code block is de-indented`() {
        val code = parseMarkdown("    第一行\n    第二行").single() as MarkdownBlock.Code
        assertEquals("", code.language)
        assertEquals("第一行\n第二行", code.code)
    }

    @Test
    fun `inline markers do not break block splitting`() {
        val blocks = parseMarkdown("用 **粗体** 和 `code` 混排")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("用 粗体 和 code 混排", inlinesText(paragraph.inlines))
        assertTrue(paragraph.inlines.any { it.bold && it.text == "粗体" })
        assertTrue(paragraph.inlines.any { it.code && it.text == "code" })
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

    @Test
    fun `heading list and table produce the expected block sequence`() {
        val source = """
            # 标题

            - 第一项
            - 第二项

            | 名称 | 数量 |
            | :--- | ---: |
            | 苹果 | 3 |
        """.trimIndent()

        val blocks = parseMarkdown(source)
        assertEquals(3, blocks.size)

        val heading = blocks[0] as MarkdownBlock.Heading
        assertEquals(1, heading.level)
        assertEquals("标题", inlinesText(heading.inlines))

        val list = blocks[1] as MarkdownBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(2, list.items.size)
        assertEquals("第一项", list.items[0].blocks[0].plainText())
        assertEquals("第二项", list.items[1].blocks[0].plainText())

        val table = blocks[2] as MarkdownBlock.Table
        assertEquals(2, table.header.size)
        assertEquals("名称", inlinesText(table.header[0]))
        assertEquals("数量", inlinesText(table.header[1]))
        assertEquals(
            listOf(MarkdownAlignment.START, MarkdownAlignment.END),
            table.alignments,
        )
        assertEquals(1, table.rows.size)
        assertEquals("苹果", inlinesText(table.rows[0][0]))
        assertEquals("3", inlinesText(table.rows[0][1]))
    }

    @Test
    fun `table row split mid-stream still yields a table`() {
        val blocks = parseMarkdown("| 名称 | 数量 |\n| --- | --- |\n| 苹果")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals("苹果", inlinesText(table.rows[0][0]))
        assertEquals("", inlinesText(table.rows[0][1]))
    }

    @Test
    fun `table without body rows is still a table while streaming`() {
        val blocks = parseMarkdown("| 名称 | 数量 |\n| --- | --- |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(0, table.rows.size)
        assertEquals(2, table.header.size)
    }

    @Test
    fun `emphasis marker split mid-stream does not throw and stays literal`() {
        val blocks = parseMarkdown("正在 **加粗")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("正在 **加粗", inlinesText(paragraph.inlines))
        assertTrue(paragraph.inlines.none { it.bold })
    }

    @Test
    fun `unclosed single emphasis marker stays literal too`() {
        val blocks = parseMarkdown("前 *斜体")
        val paragraph = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("前 *斜体", inlinesText(paragraph.inlines))
        assertTrue(paragraph.inlines.none { it.italic })
    }

    @Test
    fun `closed emphasis produces styled runs`() {
        val paragraph = parseMarkdown("**粗体** 和 *斜体*").single() as MarkdownBlock.Paragraph
        assertEquals("粗体 和 斜体", inlinesText(paragraph.inlines))
        assertEquals("粗体", paragraph.inlines.first { it.bold }.text)
        assertEquals("斜体", paragraph.inlines.first { it.italic }.text)
    }

    @Test
    fun `nested emphasis keeps both flags`() {
        val paragraph = parseMarkdown("***粗斜体***").single() as MarkdownBlock.Paragraph
        val run = paragraph.inlines.single()
        assertEquals("粗斜体", run.text)
        assertTrue(run.bold)
        assertTrue(run.italic)
    }

    @Test
    fun `strikethrough composes with strong`() {
        val paragraph = parseMarkdown("~~删除 **粗**~~").single() as MarkdownBlock.Paragraph
        assertTrue(paragraph.inlines.any { it.text == "粗" && it.bold && it.strikethrough })
        assertTrue(paragraph.inlines.first { it.text.contains("删除") }.strikethrough)
    }

    @Test
    fun `nested list becomes a child list of the first item`() {
        val blocks = parseMarkdown("- 一\n  - 一甲\n  - 一乙\n- 二")
        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks
            .filterIsInstance<MarkdownBlock.ListBlock>()
            .single()
        assertFalse(nested.ordered)
        assertEquals(2, nested.items.size)
        assertEquals("一甲", nested.items[0].blocks[0].plainText())
        assertEquals("一乙", nested.items[1].blocks[0].plainText())
    }

    @Test
    fun `ordered list keeps each item's source number`() {
        val list = parseMarkdown("3. 三\n4. 四").single() as MarkdownBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(3, list.items[0].number)
        assertEquals(4, list.items[1].number)
    }

    @Test
    fun `link with parentheses in the url keeps the whole destination`() {
        val paragraph = parseMarkdown("[文档](https://example.test/a_(b))")
            .single() as MarkdownBlock.Paragraph
        val link = paragraph.inlines.single { it.link != null }
        assertEquals("文档", link.text)
        assertEquals("https://example.test/a_(b)", link.link)
    }

    @Test
    fun `blockquote containing a list parses as nested blocks`() {
        val quote = parseMarkdown("> 引用说明\n> - 要点一\n> - 要点二")
            .single() as MarkdownBlock.Quote
        assertEquals("引用说明", quote.blocks[0].plainText())
        val list = quote.blocks[1] as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("要点一", list.items[0].blocks[0].plainText())
    }

    @Test
    fun `thematic break and heading levels are modelled`() {
        val source = "# 一\n\n正文\n\n---\n\n###### 六"
        val blocks = parseMarkdown(source)
        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals("正文", blocks[1].plainText())
        assertTrue(blocks[2] is MarkdownBlock.ThematicBreak)
        assertEquals(6, (blocks[3] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `table cells support inline styling`() {
        val table = parseMarkdown("| 名称 |\n| --- |\n| **粗** |").single() as MarkdownBlock.Table
        val cell = table.rows[0][0]
        assertEquals("粗", inlinesText(cell))
        assertTrue(cell.single().bold)
    }
}
