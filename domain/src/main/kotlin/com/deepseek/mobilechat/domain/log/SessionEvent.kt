package com.deepseek.mobilechat.domain.log

import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage

/**
 * The conversation event vocabulary.
 *
 * Mirrors DSH's `session/…` naming (`@deepseek-ai/dsh-session`), so that reading
 * their logs and ours does not require a mental translation. Only the subset this
 * app actually produces is declared.
 *
 * Two groups, and the split is the whole design:
 *
 *  - [isSurfaceEligible] events can become part of the model-visible history.
 *    They *must* carry a [SurfaceOp]; see [SessionEvent].
 *  - everything else is a record. It can never change the prompt, and carrying a
 *    surface marker on it is an error, not a no-op.
 */
enum class EventType(val wire: String) {
    SYSTEM_MESSAGE("system/message"),
    DEVELOPER_MESSAGE("developer/message"),
    USER_MESSAGE("user/message"),
    ASSISTANT_MESSAGE("assistant/message"),
    TOOL_RESULT("tool/result"),

    TURN_START("turn/start"),
    TURN_END("turn/end"),
    TURN_INTERRUPTED("turn/interrupted"),
    COMPACTION_START("compaction/start"),
    COMPACTION_END("compaction/end"),
    COMPACTION_PRUNE("compaction/prune"),
    IMAGE_OFFLOAD("image/offload"),
    LEAF_CHANGED("leaf/changed"),
    MODEL_SELECTION("model/selection"),
    SESSION_TITLE("session/title"),
    ;

    /** True when an event of this type may enter the model-visible surface. */
    val isSurfaceEligible: Boolean
        get() = this == SYSTEM_MESSAGE ||
            this == DEVELOPER_MESSAGE ||
            this == USER_MESSAGE ||
            this == ASSISTANT_MESSAGE ||
            this == TOOL_RESULT

    companion object {
        private val byWire: Map<String, EventType> = entries.associateBy { it.wire }

        fun fromWire(wire: String): EventType? = byWire[wire]
    }
}

/**
 * How a surface event joins (or edits) the model-visible history.
 *
 * [Append] means "this event becomes one surface node at its own seq" — the
 * append-origin marker the transcript is built from. [Replace] means "this event
 * is a stand-in that shadows the surface nodes in `startSeq..endSeq`"; it is how
 * compaction and tool-result pruning work without deleting anything.
 */
sealed interface SurfaceOp {
    data object Append : SurfaceOp

    data class Replace(val startSeq: Long, val endSeq: Long) : SurfaceOp
}

/**
 * Typed payloads. `EventType` and payload must agree (see [SessionEvent.validate]).
 *
 * Messages are carried as they will be shown; the surface projection turns them
 * into prompt blocks, the transcript projection into rows.
 */
sealed interface EventData {
    data class SystemMessage(val text: String) : EventData

    data class DeveloperMessage(val text: String, val kind: String = "injection") : EventData

    data class UserMessage(
        val messageId: MessageId,
        val parentId: MessageId?,
        val parts: List<MessagePart>,
        /**
         * Non-null only on a compaction stand-in: the summary node is a normal
         * user message so that every consumer (prompt, transcript, export) can
         * treat it as one, while still being recognisable and correlatable.
         */
        val compactionId: CompactionId? = null,
    ) : EventData

    data class AssistantMessage(
        val messageId: MessageId,
        val parentId: MessageId?,
        val parts: List<MessagePart>,
        val modelInfo: ModelInfo? = null,
        val usage: Usage? = null,
        val finishReason: FinishReason = FinishReason.STOP,
        val citations: List<SearchHit> = emptyList(),
    ) : EventData

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : EventData

    data class TurnStart(
        val messageId: MessageId,
        val parentId: MessageId?,
        val modelInfo: ModelInfo?,
    ) : EventData

    data class TurnEnd(
        val messageId: MessageId,
        val finishReason: FinishReason,
        val usage: Usage?,
        val durationMs: Long,
    ) : EventData

    /** Written on startup for a turn whose process died mid-stream. */
    data class TurnInterrupted(
        val messageId: MessageId,
        val parentId: MessageId?,
        val partialText: String,
        val reason: String,
    ) : EventData

    data class CompactionStart(
        val compactionId: CompactionId,
        val strategyId: String,
        val targetFromSeq: Long,
        val targetToSeq: Long,
        val tokensBefore: Int,
    ) : EventData

    data class CompactionEnd(
        val compactionId: CompactionId,
        val summarySeq: Long,
        val tokensAfter: Int,
    ) : EventData

    /**
     * Groups the `tool/result` rewrites a prune performed. The rewrites themselves
     * are ordinary replace-op events; this is the bookmark that says why.
     */
    data class CompactionPrune(
        val compactionId: CompactionId,
        val rewrittenSeqs: List<Long>,
    ) : EventData

    /** Deferred: moves old images out of the prompt without deleting them. */
    data class ImageOffload(
        val messageId: MessageId,
        val attachmentIds: List<AttachmentId>,
        val placeholder: String,
    ) : EventData

