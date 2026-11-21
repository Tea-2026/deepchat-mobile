package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.MessageTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `visibleMessages` decides what the transcript renders. The transcript keys its
 * rows by parent slot (`ChatMessageList.slotKey`), so any list returned here must
 * never contain two messages with the same parent — Compose throws
 * `IllegalArgumentException: Key "child-of:…" was already used` for that.
 *
 * The three states are covered: no conversation aggregate (loading, nothing
 * renderable), a stale aggregate whose leaf left the tree (only a linear history
 * may be flattened), and the normal active path.
 */
class ChatDerivationTest {

    private val conversationId = ConversationId("c")

    private fun message(
        id: String,
        parent: String?,
        seq: Long,
        role: MessageRole = if (parent == null) MessageRole.USER else MessageRole.ASSISTANT,
    ) = Message(
        id = MessageId(id),
        conversationId = conversationId,
        parentId = parent?.let(::MessageId),
        seq = seq,
        role = role,
        parts = listOf(MessagePart.Text(id)),
        status = MessageStatus.COMPLETE,
        createdAt = seq,
    )

    private fun conversation(leafId: MessageId?) = Conversation(
        id = conversationId,
        title = "t",
        activeLeafId = leafId,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /**
     * Mirrors `ChatMessageList.slotKey` (private there): one row per turn, keyed
     * by the parent. Duplicates in this list are exactly the Compose crash.
     */
    private fun slotKeys(messages: List<Message>): List<String> =
        messages.map { message ->
            message.parentId?.let { parent -> "child-of:${parent.raw}" } ?: "root:${message.id.raw}"
        }

    // root ─ user
    //   ├── reply-1   (sibling)
    //   └── reply-2   (sibling, the active one)
    private val root = message("root", null, 1)
    private val reply1 = message("reply-1", "root", 2)
    private val reply2 = message("reply-2", "root", 3)
    private val branched = listOf(root, reply1, reply2)

    @Test
    fun `no conversation aggregate renders nothing instead of a flat branched list`() {
        val tree = MessageTree.from(branched)

        // The fixture really is the crashing shape: flattening it duplicates a slot.
        assertEquals(listOf("root:root", "child-of:root", "child-of:root"), slotKeys(branched))

        assertTrue(
            "a branched history without its aggregate must wait for the loading state",
            visibleMessages(tree, conversation = null, all = branched).isEmpty(),
        )
    }

    @Test
    fun `a stale aggregate never flattens a branched history either`() {
        val tree = MessageTree.from(branched)
        val stale = conversation(MessageId("deleted-message"))

        assertTrue(
            "the repairable path is to wait for the conversation emission, not to crash",
            visibleMessages(tree, stale, branched).isEmpty(),
        )
    }

    @Test
    fun `the active path of a branched conversation has one row per parent slot`() {
        val tree = MessageTree.from(branched)

        val visible = visibleMessages(tree, conversation(reply2.id), branched)

        assertEquals(listOf(root.id, reply2.id), visible.map { it.id })
        assertEquals(slotKeys(visible).size, slotKeys(visible).distinct().size)
    }

    @Test
    fun `a stale aggregate on a linear history still degrades to chronological order`() {
        val linear = listOf(root, reply1)
        val stale = conversation(MessageId("deleted-message"))

        val visible = visibleMessages(MessageTree.from(linear), stale, linear)

        assertEquals(listOf(root.id, reply1.id), visible.map { it.id })
    }

    @Test
    fun `an unanswered user leaf reports that it needs a reply`() {
        val unanswered = listOf(root)

        assertTrue(leafNeedsReply(MessageTree.from(unanswered), conversation(root.id)))
    }

    @Test
    fun `a user leaf that already has a reply does not need one`() {
        // root has reply-1/reply-2 children; pointing the leaf at root is the
        // malformed-but-visible shape and must not offer a duplicate reply.
        assertFalse(leafNeedsReply(MessageTree.from(branched), conversation(root.id)))
    }

    @Test
    fun `an assistant leaf does not need a reply`() {
        assertFalse(leafNeedsReply(MessageTree.from(branched), conversation(reply2.id)))
    }

    @Test
    fun `no conversation or no leaf never needs a reply`() {
        val tree = MessageTree.from(listOf(root))

        assertFalse(leafNeedsReply(tree, null))
        assertFalse(leafNeedsReply(tree, conversation(null)))
    }
}
