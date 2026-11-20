package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageTree
import com.deepseek.mobilechat.domain.ThinkingLevel

/**
 * Pure derivations the chat screen needs from the raw message tree.
 *
 * Extracted from the ViewModel so they can be reasoned about (and tested)
 * without coroutines, stores or Android.
 */

/**
 * Messages on the conversation's active path, in display order.
 *
 * Falls back to chronological order when the leaf is not written yet (first
 * turn) or when a store can only hand back a flat list — a flat transcript is
 * still better than an empty screen.
 */
internal fun visibleMessages(
    tree: MessageTree,
    conversation: Conversation?,
    all: List<Message>,
): List<Message> {
    val path = tree.pathTo(conversation?.activeLeafId)
    return if (path.isEmpty()) {
        all.sortedWith(compareBy({ it.seq }, { it.createdAt }))
    } else {
        path
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
 * The model name to print on an assistant turn: the model the reply was
 * actually produced with wins over the conversation's current selection (they
 * differ after the user switches models mid-conversation).
 */
internal fun modelNameFor(message: Message, fallback: String): String {
    if (message.role != MessageRole.ASSISTANT) return fallback
    return message.modelInfo?.ref?.modelId?.raw?.takeIf { it.isNotBlank() } ?: fallback
}
