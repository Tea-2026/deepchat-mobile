package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.db.Db
import com.deepseek.mobilechat.data.local.db.DbException
import com.deepseek.mobilechat.data.local.db.Schema
import com.deepseek.mobilechat.data.local.log.verifyMigration
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.SurfaceOp
import com.deepseek.mobilechat.domain.log.TranscriptNode
import com.deepseek.mobilechat.domain.log.TranscriptProjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 -> v4 acceptance tests.
 *
 * The fixture is a real v3 database: build the current schema, drop the objects
 * that only v4 adds, stamp `user_version = 3`. Everything else (messages,
 * conversations, compactions, FTS triggers) is the shape v3 shipped, so the
 * upgrade path is exercised against the same DDL the old build wrote.
 */
class LegacyLogMigrationTest {

    private val cid = ConversationId("c1")

    @Test
    fun convertsTitleMessagesAndLeafIntoOneContiguousLog() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(
                conversation("c1", title = "Hello", activeLeafId = "a1", updatedAt = 9_000),
            )
            val u1 = message("u1", "c1", seq = 1, role = MessageRole.USER, text = "hi", createdAt = 1_000)
            val a1 = message("a1", "c1", parentId = "u1", seq = 1, role = MessageRole.ASSISTANT, createdAt = 2_000)
                .copy(
                    parts = listOf(
                        MessagePart.Text("hello"),
                        MessagePart.Reasoning("thinking", "sig"),
                        MessagePart.Image(AttachmentId("att-1")),
                        MessagePart.FileRef(AttachmentId("file-1")),
                    ),
                    usage = Usage(promptTokens = 10, completionTokens = 5, reasoningTokens = 2, cachedPromptTokens = 3, cost = 0.1),
                    modelInfo = modelInfo("prov", "model", ThinkingLevel.HIGH),
                    finishReason = FinishReason.STOP,
                )
            val u2 = message("u2", "c1", parentId = "a1", seq = 1, role = MessageRole.USER, text = "again", createdAt = 3_000)
            listOf(u1, a1, u2).forEach { env.messages.append(it) }

            Schema.migrate(env.db, fromVersion = 3)

