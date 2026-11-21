package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.log.Surface
import com.deepseek.mobilechat.domain.log.SurfaceContent
import com.deepseek.mobilechat.domain.log.SurfaceNode

/** A contiguous stretch of current surface nodes, in surface order. */
internal data class SurfaceRange(
    val startSeq: Long,
    val endSeq: Long,
    /** Every node the range shadows, in order — the `sourceEventSeqs` of a replace. */
    val seqs: List<Long>,
)

/** The message a node belongs to; `null` for system/developer/tool-only nodes. */
internal fun SurfaceNode.messageId(): MessageId? = when (val content = content) {
    is SurfaceContent.User -> content.message.id
    is SurfaceContent.Assistant -> content.message.id
    else -> null
}

/**
 * Resolves a message-id range onto the *current* surface and expands it until
 * it cannot split a `tool_use` from its `tool_result` (invariant I7).
 *
 * Rules, applied until a fixed point:
 *  - if the range contains an assistant message with tool calls, every following
 *    `tool/result` belonging to those calls joins the range;
 *  - if the range contains a `tool/result` whose call was issued by an assistant
 *    *before* the range, the range grows backwards to include that assistant.
 *
 * Returns `null` when either endpoint is no longer on the surface (a stale
 * proposal); callers turn that into [com.deepseek.mobilechat.application.AppError.BranchConflict]
 * rather than guessing a different range.
 */
internal fun Surface.rangeFor(fromMessageId: MessageId, toMessageId: MessageId): SurfaceRange? {
    val fromIndex = nodes.indexOfFirst { it.messageId() == fromMessageId }
    val toIndex = nodes.indexOfFirst { it.messageId() == toMessageId }
    if (fromIndex < 0 || toIndex < 0) return null

    var start = minOf(fromIndex, toIndex)
    var end = maxOf(fromIndex, toIndex)
    var changed = true
    while (changed) {
        changed = false

        val toolUses = HashSet<String>()
        for (index in start..end) {
            val content = nodes[index].content
            if (content is SurfaceContent.Assistant) {
                content.message.parts.filterIsInstance<MessagePart.ToolCall>()
                    .forEach { call -> toolUses += call.callId }
            }
        }

        var next = end + 1
        while (next < nodes.size) {
            val content = nodes[next].content
            if (content is SurfaceContent.Tool && content.toolUseId in toolUses) {
                end = next
                changed = true
                next++
            } else {
                break
            }
        }

        for (index in start..end) {
            val content = nodes[index].content
            if (content !is SurfaceContent.Tool) continue
            val issuer = (0 until start).firstOrNull { candidateIndex ->
                val candidate = nodes[candidateIndex].content
                candidate is SurfaceContent.Assistant &&
                    candidate.message.parts.any { part ->
                        part is MessagePart.ToolCall && part.callId == content.toolUseId
                    }
            }
            if (issuer != null) {
                start = issuer
                changed = true
                break
            }
        }
    }

    return SurfaceRange(
        startSeq = nodes[start].seq,
        endSeq = nodes[end].seq,
        seqs = nodes.subList(start, end + 1).map { it.seq },
    )
}
