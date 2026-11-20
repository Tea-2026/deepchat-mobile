package com.deepseek.mobilechat.domain

/**
 * Immutable index over the message tree of one conversation.
 *
 * Pure Kotlin, no I/O. This is the single place where branch semantics live:
 * paths, siblings, subtrees, edit eligibility. Every write use case must
 * validate through this class before persisting.
 */
class MessageTree private constructor(private val byId: Map<MessageId, Message>) {

    val size: Int get() = byId.size

    fun contains(id: MessageId): Boolean = byId.containsKey(id)

    fun message(id: MessageId): Message? = byId[id]

    fun all(): List<Message> = byId.values.sortedWith(compareBy({ it.seq }, { it.createdAt }))

    /** Children ordered by [Message.seq], then creation time. */
    fun childrenOf(parentId: MessageId?): List<Message> =
        byId.values
            .filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.seq }, { it.createdAt }))

    /** Path from the root down to [leaf]. Empty when [leaf] is null or unknown. */
    fun pathTo(leaf: MessageId?): List<Message> {
        if (leaf == null) return emptyList()
        val out = ArrayList<Message>()
        val seen = HashSet<MessageId>()
        var cursor: MessageId? = leaf
        while (cursor != null) {
            if (!seen.add(cursor)) return emptyList() // cycle guard: treat as broken tree
            val node = byId[cursor] ?: return emptyList()
            out.add(node)
            cursor = node.parentId
        }
        out.reverse()
        return out
    }

    /** Siblings share a parent. Roots are siblings of each other. */
    fun siblingsOf(id: MessageId): List<Message> {
        val node = byId[id] ?: return emptyList()
        return childrenOf(node.parentId)
    }

    fun isLeaf(id: MessageId): Boolean =
        byId[id] != null && byId.values.none { it.parentId == id }

    /** [id] plus everything below it. */
    fun subtreeOf(id: MessageId): Set<MessageId> {
        if (!byId.containsKey(id)) return emptySet()
        val out = LinkedHashSet<MessageId>()
        val queue = ArrayDeque<MessageId>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!out.add(current)) continue
            byId.values.forEach { if (it.parentId == current) queue.add(it.id) }
        }
        return out
    }

    fun depthOf(id: MessageId): Int = pathTo(id).size - 1

    /**
     * In-place editing is only legal on a leaf that has no children; anything
     * else would silently rewrite history that other branches still depend on.
     */
    fun canEditInPlace(id: MessageId): Boolean = byId.containsKey(id) && isLeaf(id)

    /** Next sibling sequence number to append under [parentId]. */
    fun nextSeq(parentId: MessageId?): Long =
        (childrenOf(parentId).maxOfOrNull { it.seq } ?: 0L) + 1L

    /** Structural violations. An empty list means the tree is consistent. */
    fun validate(conversationId: ConversationId? = null): List<TreeViolation> {
        val violations = ArrayList<TreeViolation>()
        val seqByParent = HashMap<MessageId?, MutableSet<Long>>()

        byId.values.forEach { node ->
            if (conversationId != null && node.conversationId != conversationId) {
                violations += TreeViolation(node.id, "message belongs to another conversation")
            }
            if (node.parentId != null && !byId.containsKey(node.parentId)) {
                violations += TreeViolation(node.id, "missing parent ${node.parentId.raw}")
            }
            if (node.parentId != null) {
                val parent = byId[node.parentId]
                if (parent != null && parent.conversationId != node.conversationId) {
                    violations += TreeViolation(node.id, "parent lives in another conversation")
                }
            }
            val seen = seqByParent.getOrPut(node.parentId) { HashSet() }
            if (!seen.add(node.seq)) {
                violations += TreeViolation(node.id, "duplicate seq ${node.seq} among siblings")
            }
        }

        // Cycle detection: every node must reach a root within size steps.
        byId.keys.forEach { start ->
            var cursor: MessageId? = start
            var steps = 0
            val visited = HashSet<MessageId>()
            while (cursor != null && steps <= byId.size) {
                if (!visited.add(cursor)) {
                    violations += TreeViolation(start, "cycle detected")
                    break
                }
                cursor = byId[cursor]?.parentId
                steps++
            }
            if (steps > byId.size) {
                violations += TreeViolation(start, "path too long, likely a cycle")
            }
        }
        return violations
    }

    companion object {
        fun from(messages: List<Message>): MessageTree =
            MessageTree(messages.associateBy { it.id })

        fun empty(): MessageTree = MessageTree(emptyMap())
    }
}

data class TreeViolation(val messageId: MessageId, val rule: String)