    data class LeafChanged(val messageId: MessageId?) : EventData

    data class ModelSelection(
        val modelRef: ModelRef?,
        val params: SamplingParams?,
        val thinkingLevel: ThinkingLevel?,
    ) : EventData

    data class SessionTitle(val title: String, val source: String = "manual") : EventData
}

/**
 * One immutable entry in a conversation's log.
 *
 * Field rules (all enforced by [validate] and by the fold):
 *
 *  - [seq] is 1-based and contiguous within a conversation;
 *  - surface-eligible types **must** carry [surfaceOp]; record types must not;
 *  - [SurfaceOp.Replace] may only reference *earlier* seqs and must list every
 *    shadowed node in [sourceEventSeqs] — an incomplete list is an error, so a
 *    replacement can never quietly swallow history;
 *  - [ignorable] is the forward-compatibility bit: a reader that does not
 *    understand a type may skip it only when the writer declared it safe.
 */
data class SessionEvent(
    val seq: Long,
    val eventId: String,
    val type: EventType,
    val time: Long,
    val data: EventData,
    val schemaVersion: Int = 1,
    val ignorable: Boolean = false,
    val surfaceOp: SurfaceOp? = null,
    val sourceEventSeqs: List<Long> = emptyList(),
) {
    /** Throws [LogFormatException] when this event violates the local rules. */
    fun validate() {
        if (seq < 1) throw LogFormatException("seq must be >= 1, got $seq")
        if (eventId.isBlank()) throw LogFormatException("eventId must not be blank")
        if (schemaVersion < 1) throw LogFormatException("schemaVersion must be >= 1")
        requirePayloadMatchesType()

        if (type.isSurfaceEligible) {
            val op = surfaceOp
                ?: throw LogFormatException("${type.wire} at seq $seq is surface-eligible and requires a surfaceOp")
            if (op is SurfaceOp.Replace) {
                if (op.startSeq >= seq || op.endSeq >= seq) {
                    throw LogFormatException(
                        "${type.wire} at seq $seq: replace range must reference earlier events " +
                            "(start ${op.startSeq}, end ${op.endSeq})",
                    )
                }
                if (op.startSeq > op.endSeq) {
                    throw LogFormatException("${type.wire} at seq $seq: replace start after end")
                }
            }
        } else {
            if (surfaceOp != null) {
                throw LogFormatException("${type.wire} at seq $seq is not surface-eligible and cannot carry a surfaceOp")
            }
            if (sourceEventSeqs.isNotEmpty()) {
                throw LogFormatException("${type.wire} at seq $seq is not surface-eligible and cannot carry sourceEventSeqs")
            }
        }

        if (sourceEventSeqs.isNotEmpty()) {
            if (sourceEventSeqs.size != sourceEventSeqs.distinct().size) {
                throw LogFormatException("${type.wire} at seq $seq: sourceEventSeqs must not contain duplicates")
            }
            val future = sourceEventSeqs.firstOrNull { it >= seq }
            if (future != null) {
                throw LogFormatException(
                    "${type.wire} at seq $seq: sourceEventSeqs must reference earlier events, got $future",
                )
            }
        }
        if (type == EventType.ASSISTANT_MESSAGE && sourceEventSeqs.isNotEmpty()) {
            throw LogFormatException("assistant/message carries its own content and cannot carry sourceEventSeqs")
        }
        if (type != EventType.SESSION_TITLE && data is EventData.SessionTitle) {
            throw LogFormatException("session/title payload on ${type.wire}")
        }
    }

    private fun requirePayloadMatchesType() {
        val matches = when (type) {
            EventType.SYSTEM_MESSAGE -> data is EventData.SystemMessage
            EventType.DEVELOPER_MESSAGE -> data is EventData.DeveloperMessage
            EventType.USER_MESSAGE -> data is EventData.UserMessage
            EventType.ASSISTANT_MESSAGE -> data is EventData.AssistantMessage
            EventType.TOOL_RESULT -> data is EventData.ToolResult
            EventType.TURN_START -> data is EventData.TurnStart
            EventType.TURN_END -> data is EventData.TurnEnd
            EventType.TURN_INTERRUPTED -> data is EventData.TurnInterrupted
            EventType.COMPACTION_START -> data is EventData.CompactionStart
            EventType.COMPACTION_END -> data is EventData.CompactionEnd
            EventType.COMPACTION_PRUNE -> data is EventData.CompactionPrune
            EventType.IMAGE_OFFLOAD -> data is EventData.ImageOffload
            EventType.LEAF_CHANGED -> data is EventData.LeafChanged
            EventType.MODEL_SELECTION -> data is EventData.ModelSelection
            EventType.SESSION_TITLE -> data is EventData.SessionTitle
        }
        if (!matches) throw LogFormatException("${type.wire} at seq $seq carries ${data::class.simpleName}")
    }
}

/** Raised whenever a log cannot be replayed *losslessly*. Never caught to "carry on". */
class LogFormatException(message: String) : Exception(message)
