package com.deepseek.mobilechat.domain.log

import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fold is the part of the design that must never be wrong: it decides what the
 * model sees and it is the only thing that can reject a corrupt log.
 */
class SurfaceFoldTest {

    private val conversationId = ConversationId("c1")

    private fun ev(
        seq: Long,
        type: EventType,
        data: EventData,
        op: SurfaceOp? = null,
        sources: List<Long> = emptyList(),
    ) = SessionEvent(
        seq = seq,
        eventId = "e$seq",
        type = type,
        time = 1_000L + seq,
        data = data,
        surfaceOp = op,
        sourceEventSeqs = sources,
    )

    private fun user(seq: Long, id: String, parent: String? = null, text: String = "hi") = ev(
        seq = seq,
        type = EventType.USER_MESSAGE,
        data = EventData.UserMessage(
            messageId = MessageId(id),
            parentId = parent?.let { MessageId(it) },
            parts = listOf(MessagePart.Text(text)),
        ),
        op = SurfaceOp.Append,
    )

    private fun assistant(seq: Long, id: String, parent: String, text: String = "hello") = ev(
        seq = seq,
        type = EventType.ASSISTANT_MESSAGE,
        data = EventData.AssistantMessage(
            messageId = MessageId(id),
            parentId = MessageId(parent),
            parts = listOf(MessagePart.Text(text)),
            finishReason = FinishReason.STOP,
        ),
        op = SurfaceOp.Append,
    )

    private fun log(vararg events: SessionEvent) = ConversationLog(conversationId, events.toList())

    private fun expectFailure(block: () -> Unit): String {
        try {
            block()
        } catch (failure: LogFormatException) {
            return failure.message.orEmpty()
        }
        fail("expected a LogFormatException")
        error("unreachable")
    }

    @Test
    fun `append-only events become surface nodes in order`() {
        val surface = SurfaceFolder.fold(log(user(1, "u1"), assistant(2, "a1", "u1")))

        assertEquals(listOf(1L, 2L), surface.nodes.map { it.seq })
        assertEquals(EventType.USER_MESSAGE, surface.nodes[0].type)
        assertTrue(surface.nodes[0].content is SurfaceContent.User)
        assertTrue(surface.nodes[1].content is SurfaceContent.Assistant)
    }

    @Test
    fun `record events never enter the surface`() {
        val surface = SurfaceFolder.fold(
            log(
                user(1, "u1"),
                ev(
                    2,
                    EventType.TURN_START,
                    EventData.TurnStart(MessageId("a1"), MessageId("u1"), null),
                ),
                ev(
                    3,
                    EventType.LEAF_CHANGED,
                    EventData.LeafChanged(MessageId("a1")),
                ),
                assistant(4, "a1", "u1"),
            ),
        )

        assertEquals(listOf(1L, 4L), surface.nodes.map { it.seq })
    }

    @Test
    fun `a gap in the seq throws`() {
        val message = expectFailure { SurfaceFolder.fold(log(user(1, "u1"), user(3, "u2"))) }

        assertTrue(message.contains("not contiguous"), )
    }

    @Test
    fun `a surface event without an op throws`() {
        val broken = SessionEvent(
            seq = 1,
            eventId = "e1",
            type = EventType.USER_MESSAGE,
            time = 1L,
            data = EventData.UserMessage(MessageId("u1"), null, listOf(MessagePart.Text("x"))),
            surfaceOp = null,
        )

        val message = expectFailure { SurfaceFolder.fold(log(broken)) }

        assertTrue(message.contains("requires a surfaceOp"))
    }

    @Test
    fun `a record event carrying a surface op throws`() {
        val broken = ev(
            1,
            EventType.LEAF_CHANGED,
            EventData.LeafChanged(null),
            op = SurfaceOp.Append,
        )

        val message = expectFailure { SurfaceFolder.fold(log(broken)) }

        assertTrue(message.contains("cannot carry a surfaceOp"))
    }

    @Test
    fun `a replace must declare every shadowed node`() {
        val standIn = ev(
            seq = 4,
            type = EventType.USER_MESSAGE,
            data = EventData.UserMessage(
                messageId = MessageId("summary"),
                parentId = null,
                parts = listOf(MessagePart.Text("summary")),
                compactionId = CompactionId("k1"),
            ),
            op = SurfaceOp.Replace(startSeq = 1, endSeq = 2),
            sources = listOf(1L), // 2 is missing on purpose
        )

        val message = expectFailure {
            SurfaceFolder.fold(log(user(1, "u1"), assistant(2, "a1", "u1"), user(3, "u2"), standIn))
        }

        assertTrue(message.contains("missing 2"), )
    }

    @Test
    fun `a replace may not cite nodes outside its range`() {
        val standIn = ev(
            seq = 4,
            type = EventType.USER_MESSAGE,
            data = EventData.UserMessage(
                messageId = MessageId("summary"),
                parentId = null,
                parts = listOf(MessagePart.Text("s")),
                compactionId = CompactionId("k1"),
            ),
            op = SurfaceOp.Replace(startSeq = 1, endSeq = 1),
            sources = listOf(1L, 2L),
        )

        val message = expectFailure {
            SurfaceFolder.fold(log(user(1, "u1"), assistant(2, "a1", "u1"), user(3, "u2"), standIn))
        }

        assertTrue(message.contains("outside the range"))
    }

