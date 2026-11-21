package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.application.log.ConversationLogStore
import com.deepseek.mobilechat.data.local.codec.SnapshotCodec
import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.queryOne
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.log.Surface
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.Transcript
import com.deepseek.mobilechat.domain.log.TranscriptProjector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two fold products cached together, always at one exact log version. */
data class ConversationProjection(
    val conversationId: ConversationId,
    val version: Long,
    val surface: Surface,
    val transcript: Transcript,
)

/**
 * Projection cache (the `conversation_snapshots` table).
 *
 * Cache purity is the contract: [get] returns a projection only for the exact
 * requested [version]; a missing row, an older row, a newer row or a row that no
 * longer decodes all read as a miss and the caller folds the log instead.
 * Correctness never depends on a hit.
 */
interface ConversationSnapshotCache {
    suspend fun get(conversationId: ConversationId, version: Long): ConversationProjection?

    /** Highest cached version, or `null` when the conversation has no snapshot. */
    suspend fun latestVersion(conversationId: ConversationId): Long?

    suspend fun put(projection: ConversationProjection)

    suspend fun delete(conversationId: ConversationId)
}

/**
 * When to spend a snapshot write.
 *
 * A conversation is folded every time it is opened unless a cache row exists for
 * exactly the current version. Writing on every append would turn the log into a
 * cache-write amplifier, so the policy writes after an interval of appends
 * ([eventThreshold], 200 by default) — i.e. at version 200, 400, … for a fresh
 * conversation — or on demand when a caller knows it is about to do something
 * expensive and asks for one ([force]).
 */
class SnapshotPolicy(val eventThreshold: Int = DEFAULT_THRESHOLD) {

    init {
        require(eventThreshold > 0) { "eventThreshold must be > 0, was $eventThreshold" }
    }

    fun shouldWrite(lastVersion: Long, newVersion: Long, force: Boolean = false): Boolean =
        force || newVersion - lastVersion >= eventThreshold

    companion object {
        const val DEFAULT_THRESHOLD: Int = 200
    }
}

/** SQLite-backed [ConversationSnapshotCache]; one row per (conversation, version). */
class SqliteConversationSnapshotCache(
    private val db: Db,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ConversationSnapshotCache {

    override suspend fun get(
        conversationId: ConversationId,
        version: Long,
    ): ConversationProjection? = withContext(io) {
        val row = db.queryOne(
            "SELECT surface_json, transcript_json FROM $TABLE " +
                "WHERE conversation_id = ? AND version = ?",
            listOf(conversationId.raw, version),
        ) { row -> row.string(0) to row.string(1) } ?: return@withContext null

        return@withContext try {
            ConversationProjection(
                conversationId = conversationId,
                version = version,
                surface = SnapshotCodec.decodeSurface(row.first.orEmpty()),
                transcript = SnapshotCodec.decodeTranscript(row.second.orEmpty()),
            )
        } catch (_: IllegalArgumentException) {
            // A cache row that no longer parses is a miss, not an error. The
            // log still has the truth; the caller will fold it again.
            null
        }
    }

    override suspend fun latestVersion(conversationId: ConversationId): Long? = withContext(io) {
        db.queryOne(
            "SELECT MAX(version) FROM $TABLE WHERE conversation_id = ?",
            listOf(conversationId.raw),
        ) { row -> if (row.isNull(0)) null else row.long(0) }
    }

    override suspend fun put(projection: ConversationProjection): Unit = withContext(io) {
        val args = listOf(
            SnapshotCodec.encodeSurface(projection.surface),
            SnapshotCodec.encodeTranscript(projection.transcript),
        )
        db.transaction {
            val exists = db.query(
                "SELECT 1 FROM $TABLE WHERE conversation_id = ? AND version = ?",
                listOf(projection.conversationId.raw, projection.version),
            ) { true }.isNotEmpty()
            if (exists) {
                db.exec(
                    "UPDATE $TABLE SET surface_json = ?, transcript_json = ? " +
                        "WHERE conversation_id = ? AND version = ?",
                    args + listOf(projection.conversationId.raw, projection.version),
                )
            } else {
                db.exec(
                    "INSERT INTO $TABLE (conversation_id, version, surface_json, transcript_json) " +
                        "VALUES (?, ?, ?, ?)",
                    listOf(projection.conversationId.raw, projection.version) + args,
                )
            }
        }
    }

    override suspend fun delete(conversationId: ConversationId): Unit = withContext(io) {
        db.exec("DELETE FROM $TABLE WHERE conversation_id = ?", listOf(conversationId.raw))
    }

    private companion object {
        const val TABLE = "conversation_snapshots"
    }
}

/**
 * Folds a log into its projections, using the cache when — and only when — it
 * has a row for the exact current version.
 *
 * The `else` path is the normal path: read the log, fold it, then maybe write a
 * snapshot according to [policy]. No caller may assume a cache row exists.
 */
class ConversationProjector(
    private val logs: ConversationLogStore,
    private val cache: ConversationSnapshotCache,
    private val policy: SnapshotPolicy = SnapshotPolicy(),
) {

    suspend fun project(
        conversationId: ConversationId,
        forceSnapshot: Boolean = false,
    ): ConversationProjection {
        val log = logs.log(conversationId)
        cache.get(conversationId, log.version)?.let { return it }

        val projection = ConversationProjection(
            conversationId = conversationId,
            version = log.version,
            surface = SurfaceFolder.fold(log),
            transcript = TranscriptProjector.project(log),
        )

        // `?: 0` is deliberate: "no snapshot yet" means "the whole log is new
        // since the last snapshot", so a conversation that already has 200+
        // events gets one on its first open instead of never.
        val lastVersion = cache.latestVersion(conversationId) ?: 0L
        if (policy.shouldWrite(lastVersion, log.version, forceSnapshot)) {
            cache.put(projection)
        }
        return projection
    }
}
