package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.db.Db
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
                usage = Usage(promptTokens = 10, completionTokens = 20, reasoningTokens = 3, cost = 0.001),
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

    @Test
    fun appendWritesTheDenormalisedUsageAndModelColumns() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(
                message("m1", "c1", role = MessageRole.ASSISTANT).copy(
                    modelInfo = modelInfo(providerId = "prov-a", modelId = "model-a"),
                    usage = Usage(
                        promptTokens = 11,
                        completionTokens = 7,
                        reasoningTokens = 2,
                        cachedPromptTokens = 3,
                        cost = 0.25,
                    ),
                ),
            )

            assertEquals(
                listOf<Any?>(11L, 3L, 7L, 0.25, "model-a", "prov-a"),
                env.db.denormalisedUsage("m1"),
            )
        }
    }

    @Test
    fun appendingAMessageWithoutUsageLeavesTheDenormalisedColumnsNull() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", role = MessageRole.USER))

            assertEquals(
                listOf<Any?>(null, null, null, null, null, null),
                env.db.denormalisedUsage("m1"),
            )
        }
    }

    @Test
    fun streamingCheckpointUpdateRewritesTheDenormalisedUsageColumns() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1", status = MessageStatus.STREAMING))
            assertEquals(
                listOf<Any?>(null, null, null, null, null, null),
                env.db.denormalisedUsage("m1"),
            )

            // A checkpoint is an `update` on the streaming message with the
            // usage reported so far.
            env.messages.update(
                env.messages.get(MessageId("m1"))!!.copy(
                    modelInfo = modelInfo(providerId = "prov-a", modelId = "model-a"),
                    usage = Usage(promptTokens = 5, completionTokens = 2, cachedPromptTokens = 1, cost = 0.01),
                ),
            )
            assertEquals(
                listOf<Any?>(5L, 1L, 2L, 0.01, "model-a", "prov-a"),
                env.db.denormalisedUsage("m1"),
            )

            // A later checkpoint replaces the numbers instead of adding to
            // them: the columns mirror the latest Usage, not a running sum.
            env.messages.update(
                env.messages.get(MessageId("m1"))!!.copy(
                    status = MessageStatus.COMPLETE,
                    usage = Usage(promptTokens = 9, completionTokens = 6, cachedPromptTokens = 4, cost = 0.02),
                ),
            )
            assertEquals(
                listOf<Any?>(9L, 4L, 6L, 0.02, "model-a", "prov-a"),
                env.db.denormalisedUsage("m1"),
            )
        }
    }
}

/** The v3 columns for one message, in schema order. */
private fun Db.denormalisedUsage(id: String): List<Any?> = query(
    """
    SELECT usage_prompt_tokens, usage_cached_tokens, usage_completion_tokens,
           usage_cost_cny, model_id, provider_id
    FROM messages
    WHERE id = ?
    """.trimIndent(),
    listOf(id),
) { row ->
    listOf(
        if (row.isNull(0)) null else row.long(0),
        if (row.isNull(1)) null else row.long(1),
        if (row.isNull(2)) null else row.long(2),
        row.double(3),
        row.string(4),
        row.string(5),
    )
}.single()
