package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.log.ConversationIndexPatch
import com.deepseek.mobilechat.application.log.PendingEvent
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.LogFormatException
import com.deepseek.mobilechat.domain.log.SurfaceOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SqliteConversationLogStore` against a real SQLite engine.
 *
 * The atomicity tests matter most here: a version is supposed to be one commit,
 * so a failure halfway through a batch must leave neither events nor the
 * conversation-index patch behind.
 */
class ConversationLogStoreTest {

    private val cid = ConversationId("c1")
    private val other = ConversationId("c2")

    @Test
    fun appendAssignsContiguousSeqsAndReturnsTheNewVersion() = runBlocking {
        TestEnv().use { env ->
            val first = env.log.append(
                cid,
                listOf(userEvent("u1", time = 1_000), assistantEvent("a1", parent = "u1"), userEvent("u2", time = 3_000)),
            ).unwrap()
            assertEquals(3L, first)

            val second = env.log.append(cid, listOf(userEvent("u3", time = 4_000))).unwrap()
            assertEquals(4L, second)

            val log = env.log.log(cid)
            assertEquals(4L, log.version)
            assertEquals(listOf(1L, 2L, 3L, 4L), log.events.map { it.seq })
            assertEquals(listOf("u1", "a1", "u2", "u3"), log.events.map { messageIdOf(it.data) })
        }
    }

    @Test
    fun sequenceNumbersAreScopedToOneConversation() = runBlocking {
        TestEnv().use { env ->
            env.log.append(cid, listOf(userEvent("a"), userEvent("b"))).unwrap()
            env.log.append(other, listOf(userEvent("c"))).unwrap()

            assertEquals(listOf(1L, 2L), env.log.log(cid).events.map { it.seq })
            assertEquals(listOf(1L), env.log.log(other).events.map { it.seq })
        }
    }

    @Test
    fun appendWritesTheConversationIndexInTheSameTransaction() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", title = "old title"))

            env.log.append(
                cid,
                listOf(userEvent("u1", time = 2_500)),
                ConversationIndexPatch(
                    title = "new title",
                    activeLeafId = MessageId("u1"),
                    updatedAt = 5_000L,
                    modelRef = ModelRef(ProviderId("prov"), ModelId("model")),
                    thinkingLevel = ThinkingLevel.HIGH,
                ),
            ).unwrap()

            val stored = env.conversations.get(cid)!!
            assertEquals("new title", stored.title)
            assertEquals(MessageId("u1"), stored.activeLeafId)
            assertEquals(5_000L, stored.updatedAt)
            assertEquals(ModelRef(ProviderId("prov"), ModelId("model")), stored.overrides.modelRef)
            assertEquals(ThinkingLevel.HIGH, stored.overrides.thinkingLevel)
        }
    }

    @Test
    fun indexUpdateDefaultsUpdatedAtToTheNewestAppendedEvent() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", updatedAt = 1L))
            env.log.append(
                cid,
                listOf(userEvent("u1", time = 1_100), userEvent("u2", time = 9_900)),
                ConversationIndexPatch(activeLeafId = MessageId("u2")),
            ).unwrap()
            assertEquals(9_900L, env.conversations.get(cid)!!.updatedAt)
        }
    }

    @Test
    fun aFailedInsertRollsBackTheWholeBatchIncludingTheIndexPatch() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", title = "before"))
            env.log.append(cid, listOf(pending("u1", eventId = "event-u1"))).unwrap()

            // The second insert collides with the globally unique event_id, so
            // the first insert and the index UPDATE of the same batch must be
            // rolled back together.
            val result = env.log.append(
                cid,
                listOf(
                    pending("u2", eventId = "event-u2"),
                    pending("u1", eventId = "event-u1"),
                ),
                ConversationIndexPatch(title = "must not stick"),
            )

            assertTrue("expected storage failure, was $result", result is Outcome.Err)
            assertTrue((result as Outcome.Err).error is AppError.Storage)
            assertEquals("before", env.conversations.get(cid)!!.title)
            assertEquals(listOf(1L), env.log.log(cid).events.map { it.seq })
        }
    }

    @Test
    fun appendRejectsARecordTypeCarryingASurfaceOpAndWritesNothing() = runBlocking {
        TestEnv().use { env ->
            val invalid = PendingEvent(
                eventId = "title-1",
                type = EventType.SESSION_TITLE,
                time = 1_000,
                data = EventData.SessionTitle("title"),
                surfaceOp = SurfaceOp.Append,
            )
            val result = env.log.append(cid, listOf(userEvent("u1"), invalid))

            assertTrue(result is Outcome.Err)
            assertTrue((result as Outcome.Err).error is AppError.Validation)
            // Even the valid event before the invalid one is not committed:
            // validation happens for the whole batch before the first insert.
            assertTrue(env.log.log(cid).events.isEmpty())
        }
    }

    @Test
    fun appendRejectsASourceEventSeqsOnARecordType() = runBlocking {
        TestEnv().use { env ->
            val invalid = PendingEvent(
                eventId = "turn-1",
                type = EventType.TURN_START,
                time = 1_000,
                data = EventData.TurnStart(MessageId("a1"), null, null),
                sourceEventSeqs = listOf(1L),
            )
            val result = env.log.append(cid, listOf(invalid))
            assertTrue((result as Outcome.Err).error is AppError.Validation)
            assertTrue(env.log.log(cid).events.isEmpty())
        }
    }

    @Test
    fun appendRejectsAReplaceRangeThatPointsIntoTheFuture() = runBlocking {
        TestEnv().use { env ->
            val invalid = PendingEvent(
                eventId = "u1",
                type = EventType.USER_MESSAGE,
                time = 1_000,
                data = EventData.UserMessage(MessageId("u1"), null, listOf(MessagePart.Text("x"))),
                surfaceOp = SurfaceOp.Replace(5, 6),
                sourceEventSeqs = listOf(5L, 6L),
            )
            val result = env.log.append(cid, listOf(invalid))
            assertTrue((result as Outcome.Err).error is AppError.Validation)
            assertTrue(env.log.log(cid).events.isEmpty())
        }
    }

    @Test
    fun appendWithAnIndexPatchFailsWhenTheConversationRowIsMissing() = runBlocking {
        TestEnv().use { env ->
            // Committing events the conversation list cannot see would split
            // the log from the index; the whole append is rejected instead.
            val result = env.log.append(cid, listOf(userEvent("u1")), ConversationIndexPatch(title = "x"))
            assertTrue(result is Outcome.Err)
            assertTrue((result as Outcome.Err).error is AppError.Storage)
            assertTrue(env.log.log(cid).events.isEmpty())
        }
    }

    @Test
    fun emptyBatchReturnsTheCurrentVersionAndStillWritesTheIndex() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1", title = "a"))
            assertEquals(0L, env.log.append(cid, emptyList(), ConversationIndexPatch(title = "b")).unwrap())
            assertEquals("b", env.conversations.get(cid)!!.title)

            env.log.append(cid, listOf(userEvent("u1"))).unwrap()
            assertEquals(1L, env.log.append(cid, emptyList(), ConversationIndexPatch(title = "c")).unwrap())
            assertEquals("c", env.conversations.get(cid)!!.title)
            assertEquals(1, env.log.log(cid).events.size)
        }
    }

    @Test
    fun logDecodesEveryPayloadItWrote() = runBlocking {
        TestEnv().use { env ->
            val samples = eventSamples()
            env.log.append(cid, samples.map { it.second }).unwrap()

            val log = env.log.log(cid)
            assertEquals(samples.map { it.second.type }, log.events.map { it.type })
            assertEquals(samples.map { it.second.data }, log.events.map { it.data })
            assertEquals(samples.map { it.second.surfaceOp }, log.events.map { it.surfaceOp })
            assertEquals(samples.map { it.second.time }, log.events.map { it.time })
            assertTrue(log.events.all { it.schemaVersion == 1 && !it.ignorable })
        }
    }

    @Test
    fun logRoundTripsAReplaceEventWithItsSourceNodes() = runBlocking {
        TestEnv().use { env ->
            env.log.append(cid, listOf(userEvent("u1"), userEvent("u2"))).unwrap()
            env.log.append(
                cid,
                listOf(
                    PendingEvent(
                        eventId = "replace-1",
                        type = EventType.USER_MESSAGE,
                        time = 3_000,
                        data = EventData.UserMessage(
                            MessageId("summary"),
                            MessageId("u2"),
                            listOf(MessagePart.Text("summary")),
                            compactionId = CompactionId("cp1"),
                        ),
                        surfaceOp = SurfaceOp.Replace(1, 2),
                        sourceEventSeqs = listOf(1L, 2L),
                    ),
                ),
            ).unwrap()

            val replace = env.log.log(cid).events.single { it.type == EventType.USER_MESSAGE && it.seq == 3L }
            assertEquals(SurfaceOp.Replace(1, 2), replace.surfaceOp)
            assertEquals(listOf(1L, 2L), replace.sourceEventSeqs)
            assertEquals(CompactionId("cp1"), (replace.data as EventData.UserMessage).compactionId)
        }
    }

    @Test
    fun logIsEmptyForAConversationWithoutEvents() = runBlocking {
        TestEnv().use { env ->
            val log = env.log.log(cid)
            assertTrue(log.events.isEmpty())
            assertEquals(0L, log.version)
        }
    }

    @Test
    fun logRejectsAnUnknownEventType() = runBlocking {
        TestEnv().use { env ->
            env.db.exec(
                "INSERT INTO conversation_events " +
                    "(conversation_id, seq, event_id, type, schema_version, time, ignorable, " +
                    "surface_op_json, source_seqs_json, data_json) " +
                    "VALUES ('c1', 1, 'raw-1', 'mystery/event', 1, 1, 0, NULL, NULL, '{}')",
            )
            val failure = runCatching { env.log.log(cid) }.exceptionOrNull()
            assertTrue("expected LogFormatException, was $failure", failure is LogFormatException)
        }
    }

    @Test
    fun logRejectsAPayloadVersionFromANewerBuild() = runBlocking {
        TestEnv().use { env ->
            env.db.exec(
                "INSERT INTO conversation_events " +
                    "(conversation_id, seq, event_id, type, schema_version, time, ignorable, " +
                    "surface_op_json, source_seqs_json, data_json) " +
                    "VALUES ('c1', 1, 'raw-1', 'session/title', 99, 1, 0, NULL, NULL, " +
                    "'{\"title\":\"t\",\"source\":\"manual\"}')",
            )
            val failure = runCatching { env.log.log(cid) }.exceptionOrNull()
            assertTrue("expected LogFormatException, was $failure", failure is LogFormatException)
            assertTrue(failure!!.message.orEmpty().contains("payload version 99"))
        }
    }

    @Test
    fun deleteRemovesEventsSnapshotsAndLegacyMessagesButNotTheConversationRow() = runBlocking {
        TestEnv().use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(message("m1", "c1"))
            env.log.append(cid, listOf(userEvent("u1"))).unwrap()
            env.snapshots.put(env.projector.project(cid, forceSnapshot = true))
            env.drafts.put(cid, MessageId("draft-1"), "streaming", "")

            env.log.delete(cid).unwrap()

            assertTrue(env.log.log(cid).events.isEmpty())
            assertNull(env.snapshots.latestVersion(cid))
            assertTrue(env.messages.list(cid).isEmpty())
            assertNull(env.drafts.get(MessageId("draft-1")))
            assertEquals("the index row is owned by ConversationStore", "c1", env.conversations.get(cid)!!.id.raw)
        }
    }

    // --------------------------------------------------------------- fixtures

    private fun messageIdOf(data: EventData): String = when (data) {
        is EventData.UserMessage -> data.messageId.raw
        is EventData.AssistantMessage -> data.messageId.raw
        else -> error("not a message payload: $data")
    }

    private fun pending(messageId: String, eventId: String = "event-$messageId"): PendingEvent =
        PendingEvent(
            eventId = eventId,
            type = EventType.USER_MESSAGE,
            time = 1_000,
            data = EventData.UserMessage(
                messageId = MessageId(messageId),
                parentId = null,
                parts = listOf(MessagePart.Text("text of $messageId")),
            ),
            surfaceOp = SurfaceOp.Append,
        )

    private fun userEvent(messageId: String, time: Long = 1_000): PendingEvent =
        PendingEvent(
            eventId = "event-$messageId",
            type = EventType.USER_MESSAGE,
            time = time,
            data = EventData.UserMessage(
                messageId = MessageId(messageId),
                parentId = null,
                parts = listOf(MessagePart.Text("text of $messageId")),
            ),
            surfaceOp = SurfaceOp.Append,
        )

    private fun assistantEvent(messageId: String, parent: String?, time: Long = 2_000): PendingEvent =
        PendingEvent(
            eventId = "event-$messageId",
            type = EventType.ASSISTANT_MESSAGE,
            time = time,
            data = EventData.AssistantMessage(
                messageId = MessageId(messageId),
                parentId = parent?.let(::MessageId),
                parts = listOf(MessagePart.Text("reply $messageId")),
                usage = Usage(promptTokens = 1, completionTokens = 2, cost = 0.01),
                finishReason = FinishReason.STOP,
            ),
            surfaceOp = SurfaceOp.Append,
        )

    /** One valid pending event per [EventType], in an order the fold accepts. */
    private fun eventSamples(): List<Pair<EventType, PendingEvent>> = listOf(
        EventType.SYSTEM_MESSAGE to PendingEvent(
            "e-system", EventType.SYSTEM_MESSAGE, 1_000,
            EventData.SystemMessage("system"),
            surfaceOp = SurfaceOp.Append,
        ),
        EventType.DEVELOPER_MESSAGE to PendingEvent(
            "e-developer", EventType.DEVELOPER_MESSAGE, 1_100,
            EventData.DeveloperMessage("dev", "context"),
            surfaceOp = SurfaceOp.Append,
        ),
        EventType.USER_MESSAGE to userEvent("u1", 1_200),
        EventType.ASSISTANT_MESSAGE to assistantEvent("a1", "u1", 1_300),
        EventType.TOOL_RESULT to PendingEvent(
            "e-tool", EventType.TOOL_RESULT, 1_400,
            EventData.ToolResult("call-1", "content", isError = false),
            surfaceOp = SurfaceOp.Append,
        ),
        EventType.TURN_START to PendingEvent(
            "e-turn-start", EventType.TURN_START, 1_500,
            EventData.TurnStart(MessageId("a2"), MessageId("u1"), null),
        ),
        EventType.TURN_END to PendingEvent(
            "e-turn-end", EventType.TURN_END, 1_600,
            EventData.TurnEnd(MessageId("a2"), FinishReason.STOP, Usage(1, 2), 100L),
        ),
        EventType.TURN_INTERRUPTED to PendingEvent(
            "e-interrupted", EventType.TURN_INTERRUPTED, 1_700,
            EventData.TurnInterrupted(MessageId("a3"), MessageId("u2"), "partial", "crash"),
        ),
        EventType.COMPACTION_START to PendingEvent(
            "e-compaction-start", EventType.COMPACTION_START, 1_800,
            EventData.CompactionStart(CompactionId("cp"), "strategy", 1, 2, 100),
        ),
        EventType.COMPACTION_END to PendingEvent(
            "e-compaction-end", EventType.COMPACTION_END, 1_900,
            EventData.CompactionEnd(CompactionId("cp"), 6, 10),
        ),
        EventType.COMPACTION_PRUNE to PendingEvent(
            "e-compaction-prune", EventType.COMPACTION_PRUNE, 2_000,
            EventData.CompactionPrune(CompactionId("cp"), listOf(1L, 2L)),
        ),
        EventType.IMAGE_OFFLOAD to PendingEvent(
            "e-offload", EventType.IMAGE_OFFLOAD, 2_100,
            EventData.ImageOffload(MessageId("a1"), listOf(com.deepseek.mobilechat.domain.AttachmentId("att")), "gone"),
        ),
        EventType.LEAF_CHANGED to PendingEvent(
            "e-leaf", EventType.LEAF_CHANGED, 2_200,
            EventData.LeafChanged(MessageId("a1")),
        ),
        EventType.MODEL_SELECTION to PendingEvent(
            "e-model", EventType.MODEL_SELECTION, 2_300,
            EventData.ModelSelection(
                ModelRef(ProviderId("p"), ModelId("m")),
                null,
                ThinkingLevel.LOW,
            ),
        ),
        EventType.SESSION_TITLE to PendingEvent(
            "e-title", EventType.SESSION_TITLE, 2_400,
            EventData.SessionTitle("title", "manual"),
        ),
    )
}

private fun <T> Outcome<T>.unwrap(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected Ok but got $error")
}
