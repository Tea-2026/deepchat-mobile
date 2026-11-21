package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.log.ConversationIndexPatch
import com.deepseek.mobilechat.application.log.ConversationLogStore
import com.deepseek.mobilechat.application.log.PendingEvent
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.codec.EventCodec
import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.db.ConversationEventRows
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.insertConversationEvent
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.LogFormatException
import com.deepseek.mobilechat.domain.log.SessionEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [ConversationLogStore].
 *
 * The `conversation_events` table is the only truth for a conversation's
 * history. `append` owns the sequence numbers: both the `MAX(seq)` read and the
 * inserts run in one transaction, so two writers can never interleave inside a
 * version. The `conversations` row is an index written in that same transaction
 * (when [ConversationIndexPatch] is supplied): a committed append and the list
 * entry that points at it are therefore observed together or not at all.
 *
 * Validation happens *after* seq assignment and before any insert: a batch that
 * violates the event contract (a `surfaceOp` on a record type, a replace range
 * that references the future, a payload that does not match its type) is
 * rejected as `AppError.Validation` with nothing written.
 */
class SqliteConversationLogStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ConversationLogStore {

    override suspend fun append(
        conversationId: ConversationId,
        events: List<PendingEvent>,
        index: ConversationIndexPatch?,
    ): Outcome<Long> = withContext(io) {
        try {
            val newVersion = db.transaction {
                var seq = currentMaxSeq(conversationId)
                val stamped = ArrayList<SessionEvent>(events.size)
                events.forEach { pending ->
                    seq += 1
                    stamped += SessionEvent(
                        seq = seq,
                        eventId = pending.eventId,
                        type = pending.type,
                        time = pending.time,
                        data = pending.data,
                        schemaVersion = pending.schemaVersion,
                        surfaceOp = pending.surfaceOp,
                        sourceEventSeqs = pending.sourceEventSeqs,
                    )
                }
                // Local rules only (C1 contiguity is implied by the assignment
                // above); cross-event surface rules are the fold's job.
                stamped.forEach { it.validate() }
                stamped.forEach { event -> db.insertConversationEvent(conversationId.raw, event) }
                if (index != null) applyIndexPatch(conversationId, index, stamped)
                seq
            }
            bus.notifyChanged()
            Outcome.Ok(newVersion)
        } catch (e: LogFormatException) {
            Outcome.Err(AppError.Validation(e.message ?: "invalid event batch"))
        } catch (e: DbException) {
            Outcome.Err(AppError.Storage(e.message ?: "could not append conversation events"))
        }
    }

    override suspend fun log(conversationId: ConversationId): ConversationLog = withContext(io) {
        val events = db.query(
            "SELECT ${ConversationEventRows.COLUMNS} FROM ${ConversationEventRows.TABLE} " +
                "WHERE conversation_id = ? ORDER BY seq ASC",
            listOf(conversationId.raw),
        ) { row ->
            val seq = row.long(0)
            val eventId = row.string(1) ?: throw LogFormatException("event at seq $seq has no event_id")
            val wire = row.string(2) ?: throw LogFormatException("event at seq $seq has no type")
            val type = EventType.fromWire(wire)
                ?: throw LogFormatException("event at seq $seq has unknown type '$wire'")
            val schemaVersion = row.int(3)
            if (schemaVersion > SUPPORTED_EVENT_SCHEMA_VERSION) {
                throw LogFormatException(
                    "event at seq $seq has payload version $schemaVersion; " +
                        "this build understands up to $SUPPORTED_EVENT_SCHEMA_VERSION",
                )
            }
            val dataJson = row.string(8) ?: throw LogFormatException("event at seq $seq has no data_json")
            val data = try {
                EventCodec.decodeData(type, dataJson)
            } catch (e: IllegalArgumentException) {
                throw LogFormatException("event at seq $seq (${type.wire}) has an invalid payload: ${e.message}")
            }
            SessionEvent(
                seq = seq,
                eventId = eventId,
                type = type,
                time = row.long(4),
                data = data,
                schemaVersion = schemaVersion,
                ignorable = row.int(5) != 0,
                surfaceOp = try {
                    EventCodec.decodeSurfaceOp(row.string(6))
                } catch (e: IllegalArgumentException) {
                    throw LogFormatException("event at seq $seq has an invalid surface_op_json: ${e.message}")
                },
                sourceEventSeqs = try {
                    EventCodec.decodeSourceSeqs(row.string(7))
                } catch (e: IllegalArgumentException) {
                    throw LogFormatException("event at seq $seq has invalid source_seqs_json: ${e.message}")
                },
            )
        }

        events.forEach { it.validate() }
        ConversationLog(conversationId, events)
    }

