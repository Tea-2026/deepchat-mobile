package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
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

class MessageStoreTest {

    @Test
    fun appendAndGetRoundTripPreservesEveryColumn() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val modelInfo = ModelInfo(
                ref = ModelRef(ProviderId("prov"), ModelId("model-x")),
                params = SamplingParams(temperature = 0.2, topP = 0.8, maxTokens = 512, stop = listOf("\n\n")),
                thinkingLevel = ThinkingLevel.HIGH,
            )
            val original = message(
                id = "m1",
                conversationId = "c1",
                parentId = null,
                seq = 3,
                role = MessageRole.ASSISTANT,
                status = MessageStatus.COMPLETE,
                parts = listOf(
                    MessagePart.Reasoning("thought", "sig"),
                    MessagePart.Text("answer"),
                ),
                createdAt = 5_000L,
                editedAt = 6_000L,
                pinned = true,
            ).copy(
                modelInfo = modelInfo,
                usage = Usage(promptTokens = 10, completionTokens = 20, reasoningTokens = 3, costUsd = 0.001),
                finishReason = FinishReason.STOP,
            )

            env.messages.append(original)

            assertEquals(original, env.messages.get(MessageId("m1")))
        }
    }

    @Test
    fun listOrdersByCreationTimeThenSeq() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m3", "c1", seq = 1, createdAt = 300L))
            env.messages.append(message("m1", "c1", seq = 3, createdAt = 100L))
            env.messages.append(message("m2", "c1", seq = 2, createdAt = 200L))

            assertEquals(
                listOf("m1", "m2", "m3"),
                env.messages.list(ConversationId("c1")).map { it.id.raw },
            )
        }
    }

    @Test
    fun updateRewritesMutableColumns() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", status = MessageStatus.STREAMING, text = "partial"))

            val updated = env.messages.get(MessageId("m1"))!!.copy(
                parts = listOf(MessagePart.Text("complete answer")),
                status = MessageStatus.COMPLETE,
                editedAt = 42L,
                finishReason = FinishReason.STOP,
                usage = Usage(promptTokens = 1, completionTokens = 2),
            )
            env.messages.update(updated)

            assertEquals(updated, env.messages.get(MessageId("m1")))
            assertEquals(
                listOf("m1"),
                ftsMessageIds(env.db, "complete"),
            )
        }
    }

    @Test
    fun removeDeletesOnlyRequestedIds() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", seq = 1, createdAt = 1L))
            env.messages.append(message("m2", "c1", seq = 2, createdAt = 2L))

            env.messages.remove(setOf(MessageId("m1")))

            assertNull(env.messages.get(MessageId("m1")))
            assertEquals(listOf("m2"), env.messages.list(ConversationId("c1")).map { it.id.raw })
        }
    }

    @Test
    fun removeEmptySetIsNoOp() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1"))
            env.messages.remove(emptySet())
            assertEquals(1, env.messages.list(ConversationId("c1")).size)
        }
    }

    @Test
    fun appendRejectsDanglingParentBecauseTreeIntegrityIsEnforced() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            val failure = runCatching {
                env.messages.append(message("m1", "c1", parentId = "does-not-exist"))
            }.exceptionOrNull()

            assertTrue("expected DbException, got $failure", failure is DbException)
        }
    }

    @Test
    fun appendRejectsUnknownConversation() = runBlocking {
        TestEnv().use { env ->
            val failure = runCatching {
                env.messages.append(message("m1", "missing"))
            }.exceptionOrNull()

            assertTrue("expected DbException, got $failure", failure is DbException)
        }
    }

    @Test
    fun observeEmitsCurrentValueThenReemitsAfterAppend() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1"))

            assertEquals(1, env.messages.observe(ConversationId("c1")).first().size)

            val next = async {
                withTimeout(2_000) {
                    env.messages.observe(ConversationId("c1")).drop(1).first()
                }
            }
            yield()
            env.messages.append(message("m2", "c1", seq = 2))

            assertEquals(2, next.await().size)
        }
    }
}