    @Test
    fun `a replace range must resolve on the current surface`() {
        // 2 was already shadowed by the replacement at 4, so it is no longer a node.
        val firstStandIn = ev(
            seq = 4,
            type = EventType.USER_MESSAGE,
            data = EventData.UserMessage(
                messageId = MessageId("summary1"),
                parentId = null,
                parts = listOf(MessagePart.Text("s1")),
                compactionId = CompactionId("k1"),
            ),
            op = SurfaceOp.Replace(1, 2),
            sources = listOf(1L, 2L),
        )
        val secondStandIn = ev(
            seq = 5,
            type = EventType.USER_MESSAGE,
            data = EventData.UserMessage(
                messageId = MessageId("summary2"),
                parentId = null,
                parts = listOf(MessagePart.Text("s2")),
                compactionId = CompactionId("k2"),
            ),
            op = SurfaceOp.Replace(2, 2),
            sources = listOf(2L),
        )

        val message = expectFailure {
            SurfaceFolder.fold(log(user(1, "u1"), assistant(2, "a1", "u1"), user(3, "u2"), firstStandIn, secondStandIn))
        }

        assertTrue(message.contains("is not on the surface"))
    }

    @Test
    fun `tool result rewrites may only change content`() {
        val original = ev(
            1,
            EventType.TOOL_RESULT,
            EventData.ToolResult("call-1", "full page text", isError = false),
            op = SurfaceOp.Append,
        )
        val pruned = ev(
            2,
            EventType.TOOL_RESULT,
            EventData.ToolResult("call-2", "…", isError = false),
            op = SurfaceOp.Replace(1, 1),
            sources = listOf(1L),
        )

        val message = expectFailure { SurfaceFolder.fold(log(original, pruned)) }

        assertTrue(message.contains("may change only content"))
    }

    @Test
    fun `tool result content may be shortened`() {
        val original = ev(
            1,
            EventType.TOOL_RESULT,
            EventData.ToolResult("call-1", "full page text", isError = false),
            op = SurfaceOp.Append,
        )
        val pruned = ev(
            2,
            EventType.TOOL_RESULT,
            EventData.ToolResult("call-1", "…", isError = false),
            op = SurfaceOp.Replace(1, 1),
            sources = listOf(1L),
        )

        val surface = SurfaceFolder.fold(log(original, pruned))

        assertEquals(1, surface.nodes.size)
        assertEquals("…", (surface.nodes[0].content as SurfaceContent.Tool).content)
    }

    @Test
    fun `the system prompt can only be replaced by a system message`() {
        val system = ev(1, EventType.SYSTEM_MESSAGE, EventData.SystemMessage("sys"), op = SurfaceOp.Append)
        val standIn = ev(
            seq = 3,
            type = EventType.USER_MESSAGE,
            data = EventData.UserMessage(
                messageId = MessageId("summary"),
                parentId = null,
                parts = listOf(MessagePart.Text("s")),
                compactionId = CompactionId("k1"),
            ),
            op = SurfaceOp.Replace(1, 1),
            sources = listOf(1L),
        )

        val message = expectFailure { SurfaceFolder.fold(log(system, user(2, "u1"), standIn)) }

        assertTrue(message.contains("system prompt"), )
    }

    // -----------------------------------------------------------------------
    // transcript
    // -----------------------------------------------------------------------

    @Test
    fun `compaction becomes a divider and keeps every covered message`() {
        val events = arrayOf(
            user(1, "u1"),
            assistant(2, "a1", "u1"),
            ev(
                3,
                EventType.COMPACTION_START,
                EventData.CompactionStart(CompactionId("k1"), "summary", 1, 2, 900),
            ),
            ev(
                4,
                EventType.USER_MESSAGE,
                EventData.UserMessage(
                    messageId = MessageId("summary"),
                    parentId = null,
                    parts = listOf(MessagePart.Text("摘要：聊了打招呼")),
                    compactionId = CompactionId("k1"),
                ),
                op = SurfaceOp.Replace(1, 2),
                sources = listOf(1L, 2L),
            ),
            ev(5, EventType.COMPACTION_END, EventData.CompactionEnd(CompactionId("k1"), 4, 120)),
        )

        val log = log(*events)
        val surface = SurfaceFolder.fold(log)
        val transcript = TranscriptProjector.project(log)

        // The model sees only the summary stand-in.
        assertEquals(1, surface.nodes.size)
        assertEquals("摘要：聊了打招呼", (surface.nodes[0].content as SurfaceContent.User).message.text)

        // The human still sees both messages, plus a divider.
        assertEquals(2, transcript.messages.size)
        val bar = transcript.nodes.filterIsInstance<TranscriptNode.CompactionBar>().single()
        assertEquals(CompactionId("k1"), bar.compactionId)
        assertEquals(900, bar.tokensBefore)
        assertEquals(120, bar.tokensAfter)
        assertEquals(listOf("u1", "a1"), transcript.messages.map { it.id.raw })
    }

