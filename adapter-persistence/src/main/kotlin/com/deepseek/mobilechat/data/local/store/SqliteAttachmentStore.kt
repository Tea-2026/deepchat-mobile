package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.AttachmentStore
import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.IdPort
import com.deepseek.mobilechat.application.ImportedFile
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.files.FileSink
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.ConversationId
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [AttachmentStore].
 *
 * Bytes are written through the injected [FileSink] (production:
 * `AndroidFileSink(context.filesDir)`), the row keeps only metadata and the
 * absolute local path. `id` and `createdAt` come from [IdPort]/[ClockPort] so
 * tests are deterministic.
 *
 * Failures are reported as `Outcome.Err(AppError.Storage)`; a partially written
 * file is removed again if the row cannot be persisted.
 *
 * A `conversationId` of `null` stores a **draft** attachment: the conversation
 * row does not exist yet (a draft is not persisted before its first message),
 * so `conversation_id` stays NULL and the row is invisible to
 * [observe] until [linkToConversation] claims it for the real conversation.
 */
class SqliteAttachmentStore(
    private val db: Db,
    private val sink: FileSink,
    private val ids: IdPort,
    private val clock: ClockPort,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentStore {

    override fun observe(conversationId: ConversationId): Flow<List<Attachment>> =
        bus.observed(io) { loadList(conversationId) }

    override suspend fun get(id: AttachmentId): Attachment? = withContext(io) { loadOne(id) }

    override suspend fun save(
        conversationId: ConversationId?,
        file: ImportedFile,
    ): Outcome<Attachment> = withContext(io) {
        val attachmentId = AttachmentId(ids.newId())
        val safeName = sanitizeFileName(file.fileName)
        val mimeType = file.mimeType.trim().ifEmpty { DEFAULT_MIME }
        val bytes = file.bytes
        val kind = kindOf(mimeType)
        val digest = sha256(bytes)

        val localPath = try {
            sink.write(ATTACHMENTS_DIR + "/" + attachmentId.raw + "/" + safeName, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Outcome.Err(
                AppError.Storage("could not write attachment bytes: ${e.message}"),
            )
        }

        val attachment = Attachment(
            id = attachmentId,
            fileName = safeName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            localPath = localPath,
            sha256 = digest,
            kind = kind,
            extractedText = if (kind == AttachmentKind.TEXT && bytes.size <= MAX_EXTRACTED_TEXT_BYTES) {
                String(bytes, Charsets.UTF_8)
            } else {
                null
            },
            createdAt = clock.nowMillis(),
        )

        try {
            db.exec(
                """
                INSERT INTO attachments
                    (id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256,
                     kind, extracted_text, width, height, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(
                    attachment.id.raw,
                    conversationId?.raw,
                    attachment.fileName,
                    attachment.mimeType,
                    attachment.sizeBytes,
                    attachment.localPath,
                    attachment.sha256,
                    attachment.kind.name,
                    attachment.extractedText,
                    attachment.width,
                    attachment.height,
                    attachment.createdAt,
                ),
            )
            bus.notifyChanged()
            Outcome.Ok(attachment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { sink.delete(localPath) }
            Outcome.Err(AppError.Storage("could not persist attachment row: ${e.message}"))
        }
    }

    override suspend fun linkToConversation(ids: List<AttachmentId>, conversationId: ConversationId) {
        if (ids.isEmpty()) return
        withContext(io) {
            // Chunked so the bound-variable limit holds even for a large paste;
            // one transaction keeps a multi-chunk link all-or-nothing.
            db.transaction {
                ids.chunked(IN_CLAUSE_CHUNK).forEach { chunk ->
                    db.exec(
                        "UPDATE attachments SET conversation_id = ? " +
                            "WHERE id IN (${placeholders(chunk.size)})",
                        listOf(conversationId.raw) + chunk.map { it.raw },
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun delete(id: AttachmentId) {
        withContext(io) {
            val existing = loadOne(id)
            db.exec("DELETE FROM attachments WHERE id = ?", listOf(id.raw))
            existing?.let { attachment ->
                runCatching { sink.delete(attachment.localPath) }
            }
            bus.notifyChanged()
        }
    }

    private fun loadList(conversationId: ConversationId): List<Attachment> = db.query(
        "SELECT $COLUMNS FROM attachments WHERE conversation_id = ? ORDER BY created_at ASC, id ASC",
        listOf(conversationId.raw),
        ::mapAttachment,
    )

    private fun loadOne(id: AttachmentId): Attachment? =
        db.query("SELECT $COLUMNS FROM attachments WHERE id = ?", listOf(id.raw), ::mapAttachment)
            .firstOrNull()

    private fun mapAttachment(row: Row): Attachment {
        val id = row.string(0) ?: throw DbException("attachment row without id")
        return Attachment(
            id = AttachmentId(id),
            fileName = row.string(2) ?: "",
            mimeType = row.string(3) ?: "",
            sizeBytes = row.long(4),
            localPath = row.string(5) ?: "",
            sha256 = row.string(6) ?: "",
            kind = try {
                AttachmentKind.valueOf(row.string(7) ?: "")
            } catch (e: IllegalArgumentException) {
                throw DbException("unknown attachment kind for $id", e)
            },
            extractedText = row.string(8),
            width = if (row.isNull(9)) null else row.int(9),
            height = if (row.isNull(10)) null else row.int(10),
            createdAt = row.long(11),
        )
    }

    private companion object {
        const val ATTACHMENTS_DIR = "attachments"
        const val DEFAULT_MIME = "application/octet-stream"

        /**
         * Text attachments are inlined for context injection, but only up to
         * 1 MiB; larger files keep `extracted_text = NULL` instead of risking an
         * OOM while loading a message. The bytes are still stored in full.
         */
        const val MAX_EXTRACTED_TEXT_BYTES = 1 shl 20

        const val COLUMNS =
            "id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256, " +
                "kind, extracted_text, width, height, created_at"

        private val UNSAFE_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._()\\[\\]+-]")

        fun sanitizeFileName(rawName: String): String {
            val leaf = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            val collapsed = leaf.replace(UNSAFE_FILE_NAME_CHARS, "_").trim('.', '_')
            val bounded = collapsed.take(MAX_FILE_NAME_LENGTH)
            return bounded.ifBlank { "attachment" }
        }

        const val MAX_FILE_NAME_LENGTH = 120

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = "0123456789abcdef"
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }

        fun kindOf(mimeType: String): AttachmentKind {
            val normalized = mimeType.lowercase()
            return when {
                normalized.startsWith("image/") -> AttachmentKind.IMAGE
                normalized.startsWith("text/") -> AttachmentKind.TEXT
                normalized == "application/json" ||
                    normalized.endsWith("+json") ||
                    normalized == "application/xml" ||
                    normalized.endsWith("+xml") -> AttachmentKind.TEXT
                else -> AttachmentKind.OTHER
            }
        }
    }
}