            assertEquals(4, env.db.userVersion())
            val log = env.log.log(cid)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), log.events.map { it.seq })
            assertEquals(
                listOf(
                    EventType.SESSION_TITLE,
                    EventType.USER_MESSAGE,
                    EventType.ASSISTANT_MESSAGE,
                    EventType.USER_MESSAGE,
                    EventType.LEAF_CHANGED,
                ),
                log.events.map { it.type },
            )
            assertEquals(EventData.SessionTitle("Hello", "migrated"), log.events[0].data)
            assertEquals(1_000L, log.events[1].time)
            assertEquals(2_000L, log.events[2].time)
            assertEquals(3_000L, log.events[3].time)
            assertTrue(log.events.drop(1).take(3).all { it.surfaceOp == SurfaceOp.Append })

            val assistantEvent = log.events[2]
            val assistantData = assistantEvent.data as EventData.AssistantMessage
            assertEquals(MessageId("a1"), assistantData.messageId)
            assertEquals(MessageId("u1"), assistantData.parentId)
            assertEquals(a1.parts, assistantData.parts)
            assertEquals(a1.usage, assistantData.usage)
            assertEquals(a1.modelInfo, assistantData.modelInfo)
            assertEquals(FinishReason.STOP, assistantData.finishReason)
            assertEquals(EventData.LeafChanged(MessageId("a1")), log.events[4].data)

            assertTrue(verifyMigration(cid, listOf(u1, a1, u2), log).isEmpty())
            val transcript = TranscriptProjector.project(log)
            assertEquals("Hello", transcript.title)
            assertEquals(MessageId("a1"), transcript.activeLeafId)
            assertEquals(listOf("u1", "a1", "u2"), transcript.messages.map { it.id.raw })

            // The legacy table is kept, read-only, for the rollback window.
            assertEquals(3, env.messages.list(cid).size)
            assertEquals("Hello", env.conversations.get(cid)!!.title)
        }
    }

    @Test
    fun usesTopologicalOrderNotCreatedAtOrder() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1", title = "Branches"))
            val u1 = message("u1", "c1", seq = 1, role = MessageRole.USER, text = "u1", createdAt = 100)
            val a1 = message("a1", "c1", parentId = "u1", seq = 1, role = MessageRole.ASSISTANT, createdAt = 300)
            val u2 = message("u2", "c1", parentId = "u1", seq = 2, role = MessageRole.USER, text = "u2", createdAt = 200)
            // A child created before its parent: created_at order and tree order differ.
            val a2 = message("a2", "c1", parentId = "u2", seq = 1, role = MessageRole.ASSISTANT, createdAt = 150)
            listOf(u1, a1, u2, a2).forEach { env.messages.append(it) }

            Schema.migrate(env.db, fromVersion = 3)

            val log = env.log.log(cid)
            assertEquals(
                listOf("u1", "a1", "u2", "a2"),
                log.events
                    .mapNotNull { event ->
                        when (val data = event.data) {
                            is EventData.UserMessage -> data.messageId.raw
                            is EventData.AssistantMessage -> data.messageId.raw
                            else -> null
                        }
                    },
            )
            assertEquals(
                "the verifier requires legacy in the same computed order",
                emptyList<String>(),
                verifyMigration(cid, listOf(u1, a1, u2, a2), log),
            )
        }
    }

    @Test
    fun convertsAnActiveCompactionIntoStartStandInAndEnd() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1", title = "Compacted", activeLeafId = "m4"))
            val m1 = message("m1", "c1", seq = 1, role = MessageRole.USER, text = "m1", createdAt = 100)
            val m2 = message("m2", "c1", parentId = "m1", seq = 1, role = MessageRole.ASSISTANT, createdAt = 200)
            val m3 = message("m3", "c1", parentId = "m2", seq = 1, role = MessageRole.USER, text = "m3", createdAt = 300)
            val m4 = message("m4", "c1", parentId = "m3", seq = 1, role = MessageRole.ASSISTANT, createdAt = 400)
            listOf(m1, m2, m3, m4).forEach { env.messages.append(it) }
            env.compactions.add(
                Compaction(
                    id = CompactionId("cp1"),
                    conversationId = cid,
                    fromMessageId = MessageId("m1"),
                    toMessageId = MessageId("m3"),
                    summary = "summary of m1..m3",
                    kind = CompactionKind.SUMMARY,
                    createdAt = 5_000,
                ),
            )

            Schema.migrate(env.db, fromVersion = 3)

            val log = env.log.log(cid)
            val byType = log.events.groupBy { it.type }
            assertEquals(1, byType[EventType.COMPACTION_START]!!.size)
            assertEquals(1, byType[EventType.COMPACTION_END]!!.size)

            val seqOf = log.events.filter { it.type == EventType.USER_MESSAGE || it.type == EventType.ASSISTANT_MESSAGE }
                .associate { event ->
                    val messageId = when (val data = event.data) {
                        is EventData.UserMessage -> data.messageId.raw
                        is EventData.AssistantMessage -> data.messageId.raw
                        else -> error("unexpected")
                    }
                    messageId to event.seq
                }
            val start = byType[EventType.COMPACTION_START]!!.single()
            assertEquals(
                EventData.CompactionStart(
                    compactionId = CompactionId("cp1"),
                    strategyId = "legacy:summary",
                    targetFromSeq = seqOf.getValue("m1"),
                    targetToSeq = seqOf.getValue("m3"),
                    tokensBefore = 0,
                ),
                start.data,
            )

            val standIn = log.events.single {
                it.type == EventType.USER_MESSAGE && it.surfaceOp is SurfaceOp.Replace
            }
            assertEquals(SurfaceOp.Replace(seqOf.getValue("m1"), seqOf.getValue("m3")), standIn.surfaceOp)
            assertEquals(
                listOf(seqOf.getValue("m1"), seqOf.getValue("m2"), seqOf.getValue("m3")),
                standIn.sourceEventSeqs,
            )
            val standInData = standIn.data as EventData.UserMessage
            assertEquals(CompactionId("cp1"), standInData.compactionId)
            assertEquals(MessageId("compaction:cp1"), standInData.messageId)
            assertEquals(listOf(MessagePart.Text("summary of m1..m3")), standInData.parts)

            val end = byType[EventType.COMPACTION_END]!!.single()
            assertEquals(
                EventData.CompactionEnd(CompactionId("cp1"), standIn.seq, 0),
                end.data,
            )

            // The model-visible surface sees the stand-in, not the covered messages.
            val surface = SurfaceFolder.fold(log)
            assertEquals(2, surface.nodes.size)
            assertEquals(standIn.seq, surface.nodes[0].seq)
            assertEquals(CompactionId("cp1"), surface.nodes[0].compactionId)
            assertEquals(seqOf.getValue("m4"), surface.nodes[1].seq)

            // The transcript still has every original message plus a divider.
            val transcript = TranscriptProjector.project(log)
            assertEquals(listOf("m1", "m2", "m3", "m4"), transcript.messages.map { it.id.raw })
            val bar = transcript.nodes.filterIsInstance<TranscriptNode.CompactionBar>().single()
            assertEquals(CompactionId("cp1"), bar.compactionId)
            assertEquals(seqOf.getValue("m1"), bar.startSeq)
            assertEquals(seqOf.getValue("m3"), bar.endSeq)
            assertEquals("summary of m1..m3", bar.summary)
            assertTrue(verifyMigration(cid, listOf(m1, m2, m3, m4), log).isEmpty())
        }
    }

    @Test
    fun undoneCompactionsAreNotMigrated() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1"))
            val m1 = message("m1", "c1", seq = 1, role = MessageRole.USER, createdAt = 100)
            val m2 = message("m2", "c1", parentId = "m1", seq = 1, role = MessageRole.ASSISTANT, createdAt = 200)
            listOf(m1, m2).forEach { env.messages.append(it) }
            env.compactions.add(
                Compaction(
                    id = CompactionId("cp-undone"),
                    conversationId = cid,
                    fromMessageId = MessageId("m1"),
                    toMessageId = MessageId("m2"),
                    summary = "was undone",
                    kind = CompactionKind.MANUAL,
                    createdAt = 5_000,
                    undoneAt = 6_000,
                ),
            )

            Schema.migrate(env.db, fromVersion = 3)

            val log = env.log.log(cid)
            assertTrue(log.events.none { it.type == EventType.COMPACTION_START })
            assertTrue(log.events.none { it.type == EventType.COMPACTION_END })
            assertEquals(2, SurfaceFolder.fold(log).nodes.size)
            assertTrue(verifyMigration(cid, listOf(m1, m2), log).isEmpty())
        }
    }

    @Test
    fun overlappingCompactionsAreSkippedInsteadOfCorruptingTheLog() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1"))
            val m1 = message("m1", "c1", seq = 1, role = MessageRole.USER, createdAt = 100)
            val m2 = message("m2", "c1", parentId = "m1", seq = 1, role = MessageRole.ASSISTANT, createdAt = 200)
            val m3 = message("m3", "c1", parentId = "m2", seq = 1, role = MessageRole.USER, createdAt = 300)
            val m4 = message("m4", "c1", parentId = "m3", seq = 1, role = MessageRole.ASSISTANT, createdAt = 400)
            listOf(m1, m2, m3, m4).forEach { env.messages.append(it) }
            env.compactions.add(
                Compaction(
                    CompactionId("cp1"), cid, MessageId("m1"), MessageId("m3"),
                    "first", CompactionKind.SUMMARY, 5_000,
                ),
            )
            env.compactions.add(
                Compaction(
                    CompactionId("cp2"), cid, MessageId("m2"), MessageId("m4"),
                    "overlaps cp1", CompactionKind.SUMMARY, 6_000,
                ),
            )

            Schema.migrate(env.db, fromVersion = 3)

            val log = env.log.log(cid)
            // cp1 is representable; cp2's range is already shadowed, so it is
            // skipped rather than producing a replace the fold would reject.
            assertEquals(1, log.events.count { it.type == EventType.COMPACTION_START })
            assertEquals(
                listOf(CompactionId("cp1")),
                log.events.filter { it.type == EventType.COMPACTION_START }
                    .map { (it.data as EventData.CompactionStart).compactionId },
            )
            SurfaceFolder.fold(log) // must not throw
            assertEquals(4, TranscriptProjector.project(log).messages.size)
            assertTrue(verifyMigration(cid, listOf(m1, m2, m3, m4), log).isEmpty())
        }
    }

    @Test
    fun anEmptyConversationBecomesTitleAndLeafOnly() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("empty", title = "Empty", activeLeafId = null))

            Schema.migrate(env.db, fromVersion = 3)

            val log = env.log.log(ConversationId("empty"))
            assertEquals(listOf(EventType.SESSION_TITLE, EventType.LEAF_CHANGED), log.events.map { it.type })
            assertEquals(EventData.SessionTitle("Empty", "migrated"), log.events[0].data)
            assertEquals(EventData.LeafChanged(null), log.events[1].data)
            val transcript = TranscriptProjector.project(log)
            assertTrue(transcript.messages.isEmpty())
            assertEquals("Empty", transcript.title)
            assertTrue(verifyMigration(ConversationId("empty"), emptyList(), log).isEmpty())
        }
    }

    @Test
    fun everyConversationGetsItsOwnSeqRangeStartingAtOne() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("a", title = "A"))
            env.conversations.upsert(conversation("b", title = "B"))
            env.messages.append(message("a1", "a", seq = 1, role = MessageRole.USER, createdAt = 100))
            env.messages.append(message("b1", "b", seq = 1, role = MessageRole.USER, createdAt = 200))

            Schema.migrate(env.db, fromVersion = 3)

            listOf("a", "b").forEach { id ->
                val log = env.log.log(ConversationId(id))
                assertEquals(listOf(1L, 2L, 3L), log.events.map { it.seq })
                assertEquals(EventType.SESSION_TITLE, log.events[0].type)
                assertEquals(EventType.LEAF_CHANGED, log.events[2].type)
            }
            // Event ids are globally unique across conversations and stamped
            // deterministically so a replay re-run cannot duplicate them.
            val eventIds = listOf("a", "b").flatMap { id ->
                env.log.log(ConversationId(id)).events.map { it.eventId }
            }
            assertEquals(6, eventIds.size)
            assertEquals(6, eventIds.toSet().size)
        }
    }

    @Test
    fun rerunningTheStepDoesNotDuplicateAlreadyMigratedConversations() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1", title = "Once"))
            env.messages.append(message("m1", "c1", seq = 1, role = MessageRole.USER, createdAt = 100))

            Schema.migrate(env.db, fromVersion = 3)
            val before = env.log.log(cid).events.map { it.seq to it.eventId }

            // A hand-rolled downgrade of the version stamp must be harmless.
            env.db.setUserVersion(3)
            Schema.migrate(env.db, fromVersion = 3)

            assertEquals(4, env.db.userVersion())
            assertEquals(before, env.log.log(cid).events.map { it.seq to it.eventId })
        }
    }

    @Test
    fun aConversationThatCannotBeReplayedFaithfullyRollsBackTheWholeUpgrade() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1", title = "Lossy"))
            // A user row with usage is not representable: user/message has no
            // usage field. The verifier must catch the loss and refuse the
            // upgrade instead of silently dropping the numbers.
            env.messages.append(
                message("u1", "c1", seq = 1, role = MessageRole.USER, createdAt = 100)
                    .copy(usage = Usage(promptTokens = 42, completionTokens = 7)),
            )

            val failure = assertThrows(DbException::class.java) {
                Schema.migrate(env.db, fromVersion = 3)
            }
            assertTrue(failure.message.orEmpty().contains("not faithful"))

            assertEquals(3, env.db.userVersion())
            assertEquals(1, env.messages.list(cid).size)
            assertEquals(
                0,
                env.db.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'conversation_events'",
                    emptyList(),
                ) { true }.size,
            )
        }
    }

    @Test
    fun anUnsupportedLegacyRoleAbortsInsteadOfBeingDropped() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            env.conversations.upsert(conversation("c1"))
            env.messages.append(
                message("s1", "c1", seq = 1, role = MessageRole.SYSTEM, text = "system prompt", createdAt = 100),
            )

            assertThrows(DbException::class.java) {
                Schema.migrate(env.db, fromVersion = 3)
            }

            assertEquals(3, env.db.userVersion())
            assertEquals(1, env.messages.list(cid).size)
        }
    }

    @Test
    fun theV4StepCreatesTheTablesAndIndexesAndTheirShape() = runBlocking {
        TestEnv(schema = ::createV3Schema).use { env ->
            assertNull(
                env.db.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'conversation_events'",
                    emptyList(),
                ) { true }.firstOrNull(),
            )

            Schema.migrate(env.db, fromVersion = 3)

            val objects = env.db.query("SELECT type, name FROM sqlite_master", emptyList()) { row ->
                (row.string(0) ?: "") to (row.string(1) ?: "")
            }.toSet()
            listOf("conversation_events", "conversation_snapshots", "turn_drafts").forEach { table ->
                assertTrue("missing $table", ("table" to table) in objects)
            }
            listOf("idx_events_type", "idx_events_event_id").forEach { index ->
                assertTrue("missing $index", ("index" to index) in objects)
            }

            val eventColumns = env.db
                .query("PRAGMA table_info(conversation_events)", emptyList()) { it.string(1) ?: "" }
                .toSet()
            assertTrue(
                eventColumns.containsAll(
                    listOf(
                        "conversation_id",
                        "seq",
                        "event_id",
                        "type",
                        "schema_version",
                        "time",
                        "ignorable",
                        "surface_op_json",
                        "source_seqs_json",
                        "data_json",
                    ),
                ),
            )
            assertTrue(
                env.db
                    .query("PRAGMA table_info(turn_drafts)", emptyList()) { it.string(1) ?: "" }
                    .toSet()
                    .containsAll(listOf("message_id", "conversation_id", "text", "reasoning", "updated_at")),
            )
            assertTrue(
                env.db
                    .query("PRAGMA table_info(conversation_snapshots)", emptyList()) { it.string(1) ?: "" }
                    .toSet()
                    .containsAll(listOf("conversation_id", "version", "surface_json", "transcript_json")),
            )
        }
    }
}

/**
 * A real v3 database: the current DDL minus the objects v4 adds, stamped 3.
 * The v1-v3 shapes are already covered by `MigrationTest`; what matters here is
 * that the data looks like a v3 install (same `messages`/`conversations`
 * columns, FTS triggers, compactions table) and that `user_version` gates the
 * step exactly as on a device.
 */
private fun createV3Schema(db: Db) {
    Schema.create(db)
    db.exec("DROP TABLE IF EXISTS turn_drafts")
    db.exec("DROP TABLE IF EXISTS conversation_snapshots")
    db.exec("DROP TABLE IF EXISTS conversation_events")
    db.setUserVersion(3)
}
