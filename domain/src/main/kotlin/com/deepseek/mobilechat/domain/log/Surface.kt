package com.deepseek.mobilechat.domain.log

import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel

/** A conversation's whole history, in order. The only source of truth. */
data class ConversationLog(
    val conversationId: ConversationId,
    val events: List<SessionEvent>,
) {
    /** Number of events; the atomic version the UI sees. */
    val version: Long get() = events.size.toLong()
}

// ---------------------------------------------------------------------------
// surface：模型可见
// ---------------------------------------------------------------------------

sealed interface SurfaceContent {
    data class System(val text: String) : SurfaceContent
    data class Developer(val text: String, val kind: String) : SurfaceContent
    data class User(val message: Message) : SurfaceContent
    data class Assistant(val message: Message) : SurfaceContent
    data class Tool(val toolUseId: String, val content: String, val isError: Boolean) : SurfaceContent
}

data class SurfaceNode(
    val seq: Long,
    val type: EventType,
    val content: SurfaceContent,
    /** Set on a compaction stand-in so consumers can tell it from a typed message. */
    val compactionId: CompactionId? = null,
)

/** Exactly what the model sees, in order. */
data class Surface(val nodes: List<SurfaceNode>)

/**
 * Replays a log into the model-visible [Surface].
 *
 * Every mutation is validated *before* it is applied, and the whole replay is a
 * pure function: a rejected log throws and the caller keeps whatever it had.
 *
 * The rules mirror DSH's `surface.js` (see `docs/architecture-v2-log.md` §13.3):
 * contiguity, mandatory `surfaceOp` on surface-eligible types, replace ranges that
 * resolve against the *current* surface, complete `sourceEventSeqs`, a
 * content-only rewrite rule for `tool/result`, and protection for node 0.
 */
object SurfaceFolder {

    fun fold(log: ConversationLog): Surface {
        val nodes = ArrayList<SurfaceNode>()
        var expected = 1L
        for (event in log.events) {
            if (event.seq != expected) {
                throw LogFormatException(
                    "event seq ${event.seq} is not contiguous; expected $expected",
                )
            }
            event.validate()
            when (val op = event.surfaceOp) {
                null -> Unit // record-only event: can never change the surface
                SurfaceOp.Append -> nodes += nodeFor(event, log.conversationId)
                is SurfaceOp.Replace -> applyReplace(nodes, event, op, log.conversationId)
            }
            expected++
        }
        return Surface(nodes)
    }

    private fun applyReplace(
        nodes: MutableList<SurfaceNode>,
        event: SessionEvent,
        op: SurfaceOp.Replace,
        conversationId: ConversationId,
    ) {
        val startIdx = nodes.indexOfFirst { it.seq == op.startSeq }
        if (startIdx < 0) {
            throw LogFormatException(
                "surface replace at seq ${event.seq}: start seq ${op.startSeq} is not on the surface",
            )
        }
        val endIdx = nodes.indexOfFirst { it.seq == op.endSeq }
        if (endIdx < 0) {
            throw LogFormatException(
                "surface replace at seq ${event.seq}: end seq ${op.endSeq} is not on the surface",
            )
        }
        if (startIdx > endIdx) {
            throw LogFormatException(
                "surface replace at seq ${event.seq}: start ${op.startSeq} is after end ${op.endSeq}",
            )
        }
        val shadowed = nodes.subList(startIdx, endIdx + 1).map { it.seq }
        val declared = event.sourceEventSeqs.toSet()
        val missing = shadowed.filter { it !in declared }
        if (missing.isNotEmpty()) {
            throw LogFormatException(
                "surface replace at seq ${event.seq}: sourceEventSeqs must list every shadowed node; " +
                    "missing ${missing.joinToString(",")}",
            )
        }
        val extra = declared.filter { it !in shadowed }
        if (extra.isNotEmpty()) {
            throw LogFormatException(
                "surface replace at seq ${event.seq}: sourceEventSeqs lists nodes outside the range: " +
                    extra.joinToString(","),
            )
        }
        assertSystemHeadRewrite(nodes, startIdx, shadowed, event)
        if (event.type == EventType.TOOL_RESULT) {
            assertToolResultRewrite(nodes[startIdx], event, shadowed)
        }
        repeat(shadowed.size) { nodes.removeAt(startIdx) }
        nodes.add(startIdx, nodeFor(event, conversationId))
    }

    /**
     * Node 0 normally holds the system prompt. Compaction must not be able to eat
     * it; only another `system/message` over exactly that node may replace it.
     */
    private fun assertSystemHeadRewrite(
        nodes: List<SurfaceNode>,
        startIdx: Int,
        shadowed: List<Long>,
        event: SessionEvent,
    ) {
        if (startIdx != 0) return
        if (nodes[0].type != EventType.SYSTEM_MESSAGE) return
        if (event.type != EventType.SYSTEM_MESSAGE || shadowed.size != 1) {
            throw LogFormatException(
                "surface replace at seq ${event.seq}: node 0 holds the system prompt and may only be " +
                    "rewritten by a system/message over exactly that node",
            )
        }
    }

