package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence contract that matters most: what [MessageTree] reads back
 * from SQLite must describe the same paths and sibling order it had before the
 * write. Messages are deliberately inserted in an order that does not match the
 * tree order, so only `seq`/`parent_id` can reproduce it.
 */
class MessageTreePersistenceTest {

    private suspend fun persistTree(env: TestEnv) {
        env.conversations.upsert(conversation("c1", activeLeafId = "m4"))

        // Insertion order is intentionally "wrong": m3 is written before m2 even
        // though m2 has the smaller seq among m1's children.
        env.messages.append(message("m1", "c1", seq = 1, role = MessageRole.USER, createdAt = 100L, text = "q"))
        env.messages.append(
            message("m3", "c1", parentId = "m1", seq = 2, role = MessageRole.ASSISTANT, createdAt = 150L, text = "alt answer"),
        )
        env.messages.append(
            message("m2", "c1", parentId = "m1", seq = 1, role = MessageRole.ASSISTANT, createdAt = 200L, text = "chosen answer"),
        )
        env.messages.append(
            message("m4", "c1", parentId = "m2", seq = 1, role = MessageRole.USER, createdAt = 300L, text = "follow up"),
        )
    }

    @Test
    fun readBackTreeHasSamePathAndSiblingOrder() = runBlocking {
        TestEnv().use { env ->
            persistTree(env)

            val tree = MessageTree.from(env.messages.list(ConversationId("c1")))

            assertEquals(4, tree.size)
            assertEquals(
                listOf("m1", "m2", "m4"),
                tree.pathTo(MessageId("m4")).map { it.id.raw },
            )
            assertEquals(
                listOf("m1", "m3"),
                tree.pathTo(MessageId("m3")).map { it.id.raw },
            )
            assertEquals(
                listOf("m2", "m3"),
                tree.childrenOf(MessageId("m1")).map { it.id.raw },
            )
            assertEquals(
                listOf("m2", "m3"),
                tree.siblingsOf(MessageId("m3")).map { it.id.raw },
            )
            assertEquals(3L, tree.nextSeq(MessageId("m1")))
            assertEquals(1L, tree.nextSeq(MessageId("m4")))
            assertTrue(tree.canEditInPlace(MessageId("m4")))
            assertFalse(tree.canEditInPlace(MessageId("m2")))
            assertTrue(tree.validate(ConversationId("c1")).isEmpty())
        }
    }

    @Test
    fun removingBranchLeavesTheRestOfTheTreeConsistent() = runBlocking {
        TestEnv().use { env ->
            persistTree(env)

            env.messages.remove(setOf(MessageId("m3")))

            val tree = MessageTree.from(env.messages.list(ConversationId("c1")))
            assertEquals(3, tree.size)
            assertEquals(listOf("m2"), tree.childrenOf(MessageId("m1")).map { it.id.raw })
            assertEquals(listOf("m1", "m2", "m4"), tree.pathTo(MessageId("m4")).map { it.id.raw })
            assertTrue(tree.validate(ConversationId("c1")).isEmpty())
        }
    }

    @Test
    fun deletingANonLeafCascadesToItsSubtreeAndFtsIndex() = runBlocking {
        TestEnv().use { env ->
            persistTree(env)
            assertEquals(listOf("m2"), ftsMessageIds(env.db, "chosen"))

            // m4 is a child of m2: the self-referencing FK removes it as well.
            env.messages.remove(setOf(MessageId("m2")))

            val tree = MessageTree.from(env.messages.list(ConversationId("c1")))
            assertEquals(listOf("m1", "m3"), tree.all().map { it.id.raw })
            assertTrue(tree.validate(ConversationId("c1")).isEmpty())
            assertTrue(ftsMessageIds(env.db, "chosen").isEmpty())
            assertTrue(ftsMessageIds(env.db, "follow").isEmpty())
        }
    }

    @Test
    fun partsSurviveTheTreeRoundTrip() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val parts = listOf(
                MessagePart.Reasoning("plan", "sig-1"),
                MessagePart.Text("final"),
            )
            env.messages.append(message("m1", "c1", parts = parts))

            val stored = MessageTree.from(env.messages.list(ConversationId("c1")))
                .message(MessageId("m1"))!!
            assertEquals(parts, stored.parts)
        }
    }
}
