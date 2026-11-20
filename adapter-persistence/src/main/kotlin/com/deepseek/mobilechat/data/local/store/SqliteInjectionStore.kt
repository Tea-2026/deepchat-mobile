package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.InjectionStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ContextInjection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** SQLite-backed [InjectionStore]. Ordering is `(position, created_at)`. */
class SqliteInjectionStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : InjectionStore {

    override fun observe(conversationId: ConversationId): Flow<List<ContextInjection>> =
        bus.observed(io) { loadList(conversationId) }

    override suspend fun list(conversationId: ConversationId): List<ContextInjection> =
        withContext(io) { loadList(conversationId) }

    override suspend fun add(injection: ContextInjection) {
        withContext(io) {
            val args = listOf(
                injection.conversationId.raw,
                injection.anchorMessageId?.raw,
                injection.scope.name,
                injection.position,
                injection.content,
                injection.createdAt,
            )
            db.transaction {
                if (db.rowExists(TABLE, injection.id.raw)) {
                    db.exec(
                        """
                        UPDATE injections
                        SET conversation_id = ?, anchor_message_id = ?, scope = ?, position = ?,
                            content = ?, created_at = ?
                        WHERE id = ?
                        """.trimIndent(),
                        args + injection.id.raw,
                    )
                } else {
                    db.exec(
                        """
                        INSERT INTO injections
                            (conversation_id, anchor_message_id, scope, position, content, created_at, id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        args + injection.id.raw,
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun remove(id: InjectionId) {
        withContext(io) {
            db.exec("DELETE FROM injections WHERE id = ?", listOf(id.raw))
            bus.notifyChanged()
        }
    }

    private fun loadList(conversationId: ConversationId): List<ContextInjection> = db.query(
        "SELECT $COLUMNS FROM injections WHERE conversation_id = ? ORDER BY position ASC, created_at ASC, id ASC",
        listOf(conversationId.raw),
        ::mapInjection,
    )

    private fun mapInjection(row: Row): ContextInjection {
        val id = row.string(0) ?: throw DbException("injection row without id")
        return ContextInjection(
            id = InjectionId(id),
            conversationId = ConversationId(row.string(1) ?: ""),
            anchorMessageId = row.string(2)?.let(::MessageId),
            scope = try {
                InjectionScope.valueOf(row.string(3) ?: "")
            } catch (e: IllegalArgumentException) {
                throw DbException("unknown injection scope for $id", e)
            },
            position = row.int(4),
            content = row.string(5) ?: "",
            createdAt = row.long(6),
        )
    }

    private companion object {
        const val TABLE = "injections"

        const val COLUMNS = "id, conversation_id, anchor_message_id, scope, position, content, created_at"
    }
}
