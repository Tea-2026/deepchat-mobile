package com.deepseek.mobilechat.platform.export

import com.deepseek.mobilechat.domain.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMarkdownTest {

    private val bundle = ExportFixtures.bundle()

    @Test
    fun `active path only - inactive branch siblings are not rendered`() {
        val conversation = bundle.conversations.single()
        val markdown = ExportMarkdown.renderConversation(bundle, conversation)

        assertTrue(markdown.startsWith("# Branching\n"))
        assertTrue(markdown.contains("- Conversation ID: `conv-1`"))
        assertTrue(markdown.contains("Hello \"world\"\nline 2 ✓ 🚀"))
        assertTrue(markdown.contains("Second answer"))

        assertFalse(markdown.contains("First answer"))
        assertFalse(markdown.contains("Inactive branch answer"))
        assertFalse(markdown.contains("Deliberately verbose reasoning"))
        assertFalse(markdown.contains("Tool call"))

        assertEquals(
            listOf("Hello \"world\"\nline 2 ✓ 🚀", "Second answer"),
            ExportMarkdown.activePath(bundle, conversation).flatMap { message ->
                message.parts.mapNotNull { (it as? com.deepseek.mobilechat.domain.MessagePart.Text)?.text }
            },
        )
        assertEquals(2, ExportMarkdown.activePath(bundle, conversation).size)
    }

    @Test
    fun `roles timestamps and branch marker are present`() {
        val markdown = ExportMarkdown.renderConversation(bundle, bundle.conversations.single())

        // 1700000000000 ms = 2023-11-14 22:13:20 UTC; +100 ms stays in the same second.
        assertTrue(markdown.contains("## User · 2023-11-14 22:13:20 UTC"))
        assertTrue(markdown.contains("## Assistant · 2023-11-14 22:13:20 UTC · branch 2/2"))
        assertTrue(markdown.contains("- Created: 2023-11-14 22:13:20 UTC"))
        assertTrue(markdown.contains("- Updated: 2023-11-14 22:13:20 UTC"))
    }

    @Test
    fun `attachments on the active path get a readable placeholder and appendix entry`() {
        val markdown = ExportMarkdown.renderConversation(bundle, bundle.conversations.single())

        assertTrue(markdown.contains("[Image: diagram.png]"))
        assertTrue(markdown.contains("## Attachments"))
        assertTrue(markdown.contains("`att-1` diagram.png (image/png, 3 bytes)"))
        // notes.txt is only referenced from the abandoned branch.
        assertFalse(markdown.contains("notes.txt"))
    }

    @Test
    fun `renderAll separates conversations and file names are safe`() {
        val second = bundle.conversations.single().copy(
            id = com.deepseek.mobilechat.domain.ConversationId("conv-2"),
            title = "A/B: what?",
            activeLeafId = null,
        )
        val all = bundle.copy(conversations = bundle.conversations + second)

        val markdown = ExportMarkdown.renderAll(all)
        assertTrue(markdown.contains("# Branching"))
        assertTrue(markdown.contains("# A/B: what?"))
        assertTrue(markdown.contains("\n\n---\n\n"))

        val fileName = ExportMarkdown.fileNameFor(second)
        assertTrue(fileName.endsWith(".md"))
        assertFalse(fileName.contains("/"))
        assertFalse(fileName.contains(":"))
        assertFalse(fileName.contains("?"))
    }

    @Test
    fun `empty active path renders a readable note`() {
        val empty = bundle.conversations.single().copy(activeLeafId = null)
        val markdown = ExportMarkdown.renderConversation(bundle, empty)
        assertTrue(markdown.contains("_No messages on the active branch._"))
    }

    @Test
    fun `timestamps use UTC`() {
        assertEquals("1970-01-01 00:00:00 UTC", ExportMarkdown.formatTimestamp(0L))
        assertEquals("2023-11-14 22:13:20 UTC", ExportMarkdown.formatTimestamp(ExportFixtures.T0))
    }

    @Test
    fun `rich parts render as readable blocks`() {
        val conversation = bundle.conversations.single().copy(
            activeLeafId = com.deepseek.mobilechat.domain.MessageId("m-2"),
        )
        val markdown = ExportMarkdown.renderConversation(bundle, conversation)

        assertTrue(markdown.contains("> **Reasoning**"))
        assertTrue(markdown.contains("#### Context block · Search context (SEARCH)"))
        assertTrue(markdown.contains("#### Search · kotlin"))
        assertTrue(markdown.contains("#### Tool call · lookup"))
        assertTrue(markdown.contains("#### Tool result · call-1 (ok)"))
        assertTrue(markdown.contains("[File: notes.txt]"))
        assertTrue(markdown.contains("branch 1/2"))
        assertTrue(markdown.contains("`att-2` notes.txt"))
    }

    @Test
    fun `roles map to readable labels`() {
        val markdown = ExportMarkdown.renderConversation(bundle, bundle.conversations.single())
        assertTrue(markdown.contains("## User"))
        assertTrue(markdown.contains("## Assistant"))
        assertFalse(markdown.contains(MessageRole.SYSTEM.name))
    }
}
