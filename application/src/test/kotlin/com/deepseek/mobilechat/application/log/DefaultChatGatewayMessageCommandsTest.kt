package com.deepseek.mobilechat.application.log

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.log.testutil.LogHarness
import com.deepseek.mobilechat.application.log.testutil.assistantEvent
import com.deepseek.mobilechat.application.log.testutil.leafEvent
import com.deepseek.mobilechat.application.log.testutil.seedConversation
import com.deepseek.mobilechat.application.log.testutil.userEvent
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SessionEvent
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import com.deepseek.mobilechat.domain.log.TranscriptNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `setPinned` / `deleteMessage`: batch composition, idempotency, leaf movement,
 * tombstone semantics and the overlay invariant (I11).
 *
 * Everything below drives the real [DefaultChatGateway] over the in-memory log
 * store from [LogHarness]; no fake ever replaces the gateway itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultChatGatewayMessageCommandsTest {

    private suspend fun DefaultChatGateway.snapshotOf(
        conversationId: ConversationId,
    ): ChatSnapshot = snapshots(conversationId).first()

    private fun pinEvent(seq: Long, messageId: String, pinned: Boolean): SessionEvent = SessionEvent(
        seq = seq,
        eventId = "event-$seq",
        type = EventType.MESSAGE_PINNED,
        time = seq * 1_000L,
        data = EventData.MessagePinned(MessageId(messageId), pinned),
    )

    private fun deletedEvent(seq: Long, messageId: String): SessionEvent = SessionEvent(
        seq = seq,
        eventId = "event-$seq",
        type = EventType.MESSAGE_DELETED,
        time = seq * 1_000L,
        data = EventData.MessageDeleted(MessageId(messageId)),
    )

    private fun LogHarness.seedExchange(conversationId: ConversationId) {
        seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
        )
    }

    // -----------------------------------------------------------------------
    // setPinned
    // -----------------------------------------------------------------------

    @Test
    fun `pin appends exactly one record event and pins the transcript row`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedExchange(conversationId)
        val gateway = harness.gateway()
        val surfaceBefore = SurfaceFolder.fold(harness.logStore.logOf(conversationId)).nodes.size

        val outcome = gateway.setPinned(MessageId("u1"), true)

        assertTrue(outcome is Outcome.Ok)
        val batch = harness.logStore.appends.single()
        assertEquals(listOf(EventType.MESSAGE_PINNED), batch.events.map { it.type })
        assertEquals(EventData.MessagePinned(MessageId("u1"), pinned = true), batch.events.single().data)
        assertNull("pinned state must stay out of the index", batch.index)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(3L, snapshot.version)
        val transcriptRow = snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>()
            .single { it.message.id == MessageId("u1") }
        assertTrue(transcriptRow.message.pinned)
        assertFalse(
            snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>()
                .single { it.message.id == MessageId("a1") }
                .message.pinned,
        )
        // The row is pinned in `messages` too, and nothing else moved.
        assertTrue(snapshot.messages.single { it.id == MessageId("u1") }.pinned)
        assertEquals(2, snapshot.messages.size)
        assertEquals(MessageId("a1"), snapshot.activeLeafId)
        // A record event never enters the model-visible surface.
        assertEquals(
            surfaceBefore,
            SurfaceFolder.fold(harness.logStore.logOf(conversationId)).nodes.size,
        )
        // The log is the truth: a brand-new gateway over the same store replays it.
        assertTrue(
            harness.gateway().snapshotOf(conversationId).messages
                .first { it.id == MessageId("u1") }.pinned,
        )
        assertNull(snapshot.overlay.busy)
    }

    @Test
    fun `unpin appends one event and clears the flag`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            pinEvent(2, "u1", true),
        )
        val gateway = harness.gateway()
        assertTrue(gateway.snapshotOf(conversationId).messages.single().pinned)

        val outcome = gateway.setPinned(MessageId("u1"), false)

        assertTrue(outcome is Outcome.Ok)
        val batch = harness.logStore.appends.single()
        assertEquals(listOf(EventType.MESSAGE_PINNED), batch.events.map { it.type })
        assertEquals(EventData.MessagePinned(MessageId("u1"), pinned = false), batch.events.single().data)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(3L, snapshot.version)
        assertFalse(snapshot.messages.single().pinned)
        assertFalse(
            snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>().single().message.pinned,
        )
        assertNull(snapshot.overlay.busy)
    }

    @Test
    fun `pinning an already pinned message appends nothing and returns Ok`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
            pinEvent(3, "u1", true),
        )
        val gateway = harness.gateway()

        val outcome = gateway.setPinned(MessageId("u1"), true)

        assertTrue("a no-op pin is still a success: $outcome", outcome is Outcome.Ok)
        assertEquals("a no-op is not an event", 0, harness.logStore.appends.size)
        assertEquals(3, harness.logStore.events(conversationId).size)
        assertEquals(3L, gateway.snapshotOf(conversationId).version)
        assertNull(gateway.snapshotOf(conversationId).overlay.busy)

        // The same rule applies in the other direction.
        val stillUnpinned = gateway.setPinned(MessageId("a1"), false)
        assertTrue("an unpin of an unpinned message is a success: $stillUnpinned", stillUnpinned is Outcome.Ok)
        assertEquals(0, harness.logStore.appends.size)
    }

    // -----------------------------------------------------------------------
    // deleteMessage
    // -----------------------------------------------------------------------

    private fun LogHarness.seedTwoExchanges(conversationId: ConversationId) {
        seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
            userEvent(3, "u2", "q2", parentId = "a1"),
            assistantEvent(4, "a2", "r2", parentId = "u2"),
        )
    }

    @Test
    fun `delete removes the message and its reply and leaves earlier exchanges alone`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedTwoExchanges(conversationId)
        val gateway = harness.gateway()

        val outcome = gateway.deleteMessage(MessageId("u2"))

        assertTrue(outcome is Outcome.Ok)
        // One command = one batch = one version bump.
        assertEquals(1, harness.logStore.appends.size)
        val batch = harness.logStore.appends.single()
        assertEquals(
            listOf(EventType.MESSAGE_DELETED, EventType.MESSAGE_DELETED, EventType.LEAF_CHANGED),
            batch.events.map { it.type },
        )
        assertEquals(
            "descendants of u2, root first",
            listOf(MessageId("u2"), MessageId("a2")),
            batch.events.filter { it.type == EventType.MESSAGE_DELETED }
                .map { (it.data as EventData.MessageDeleted).messageId },
        )

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals("4 seeded events + 2 tombstones + 1 leaf move", 7L, snapshot.version)
        // Nothing was physically removed from the log…
        assertEquals(7, harness.logStore.events(conversationId).size)
        // …the earlier exchange is untouched, and the deleted one is gone.
        assertEquals(listOf(MessageId("u1"), MessageId("a1")), snapshot.messages.map { it.id })
        assertEquals("q1", snapshot.messages.first().text)
        assertEquals("r1", snapshot.messages.last().text)
        assertEquals(
            listOf(MessageId("u1"), MessageId("a1")),
            snapshot.transcript.filterIsInstance<TranscriptNode.MessageNode>()
                .map { it.message.id },
        )
    }

    @Test
    fun `delete of the active leaf moves the leaf to the surviving ancestor`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
            userEvent(3, "u2", "q2", parentId = "a1"),
            assistantEvent(4, "a2", "r2", parentId = "u2"),
            leafEvent(5, "a2"),
        )
        val gateway = harness.gateway()

        val outcome = gateway.deleteMessage(MessageId("a2"))

        assertTrue(outcome is Outcome.Ok)
        val batch = harness.logStore.appends.single()
        assertEquals(
            listOf(EventType.MESSAGE_DELETED, EventType.LEAF_CHANGED),
            batch.events.map { it.type },
        )
        assertEquals(
            MessageId("u2"),
            (batch.events.last().data as EventData.LeafChanged).messageId,
        )
        assertEquals(MessageId("u2"), batch.index?.activeLeafId)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(MessageId("u2"), snapshot.activeLeafId)
        assertEquals(
            listOf(MessageId("u1"), MessageId("a1"), MessageId("u2")),
            snapshot.messages.map { it.id },
        )
    }

    @Test
    fun `deleting a branch that is not active keeps the leaf where it is`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
            // A second root: an old branch that is not on screen.
            userEvent(3, "u2", "old question"),
            assistantEvent(4, "a2", "old answer", parentId = "u2"),
            leafEvent(5, "a1"),
        )
        val gateway = harness.gateway()

        val outcome = gateway.deleteMessage(MessageId("u2"))

        assertTrue(outcome is Outcome.Ok)
        val batch = harness.logStore.appends.single()
        assertEquals(3, batch.events.size)
        assertEquals(
            "the active branch must not be yanked",
            MessageId("a1"),
            (batch.events.last().data as EventData.LeafChanged).messageId,
        )
        assertEquals(MessageId("a1"), batch.index?.activeLeafId)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(MessageId("a1"), snapshot.activeLeafId)
        assertEquals(listOf(MessageId("u1"), MessageId("a1")), snapshot.messages.map { it.id })
    }

    @Test
    fun `deleting the whole conversation leaves an empty transcript and a null leaf`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedExchange(conversationId)
        val gateway = harness.gateway()

        val outcome = gateway.deleteMessage(MessageId("u1"))

        assertTrue(outcome is Outcome.Ok)
        val batch = harness.logStore.appends.single()
        assertEquals(
            listOf(EventType.MESSAGE_DELETED, EventType.MESSAGE_DELETED, EventType.LEAF_CHANGED),
            batch.events.map { it.type },
        )
        assertNull((batch.events.last().data as EventData.LeafChanged).messageId)
        assertNotNull(batch.index)
        assertNull(batch.index?.activeLeafId)

        val snapshot = gateway.snapshotOf(conversationId)
        assertEquals(5L, snapshot.version)
        assertTrue(snapshot.messages.isEmpty())
        assertTrue(snapshot.transcript.isEmpty())
        assertNull(snapshot.activeLeafId)
        assertTrue(SurfaceFolder.fold(harness.logStore.logOf(conversationId)).nodes.isEmpty())
        assertNull(snapshot.overlay.busy)
    }

    @Test
    fun `delete refuses while a turn is streaming under the message`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        val gateway = harness.gateway()
        harness.completion.events = listOf(GenerationEvent.TextDelta("partial"))
        harness.completion.hangAfter = 1

        gateway.send(conversationId, "hello")
        advanceUntilIdle()
        val userMessageId =
            (harness.logStore.appends.first().events.first().data as EventData.UserMessage).messageId
        assertNotNull(gateway.snapshotOf(conversationId).overlay.streaming)
        val appendsBefore = harness.logStore.appends.size

        val outcome = gateway.deleteMessage(userMessageId)

        assertTrue(outcome is Outcome.Err)
        assertEquals(AppError.BranchConflict::class.java, (outcome as Outcome.Err).error.javaClass)
        assertEquals("no fighting the runner", appendsBefore, harness.logStore.appends.size)
        assertNull(gateway.snapshotOf(conversationId).overlay.busy)

        gateway.cancel(conversationId)
        advanceUntilIdle()
    }

    @Test
    fun `delete of an unrelated branch is allowed while a turn streams`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "r0", "start"),
            assistantEvent(2, "r1", "ok", parentId = "r0"),
            // The active branch: the next turn streams under it.
            userEvent(3, "u1", "current question", parentId = "r1"),
            assistantEvent(4, "a1", "current answer", parentId = "u1"),
            // A sibling branch the user is not looking at.
            userEvent(5, "u2", "old question", parentId = "r1"),
            assistantEvent(6, "a2", "old answer", parentId = "u2"),
            leafEvent(7, "a1"),
        )
        val gateway = harness.gateway()
        harness.completion.events = listOf(GenerationEvent.TextDelta("partial"))
        harness.completion.hangAfter = 1

        gateway.send(conversationId, "next question")
        advanceUntilIdle()
        assertNotNull(gateway.snapshotOf(conversationId).overlay.streaming)

        val outcome = gateway.deleteMessage(MessageId("u2"))

        assertTrue("deleting an unrelated branch must be allowed: $outcome", outcome is Outcome.Ok)
        val batch = harness.logStore.appends.last()
        assertEquals(
            listOf(EventType.MESSAGE_DELETED, EventType.MESSAGE_DELETED, EventType.LEAF_CHANGED),
            batch.events.map { it.type },
        )
        // The streaming turn hangs off a1, which survives, so the leaf stays.
        assertEquals(MessageId("a1"), batch.index?.activeLeafId)

        gateway.cancel(conversationId)
        advanceUntilIdle()
    }

    // -----------------------------------------------------------------------
    // overlay invariant (I11) and refusals
    // -----------------------------------------------------------------------

    @Test
    fun `pin and delete clear the busy overlay on success`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedExchange(conversationId)
        val gateway = harness.gateway()

        assertTrue(gateway.setPinned(MessageId("u1"), true) is Outcome.Ok)
        assertNull("pin left busy set", gateway.snapshotOf(conversationId).overlay.busy)

        assertTrue(gateway.deleteMessage(MessageId("a1")) is Outcome.Ok)
        assertNull("delete left busy set", gateway.snapshotOf(conversationId).overlay.busy)
    }

    @Test
    fun `pin and delete clear the busy overlay when the append fails`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedExchange(conversationId)
        val gateway = harness.gateway()
        harness.logStore.appendFailure = AppError.Storage("disk is full")

        val pinned = gateway.setPinned(MessageId("u1"), true)
        assertTrue(pinned is Outcome.Err)
        assertEquals(AppError.Storage::class.java, (pinned as Outcome.Err).error.javaClass)
        assertNull("a failed pin left busy set", gateway.snapshotOf(conversationId).overlay.busy)

        val deleted = gateway.deleteMessage(MessageId("u1"))
        assertTrue(deleted is Outcome.Err)
        assertEquals(AppError.Storage::class.java, (deleted as Outcome.Err).error.javaClass)
        assertNull("a failed delete left busy set", gateway.snapshotOf(conversationId).overlay.busy)

        assertEquals(0, harness.logStore.appends.size)
        assertEquals(
            listOf(MessageId("u1"), MessageId("a1")),
            gateway.snapshotOf(conversationId).messages.map { it.id },
        )
    }

    @Test
    fun `pin and delete of a missing message return validation and append nothing`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedExchange(conversationId)
        val gateway = harness.gateway()

        val pinned = gateway.setPinned(MessageId("nope"), true)
        assertTrue(pinned is Outcome.Err)
        assertEquals(AppError.Validation::class.java, (pinned as Outcome.Err).error.javaClass)

        val deleted = gateway.deleteMessage(MessageId("nope"))
        assertTrue(deleted is Outcome.Err)
        assertEquals(AppError.Validation::class.java, (deleted as Outcome.Err).error.javaClass)

        assertEquals(0, harness.logStore.appends.size)
        assertNull(gateway.snapshotOf(conversationId).overlay.busy)
    }

    @Test
    fun `a second delete of the same message fails validation and still clears busy`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seedExchange(conversationId)
        val gateway = harness.gateway()

        assertTrue(gateway.deleteMessage(MessageId("a1")) is Outcome.Ok)
        // `conversationOf` remembered the message, so the refusal happens
        // *inside* the command (where busy is set) rather than before it.
        val again = gateway.deleteMessage(MessageId("a1"))

        assertTrue(again is Outcome.Err)
        assertEquals(AppError.Validation::class.java, (again as Outcome.Err).error.javaClass)
        assertEquals(1, harness.logStore.appends.size)
        assertNull(gateway.snapshotOf(conversationId).overlay.busy)
    }

    @Test
    fun `a tombstoned message cannot be pinned or deleted again`() = runTest {
        val harness = LogHarness(this)
        val conversationId = seedConversation(harness.conversations)
        harness.seed(
            conversationId,
            userEvent(1, "u1", "q1"),
            assistantEvent(2, "a1", "r1", parentId = "u1"),
            deletedEvent(3, "a1"),
        )
        val gateway = harness.gateway()

        val pinned = gateway.setPinned(MessageId("a1"), true)

        assertTrue(pinned is Outcome.Err)
        assertEquals(AppError.Validation::class.java, (pinned as Outcome.Err).error.javaClass)
        assertEquals(0, harness.logStore.appends.size)
        assertNull(gateway.snapshotOf(conversationId).overlay.busy)
    }
}
