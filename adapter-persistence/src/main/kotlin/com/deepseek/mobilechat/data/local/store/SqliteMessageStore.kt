package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.MessageStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.codec.PartsCodec
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [MessageStore] for the message tree.
 *
 * The tree shape lives in `(conversation_id, parent_id, seq)` — nothing here
 * reorders branches; callers compute `seq` through `MessageTree`. `plain_text`
 * is derived from the ordered text parts on every write and feeds the FTS4
 * index via triggers.
 *
 * `conversation_id` and `created_at` are immutable after append, so [update]
 * rewrites only the mutable columns.
 */
class SqliteMessageStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MessageStore {

    override fun observe(conversationId: ConversationId): Flow<List<Message>> =
        bus.observed(io) { loadList(conversationId) }

    override suspend fun get(id: MessageId): Message? = withContext(io) {
        db.queryOne("SELECT $COLUMNS FROM messages WHERE id = ?", listOf(id.raw), ::mapMessage)
    }

    override suspend fun list(conversationId: ConversationId): List<Message> =
        withContext(io) { loadList(conversationId) }

    override suspend fun append(message: Message) {
        withContext(io) {
            db.exec(
                """
                INSERT INTO messages
                    (id, conversation_id, parent_id, seq, role, status, parts_json, plain_text,
                     created_at, edited_at, pinned, model_info_json, usage_json, finish_reason, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf<Any?>(message.id.raw) + argumentList(message),
            )
            bus.notifyChanged()
        }
    }

    override suspend fun update(message: Message) {
        withContext(io) {
            db.exec(
                """
                UPDATE messages
                SET parent_id = ?, seq = ?, role = ?, status = ?, parts_json = ?, plain_text = ?,
                    edited_at = ?, pinned = ?, model_info_json = ?, usage_json = ?,
                    finish_reason = ?, error = ?
                WHERE id = ?
                """.trimIndent(),
                listOf(
                    message.parentId?.raw,
                    message.seq,
                    message.role.name,
                    message.status.name,
                    PartsCodec.encode(message.parts),
                    PartsCodec.plainText(message.parts),
                    message.editedAt,
                    message.pinned.asInt(),
                    message.modelInfo?.let(JsonCodecs::encodeModelInfo),
                    message.usage?.let(JsonCodecs::encodeUsage),
                    message.finishReason?.name,
                    message.error,
                    message.id.raw,
                ),
            )
            bus.notifyChanged()
        }
    }

    override suspend fun remove(ids: Set<MessageId>) {
        withContext(io) {
            if (ids.isEmpty()) return@withContext
            db.transaction {
                ids.map { it.raw }.chunked(IN_CLAUSE_CHUNK).forEach { chunk ->
                    db.exec(
                        "DELETE FROM messages WHERE id IN (${placeholders(chunk.size)})",
                        chunk,
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    private fun loadList(conversationId: ConversationId): List<Message> =
        db.query(
            "SELECT $COLUMNS FROM messages WHERE conversation_id = ? ORDER BY created_at ASC, seq ASC",
            listOf(conversationId.raw),
            ::mapMessage,
        )

    private fun mapMessage(row: Row): Message {
        val id = row.string(0) ?: throw DbException("message row without id")
        val parts = try {
            PartsCodec.decode(row.string(6))
        } catch (e: IllegalArgumentException) {
            throw DbException("corrupt parts_json for message $id", e)
        }
        return Message(
            id = MessageId(id),
            conversationId = ConversationId(
                row.string(1) ?: throw DbException("message $id has no conversation"),
            ),
            parentId = row.string(2)?.let(::MessageId),
            seq = row.long(3),
            role = parseEnum(row.string(4), "role", id),
            parts = parts,
            status = parseEnum(row.string(5), "status", id),
            createdAt = row.long(8),
            editedAt = if (row.isNull(9)) null else row.long(9),
            pinned = row.int(10) != 0,
            modelInfo = try {
                JsonCodecs.decodeModelInfo(row.string(11))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt model_info_json for message $id", e)
            },
            usage = try {
                JsonCodecs.decodeUsage(row.string(12))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt usage_json for message $id", e)
            },
            finishReason = row.string(13)?.let { parseEnum<FinishReason>(it, "finish_reason", id) },
            error = row.string(14),
        )
    }

    private fun argumentList(message: Message): List<Any?> = listOf(
        message.conversationId.raw,
        message.parentId?.raw,
        message.seq,
        message.role.name,
        message.status.name,
        PartsCodec.encode(message.parts),
        PartsCodec.plainText(message.parts),
        message.createdAt,
        message.editedAt,
        message.pinned.asInt(),
        message.modelInfo?.let(JsonCodecs::encodeModelInfo),
        message.usage?.let(JsonCodecs::encodeUsage),
        message.finishReason?.name,
        message.error,
    )

    private inline fun <reified E : Enum<E>> parseEnum(value: String?, field: String, messageId: String): E {
        val raw = value ?: throw DbException("message $messageId has no $field")
        return try {
            enumValueOf<E>(raw)
        } catch (e: IllegalArgumentException) {
            throw DbException("message $messageId has unknown $field '$raw'", e)
        }
    }

    private companion object {
        const val COLUMNS =
            "id, conversation_id, parent_id, seq, role, status, parts_json, plain_text, " +
                "created_at, edited_at, pinned, model_info_json, usage_json, finish_reason, error"

        fun Boolean.asInt(): Int = if (this) 1 else 0
    }
}
