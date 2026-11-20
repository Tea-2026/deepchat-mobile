package com.deepseek.mobilechat.data.local.store

import com.deepseek.mobilechat.application.ConversationStore
import com.deepseek.mobilechat.data.local.bus.ChangeBus
import com.deepseek.mobilechat.data.local.codec.JsonCodecs
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Row
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * SQLite-backed [ConversationStore].
 *
 * `search` combines an FTS4 match over message bodies with a `title LIKE`
 * fallback in a single statement, so a conversation that matches both ways is
 * returned once (the `id IN (…) OR title LIKE …` predicate dedupes naturally).
 * Malformed FTS input (e.g. a query made only of operators) degrades to the
 * title fallback instead of surfacing an engine error.
 */
class SqliteConversationStore(
    private val db: Db,
    private val bus: ChangeBus,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ConversationStore {

    override fun observeAll(): Flow<List<Conversation>> = bus.observed(io) { loadAll() }

    override fun observe(id: ConversationId): Flow<Conversation?> = bus.observed(io) { loadOne(id) }

    override fun search(query: String): Flow<List<Conversation>> =
        bus.observed(io) { searchInternal(query) }

    override suspend fun get(id: ConversationId): Conversation? = withContext(io) { loadOne(id) }

    override suspend fun upsert(conversation: Conversation) {
        withContext(io) {
            val id = conversation.id.raw
            db.transaction {
                // Never INSERT OR REPLACE here: REPLACE is a DELETE + INSERT and
                // the FK cascade would wipe the whole message tree of that row.
                if (db.rowExists(TABLE, id)) {
                    db.exec(
                        """
                        UPDATE conversations
                        SET title = ?, active_leaf_id = ?, folder_id = ?, overrides_json = ?,
                            created_at = ?, updated_at = ?, pinned = ?
                        WHERE id = ?
                        """.trimIndent(),
                        listOf(
                            conversation.title,
                            conversation.activeLeafId?.raw,
                            conversation.folderId?.raw,
                            JsonCodecs.encodeOverrides(conversation.overrides),
                            conversation.createdAt,
                            conversation.updatedAt,
                            conversation.pinned.asInt(),
                            id,
                        ),
                    )
                } else {
                    db.exec(
                        """
                        INSERT INTO conversations
                            (id, title, active_leaf_id, folder_id, overrides_json,
                             created_at, updated_at, pinned)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        listOf(
                            id,
                            conversation.title,
                            conversation.activeLeafId?.raw,
                            conversation.folderId?.raw,
                            JsonCodecs.encodeOverrides(conversation.overrides),
                            conversation.createdAt,
                            conversation.updatedAt,
                            conversation.pinned.asInt(),
                        ),
                    )
                }

                db.exec("DELETE FROM conversation_tags WHERE conversation_id = ?", listOf(id))
                conversation.tagIds.forEach { tagId ->
                    db.exec(
                        "INSERT INTO conversation_tags(conversation_id, tag_id) VALUES (?, ?)",
                        listOf(id, tagId.raw),
                    )
                }
            }
            bus.notifyChanged()
        }
    }

    override suspend fun delete(id: ConversationId) {
        withContext(io) {
            val rawId = id.raw
            db.transaction {
                // Explicit cleanup keeps behaviour identical even if a caller
                // opens the database with foreign_keys off.
                db.exec(
                    "DELETE FROM messages WHERE conversation_id = ?",
                    listOf(rawId),
                )
                db.exec("DELETE FROM injections WHERE conversation_id = ?", listOf(rawId))
                db.exec("DELETE FROM compactions WHERE conversation_id = ?", listOf(rawId))
                db.exec("DELETE FROM conversation_tags WHERE conversation_id = ?", listOf(rawId))
                db.exec("DELETE FROM attachments WHERE conversation_id = ?", listOf(rawId))
                db.exec("DELETE FROM conversations WHERE id = ?", listOf(rawId))
            }
            bus.notifyChanged()
        }
    }

    // ------------------------------------------------------------------ reads

    private fun loadAll(): List<Conversation> =
        attachTags(
            db.query(
                "SELECT $COLUMNS FROM conversations ORDER BY pinned DESC, updated_at DESC, created_at DESC",
                emptyList(),
                ::mapConversation,
            ),
        )

    private fun loadOne(id: ConversationId): Conversation? {
        val conversation = db.queryOne(
            "SELECT $COLUMNS FROM conversations WHERE id = ?",
            listOf(id.raw),
            ::mapConversation,
        ) ?: return null
        return attachTags(listOf(conversation)).single()
    }

    private fun searchInternal(rawQuery: String): List<Conversation> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val likePattern = "%" + escapeLike(query) + "%"
        val ftsQuery = toFtsQuery(query)

        if (ftsQuery != null) {
            val combined = try {
                db.query(SEARCH_SQL, listOf(ftsQuery, likePattern), ::mapConversation)
            } catch (_: DbException) {
                null
            }
            if (combined != null) {
                if (combined.isNotEmpty()) return attachTags(combined)
            } else {
                // Malformed FTS input (e.g. only operators): title fallback only.
                val titleMatches = db.query(TITLE_SEARCH_SQL, listOf(likePattern), ::mapConversation)
                if (titleMatches.isNotEmpty()) return attachTags(titleMatches)
            }
        }

        // FTS4's unicode61 tokenizer has no word boundaries inside CJK runs, so a
        // Chinese substring never matches the index. When nothing else matched,
        // fall back to a plain-text substring scan (only on empty results, so the
        // hot path stays FTS).
        return attachTags(db.query(BODY_LIKE_SQL, listOf(likePattern), ::mapConversation))
    }

    private fun attachTags(conversations: List<Conversation>): List<Conversation> {
        if (conversations.isEmpty()) return conversations
        val ids = conversations.map { it.id.raw }
        val tagsByConversation = HashMap<String, MutableSet<TagId>>()
        ids.chunked(IN_CLAUSE_CHUNK).forEach { chunk ->
            db.query(
                "SELECT conversation_id, tag_id FROM conversation_tags WHERE conversation_id IN (${placeholders(chunk.size)})",
                chunk,
            ) { row ->
                val conversationId = row.string(0)
                val tagId = row.string(1)
                if (conversationId != null && tagId != null) {
                    tagsByConversation.getOrPut(conversationId) { LinkedHashSet() }.add(TagId(tagId))
                }
            }
        }
        return conversations.map { conversation ->
            val tags = tagsByConversation[conversation.id.raw]
            if (tags == null) conversation else conversation.copy(tagIds = tags.toSet())
        }
    }

    private fun mapConversation(row: Row): Conversation = Conversation(
        id = ConversationId(row.string(0) ?: throw DbException("conversation row without id")),
        title = row.string(1) ?: "",
        activeLeafId = row.string(2)?.let(::MessageId),
        folderId = row.string(3)?.let(::FolderId),
        overrides = try {
            JsonCodecs.decodeOverrides(row.string(4))
        } catch (e: IllegalArgumentException) {
            throw DbException("corrupt overrides_json for conversation ${row.string(0)}", e)
        },
        createdAt = row.long(5),
        updatedAt = row.long(6),
        pinned = row.int(7) != 0,
    )

    private companion object {
        const val TABLE = "conversations"

        const val COLUMNS =
            "id, title, active_leaf_id, folder_id, overrides_json, created_at, updated_at, pinned"

        const val SEARCH_SQL = """
            SELECT $COLUMNS FROM conversations
            WHERE id IN (
                SELECT m.conversation_id
                FROM messages m
                JOIN messages_fts ON messages_fts.rowid = m.rowid
                WHERE messages_fts MATCH ?
            )
            OR title LIKE ? ESCAPE '\'
            ORDER BY pinned DESC, updated_at DESC, created_at DESC
        """

        const val TITLE_SEARCH_SQL = """
            SELECT $COLUMNS FROM conversations
            WHERE title LIKE ? ESCAPE '\'
            ORDER BY pinned DESC, updated_at DESC, created_at DESC
        """

        const val BODY_LIKE_SQL = """
            SELECT $COLUMNS FROM conversations
            WHERE id IN (SELECT conversation_id FROM messages WHERE plain_text LIKE ? ESCAPE '\')
            ORDER BY pinned DESC, updated_at DESC, created_at DESC
        """

        fun Boolean.asInt(): Int = if (this) 1 else 0

        fun escapeLike(value: String): String = buildString(value.length + 8) {
            value.forEach { ch ->
                if (ch == '\\' || ch == '%' || ch == '_') append('\\')
                append(ch)
            }
        }

        /** Turns free text into an FTS4 AND query of quoted tokens. */
        fun toFtsQuery(raw: String): String? {
            val tokens = raw.split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" AND ") { token ->
                "\"" + token.replace("\"", "\"\"") + "\""
            }
        }

        val WHITESPACE = Regex("\\s+")
    }
}
