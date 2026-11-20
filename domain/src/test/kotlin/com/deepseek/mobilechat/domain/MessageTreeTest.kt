package com.deepseek.mobilechat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTreeTest {

    private val c = ConversationId("c1")

    private fun msg(
        id: String,
        parent: String?,
        seq: Long = 0,
        role: MessageRole = MessageRole.USER,
        conversationId: ConversationId = c,
    ) = Message(
        id = MessageId(id),
        conversationId = conversationId,
        parentId = parent?.let { MessageId(it) },
        seq = seq,
        role = role,
        parts = listOf(MessagePart.Text(id)),
        status = MessageStatus.COMPLETE,
        createdAt = seq,
    )

    @Test
    fun `pathTo walks from root to leaf`() {
        val tree = MessageTree.from(listOf(msg("a", null), msg("b", "a"), msg("c", "b")))
        assertEquals(listOf("a", "b", "c"), tree.pathTo(MessageId("c")).map { it.id.raw })
    }

    @Test
    fun `pathTo null or unknown leaf is empty`() {
        val tree = MessageTree.from(listOf(msg("a", null)))
        assertTrue(tree.pathTo(null).isEmpty())
        assertTrue(tree.pathTo(MessageId("nope")).isEmpty())
    }

    @Test
    fun `siblings are ordered by seq then creation time`() {
        val tree = MessageTree.from(
            listOf(
                msg("a", null),
                msg("b1", "a", seq = 2),
                msg("b2", "a", seq = 1),
                msg("b3", "a", seq = 1),
            ),
        )
        assertEquals(listOf("b2", "b3", "b1"), tree.siblingsOf(MessageId("b1")).map { it.id.raw })
    }

    @Test
    fun `root messages are siblings of each other`() {
        val tree = MessageTree.from(listOf(msg("a", null, 1), msg("b", null, 2)))
        assertEquals(listOf("a", "b"), tree.siblingsOf(MessageId("b")).map { it.id.raw })
    }

    @Test
    fun `subtreeOf collects the whole branch below a node`() {
        val tree = MessageTree.from(
            listOf(
                msg("a", null),
                msg("b", "a"),
                msg("c", "b"),
                msg("d", "c"),
                msg("other", null),
            ),
        )
        assertEquals(setOf("b", "c", "d"), tree.subtreeOf(MessageId("b")).map { it.raw }.toSet())
        assertTrue(tree.subtreeOf(MessageId("missing")).isEmpty())
    }

    @Test
    fun `only childless messages can be edited in place`() {
        val tree = MessageTree.from(listOf(msg("a", null), msg("b", "a")))
        assertFalse(tree.canEditInPlace(MessageId("a")))
        assertTrue(tree.canEditInPlace(MessageId("b")))
        assertFalse(tree.canEditInPlace(MessageId("nope")))
    }

    @Test
    fun `nextSeq is one past the largest sibling seq`() {
        val tree = MessageTree.from(listOf(msg("a", null, 5), msg("b", null, 2)))
        assertEquals(6L, tree.nextSeq(null))
        assertEquals(1L, tree.nextSeq(MessageId("a")))
    }

    @Test
    fun `depthOf counts edges from the root`() {
        val tree = MessageTree.from(listOf(msg("a", null), msg("b", "a"), msg("c", "b")))
        assertEquals(0, tree.depthOf(MessageId("a")))
        assertEquals(2, tree.depthOf(MessageId("c")))
    }

    @Test
    fun `valid tree reports no violations`() {
        val tree = MessageTree.from(listOf(msg("a", null), msg("b", "a", 1), msg("c", "a", 2)))
        assertTrue(tree.validate(c).isEmpty())
    }

    @Test
    fun `missing parent is a violation`() {
        val tree = MessageTree.from(listOf(msg("a", "ghost")))
        val v = tree.validate(c)
        assertTrue(v.any { it.rule.contains("missing parent") })
    }

    @Test
    fun `cross conversation parent is a violation`() {
        val other = ConversationId("c2")
        val tree = MessageTree.from(
            listOf(msg("a", null, conversationId = other), msg("b", "a", conversationId = c)),
        )
        assertTrue(tree.validate(c).any { it.rule.contains("another conversation") })
    }

    @Test
    fun `duplicate seq among siblings is a violation`() {
        val tree = MessageTree.from(listOf(msg("a", null), msg("b", "a", 1), msg("c", "a", 1)))
        assertTrue(tree.validate(c).any { it.rule.contains("duplicate seq") })
    }

    @Test
    fun `cycle is detected instead of looping forever`() {
        val tree = MessageTree.from(listOf(msg("a", "b"), msg("b", "a")))
        assertTrue(tree.validate(c).any { it.rule.contains("cycle") })
        // pathTo must not hang and must fail closed.
        assertTrue(tree.pathTo(MessageId("a")).isEmpty())
    }

    @Test
    fun `empty tree is valid and has no path`() {
        val tree = MessageTree.empty()
        assertEquals(0, tree.size)
        assertTrue(tree.validate(c).isEmpty())
        assertNull(tree.message(MessageId("x")))
        assertTrue(tree.pathTo(MessageId("x")).isEmpty())
    }
}
