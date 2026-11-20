package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionStoreTest {

    private fun compaction(
        id: String,
        createdAt: Long = 1_000L,
        kind: CompactionKind = CompactionKind.SUMMARY,
    ) = Compaction(
        id = CompactionId(id),
        conversationId = ConversationId("c1"),
        fromMessageId = MessageId("m1"),
        toMessageId = MessageId("m9"),
        summary = "summary of $id",
        kind = kind,
        createdAt = createdAt,
    )

    @Test
    fun addAndListRoundTrip() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.compactions.add(compaction("cp2", createdAt = 200L, kind = CompactionKind.MANUAL))
            env.compactions.add(compaction("cp1", createdAt = 100L))

            val rows = env.compactions.list(ConversationId("c1"))
            assertEquals(listOf("cp1", "cp2"), rows.map { it.id.raw })
            assertEquals(CompactionKind.MANUAL, rows.last().kind)
            assertNull(rows.first().undoneAt)
        }
    }

    @Test
    fun addExistingIdUpdatesInsteadOfDuplicating() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.compactions.add(compaction("cp1"))
            env.compactions.add(compaction("cp1").copy(summary = "rewritten"))

            val rows = env.compactions.list(ConversationId("c1"))
            assertEquals(1, rows.size)
            assertEquals("rewritten", rows.single().summary)
        }
    }

    @Test
    fun markUndoneIsIdempotentAndIgnoresUnknownIds() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.compactions.add(compaction("cp1"))

            env.compactions.markUndone(CompactionId("missing"), atMillis = 10L)
            env.compactions.markUndone(CompactionId("cp1"), atMillis = 20L)

            assertEquals(20L, env.compactions.list(ConversationId("c1")).single().undoneAt)
        }
    }

    @Test
    fun observeEmitsCompactionsForConversationOnly() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.conversations.upsert(conversation("c2"))
            env.compactions.add(compaction("cp1"))

            val rows = env.compactions.observe(ConversationId("c1")).first()
            assertEquals(listOf("cp1"), rows.map { it.id.raw })
            assertTrue(env.compactions.observe(ConversationId("c2")).first().isEmpty())
        }
    }
}
