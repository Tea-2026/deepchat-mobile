package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.log.PendingEvent
import com.deepseek.mobilechat.data.local.log.ConversationProjector
import com.deepseek.mobilechat.data.local.log.SnapshotPolicy
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceOp
import com.deepseek.mobilechat.domain.log.TranscriptNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot table is an optimisation, never the truth.
 *
 * These tests pin both halves of that sentence: a hit must reproduce the folds
 * exactly, and every kind of miss — absent, stale (another version), corrupt —
 * must fall back to folding without affecting the result.
 */
class ConversationSnapshotCacheTest {

    private val cid = ConversationId("c1")

    /**
     * Version layout of the fixture:
     * 1 title, 2 u1, 3 u2, 4 compaction/start, 5 stand-in, 6 compaction/end,
     * 7 leaf/changed.
     */
    private val historyVersion = 7L

    @Test
    fun getReturnsNullWhenThereIsNoRow() = runBlocking {
        TestEnv().use { env ->
            assertNull(env.snapshots.get(cid, 1))
            assertNull(env.snapshots.latestVersion(cid))
        }
    }

    @Test
    fun putThenGetRoundTripsBothProjections() = runBlocking {
        TestEnv().use { env ->
            appendHistory(env)
            val projection = env.projector.project(cid, forceSnapshot = true)
            assertEquals(historyVersion, projection.version)

            val cached = env.snapshots.get(cid, projection.version)!!
            assertEquals(projection, cached)
            assertEquals(historyVersion, env.snapshots.latestVersion(cid))
            assertEquals(2, cached.transcript.messages.size)
            assertEquals("History", cached.transcript.title)
            assertEquals(MessageId("a1"), cached.transcript.activeLeafId)
            assertTrue(cached.transcript.nodes.any { it is TranscriptNode.CompactionBar })
            // The replace op shadows both appended messages and installs the
            // stand-in, so the surface has exactly one node.
            assertEquals(1, cached.surface.nodes.size)
        }
    }

    @Test
    fun getOnlyMatchesTheExactVersion() = runBlocking {
        TestEnv().use { env ->
            appendHistory(env)
            val projection = env.projector.project(cid, forceSnapshot = true)
            assertEquals(historyVersion, projection.version)

            assertTrue(env.snapshots.get(cid, historyVersion) != null)
            assertNull(
                "a different version must be a miss, not a best-effort hit",
                env.snapshots.get(cid, historyVersion - 1),
            )
            assertNull(env.snapshots.get(cid, historyVersion + 1))
        }
    }

    @Test
    fun aCorruptSnapshotRowIsAMissInsteadOfAnError() = runBlocking {
        TestEnv().use { env ->
            appendHistory(env)
            env.db.exec(
                "INSERT INTO conversation_snapshots " +
                    "(conversation_id, version, surface_json, transcript_json) " +
                    "VALUES ('c1', 7, '{not json', 'also not json')",
            )
            assertNull(env.snapshots.get(cid, historyVersion))

            // ... and the projector still produces the real thing.
            val projection = env.projector.project(cid)
            assertEquals(historyVersion, projection.version)
            assertEquals(2, projection.transcript.messages.size)
        }
    }

    @Test
    fun aStaleSnapshotDoesNotAffectTheResultOfAFold() = runBlocking {
        TestEnv().use { env ->
            // A row from an older log shape, at an older version (the empty log).
            val stale = env.projector.project(cid, forceSnapshot = true)
            assertEquals(0L, stale.version)

            appendHistory(env)
            env.snapshots.put(stale)

            val projection = env.projector.project(cid)
            assertEquals("must be the current log version", historyVersion, projection.version)
            assertEquals(2, projection.transcript.messages.size)
            assertEquals("History", projection.transcript.title)
        }
    }

    @Test
    fun projectorRecomputesFromTheLogWhenTheCacheIsEmpty() = runBlocking {
        TestEnv().use { env ->
            appendHistory(env)
            // The default policy does not write below 200 events, so this is
            // exactly the cold path: every open folds the log.
            val projection = env.projector.project(cid)
            assertEquals(historyVersion, projection.version)
            assertEquals(2, projection.transcript.messages.size)
            assertNull("policy says no snapshot yet", env.snapshots.latestVersion(cid))
        }
    }

