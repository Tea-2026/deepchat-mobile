package com.deepseek.mobilechat.data.local.db

import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.codec.PartsCodec
import com.deepseek.mobilechat.data.local.log.assistantFinishReason
import com.deepseek.mobilechat.data.local.log.verifyMigration
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.LogFormatException
import com.deepseek.mobilechat.domain.log.SessionEvent
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.SurfaceOp
import com.deepseek.mobilechat.domain.log.Transcript
import com.deepseek.mobilechat.domain.log.TranscriptProjector

/**
 * v3 -> v4 history conversion: legacy `messages` rows into one event log per
 * conversation.
 *
 * Order per conversation (the order the brief freezes):
 *
 *  1. `session/title` from `conversations.title`;
 *  2. one `user/message` / `assistant/message` per legacy row, in
 *     `(parentId, seq)` topological order, carrying the row's original id,
 *     parent, parts, usage, modelInfo, finishReason and `created_at` as the
 *     event time, with `surfaceOp = Append` (append-origin);
 *  3. `leaf/changed` from `conversations.active_leaf_id`;
 *  4. for each legacy compaction that is still active (`undone_at IS NULL`), a
 *     `compaction/start` + a stand-in `user/message` (`surfaceOp = Replace`) +
 *     `compaction/end`.
 *
 * `seq` starts at 1 per conversation and has no holes.
 *
 * **Fail-closed.** After building the events the conversion is replayed with
 * [TranscriptProjector]/[SurfaceFolder.fold] and compared against the old rows
 * with [verifyMigration]. Any difference throws [DbException], which rolls back
 * the enclosing upgrade and leaves the legacy tables untouched (`user_version`
 * stays 3). Silently dropping a row is never an option.
 *
 * Each conversation runs inside its own `db.transaction`, so the unit is one
 * conversation. `Schema.migrate` and Android's `SQLiteOpenHelper.onUpgrade`
 * already own an outer transaction, and SQLite has no nested commit: the nested
 * blocks join it, making the whole upgrade atomic (stronger than per-conversation
 * durability, not weaker). See the note in `Schema.migrateV3ToV4`.
 */
internal object LegacyLogMigration {

    private val SIBLING_ORDER: Comparator<Message> =
        compareBy({ it.seq }, { it.createdAt }, { it.id.raw })

