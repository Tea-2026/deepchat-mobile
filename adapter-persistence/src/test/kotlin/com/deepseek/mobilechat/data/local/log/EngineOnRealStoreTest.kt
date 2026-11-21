package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.data.local.TestEnv
import com.deepseek.mobilechat.data.local.conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam the unit tests cannot cover: the real `DefaultChatGateway` on top of the
 * real SQLite log, projections and draft store. Only the completion port (and the
 * settings/search ports) are fakes.
 */
class EngineOnRealStoreTest {

    private val env = TestEnv()
    private val conversationId = ConversationId("c-e2e")

    @After
    fun tearDown() = env.close()

    private suspend fun seedConversation() {
        env.conversations.upsert(conversation(conversationId.raw))
    }

    @Test
    fun `a real send writes one atomic turn and the snapshot shows it`() = runBlocking {
        seedConversation()
        val completion = ScriptedCompletion(listOf("你好", "，世界"))
        val gateway = engineGateway(env, completion)

        val sent = gateway.send(conversationId, "冒烟测试")
        assertTrue("send should succeed: $sent", sent is Outcome.Ok)

        // Wait for the *settled* turn: a snapshot with two rows can already be the
        // mid-stream one (user + synthetic streaming row).
        val snapshot = withTimeout(15_000) {
            gateway.snapshots(conversationId).first { candidate ->
                candidate.overlay.streaming == null &&
                    candidate.messages.any { it.role == MessageRole.ASSISTANT && it.text == "你好，世界" }
            }
        }

        assertEquals("冒烟测试", snapshot.messages.first().text)
        assertEquals("你好，世界", snapshot.messages.last().text)
        assertNull("overlay must be settled once the turn completes", snapshot.overlay.streaming)
        assertNull("no command may stay busy", snapshot.overlay.busy)

        val log = env.log.log(conversationId)
        val types = log.events.map { it.type }
        assertTrue(types.contains(EventType.USER_MESSAGE))
        assertTrue(types.contains(EventType.TURN_START))
        assertTrue(types.contains(EventType.ASSISTANT_MESSAGE))
        assertTrue(types.contains(EventType.TURN_END))
        assertTrue(types.contains(EventType.LEAF_CHANGED))
        assertEquals("every seq is distinct", log.events.size, log.events.map { it.seq }.distinct().size)
        assertEquals("seqs are 1..n", log.events.size.toLong(), log.events.last().seq)

        val surface = SurfaceFolder.fold(log)
        assertTrue("surface must contain the answer", surface.nodes.size >= 2)

        assertEquals(1, completion.requests.size)
        assertTrue(completion.requests.single().messages.isNotEmpty())
    }

    @Test
    fun `regenerate keeps the previous answer on the active path until the new one lands`() = runBlocking {
        seedConversation()
        val first = engineGateway(env, ScriptedCompletion(listOf("旧回答")))
        assertTrue(first.send(conversationId, "问题") is Outcome.Ok)
        val settled = withTimeout(15_000) {
            first.snapshots(conversationId).first { candidate ->
                candidate.overlay.streaming == null &&
                    candidate.messages.any { it.role == MessageRole.ASSISTANT && it.text == "旧回答" }
            }
        }
        val oldAnswer = settled.messages.last()
        assertEquals("旧回答", oldAnswer.text)

        // The second turn stops after its first chunk so we can look mid-flight.
        val gate = CompletableDeferred<Unit>()
        val slow = engineGateway(env, ScriptedCompletion(listOf("新", "回答"), gate))
        assertTrue(slow.regenerate(oldAnswer.id) is Outcome.Ok)

        val midFlight = withTimeout(15_000) {
            slow.snapshots(conversationId).first { it.overlay.streaming != null }
        }
        assertNotNull(
            "the old answer must still be visible while regenerating",
            midFlight.messages.firstOrNull { it.id == oldAnswer.id },
        )
        assertEquals(
            "the active path should follow the streaming reply",
            midFlight.overlay.streaming?.messageId,
            midFlight.activeLeafId,
        )
        assertTrue(
            "the streaming text must be rendered as a row",
            midFlight.messages.any { it.status == MessageStatus.STREAMING },
        )

        gate.complete(Unit)

        val done = withTimeout(15_000) {
            slow.snapshots(conversationId).first { snapshot ->
                snapshot.overlay.streaming == null && snapshot.messages.any { it.text == "新回答" }
            }
        }
        assertNull(done.overlay.busy)
        assertEquals(
            "both answers stay in history",
            2,
            done.messages.count { it.role == MessageRole.ASSISTANT },
        )
        assertEquals(
            "the new answer becomes the leaf",
            "新回答",
            done.messages.first { it.id == done.activeLeafId }.text,
        )
    }

    @Test
    fun `the first-run path creates a conversation and sends into it`() = runBlocking {
        val completion = ScriptedCompletion(listOf("首答"))
        val gateway = engineGateway(env, completion)

        // Before anything happens the draft snapshot is idle, not a fake conversation.
        val draft = gateway.draftSnapshot().first()
        assertTrue("draft has no messages", draft.messages.isEmpty())
        assertNull(draft.overlay.busy)

        val created = gateway.createConversation()
        assertTrue("createConversation should succeed: $created", created is Outcome.Ok)
        val id = (created as Outcome.Ok).value

        assertTrue(gateway.send(id, "第一句") is Outcome.Ok)
        val snapshot = withTimeout(15_000) {
            gateway.snapshots(id).first { candidate ->
                candidate.overlay.streaming == null &&
                    candidate.messages.any { it.role == MessageRole.ASSISTANT && it.text == "首答" }
            }
        }
        assertEquals(2, snapshot.messages.size)
        assertEquals("first turn becomes a title", true, snapshot.title.isNotEmpty())
        assertNull(snapshot.overlay.busy)
    }

    @Test
    fun `startup reconciliation settles a leftover draft into an interrupted turn`() = runBlocking {
        seedConversation()
        // Simulate a process death mid-stream: a draft exists, but the log has no
        // terminal event for that message.
        val orphan = com.deepseek.mobilechat.domain.MessageId("orphan-1")
        env.drafts.put(conversationId, orphan, "半个回答", "半段思考")

        engineGateway(env, ScriptedCompletion(listOf("unused")), reconcileOnStart = true)

        val log = withTimeout(15_000) {
            var current = env.log.log(conversationId)
            while (current.events.none { it.type == EventType.TURN_INTERRUPTED }) {
                kotlinx.coroutines.delay(20)
                current = env.log.log(conversationId)
            }
            current
        }
        val interrupted = log.events.single { it.type == EventType.TURN_INTERRUPTED }
        val data = interrupted.data as com.deepseek.mobilechat.domain.log.EventData.TurnInterrupted
        assertEquals(orphan, data.messageId)
        assertEquals("半个回答", data.partialText)

        val transcript = com.deepseek.mobilechat.domain.log.TranscriptProjector.project(log)
        val message = transcript.message(orphan)
        assertNotNull("the half answer must survive the crash", message)
        assertEquals(MessageStatus.CANCELLED, message!!.status)
        assertEquals("半个回答", message.text)
        assertNull("the draft is consumed", env.drafts.get(orphan))
    }
}