    /** A pruned tool result may shorten its content and nothing else. */
    private fun assertToolResultRewrite(
        original: SurfaceNode,
        event: SessionEvent,
        shadowed: List<Long>,
    ) {
        if (shadowed.size != 1) {
            throw LogFormatException(
                "tool/result replace at seq ${event.seq}: must rewrite exactly one node",
            )
        }
        val before = original.content as? SurfaceContent.Tool
            ?: throw LogFormatException("tool/result replace at seq ${event.seq}: target is not a tool result")
        val after = event.data as EventData.ToolResult
        if (before.toolUseId != after.toolUseId || before.isError != after.isError) {
            throw LogFormatException(
                "tool/result replace at seq ${event.seq}: may change only content",
            )
        }
    }

    private fun nodeFor(event: SessionEvent, conversationId: ConversationId): SurfaceNode =
        when (val data = event.data) {
            is EventData.SystemMessage -> SurfaceNode(
                seq = event.seq,
                type = event.type,
                content = SurfaceContent.System(data.text),
            )

            is EventData.DeveloperMessage -> SurfaceNode(
                seq = event.seq,
                type = event.type,
                content = SurfaceContent.Developer(data.text, data.kind),
            )

            is EventData.UserMessage -> SurfaceNode(
                seq = event.seq,
                type = event.type,
                content = SurfaceContent.User(data.toMessage(conversationId, event.time)),
                compactionId = data.compactionId,
            )

            is EventData.AssistantMessage -> SurfaceNode(
                seq = event.seq,
                type = event.type,
                content = SurfaceContent.Assistant(data.toMessage(conversationId, event.time)),
            )

            is EventData.ToolResult -> SurfaceNode(
                seq = event.seq,
                type = event.type,
                content = SurfaceContent.Tool(data.toolUseId, data.content, data.isError),
            )

            else -> throw LogFormatException(
                "${event.type.wire} at seq ${event.seq} is not surface-eligible but has a surface payload",
            )
        }
}

// ---------------------------------------------------------------------------
// transcript：人可见
// ---------------------------------------------------------------------------

sealed interface TranscriptNode {
    data class MessageNode(val seq: Long, val message: Message) : TranscriptNode

    /**
     * Where a compaction landed. The covered messages stay in the transcript —
     * this is a divider, not a replacement.
     */
    data class CompactionBar(
        val compactionId: CompactionId,
        val startSeq: Long,
        val endSeq: Long,
        val summary: String,
        val tokensBefore: Int?,
        val tokensAfter: Int?,
    ) : TranscriptNode
}

data class Transcript(
    val conversationId: ConversationId,
    val title: String,
    val nodes: List<TranscriptNode>,
    /** Every message ever appended, all branches included. */
    val messages: List<Message>,
    val activeLeafId: MessageId?,
    val modelRef: ModelRef?,
    val params: SamplingParams?,
    val thinkingLevel: ThinkingLevel?,
) {
    fun message(id: MessageId): Message? = messages.firstOrNull { it.id == id }
}

/**
 * Replays a log into the human transcript.
 *
 * **Hard rule (DSH `surface.js`):** the transcript is built from append-origin
 * events only. The surface deliberately shadows replaced ranges; deriving the
 * transcript from it would erase conversation the user has already seen the
 * first time a compaction lands.
 */
object TranscriptProjector {