    fun run(db: Db) {
        val conversations = db.query(
            """
            SELECT id, title, active_leaf_id, created_at, updated_at
            FROM conversations
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
            emptyList(),
        ) { row ->
            LegacyConversation(
                id = row.string(0) ?: throw DbException("conversation row without id"),
                title = row.string(1) ?: "",
                activeLeafId = row.string(2),
                createdAt = row.long(3),
                updatedAt = row.long(4),
            )
        }

        conversations.forEach { conversation ->
            db.transaction {
                // A hand-rolled `user_version` rollback can re-run this step.
                // A conversation that already has events was migrated before;
                // leave it alone rather than duplicating seq 1..N.
                if (!hasEvents(db, conversation.id)) {
                    migrateConversation(db, conversation)
                }
            }
        }
    }

    private fun hasEvents(db: Db, conversationId: String): Boolean =
        db.query(
            "SELECT 1 FROM ${ConversationEventRows.TABLE} WHERE conversation_id = ? LIMIT 1",
            listOf(conversationId),
        ) { true }.isNotEmpty()

    private fun migrateConversation(db: Db, conversation: LegacyConversation) {
        val legacy = loadMessages(db, conversation.id)
        val ordered = topologicalOrder(legacy)

        val builder = EventBuilder(conversation.id)
        builder.title(conversation.title, conversation.createdAt)

        val seqByMessage = LinkedHashMap<String, Long>(legacy.size * 2)
        ordered.forEach { message ->
            seqByMessage[message.id.raw] = builder.message(message)
        }
        builder.leaf(conversation.activeLeafId, conversation.updatedAt)

        val shadowed = HashSet<Long>()
        loadCompactions(db, conversation.id).forEach { compaction ->
            builder.compaction(compaction, seqByMessage, shadowed)
        }

        val log = ConversationLog(ConversationId(conversation.id), builder.events)
        verify(conversation, ordered, log)

        builder.events.forEach { event ->
            db.insertConversationEvent(conversation.id, event)
        }
    }

    /** Replay + compare. Throws [DbException] on the first conversation that is not faithful. */
    private fun verify(
        conversation: LegacyConversation,
        legacy: List<Message>,
        log: ConversationLog,
    ) {
        val problems = ArrayList<String>(verifyMigration(ConversationId(conversation.id), legacy, log))

        var transcript: Transcript? = null
        if (problems.isEmpty()) {
            transcript = try {
                TranscriptProjector.project(log)
            } catch (e: LogFormatException) {
                problems += "projection failed: ${e.message}"
                null
            }
        }
        if (transcript != null) {
            if (transcript.title != conversation.title) {
                problems += "title differs: legacy='${conversation.title}', log='${transcript.title}'"
            }
            val expectedLeaf = conversation.activeLeafId
            if (expectedLeaf != null && transcript.activeLeafId?.raw != expectedLeaf) {
                problems += "activeLeafId differs: legacy=$expectedLeaf, log=${transcript.activeLeafId?.raw}"
            }
        }
        try {
            SurfaceFolder.fold(log)
        } catch (e: LogFormatException) {
            problems += "surface fold failed: ${e.message}"
        }

        if (problems.isNotEmpty()) {
            throw DbException(
                "v3 -> v4 migration of conversation ${conversation.id} is not faithful; " +
                    "the upgrade was rolled back: " + problems.joinToString("; "),
            )
        }
    }

    // ------------------------------------------------------------------ input

    private fun loadMessages(db: Db, conversationId: String): List<Message> = db.query(
        """
        SELECT id, parent_id, seq, role, status, parts_json, created_at, edited_at, pinned,
               model_info_json, usage_json, finish_reason, error
        FROM messages
        WHERE conversation_id = ?
        """.trimIndent(),
        listOf(conversationId),
    ) { row ->
        val id = row.string(0) ?: throw DbException("message row without id")
        val parts = try {
            PartsCodec.decode(row.string(5))
        } catch (e: IllegalArgumentException) {
            throw DbException("corrupt parts_json for message $id", e)
        }
        Message(
            id = MessageId(id),
            conversationId = ConversationId(conversationId),
            parentId = row.string(1)?.let(::MessageId),
            seq = row.long(2),
            role = parseEnum(row.string(3), "role", id),
            status = parseEnum(row.string(4), "status", id),
            parts = parts,
            createdAt = row.long(6),
            editedAt = if (row.isNull(7)) null else row.long(7),
            pinned = row.int(8) != 0,
            modelInfo = try {
                JsonCodecs.decodeModelInfo(row.string(9))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt model_info_json for message $id", e)
            },
            usage = try {
                JsonCodecs.decodeUsage(row.string(10))
            } catch (e: IllegalArgumentException) {
                throw DbException("corrupt usage_json for message $id", e)
            },
            finishReason = row.string(11)?.let { parseEnum<FinishReason>(it, "finish_reason", id) },
            error = row.string(12),
        )
    }

    private fun loadCompactions(db: Db, conversationId: String): List<LegacyCompaction> {
        if (!tableExists(db, "compactions")) return emptyList()
        return db.query(
            """
            SELECT id, from_message_id, to_message_id, summary, kind, created_at
            FROM compactions
            WHERE conversation_id = ? AND undone_at IS NULL
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
            listOf(conversationId),
        ) { row ->
            LegacyCompaction(
                id = row.string(0) ?: throw DbException("compaction row without id"),
                fromMessageId = row.string(1) ?: "",
                toMessageId = row.string(2) ?: "",
                summary = row.string(3) ?: "",
                kind = row.string(4) ?: "SUMMARY",
                createdAt = row.long(5),
            )
        }
    }

    private fun tableExists(db: Db, table: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf(table),
        ) { true }.isNotEmpty()

    // -------------------------------------------------------------- ordering

    /**
     * Parent-before-child order, siblings by `(seq, createdAt, id)`.
     *
     * This is the order [EventBuilder] assigns `seq` in, so the log's global
     * order is the computed topological order, not `created_at` order (a branch
     * created by an edit can be older than a reply that landed in between).
     * Messages whose parent is missing are treated as roots; cycles, which the
     * old tree allowed to persist but no UI could render, are appended last in
     * deterministic order so no row is ever dropped.
     */
    private fun topologicalOrder(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        val byId = messages.associateBy { it.id }
        val children = LinkedHashMap<MessageId?, MutableList<Message>>()
        messages.forEach { message ->
            children.getOrPut(message.parentId) { mutableListOf() } += message
        }
        children.values.forEach { siblings -> siblings.sortWith(SIBLING_ORDER) }

        val ordered = ArrayList<Message>(messages.size)
        val visited = HashSet<MessageId>()

        fun visit(message: Message) {
            if (!visited.add(message.id)) return
            ordered += message
            children[message.id]?.forEach { child -> visit(child) }
        }

        children[null]?.forEach { root -> visit(root) }

        // Roots whose parent row is absent from this conversation.
        messages
            .filter { it.parentId != null && it.parentId !in byId }
            .sortedWith(SIBLING_ORDER)
            .forEach { orphan -> visit(orphan) }

        // Cycle leftovers (should never happen; keep the rows anyway).
        messages.sortedWith(SIBLING_ORDER).forEach { leftover -> visit(leftover) }

        return ordered
    }

    // --------------------------------------------------------------- builder

    private class EventBuilder(private val conversationId: String) {

        val events = ArrayList<SessionEvent>()

        private var seq = 0L

