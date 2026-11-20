package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.CompactionStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** SQLite-backed [CompactionStore]. */
class SqliteCompactionStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CompactionStore {

    override fun observe(conversationId: ConversationId): Flow<List<Compaction>> =
        bus.observed(io) { loadList(conversationId) }

    override suspend fun list(conversationId: ConversationId): List<Compaction> =
        withContext(io) { loadList(conversationId) }

    override suspend fun add(compaction: Compaction) {
        withContext(io) {
            val args = listOf(
                compaction.conversationId.raw,
                compaction.fromMessageId.raw,
                compaction.toMessageId.raw,
                compaction.summary,
                compaction.kind.name,
                compaction.createdAt,
                compaction.undoneAt,
            )
            db.transaction {
                if (db.rowExists(TABLE, compaction.id.raw)) {
                    db.exec(
                        """
                        UPDATE compactions
                        SET conversation_id = ?, from_message_id = ?, to_message_id = ?, summary = ?,
                            kind = ?, created_at = ?, undone_at = ?
                        WHERE id = ?
                        """.trimIndent(),
                        args + compaction.id.raw,
                    )
                } else {
                    db.exec(
                        """
                        INSERT INTO compactions
                            (conversation_id, from_message_id, to_message_id, summary, kind,
                             created_at, undone_at, id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        args + compaction.id.raw,
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun markUndone(id: CompactionId, atMillis: Long) {
        withContext(io) {
            db.exec(
                "UPDATE compactions SET undone_at = ? WHERE id = ?",
                listOf(atMillis, id.raw),
            )
            bus.notifyChanged()
        }
    }

    private fun loadList(conversationId: ConversationId): List<Compaction> = db.query(
        "SELECT $COLUMNS FROM compactions WHERE conversation_id = ? ORDER BY created_at ASC, id ASC",
        listOf(conversationId.raw),
        ::mapCompaction,
    )

    private fun mapCompaction(row: Row): Compaction {
        val id = row.string(0) ?: throw DbException("compaction row without id")
        return Compaction(
            id = CompactionId(id),
            conversationId = ConversationId(row.string(1) ?: ""),
            fromMessageId = MessageId(row.string(2) ?: ""),
            toMessageId = MessageId(row.string(3) ?: ""),
            summary = row.string(4) ?: "",
            kind = try {
                CompactionKind.valueOf(row.string(5) ?: "")
            } catch (e: IllegalArgumentException) {
                throw DbException("unknown compaction kind for $id", e)
            },
            createdAt = row.long(6),
            undoneAt = if (row.isNull(7)) null else row.long(7),
        )
    }

    private companion object {
        const val TABLE = "compactions"

        const val COLUMNS =
            "id, conversation_id, from_message_id, to_message_id, summary, kind, created_at, undone_at"
    }
}
