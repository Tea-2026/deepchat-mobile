package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.ConversationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentStoreTest {

    private val helloBytes = "hello".toByteArray(Charsets.UTF_8)

    private val helloSha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun <T> Outcome<T>.unwrap(): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> throw AssertionError("expected Ok but got $error")
    }

    private fun imported(name: String, mime: String, bytes: ByteArray = helloBytes) =
        ImportedFile(fileName = name, mimeType = mime, bytes = bytes, sizeBytes = bytes.size.toLong())

    @Test
    fun saveWritesBytesRowAndDerivedFields() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.clock.now = 123_456L

            val attachment = env.attachments.save(
                ConversationId("c1"),
                imported("notes.txt", "text/plain"),
            ).unwrap()

            assertEquals("notes.txt", attachment.fileName)
            assertEquals("text/plain", attachment.mimeType)
            assertEquals(AttachmentKind.TEXT, attachment.kind)
            assertEquals(helloSha256, attachment.sha256)
            assertEquals(helloBytes.size.toLong(), attachment.sizeBytes)
            assertEquals("hello", attachment.extractedText)
            assertEquals(123_456L, attachment.createdAt)
            assertTrue(attachment.localPath.contains("attachments"))
            assertArrayEquals(helloBytes, File(attachment.localPath).readBytes())
            assertEquals(attachment, env.attachments.get(attachment.id))
            assertEquals(
                listOf(attachment.id),
                env.attachments.observe(ConversationId("c1")).first().map { it.id },
            )
        }
    }

    @Test
    fun imageAttachmentsKeepNoExtractedText() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val attachment = env.attachments.save(
                ConversationId("c1"),
                imported("photo.png", "image/png", byteArrayOf(1, 2, 3)),
            ).unwrap()

            assertEquals(AttachmentKind.IMAGE, attachment.kind)
            assertNull(attachment.extractedText)
        }
    }

    @Test
    fun unknownMimeBecomesOther() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val attachment = env.attachments.save(
                ConversationId("c1"),
                imported("doc.pdf", "application/pdf", byteArrayOf(9)),
            ).unwrap()

            assertEquals(AttachmentKind.OTHER, attachment.kind)
            assertNull(attachment.extractedText)
        }
    }

    @Test
    fun saveKeepsFileNameInsideTheSinkRoot() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val attachment = env.attachments.save(
                ConversationId("c1"),
                imported("../../etc/passwd", "text/plain"),
            ).unwrap()

            assertEquals("passwd", attachment.fileName)
            val base = env.filesDir.canonicalPath
            val stored = File(attachment.localPath).canonicalPath
            assertTrue("$stored must stay under $base", stored.startsWith(base + File.separator))
            assertTrue(File(stored).isFile)
        }
    }

    @Test
    fun saveWithUnknownConversationFailsAndRemovesTheWrittenFile() = runBlocking {
        TestEnv().use { env ->
            val outcome = env.attachments.save(
                ConversationId("missing"),
                imported("orphan.txt", "text/plain"),
            )

            assertTrue("expected Err, got $outcome", outcome is Outcome.Err)
            assertEquals(0, env.db.query("SELECT 1 FROM attachments", emptyList()) { true }.size)
            assertTrue(env.filesDir.walkTopDown().none { it.isFile })
        }
    }

    @Test
    fun draftAttachmentIsStoredWithNullConversationIdAndReadsBack() = runBlocking {
        TestEnv().use { env ->
            val draft = env.attachments.save(
                null,
                imported("draft-photo.png", "image/png", byteArrayOf(1, 2, 3)),
            ).unwrap()

            assertEquals(draft, env.attachments.get(draft.id))
            assertEquals(
                listOf(true),
                env.db.query(
                    "SELECT conversation_id IS NULL FROM attachments WHERE id = ?",
                    listOf(draft.id.raw),
                ) { it.int(0) != 0 },
            )
            // A draft attachment has no conversation yet, so no conversation
            // observation may list it.
            assertTrue(env.attachments.observe(ConversationId("c1")).first().isEmpty())
        }
    }

    @Test
    fun savingWithAConversationIdBindsTheRowToThatConversation() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val attachment = env.attachments.save(
                ConversationId("c1"),
                imported("notes.txt", "text/plain"),
            ).unwrap()

            assertEquals(
                listOf("c1"),
                env.db.query(
                    "SELECT conversation_id FROM attachments WHERE id = ?",
                    listOf(attachment.id.raw),
                ) { it.string(0) },
            )
            assertEquals(
                listOf(attachment.id),
                env.attachments.observe(ConversationId("c1")).first().map { it.id },
            )
        }
    }

    @Test
    fun linkToConversationMakesDraftAttachmentsVisibleToObservers() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val photo = env.attachments.save(
                null,
                imported("photo.png", "image/png", byteArrayOf(1)),
            ).unwrap()
            val notes = env.attachments.save(null, imported("notes.txt", "text/plain")).unwrap()

            val snapshots = async(Dispatchers.Unconfined) {
                withTimeout(5_000) {
                    env.attachments.observe(ConversationId("c1")).take(2).toList()
                }
            }

            env.attachments.linkToConversation(
                listOf(photo.id, notes.id),
                ConversationId("c1"),
            )

            val emitted = snapshots.await()
            assertTrue("first snapshot must be empty, was ${emitted[0]}", emitted[0].isEmpty())
            // The second snapshot is the ChangeBus notification; without it the
            // collector would never wake up and the timeout above would fire.
            assertEquals(listOf(photo.id, notes.id), emitted[1].map { it.id })
            assertEquals(
                listOf(photo.id, notes.id),
                env.attachments.observe(ConversationId("c1")).first().map { it.id },
            )
        }
    }

    @Test
    fun linkToConversationWithNoIdsIsANoOp() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))

            env.attachments.linkToConversation(emptyList(), ConversationId("c1"))

            assertTrue(env.attachments.observe(ConversationId("c1")).first().isEmpty())
        }
    }

    @Test
    fun linkToConversationCannotPointAtAMissingConversation() = runBlocking {
        TestEnv().use { env ->
            val draft = env.attachments.save(null, imported("notes.txt", "text/plain")).unwrap()

            val failure = runCatching {
                env.attachments.linkToConversation(listOf(draft.id), ConversationId("missing"))
            }

            assertTrue("expected the FK to reject the link, got $failure", failure.isFailure)
            assertEquals(draft, env.attachments.get(draft.id))
            assertTrue(
                env.db.query(
                    "SELECT 1 FROM attachments WHERE id = ? AND conversation_id IS NULL",
                    listOf(draft.id.raw),
                ) { true }.isNotEmpty(),
            )
        }
    }

    @Test
    fun deleteRemovesRowAndBytes() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val attachment = env.attachments.save(
                ConversationId("c1"),
                imported("notes.txt", "text/plain"),
            ).unwrap()
            val path = attachment.localPath
            assertTrue(File(path).isFile)

            env.attachments.delete(attachment.id)

            assertNull(env.attachments.get(attachment.id))
            assertTrue(env.attachments.observe(ConversationId("c1")).first().isEmpty())
            assertFalse(File(path).exists())
        }
    }

    @Test
    fun deletingConversationHidesItsAttachments() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.attachments.save(ConversationId("c1"), imported("notes.txt", "text/plain")).unwrap()

            env.conversations.delete(ConversationId("c1"))

            assertTrue(env.attachments.observe(ConversationId("c1")).first().isEmpty())
        }
    }
}
