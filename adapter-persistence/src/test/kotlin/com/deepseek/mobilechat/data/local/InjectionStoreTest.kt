package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionStoreTest {

    private fun injection(
        id: String,
        position: Int = 0,
        createdAt: Long = 1_000L,
        content: String = "content $id",
        scope: InjectionScope = InjectionScope.PERSISTENT,
    ) = ContextInjection(
        id = InjectionId(id),
        conversationId = ConversationId("c1"),
        anchorMessageId = MessageId("m1"),
        scope = scope,
        position = position,
        content = content,
        createdAt = createdAt,
    )

    @Test
    fun listOrdersByPositionThenCreationTime() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.injections.add(injection("late", position = 2, createdAt = 10L))
            env.injections.add(injection("first", position = 1, createdAt = 20L))
            env.injections.add(injection("second", position = 2, createdAt = 5L))

            assertEquals(
                listOf("first", "second", "late"),
                env.injections.list(ConversationId("c1")).map { it.id.raw },
            )
        }
    }

    @Test
    fun addWithExistingIdUpdatesInPlace() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.injections.add(injection("i1", content = "first"))
            env.injections.add(injection("i1", content = "second", scope = InjectionScope.EPHEMERAL))

            val rows = env.injections.list(ConversationId("c1"))
            assertEquals(1, rows.size)
            assertEquals("second", rows.single().content)
            assertEquals(InjectionScope.EPHEMERAL, rows.single().scope)
        }
    }

    @Test
    fun removeDeletesInjection() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.injections.add(injection("i1"))
            env.injections.remove(InjectionId("i1"))

            assertTrue(env.injections.list(ConversationId("c1")).isEmpty())
        }
    }

    @Test
    fun observeEmitsCurrentInjections() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.injections.add(injection("i1"))
            assertEquals(listOf("i1"), env.injections.observe(ConversationId("c1")).first().map { it.id.raw })
        }
    }

    @Test
    fun markingCompactionUndonePersistsTimestamp() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.compactions.add(
                Compaction(
                    id = CompactionId("cp1"),
                    conversationId = ConversationId("c1"),
                    fromMessageId = MessageId("m1"),
                    toMessageId = MessageId("m2"),
                    summary = "summary",
                    kind = CompactionKind.SUMMARY,
                    createdAt = 100L,
                ),
            )

            assertNull(env.compactions.list(ConversationId("c1")).single().undoneAt)
            env.compactions.markUndone(CompactionId("cp1"), atMillis = 500L)
            assertEquals(500L, env.compactions.list(ConversationId("c1")).single().undoneAt)
        }
    }
}
