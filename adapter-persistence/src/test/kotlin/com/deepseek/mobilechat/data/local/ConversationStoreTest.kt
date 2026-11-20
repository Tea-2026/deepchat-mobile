package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {

    @Test
    fun upsertAndGetRoundTrip() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertFolder(Folder(FolderId("f1"), "Work"))
            env.foldersAndTags.upsertTag(Tag(TagId("t1"), "important"))
            val original = conversation(
                id = "c1",
                title = "Quarterly review",
                activeLeafId = "m9",
                folderId = "f1",
                tagIds = setOf("t1"),
                overrides = ConversationOverrides(
                    presetId = PresetId("p1"),
                    modelRef = ModelRef(ProviderId("prov"), ModelId("model")),
                    params = Defaults.params,
                    thinkingLevel = ThinkingLevel.MAX,
                    systemPrompt = "be terse",
                ),
                pinned = true,
            )

            env.conversations.upsert(original)

            assertEquals(original, env.conversations.get(ConversationId("c1")))
            assertEquals(setOf(TagId("t1")), env.conversations.get(ConversationId("c1"))!!.tagIds)
        }
    }

    @Test
    fun upsertUpdatesInPlaceWithoutCascadingTheMessageTree() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", text = "must survive"))

            env.conversations.upsert(conversation("c1", title = "Renamed", pinned = true))

            val reloaded = env.conversations.get(ConversationId("c1"))!!
            assertEquals("Renamed", reloaded.title)
            assertTrue(reloaded.pinned)
            assertEquals(1, env.messages.list(ConversationId("c1")).size)
        }
    }

    @Test
    fun upsertReplacesTagAssignments() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertTag(Tag(TagId("t1"), "one"))
            env.foldersAndTags.upsertTag(Tag(TagId("t2"), "two"))
            env.conversations.upsert(conversation("c1", tagIds = setOf("t1")))
            env.conversations.upsert(conversation("c1", tagIds = setOf("t2")))

            assertEquals(setOf(TagId("t2")), env.conversations.get(ConversationId("c1"))!!.tagIds)
        }
    }

    @Test
    fun deleteRemovesConversationAndOwnedRows() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertTag(Tag(TagId("t1"), "one"))
            env.conversations.upsert(conversation("c1", tagIds = setOf("t1")))
            env.messages.append(message("m1", "c1", text = "searchable needle"))
            env.injections.add(
                ContextInjection(
                    id = InjectionId("i1"),
                    conversationId = ConversationId("c1"),
                    anchorMessageId = null,
                    scope = InjectionScope.PERSISTENT,
                    position = 0,
                    content = "inject",
                    createdAt = 1L,
                ),
            )
            env.compactions.add(
                Compaction(
                    id = CompactionId("cp1"),
                    conversationId = ConversationId("c1"),
                    fromMessageId = MessageId("m1"),
                    toMessageId = MessageId("m1"),
                    summary = "summary",
                    kind = CompactionKind.SUMMARY,
                    createdAt = 2L,
                ),
            )

            env.conversations.delete(ConversationId("c1"))

            assertNull(env.conversations.get(ConversationId("c1")))
            assertTrue(env.messages.list(ConversationId("c1")).isEmpty())
            assertTrue(env.injections.list(ConversationId("c1")).isEmpty())
            assertTrue(env.compactions.list(ConversationId("c1")).isEmpty())
            assertTrue(env.db.query("SELECT 1 FROM conversation_tags", emptyList()) { true }.isEmpty())
            assertTrue("FTS must not keep deleted rows", ftsMessageIds(env.db, "needle").isEmpty())
        }
    }

    @Test
    fun searchMatchesMessageBodyDeduplicatedAndTitleFallback() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", title = "unrelated title", updatedAt = 10L))
            env.conversations.upsert(conversation("c2", title = "needle in title", updatedAt = 20L))
            env.conversations.upsert(conversation("c3", title = "other", updatedAt = 30L))
            env.messages.append(message("m1", "c1", text = "a needle in the body"))
            env.messages.append(message("m2", "c2", text = "needle appears here too"))

            val ids = env.conversations.search("needle").first().map { it.id.raw }
            assertEquals(listOf("c1", "c2"), ids.sorted())
        }
    }

    @Test
    fun searchOrdersPinnedAndRecentlyUpdatedFirst() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("old", title = "needle old", updatedAt = 10L))
            env.conversations.upsert(conversation("new", title = "needle new", updatedAt = 20L))
            env.conversations.upsert(conversation("pinned", title = "needle pinned", updatedAt = 5L, pinned = true))

            val ids = env.conversations.search("needle").first().map { it.id.raw }
            assertEquals(listOf("pinned", "new", "old"), ids)
        }
    }

    @Test
    fun searchEscapesLikeWildcards() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("percent", title = "100% complete"))
            env.conversations.upsert(conversation("decoy", title = "100 times complete"))

            val ids = env.conversations.search("100%").first().map { it.id.raw }
            assertEquals(listOf("percent"), ids)
        }
    }

    @Test
    fun searchFindsChineseBodyAsSubstringFallback() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", title = "没有关键词"))
            env.messages.append(message("m1", "c1", text = "这是一个测试消息，用于验证中文搜索。"))

            val ids = env.conversations.search("测试").first().map { it.id.raw }
            assertEquals(listOf("c1"), ids)
        }
    }

    @Test
    fun searchEmptyOrMalformedQueryReturnsWithoutThrowing() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", title = "title"))
            assertTrue(env.conversations.search("   ").first().isEmpty())
            // A lone quote is invalid FTS input; the store falls back to title LIKE.
            assertTrue(env.conversations.search("\"").first().isEmpty())
        }
    }

    @Test
    fun observeAllEmitsCurrentValueThenReemitsAfterWrite() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))

            assertEquals(listOf("c1"), env.conversations.observeAll().first().map { it.id.raw })

            val next = async {
                withTimeout(2_000) {
                    env.conversations.observeAll().drop(1).first()
                }
            }
            yield()
            env.conversations.upsert(conversation("c2"))

            val emitted = next.await().map { it.id.raw }.toSet()
            assertEquals(setOf("c1", "c2"), emitted)
        }
    }

    @Test
    fun observeByIdEmitsNullForMissingConversation() = runBlocking {
        TestEnv().use { env ->
            assertNull(env.conversations.observe(ConversationId("missing")).first())
        }
    }
}