    @Test
    fun `an in-place edit rewrites the row instead of appending`() {
        val edited = ev(
            seq = 3,
            type = EventType.USER_MESSAGE,
            data = EventData.UserMessage(
                messageId = MessageId("u1"),
                parentId = null,
                parts = listOf(MessagePart.Text("改了")),
            ),
            op = SurfaceOp.Replace(1, 1),
            sources = listOf(1L),
        )

        val transcript = TranscriptProjector.project(log(user(1, "u1"), assistant(2, "a1", "u1"), edited))

        assertEquals(2, transcript.messages.size)
        assertEquals("改了", transcript.messages.first { it.id.raw == "u1" }.text)
    }

    @Test
    fun `an interrupted turn appears with its partial text and a cancelled status`() {
        val interrupted = ev(
            2,
            EventType.TURN_INTERRUPTED,
            EventData.TurnInterrupted(
                messageId = MessageId("a1"),
                parentId = MessageId("u1"),
                partialText = "世卫 +",
                reason = "生成已中断",
            ),
        )

        val transcript = TranscriptProjector.project(log(user(1, "u1"), interrupted))

        val message = transcript.message(MessageId("a1"))!!
        assertEquals("世卫 +", message.text)
        assertEquals(MessageStatus.CANCELLED, message.status)
    }

    @Test
    fun `the leaf comes from the last leaf changed event`() {
        val transcript = TranscriptProjector.project(
            log(
                user(1, "u1"),
                assistant(2, "a1", "u1"),
                assistant(3, "a2", "u1"),
                ev(4, EventType.LEAF_CHANGED, EventData.LeafChanged(MessageId("a1"))),
            ),
        )

        assertEquals(MessageId("a1"), transcript.activeLeafId)
    }

    @Test
    fun `title and model selection are folded`() {
        val transcript = TranscriptProjector.project(
            log(
                ev(1, EventType.SESSION_TITLE, EventData.SessionTitle("你好问候")),
                ev(
                    2,
                    EventType.MODEL_SELECTION,
                    EventData.ModelSelection(
                        modelRef = com.deepseek.mobilechat.domain.ModelRef(
                            com.deepseek.mobilechat.domain.ProviderId("p1"),
                            com.deepseek.mobilechat.domain.ModelId("deepseek-flash"),
                        ),
                        params = null,
                        thinkingLevel = com.deepseek.mobilechat.domain.ThinkingLevel.OFF,
                    ),
                ),
            ),
        )

        assertEquals("你好问候", transcript.title)
        assertEquals("deepseek-flash", transcript.modelRef?.modelId?.raw)
        assertEquals(com.deepseek.mobilechat.domain.ThinkingLevel.OFF, transcript.thinkingLevel)
        assertNull(transcript.activeLeafId)
    }

    @Test
    fun `pinning only changes the transcript row`() {
        val events = arrayOf(
            user(1, "u1"),
            assistant(2, "a1", "u1"),
            ev(3, EventType.MESSAGE_PINNED, EventData.MessagePinned(MessageId("u1"), pinned = true)),
        )

        val transcript = TranscriptProjector.project(log(*events))
        val surface = SurfaceFolder.fold(log(*events))

        assertTrue("the row is pinned", transcript.message(MessageId("u1"))!!.pinned)
        assertEquals("pinning must not change the prompt", 2, surface.nodes.size)
    }

    @Test
    fun `a deleted message leaves both projections`() {
        val events = arrayOf(
            user(1, "u1"),
            assistant(2, "a1", "u1"),
            ev(3, EventType.MESSAGE_DELETED, EventData.MessageDeleted(MessageId("u1"))),
            ev(4, EventType.MESSAGE_DELETED, EventData.MessageDeleted(MessageId("a1"))),
        )

        val transcript = TranscriptProjector.project(log(*events))
        val surface = SurfaceFolder.fold(log(*events))

        assertTrue("nothing is left to show", transcript.messages.isEmpty())
        assertTrue("nothing is left to send", surface.nodes.isEmpty())
    }

    @Test
    fun `deleting the last exchange keeps the earlier ones`() {
        val events = arrayOf(
            user(1, "u1"),
            assistant(2, "a1", "u1"),
            user(3, "u2", parent = "a1"),
            assistant(4, "a2", "u2"),
            ev(5, EventType.MESSAGE_DELETED, EventData.MessageDeleted(MessageId("u2"))),
            ev(6, EventType.MESSAGE_DELETED, EventData.MessageDeleted(MessageId("a2"))),
        )

        val transcript = TranscriptProjector.project(log(*events))
        val surface = SurfaceFolder.fold(log(*events))

        assertEquals(listOf("u1", "a1"), transcript.messages.map { it.id.raw })
        assertEquals(
            listOf(EventType.USER_MESSAGE, EventType.ASSISTANT_MESSAGE),
            surface.nodes.map { it.type },
        )
    }
}
