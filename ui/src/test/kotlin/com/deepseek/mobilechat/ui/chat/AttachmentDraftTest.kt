package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that must not silently break: whatever is staged in the composer
 * is exactly what gets handed to `ChatService.send(attachmentIds = …)`.
 */
class AttachmentDraftTest {

    private fun attachment(
        rawId: String,
        sizeBytes: Long = 1_000L,
        kind: AttachmentKind = AttachmentKind.TEXT,
    ) = Attachment(
        id = AttachmentId(rawId),
        fileName = "$rawId.txt",
        mimeType = "text/plain",
        sizeBytes = sizeBytes,
        localPath = "/data/user/0/files/$rawId.txt",
        sha256 = "sha-$rawId",
        kind = kind,
        createdAt = 0L,
    )

    @Test
    fun `send receives the staged ids in draft order`() {
        val staged = listOf(attachment("a"), attachment("b"), attachment("c"))

        val ids = AttachmentDraft.idsOf(staged)

        assertEquals(listOf("a", "b", "c"), ids.map { it.raw })
        assertEquals(3, ids.size)
    }

    @Test
    fun `empty draft means send gets no ids`() {
        assertTrue(AttachmentDraft.idsOf(emptyList()).isEmpty())
    }

    @Test
    fun `staging the same attachment twice keeps a single entry`() {
        val first = attachment("a")
        val duplicate = attachment("a")

        val staged = AttachmentDraft.add(AttachmentDraft.add(emptyList(), first), duplicate)

        assertEquals(1, staged.size)
        assertEquals(listOf("a"), AttachmentDraft.idsOf(staged).map { it.raw })
    }

    @Test
    fun `removing one attachment leaves the rest in order`() {
        val staged = listOf(attachment("a"), attachment("b"), attachment("c"))

        val remaining = AttachmentDraft.remove(staged, AttachmentId("b"))

        assertEquals(listOf("a", "c"), AttachmentDraft.idsOf(remaining).map { it.raw })
    }

    @Test
    fun `removing an unknown id is a no-op`() {
        val staged = listOf(attachment("a"))

        assertEquals(staged, AttachmentDraft.remove(staged, AttachmentId("nope")))
    }

    @Test
    fun `attachments alone are enough to send`() {
        assertFalse(AttachmentDraft.canSend("   ", emptyList()))
        assertTrue(AttachmentDraft.canSend("hi", emptyList()))
        assertTrue(AttachmentDraft.canSend("", listOf(attachment("a"))))
    }

    @Test
    fun `draft size totals add up`() {
        val staged = listOf(
            attachment("a", sizeBytes = 1_500),
            attachment("b", sizeBytes = 2_500, kind = AttachmentKind.IMAGE),
        )

        assertEquals(4_000L, AttachmentDraft.totalBytes(staged))
    }

    @Test
    fun `file size formatting is stable`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("812 B", formatFileSize(812))
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("1.0 MB", formatFileSize(1024L * 1024L))
        assertEquals("3.5 MB", formatFileSize((3.5 * 1024 * 1024).toLong()))
    }
}