    override suspend fun delete(conversationId: ConversationId): Outcome<Unit> = withContext(io) {
        try {
            db.transaction {
                db.exec(
                    "DELETE FROM ${ConversationEventRows.TABLE} WHERE conversation_id = ?",
                    listOf(conversationId.raw),
                )
                db.exec(
                    "DELETE FROM conversation_snapshots WHERE conversation_id = ?",
                    listOf(conversationId.raw),
                )
                // Streaming scratch rows are keyed by message, not by
                // conversation; they would otherwise outlive the conversation
                // they belong to.
                db.exec(
                    "DELETE FROM turn_drafts WHERE conversation_id = ?",
                    listOf(conversationId.raw),
                )
                // The legacy table is read-only for the rest of this version,
                // but deleting a conversation still has to take its old rows
                // with it so a later export/rollback cannot resurrect them.
                db.exec("DELETE FROM messages WHERE conversation_id = ?", listOf(conversationId.raw))
            }
            bus.notifyChanged()
            Outcome.Ok(Unit)
        } catch (e: DbException) {
            Outcome.Err(AppError.Storage(e.message ?: "could not delete conversation events"))
        }
    }

    private fun currentMaxSeq(conversationId: ConversationId): Long =
        db.queryOne(
            "SELECT MAX(seq) FROM ${ConversationEventRows.TABLE} WHERE conversation_id = ?",
            listOf(conversationId.raw),
        ) { row -> if (row.isNull(0)) 0L else row.long(0) } ?: 0L

    /**
     * Writes the conversation-list index in the append transaction.
     *
     * A patch without a `conversations` row is an error, not a no-op: committing
     * events for a conversation the list cannot see would be exactly the
     * "log says one thing, index says another" split this table exists to
     * prevent. `null` fields mean "leave this column alone". The patch type
     * cannot express "clear the active leaf" (a nullable field cannot
     * distinguish absent from null), so the leaf is only ever advanced here;
     * clearing it remains a `leaf/changed` event on the log. `updatedAt`
     * defaults to the newest appended event's timestamp so the list sorts by
     * activity even when a caller does not bother to pass it.
     */
    private fun applyIndexPatch(
        conversationId: ConversationId,
        patch: ConversationIndexPatch,
        appended: List<SessionEvent>,
    ) {
        val id = conversationId.raw
        val existingJson = db.queryOne(
            "SELECT overrides_json FROM conversations WHERE id = ?",
            listOf(id),
        ) { row -> row.string(0) } ?: throw DbException("conversation $id has no index row")

        val existing = try {
            JsonCodecs.decodeOverrides(existingJson)
        } catch (e: IllegalArgumentException) {
            throw DbException("corrupt overrides_json for conversation $id", e)
        }
        val merged = existing.copy(
            modelRef = patch.modelRef ?: existing.modelRef,
            thinkingLevel = patch.thinkingLevel ?: existing.thinkingLevel,
        )
        val updatedAt = patch.updatedAt ?: appended.maxOfOrNull { it.time }

        db.exec(
            """
            UPDATE conversations
            SET title = COALESCE(?, title),
                active_leaf_id = COALESCE(?, active_leaf_id),
                updated_at = COALESCE(?, updated_at),
                overrides_json = ?
            WHERE id = ?
            """.trimIndent(),
            listOf(
                patch.title,
                patch.activeLeafId?.raw,
                updatedAt,
                JsonCodecs.encodeOverrides(merged),
                id,
            ),
        )
    }

    private companion object {
        /**
         * The newest event payload shape this build can decode. A row above it
         * means the log was written by a newer app; guessing would lose data,
         * so opening the conversation fails instead (invariant I12).
         */
        const val SUPPORTED_EVENT_SCHEMA_VERSION: Int = EventCodec.SCHEMA_VERSION
    }
}