    @Test
    fun policyWritesAfterTheConfiguredNumberOfAppendedEvents() = runBlocking {
        TestEnv().use { env ->
            val projector = ConversationProjector(env.log, env.snapshots, SnapshotPolicy(eventThreshold = 3))
            env.log.append(cid, listOf(messageEvent("u1", 1_000), messageEvent("u2", 2_000))).unwrap()

            assertEquals(2L, projector.project(cid).version)
            assertNull("2 events are below the threshold", env.snapshots.latestVersion(cid))

            env.log.append(cid, listOf(messageEvent("u3", 3_000))).unwrap()
            assertEquals(3L, projector.project(cid).version)
            assertEquals(3L, env.snapshots.latestVersion(cid))

            env.log.append(cid, listOf(messageEvent("u4", 4_000))).unwrap()
            assertEquals(4L, projector.project(cid).version)
            assertEquals("4 is only one event past the snapshot", 3L, env.snapshots.latestVersion(cid))

            projector.project(cid, forceSnapshot = true)
            assertEquals("an explicit request always writes", 4L, env.snapshots.latestVersion(cid))
        }
    }

    @Test
    fun defaultPolicyIsTwoHundredAppendedEvents() {
        val policy = SnapshotPolicy()
        assertEquals(200, policy.eventThreshold)
        assertTrue(!policy.shouldWrite(0, 199))
        assertTrue(policy.shouldWrite(0, 200))
        assertTrue(!policy.shouldWrite(200, 399))
        assertTrue(policy.shouldWrite(200, 400))
        assertTrue(policy.shouldWrite(200, 201, force = true))
    }

    @Test
    fun deleteRemovesEveryVersionForThatConversation() = runBlocking {
        TestEnv().use { env ->
            appendHistory(env)
            val projection = env.projector.project(cid, forceSnapshot = true)
            env.snapshots.put(projection.copy(version = 2))
            assertEquals(historyVersion, env.snapshots.latestVersion(cid))

            env.snapshots.delete(cid)
            assertNull(env.snapshots.latestVersion(cid))
            assertNull(env.snapshots.get(cid, historyVersion))
        }
    }

    // --------------------------------------------------------------- fixtures

    /**
     * A log with: title, two user messages, a compaction start + replacement +
     * end (so the transcript has a bar and the surface has a stand-in), and a
     * leaf.
     */
    private suspend fun appendHistory(env: TestEnv) {
        env.log.append(
            cid,
            listOf(
                PendingEvent("e-title", EventType.SESSION_TITLE, 500, EventData.SessionTitle("History")),
                messageEvent("u1", 1_000),
                messageEvent("u2", 2_000),
            ),
        ).unwrap()
        env.log.append(
            cid,
            listOf(
                PendingEvent(
                    "e-compaction-start",
                    EventType.COMPACTION_START,
                    5_000,
                    EventData.CompactionStart(CompactionId("cp1"), "legacy:summary", 2, 3, 0),
                ),
                PendingEvent(
                    "e-stand-in",
                    EventType.USER_MESSAGE,
                    5_000,
                    EventData.UserMessage(
                        MessageId("compaction:cp1"),
                        MessageId("u2"),
                        listOf(MessagePart.Text("summary")),
                        CompactionId("cp1"),
                    ),
                    surfaceOp = SurfaceOp.Replace(2, 3),
                    sourceEventSeqs = listOf(2L, 3L),
                ),
                PendingEvent(
                    "e-compaction-end",
                    EventType.COMPACTION_END,
                    5_000,
                    EventData.CompactionEnd(CompactionId("cp1"), 5, 0),
                ),
                PendingEvent("e-leaf", EventType.LEAF_CHANGED, 6_000, EventData.LeafChanged(MessageId("a1"))),
            ),
        ).unwrap()
    }

    private fun messageEvent(messageId: String, time: Long): PendingEvent = PendingEvent(
        eventId = "event-$messageId",
        type = EventType.USER_MESSAGE,
        time = time,
        data = EventData.UserMessage(
            MessageId(messageId),
            if (messageId == "u2") MessageId("u1") else null,
            listOf(MessagePart.Text("text of $messageId")),
        ),
        surfaceOp = SurfaceOp.Append,
    )
}

private fun <T> Outcome<T>.unwrap(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected Ok but got $error")
}
