package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.UsageTotals
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.log.ConversationLog
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceOp
import com.deepseek.mobilechat.domain.log.TranscriptNode
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Log storage
// ---------------------------------------------------------------------------

/**
 * An event that has not been given a `seq` yet: `seq` is assigned by the store
 * inside the same transaction that appends it, so a batch can never interleave
 * with another writer.
 */
data class PendingEvent(
    val eventId: String,
    val type: EventType,
    val time: Long,
    val data: EventData,
    val surfaceOp: SurfaceOp? = null,
    val sourceEventSeqs: List<Long> = emptyList(),
    val schemaVersion: Int = 1,
)

/**
 * The parts of the conversation *index* that live in the `conversations` table.
 *
 * The log is the truth for history; this row is a rebuildable index used by the
 * conversation list. It is written in the same transaction as the events so the
 * list and the transcript can never disagree about a version.
 */
data class ConversationIndexPatch(
    val title: String? = null,
    val activeLeafId: MessageId? = null,
    val updatedAt: Long? = null,
    val modelRef: ModelRef? = null,
    val thinkingLevel: ThinkingLevel? = null,
)

interface ConversationLogStore {
    /**
     * Appends [events] atomically and returns the resulting version.
     *
     * Contract: either every event and the index patch are durable, or nothing is.
     */
    suspend fun append(
        conversationId: ConversationId,
        events: List<PendingEvent>,
        index: ConversationIndexPatch? = null,
    ): Outcome<Long>

    /** The whole log, in seq order. Empty when the conversation has no events. */
    suspend fun log(conversationId: ConversationId): ConversationLog

    /** Drops the log of one conversation (used by delete). */
    suspend fun delete(conversationId: ConversationId): Outcome<Unit>
}

/** Streaming scratch space; never part of the log. */
interface TurnDraftStore {
    suspend fun put(conversationId: ConversationId, messageId: MessageId, text: String, reasoning: String)
    suspend fun get(messageId: MessageId): TurnDraft?
    suspend fun delete(messageId: MessageId): Unit
    suspend fun all(): List<TurnDraft>

    data class TurnDraft(
        val messageId: MessageId,
        val conversationId: ConversationId,
        val text: String,
        val reasoning: String,
        val updatedAt: Long,
    )
}

// ---------------------------------------------------------------------------
// UI contract
// ---------------------------------------------------------------------------

/**
 * What the UI is allowed to know. One immutable value per [version]; every field
 * below comes from the same log prefix, which is what makes "the transcript and
 * the leaf disagree" impossible to express.
 */
data class ChatSnapshot(
    val version: Long,
    val conversationId: ConversationId,
    val title: String,
    /**
     * Every message of every branch, append-order.
     *
     * While a turn is streaming, this **also contains exactly one synthetic
     * message** with `status = STREAMING` for the in-flight assistant turn: it is
     * derived from `Overlay.streaming`, not from the log (invariant I8 — the log
     * never holds half a turn). It exists so the UI can place the streaming row on
     * the correct branch, which matters most during regenerate: the old answer
     * stays on the active path until the new one lands.
     */
    val messages: List<Message>,
    /**
     * The leaf the UI should draw the active path through — **not** the log's leaf.
     *
     * While a turn is in flight this equals the streaming message id (see
     * [Overlay.streaming]), so the in-flight reply is on the active path and gets
     * rendered. The log's own leaf only moves when the turn completes (invariant
     * I10), which is what keeps the previous answer on screen during the assembly
     * window instead of blanking the transcript.
     */
    val activeLeafId: MessageId?,
    /**
     * Human transcript: messages + compaction dividers, append-origin only, plus
     * the same synthetic streaming row described above.
     */
    val transcript: List<TranscriptNode>,
    val overlay: Overlay,
    val totals: UsageTotals,
    val modelRef: ModelRef? = null,
    val thinkingLevel: ThinkingLevel? = null,
)

data class StreamingTurn(
    val messageId: MessageId,
    val text: String,
    val reasoning: String,
)

enum class BusyKind { SEND, REGENERATE, EDIT, SWITCH, COMPACT, CANCEL }

data class CompactionProposalView(
    val id: CompactionId,
    val coveredCount: Int,
    val tokensBefore: Int,
    val tokensAfter: Int,
)

/**
 * Work that is in flight. Owned by the application layer — the UI only renders it.
 * Every field must be cleared when its command settles, successful or not: a
 * snapshot that says "busy" while nothing is running is the bug class this type
 * exists to remove.
 */
data class Overlay(
    val streaming: StreamingTurn? = null,
    val busy: BusyKind? = null,
    val searching: Boolean = false,
    /** Text held for the user across a compaction confirmation. */
    val pendingUserText: String? = null,
    val compactionProposal: CompactionProposalView? = null,
)

sealed interface EditOutcome {
    data object InPlace : EditOutcome

    /** The edit forked a sibling branch; a reply is already being generated. */
    data class Forked(val messageId: MessageId) : EditOutcome
}

/**
 * The single seam between the UI and the conversation engine.
 *
 * The UI may not import `*Store` ports. Everything it needs to draw the chat is
 * in [ChatSnapshot]; everything it needs to change is a method here.
 */
interface ChatGateway {
    /** Snapshots of one conversation, newest last. */
    fun snapshots(conversationId: ConversationId): Flow<ChatSnapshot>

    /** Snapshot of the not-yet-created draft conversation. */
    fun draftSnapshot(): Flow<ChatSnapshot>

    suspend fun createConversation(title: String = ""): Outcome<ConversationId>

    suspend fun send(
        conversationId: ConversationId,
        text: String,
        attachmentIds: List<AttachmentId> = emptyList(),
        search: Boolean = false,
    ): Outcome<Unit>

    suspend fun regenerate(messageId: MessageId): Outcome<Unit>

    suspend fun edit(messageId: MessageId, text: String): Outcome<EditOutcome>

    suspend fun switchBranch(messageId: MessageId): Outcome<Unit>

    suspend fun cancel(conversationId: ConversationId): Outcome<Unit>

    /** Applies the pending compaction proposal, then resends the held text. */
    suspend fun confirmCompaction(conversationId: ConversationId): Outcome<Unit>

    suspend fun dismissCompaction(conversationId: ConversationId): Outcome<Unit>
}