        fun title(title: String, time: Long) {
            add(
                type = EventType.SESSION_TITLE,
                time = time,
                data = EventData.SessionTitle(title = title, source = "migrated"),
            )
        }

        fun message(message: Message): Long = when (message.role) {
            MessageRole.USER -> add(
                type = EventType.USER_MESSAGE,
                time = message.createdAt,
                data = EventData.UserMessage(
                    messageId = message.id,
                    parentId = message.parentId,
                    parts = message.parts,
                ),
                surfaceOp = SurfaceOp.Append,
            )

            MessageRole.ASSISTANT -> add(
                type = EventType.ASSISTANT_MESSAGE,
                time = message.createdAt,
                data = EventData.AssistantMessage(
                    messageId = message.id,
                    parentId = message.parentId,
                    parts = message.parts,
                    modelInfo = message.modelInfo,
                    usage = message.usage,
                    finishReason = assistantFinishReason(message),
                    citations = emptyList(),
                ),
                surfaceOp = SurfaceOp.Append,
            )

            MessageRole.SYSTEM, MessageRole.TOOL -> throw DbException(
                "cannot migrate message ${message.id.raw}: role ${message.role} has no event " +
                    "representation in the frozen vocabulary (only user/message and " +
                    "assistant/message can reproduce a transcript row)",
            )
        }

        fun leaf(messageId: String?, time: Long) {
            add(
                type = EventType.LEAF_CHANGED,
                time = time,
                data = EventData.LeafChanged(messageId = messageId?.let(::MessageId)),
            )
        }

        /**
         * Returns the stand-in's seq, or `null` when the legacy compaction cannot
         * be represented on the current surface (missing endpoint, reversed or
         * already-shadowed range). Such a compaction is skipped rather than
         * corrupting the log; the legacy `compactions` row is still there for the
         * one-version read-only rollback window.
         */
        fun compaction(
            compaction: LegacyCompaction,
            seqByMessage: Map<String, Long>,
            shadowed: MutableSet<Long>,
        ): Long? {
            val fromSeq = seqByMessage[compaction.fromMessageId] ?: return null
            val toSeq = seqByMessage[compaction.toMessageId] ?: return null
            if (fromSeq > toSeq) return null

            // The message block is contiguous (title is seq 1, messages follow,
            // leaf is after them), so every seq in the range is a live surface
            // node unless an earlier compaction already shadowed it.
            val range = (fromSeq..toSeq).toList()
            if (range.any { it in shadowed }) return null

            val standInId = "compaction:${compaction.id}"
            if (seqByMessage.containsKey(standInId)) return null

            val compactionId = CompactionId(compaction.id)
            add(
                type = EventType.COMPACTION_START,
                time = compaction.createdAt,
                data = EventData.CompactionStart(
                    compactionId = compactionId,
                    strategyId = "legacy:${compaction.kind.lowercase()}",
                    targetFromSeq = fromSeq,
                    targetToSeq = toSeq,
                    // The legacy table never recorded token counts.
                    tokensBefore = 0,
                ),
            )
            val standInSeq = add(
                type = EventType.USER_MESSAGE,
                time = compaction.createdAt,
                data = EventData.UserMessage(
                    messageId = MessageId(standInId),
                    parentId = MessageId(compaction.toMessageId),
                    parts = listOf(MessagePart.Text(compaction.summary)),
                    compactionId = compactionId,
                ),
                surfaceOp = SurfaceOp.Replace(fromSeq, toSeq),
                sourceEventSeqs = range,
            )
            add(
                type = EventType.COMPACTION_END,
                time = compaction.createdAt,
                data = EventData.CompactionEnd(
                    compactionId = compactionId,
                    summarySeq = standInSeq,
                    tokensAfter = 0,
                ),
            )
            shadowed += range
            return standInSeq
        }

        private fun add(
            type: EventType,
            time: Long,
            data: EventData,
            surfaceOp: SurfaceOp? = null,
            sourceEventSeqs: List<Long> = emptyList(),
        ): Long {
            val next = ++seq
            val event = SessionEvent(
                seq = next,
                eventId = "legacy:$conversationId:$next",
                type = type,
                time = time,
                data = data,
                surfaceOp = surfaceOp,
                sourceEventSeqs = sourceEventSeqs,
            )
            event.validate()
            events += event
            return next
        }
    }

    private inline fun <reified E : Enum<E>> parseEnum(value: String?, field: String, messageId: String): E {
        val raw = value ?: throw DbException("message $messageId has no $field")
        return try {
            enumValueOf<E>(raw)
        } catch (e: IllegalArgumentException) {
            throw DbException("message $messageId has unknown $field '$raw'", e)
        }
    }

    private data class LegacyConversation(
        val id: String,
        val title: String,
        val activeLeafId: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class LegacyCompaction(
        val id: String,
        val fromMessageId: String,
        val toMessageId: String,
        val summary: String,
        val kind: String,
        val createdAt: Long,
    )
}