    fun project(log: ConversationLog): Transcript {
        val nodes = ArrayList<TranscriptNode>()
        val messages = ArrayList<Message>()
        var title = ""
        var leaf: MessageId? = null
        var modelRef: ModelRef? = null
        var params: SamplingParams? = null
        var thinking: ThinkingLevel? = null
        val pendingTokensBefore = HashMap<CompactionId, Int>()
        val pendingTokensAfter = HashMap<CompactionId, Int>()

        var expected = 1L
        loop@ for (event in log.events) {
            if (event.seq != expected) {
                throw LogFormatException("event seq ${event.seq} is not contiguous; expected $expected")
            }
            event.validate()
            expected++

            when (val data = event.data) {
                is EventData.SessionTitle -> title = data.title
                is EventData.LeafChanged -> leaf = data.messageId
                is EventData.ModelSelection -> {
                    data.modelRef?.let { modelRef = it }
                    data.params?.let { params = it }
                    data.thinkingLevel?.let { thinking = it }
                }

                is EventData.UserMessage -> {
                    if (event.surfaceOp is SurfaceOp.Replace && data.compactionId == null) {
                        // An in-place edit: the same message id, rewritten. The user
                        // should see the new text, not the old one next to it.
                        replaceMessage(nodes, messages, log.conversationId, event, data)
                        continue@loop
                    }
                    if (event.surfaceOp != SurfaceOp.Append) {
                        // A compaction stand-in: show it as a divider, never as a
                        // message, because the covered text is still on screen.
                        val compactionId = data.compactionId
                        if (compactionId != null) {
                            nodes += TranscriptNode.CompactionBar(
                                compactionId = compactionId,
                                startSeq = (event.surfaceOp as? SurfaceOp.Replace)?.startSeq ?: 0,
                                endSeq = (event.surfaceOp as? SurfaceOp.Replace)?.endSeq ?: 0,
                                summary = data.parts.filterIsInstance<MessagePart.Text>()
                                    .joinToString("") { it.text },
                                tokensBefore = pendingTokensBefore[compactionId],
                                // `compaction/end` lands after the stand-in, so the
                                // final number is filled in below.
                                tokensAfter = null,
                            )
                        }
                    } else {
                        val message = data.toMessage(log.conversationId, event.time, event.seq)
                        messages += message
                        nodes += TranscriptNode.MessageNode(event.seq, message)
                    }
                }

                is EventData.AssistantMessage -> {
                    if (event.surfaceOp is SurfaceOp.Replace) {
                        replaceMessage(nodes, messages, log.conversationId, event, data)
                        continue@loop
                    }
                    val message = data.toMessage(log.conversationId, event.time, event.seq)
                    messages += message
                    nodes += TranscriptNode.MessageNode(event.seq, message)
                }

                is EventData.TurnInterrupted -> {
                    val message = Message(
                        id = data.messageId,
                        conversationId = log.conversationId,
                        parentId = data.parentId,
                        seq = event.seq,
                        role = MessageRole.ASSISTANT,
                        parts = if (data.partialText.isEmpty()) {
                            emptyList()
                        } else {
                            listOf(MessagePart.Text(data.partialText))
                        },
                        status = MessageStatus.CANCELLED,
                        createdAt = event.time,
                        finishReason = FinishReason.CANCELLED,
                        error = data.reason,
                    )
                    messages += message
                    nodes += TranscriptNode.MessageNode(event.seq, message)
                }

                is EventData.CompactionStart -> pendingTokensBefore[data.compactionId] = data.tokensBefore
                is EventData.CompactionEnd -> pendingTokensAfter[data.compactionId] = data.tokensAfter
                else -> Unit
            }
        }

        // Compaction bars are emitted at the stand-in's position, but the
        // post-compaction token count only arrives with the closing event.
        val finalNodes = nodes.map { node ->
            if (node is TranscriptNode.CompactionBar) {
                node.copy(tokensAfter = pendingTokensAfter[node.compactionId])
            } else {
                node
            }
        }

        return Transcript(
            conversationId = log.conversationId,
            title = title,
            nodes = finalNodes,
            messages = messages,
            activeLeafId = leaf ?: messages.lastOrNull()?.id,
            modelRef = modelRef,
            params = params,
            thinkingLevel = thinking,
        )
    }
}

/**
 * Rewrites one already-known message in place. Used by in-place edits, where the
 * user's intent is "this message now reads like this", not "keep both".
 */
private fun replaceMessage(
    nodes: MutableList<TranscriptNode>,
    messages: MutableList<Message>,
    conversationId: ConversationId,
    event: SessionEvent,
    data: EventData,
) {
    val message = when (data) {
        is EventData.UserMessage -> data.toMessage(conversationId, event.time, event.seq)
        is EventData.AssistantMessage -> data.toMessage(conversationId, event.time, event.seq)
        else -> return
    }
    val index = nodes.indexOfFirst {
        it is TranscriptNode.MessageNode && it.message.id == message.id
    }
    if (index < 0) {
        throw LogFormatException(
            "in-place edit at seq ${event.seq} targets ${message.id.raw}, which is not in the transcript",
        )
    }
    val previous = (nodes[index] as TranscriptNode.MessageNode).message
    val edited = message.copy(editedAt = event.time, pinned = previous.pinned)
    nodes[index] = TranscriptNode.MessageNode(event.seq, edited)
    val messageIndex = messages.indexOfFirst { it.id == message.id }
    if (messageIndex >= 0) messages[messageIndex] = edited
}

// ---------------------------------------------------------------------------
// shared conversions
// ---------------------------------------------------------------------------

internal fun EventData.UserMessage.toMessage(
    conversationId: ConversationId,
    time: Long,
    seq: Long = 0L,
): Message = Message(
    id = messageId,
    conversationId = conversationId,
    parentId = parentId,
    seq = seq,
    role = MessageRole.USER,
    parts = parts,
    status = MessageStatus.COMPLETE,
    createdAt = time,
)

internal fun EventData.AssistantMessage.toMessage(
    conversationId: ConversationId,
    time: Long,
    seq: Long = 0L,
): Message = Message(
    id = messageId,
    conversationId = conversationId,
    parentId = parentId,
    seq = seq,
    role = MessageRole.ASSISTANT,
    parts = parts,
    status = MessageStatus.COMPLETE,
    createdAt = time,
    modelInfo = modelInfo,
    usage = usage,
    finishReason = finishReason,
)
