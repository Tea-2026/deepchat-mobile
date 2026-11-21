package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnDraftStoreTest {

    private val cid = ConversationId("c1")

    @Test
    fun putThenGetRoundTripsTheDraft() = runBlocking {
        TestEnv().use { env ->
            env.drafts.put(cid, MessageId("a1"), text = "partial", reasoning = "thinking")
            val draft = env.drafts.get(MessageId("a1"))!!
            assertEquals(MessageId("a1"), draft.messageId)
            assertEquals(cid, draft.conversationId)
            assertEquals("partial", draft.text)
            assertEquals("thinking", draft.reasoning)
            assertEquals(env.clock.now, draft.updatedAt)
        }
    }

    @Test
    fun putOverwritesTheSingleRowAndRefreshesUpdatedAt() = runBlocking {
        TestEnv().use { env ->
            env.drafts.put(cid, MessageId("a1"), text = "one", reasoning = "r1")
            env.clock.now = 1_700_000_100_000L
            env.drafts.put(cid, MessageId("a1"), text = "one two", reasoning = "r2")

            val draft = env.drafts.get(MessageId("a1"))!!
            assertEquals("one two", draft.text)
            assertEquals("r2", draft.reasoning)
            assertEquals(1_700_000_100_000L, draft.updatedAt)
            assertEquals(1L, env.db.query("SELECT COUNT(*) FROM turn_drafts", emptyList()) { it.long(0) }.single())
        }
    }

    @Test
    fun allReturnsEveryDraftOrderedByUpdateTime() = runBlocking {
        TestEnv().use { env ->
            env.drafts.put(ConversationId("c1"), MessageId("a1"), "a1", "")
            env.clock.now = 1_700_000_050_000L
            env.drafts.put(ConversationId("c2"), MessageId("a2"), "a2", "")
            env.clock.now = 1_700_000_200_000L
            env.drafts.put(ConversationId("c1"), MessageId("a1"), "a1 updated", "")

            val all = env.drafts.all()
            assertEquals(listOf("a2", "a1"), all.map { it.messageId.raw })
            assertEquals("a1 updated", all.last().text)
        }
    }

    @Test
    fun deleteRemovesOnlyThatDraft() = runBlocking {
        TestEnv().use { env ->
            env.drafts.put(cid, MessageId("a1"), "a1", "")
            env.drafts.put(cid, MessageId("a2"), "a2", "")
            env.drafts.delete(MessageId("a1"))
            assertNull(env.drafts.get(MessageId("a1")))
            assertEquals(listOf(MessageId("a2")), env.drafts.all().map { it.messageId })
        }
    }

    @Test
    fun getReturnsNullForAMissingDraft() = runBlocking {
        TestEnv().use { env ->
            assertNull(env.drafts.get(MessageId("nope")))
            assertTrue(env.drafts.all().isEmpty())
        }
    }
}
