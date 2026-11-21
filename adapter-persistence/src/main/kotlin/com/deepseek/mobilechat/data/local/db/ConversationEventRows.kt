package com.deepseek.mobilechat.data.local.db

import com.deepseek.mobilechat.data.local.codec.EventCodec
import com.deepseek.mobilechat.domain.log.SessionEvent

/**
 * Shared wiring for the `conversation_events` table.
 *
 * Both the runtime store and the v3 -> v4 migration insert rows; keeping the
 * statement and the column order here means the two paths cannot drift apart.
 * The order is the DDL order from `docs/architecture-v2-log.md` §13.5.
 */
internal object ConversationEventRows {

    const val TABLE: String = "conversation_events"

    /** Columns after `conversation_id`, in DDL order. */
    const val COLUMNS: String =
        "seq, event_id, type, schema_version, time, ignorable, surface_op_json, " +
            "source_seqs_json, data_json"

    const val INSERT_SQL: String =
        "INSERT INTO $TABLE (conversation_id, $COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
}

/** Inserts one event. The caller owns the enclosing transaction. */
internal fun Db.insertConversationEvent(conversationId: String, event: SessionEvent) {
    exec(
        ConversationEventRows.INSERT_SQL,
        listOf(
            conversationId,
            event.seq,
            event.eventId,
            event.type.wire,
            event.schemaVersion,
            event.time,
            if (event.ignorable) 1 else 0,
            EventCodec.encodeSurfaceOp(event.surfaceOp),
            EventCodec.encodeSourceSeqs(event.sourceEventSeqs),
            EventCodec.encodeData(event.data),
        ),
    )
}
