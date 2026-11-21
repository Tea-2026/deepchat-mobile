package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.ClockPort
import com.deepseek.mobilechat.application.log.TurnDraftStore
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [TurnDraftStore].
 *
 * Streaming scratch space only: one row per in-flight assistant message,
 * overwritten on every checkpoint and deleted when the turn settles. A draft is
 * never part of the log, so a crash leaves at most a stale draft the reconciler
 * can turn into an interrupted turn — never half an event.
 */
class SqliteTurnDraftStore(
    private val db: Db,
    private val clock: ClockPort,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TurnDraftStore {

    override suspend fun put(
        conversationId: ConversationId,
        messageId: MessageId,
        text: String,
        reasoning: String,
    ): Unit = withContext(io) {
        val updatedAt = clock.nowMillis()
        db.transaction {
            val exists = db.query(
                "SELECT 1 FROM $TABLE WHERE message_id = ? LIMIT 1",
                listOf(messageId.raw),
            ) { true }.isNotEmpty()
            if (exists) {
                db.exec(
                    "UPDATE $TABLE SET conversation_id = ?, text = ?, reasoning = ?, updated_at = ? " +
                        "WHERE message_id = ?",
                    listOf(conversationId.raw, text, reasoning, updatedAt, messageId.raw),
                )
            } else {
                db.exec(
                    "INSERT INTO $TABLE (message_id, conversation_id, text, reasoning, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    listOf(messageId.raw, conversationId.raw, text, reasoning, updatedAt),
                )
            }
        }
    }

    override suspend fun get(messageId: MessageId): TurnDraftStore.TurnDraft? = withContext(io) {
        db.queryOne(
            "SELECT $COLUMNS FROM $TABLE WHERE message_id = ?",
            listOf(messageId.raw),
            ::mapDraft,
        )
    }

    override suspend fun delete(messageId: MessageId): Unit = withContext(io) {
        db.exec("DELETE FROM $TABLE WHERE message_id = ?", listOf(messageId.raw))
    }

    override suspend fun all(): List<TurnDraftStore.TurnDraft> = withContext(io) {
        db.query(
            "SELECT $COLUMNS FROM $TABLE ORDER BY updated_at ASC, message_id ASC",
            emptyList(),
            ::mapDraft,
        )
    }

    private fun mapDraft(row: Row): TurnDraftStore.TurnDraft {
        val messageId = row.string(0) ?: throw DbException("turn draft row without message_id")
        return TurnDraftStore.TurnDraft(
            messageId = MessageId(messageId),
            conversationId = ConversationId(
                row.string(1) ?: throw DbException("turn draft $messageId has no conversation_id"),
            ),
            text = row.string(2) ?: "",
            reasoning = row.string(3) ?: "",
            updatedAt = row.long(4),
        )
    }

    private companion object {
        const val TABLE = "turn_drafts"

        const val COLUMNS = "message_id, conversation_id, text, reasoning, updated_at"
    }
}
