package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageTree
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.log.TranscriptNode

/**
 * Pure derivations the chat screen needs from the raw message tree.
 *
 * Extracted from the ViewModel so they can be reasoned about (and tested)
 * without coroutines, stores or Android.
 */

/**
 * Messages on the conversation's active path, in display order.
 *
 * The conversation aggregate is required: without it there is no active leaf,
 * so the only candidate list is *every* message of the conversation — branches
 * included. The transcript keys its rows by parent (`ChatMessageList.slotKey`),
 * so two siblings share one key and Compose throws "Key … was already used".
 * That is why a missing aggregate returns an empty list (the screen shows
 * `isLoadingConversation`) instead of a flat fallback.
 *
 * When the aggregate is present but its leaf is not in the tree yet (a stale
 * aggregate racing a fresh emission, e.g. right after a branch delete), the
 * chronological fallback is kept **only** for a list that can be keyed: one
 * message per parent slot. A branched history cannot be flattened into rows
 * without a duplicated key, so it waits for the repaired aggregate instead.
 */
internal fun visibleMessages(
    tree: MessageTree,
    conversation: Conversation?,
    all: List<Message>,
): List<Message> {
    if (conversation == null) return emptyList()
    val path = tree.pathTo(conversation.activeLeafId)
    if (path.isNotEmpty()) return path
    // `slotKey` is "child-of:<parentId>" for every non-root row, so a list is
    // renderable only when no two messages share a parent.
    val parents = all.mapNotNull { it.parentId }
    return if (parents.size == parents.distinct().size) {
        all.sortedWith(compareBy({ it.seq }, { it.createdAt }))
    } else {
        emptyList()
    }
}

/** Positions for every visible message that has more than one sibling. */
internal fun buildBranches(
    tree: MessageTree,
    visible: List<Message>,
): Map<MessageId, BranchPosition> {
    val out = LinkedHashMap<MessageId, BranchPosition>()
    visible.forEach { message ->
        val siblings = tree.siblingsOf(message.id)
        if (siblings.size <= 1) return@forEach
        val index = siblings.indexOfFirst { it.id == message.id }
        if (index < 0) return@forEach
        out[message.id] = BranchPosition(
            index = index,
            count = siblings.size,
            previousId = siblings.getOrNull(index - 1)?.id,
            nextId = siblings.getOrNull(index + 1)?.id,
        )
    }
    return out
}

/**
 * The display rows for the transcript.
 *
 * [transcript] is the snapshot's human projection (append-origin messages plus
 * compaction dividers); [visible] is the active path derived from the same
 * snapshot. Keeping the transcript's order and dropping only the messages that
 * are off the active path is what lets a divider land between the messages it
 * covers without flattening a branched history into a list it cannot key.
 *
 * The covered messages are *not* removed from the transcript (I5): every
 * message in the range stays visible above the divider, unless it is off the
 * active path — in which case `visible` already excluded it.
 */
internal fun buildTranscript(
    transcript: List<TranscriptNode>,
    visible: List<Message>,
): List<TranscriptRow> {
    if (transcript.isEmpty()) return visible.map { TranscriptRow.Turn(it) }

    val visibleById = visible.associateBy { it.id }
    val coveredByBar = HashMap<TranscriptNode.CompactionBar, Int>()
    transcript.forEach { node ->
        if (node is TranscriptNode.CompactionBar) {
            coveredByBar[node] = transcript.count { candidate ->
                candidate is TranscriptNode.MessageNode &&
                    candidate.seq in node.startSeq..node.endSeq
            }
        }
    }

    val rows = ArrayList<TranscriptRow>(transcript.size)
    val used = HashSet<MessageId>()
    transcript.forEach { node ->
        when (node) {
            is TranscriptNode.MessageNode -> {
                val message = visibleById[node.message.id] ?: return@forEach
                used += message.id
                rows += TranscriptRow.Turn(message)
            }

            is TranscriptNode.CompactionBar -> rows += TranscriptRow.CompactionDivider(
                compactionId = node.compactionId,
                coveredCount = coveredByBar[node] ?: 0,
                summary = node.summary,
                tokensBefore = node.tokensBefore,
                tokensAfter = node.tokensAfter,
            )
        }
    }
    // Defensive: a visible message the projection did not mention is still shown
    // (the active path, not the transcript, decides what is visible).
    visible.forEach { if (it.id !in used) rows += TranscriptRow.Turn(it) }
    return rows
}

/**
 * 「模型名 · 思考 High」 — the top bar subtitle (spec §3).
 *
 * The level itself is printed in English ([thinkingLabel]); only the label in
 * front of it is Chinese, because it reads as prose ("thinking: high").
 *
 * When the conversation has no explicit model the label says 「默认模型」 —
 * the preset/default is genuinely in effect then, and claiming "no model" would
 * be worse than being vague about which one.
 */
internal fun describeModel(modelName: String, thinking: ThinkingLevel): String {
    val name = modelName.takeIf { it.isNotBlank() } ?: "默认模型"
    return "$name · 思考 ${thinkingLabel(thinking)}"
}

/**
 * True when the active branch ends at a user message with no reply.
 *
 * Forks created before fork edits generated a reply, and forks whose generation
 * failed, are exactly this shape. The UI uses it to offer an explicit
 * 「生成回复」 action instead of silently generating whenever the conversation is
 * opened (which would spend tokens without a user gesture).
 */
internal fun leafNeedsReply(tree: MessageTree, conversation: Conversation?): Boolean {
    val leafId = conversation?.activeLeafId ?: return false
    val leaf = tree.message(leafId) ?: return false
    return leaf.role == MessageRole.USER && tree.isLeaf(leafId)
}

/**
 * The model name to print on an assistant turn: the model the reply was
 * actually produced with wins over the conversation's current selection (they
 * differ after the user switches models mid-conversation).
 */
internal fun modelNameFor(message: Message, fallback: String): String {
    if (message.role != MessageRole.ASSISTANT) return fallback
    return message.modelInfo?.ref?.modelId?.raw?.takeIf { it.isNotBlank() } ?: fallback
}
