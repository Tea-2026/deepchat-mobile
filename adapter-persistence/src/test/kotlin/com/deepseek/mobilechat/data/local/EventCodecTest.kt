package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.codec.EventCodec
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.Citation
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip proof for [EventCodec]: every event payload, plus the parts,
 * citations, usage and modelInfo inside them, must come back byte-identical in
 * value. Losing a reasoning signature or an image attachment here would corrupt
 * history on the next read, so these are exact `assertEquals` checks, not
 * "contains" checks.
 */
class EventCodecTest {

    @Test
    fun everyEventTypeRoundTripsItsPayload() {
        val samples = sampleByType()
        assertEquals("every EventType needs a sample", EventType.entries.toSet(), samples.keys)
        samples.forEach { (type, data) ->
            val json = EventCodec.encodeData(data)
            val decoded = EventCodec.decodeData(type, json)
            assertEquals("${type.wire} round-trip", data, decoded)
        }
    }

    @Test
    fun userAndAssistantMessagesRoundTripEveryPartKind() {
        val parts = listOf(
            MessagePart.Text("hello"),
            MessagePart.Image(AttachmentId("img-1")),
            MessagePart.FileRef(AttachmentId("file-1")),
            MessagePart.Reasoning("thinking…", "signature-abc"),
            MessagePart.ContextBlock(
                title = "search",
                body = "body",
                source = BlockSource.SEARCH,
                citations = listOf(Citation("t1", "https://a"), Citation("t2", "https://b")),
            ),
            MessagePart.SearchResults(
                query = "kotlin",
                hits = listOf(
                    SearchHit("Kotlin", "https://kotlinlang.org", "snippet one"),
                    SearchHit("Docs", "https://docs", "snippet two"),
                ),
            ),
            MessagePart.ToolCall("call-1", "web_search", """{"q":"kotlin"}"""),
            MessagePart.ToolResult("call-1", ok = true, content = "result"),
        )
        val user = EventData.UserMessage(MessageId("u1"), null, parts)
        val assistant = EventData.AssistantMessage(
            messageId = MessageId("a1"),
            parentId = MessageId("u1"),
            parts = parts,
            modelInfo = richModelInfo(),
            usage = richUsage(),
            finishReason = FinishReason.LENGTH,
            citations = listOf(SearchHit("hit", "https://hit", "snippet")),
        )
        assertEquals(user, EventCodec.decodeData(EventType.USER_MESSAGE, EventCodec.encodeData(user)))
        assertEquals(
            assistant,
            EventCodec.decodeData(EventType.ASSISTANT_MESSAGE, EventCodec.encodeData(assistant)),
        )
    }

    @Test
    fun nullableAssistantFieldsRoundTripAsNull() {
        val data = EventData.AssistantMessage(
            messageId = MessageId("a1"),
            parentId = null,
            parts = emptyList(),
            modelInfo = null,
            usage = null,
            finishReason = FinishReason.STOP,
            citations = emptyList(),
        )
        assertEquals(data, EventCodec.decodeData(EventType.ASSISTANT_MESSAGE, EventCodec.encodeData(data)))
    }

    @Test
    fun surfaceOpsRoundTripInTheDocumentedShape() {
        assertNull(EventCodec.encodeSurfaceOp(null))
        assertNull(EventCodec.decodeSurfaceOp(null))

        assertEquals(SurfaceOp.Append, EventCodec.decodeSurfaceOp(EventCodec.encodeSurfaceOp(SurfaceOp.Append)))
        assertEquals(
            SurfaceOp.Replace(3, 7),
            EventCodec.decodeSurfaceOp(EventCodec.encodeSurfaceOp(SurfaceOp.Replace(3, 7))),
        )

        // The column is documented as 'append' or {op,startSeq,endSeq}; both the
        // bare token and its JSON-string form decode, the encoder writes a JSON
        // string so the column is always valid JSON.
        assertEquals(SurfaceOp.Append, EventCodec.decodeSurfaceOp("append"))
        assertEquals(SurfaceOp.Append, EventCodec.decodeSurfaceOp("\"append\""))
        assertTrue(EventCodec.encodeSurfaceOp(SurfaceOp.Replace(1, 2))!!.contains("\"op\":\"replace\""))
    }

    @Test
    fun sourceSeqsRoundTripAndEmptyIsStoredAsNull() {
        assertNull(EventCodec.encodeSourceSeqs(emptyList()))
        assertEquals(emptyList<Long>(), EventCodec.decodeSourceSeqs(null))
        assertEquals(emptyList<Long>(), EventCodec.decodeSourceSeqs(""))

        val seqs = listOf(1L, 2L, 9L)
        assertEquals(seqs, EventCodec.decodeSourceSeqs(EventCodec.encodeSourceSeqs(seqs)))
        assertThrows(IllegalArgumentException::class.java) { EventCodec.decodeSourceSeqs("\"nope\"") }
        assertThrows(IllegalArgumentException::class.java) { EventCodec.decodeSourceSeqs("[1,\"x\"]") }
    }

    @Test
    fun compactionStandInUsesTheDocumentedSourceObject() {
        val standIn = EventData.UserMessage(
            messageId = MessageId("compaction:cp1"),
            parentId = MessageId("m9"),
            parts = listOf(MessagePart.Text("summary")),
            compactionId = CompactionId("cp1"),
        )
        val json = EventCodec.encodeData(standIn)
        assertTrue(json.contains("\"kind\":\"compact-checkpoint\""))
        assertTrue(json.contains("\"compactionId\":\"cp1\""))
        assertEquals(standIn, EventCodec.decodeData(EventType.USER_MESSAGE, json))
    }

    @Test
    fun nullIdsAndEmptyModelSelectionRoundTrip() {
        val leaf = EventData.LeafChanged(null)
        assertEquals(leaf, EventCodec.decodeData(EventType.LEAF_CHANGED, EventCodec.encodeData(leaf)))

        val selection = EventData.ModelSelection(modelRef = null, params = null, thinkingLevel = null)
        assertEquals(
            selection,
            EventCodec.decodeData(EventType.MODEL_SELECTION, EventCodec.encodeData(selection)),
        )
    }

    @Test
    fun messagePinnedWritesAndRoundTripsBothBooleanValues() {
        val pinned = EventData.MessagePinned(MessageId("m1"), pinned = true)
        val unpinned = EventData.MessagePinned(MessageId("m1"), pinned = false)

        val pinnedJson = EventCodec.encodeData(pinned)
        val unpinnedJson = EventCodec.encodeData(unpinned)

        assertEquals("""{"messageId":"m1","pinned":true}""", pinnedJson)
        assertEquals("""{"messageId":"m1","pinned":false}""", unpinnedJson)

        assertEquals(pinned, EventCodec.decodeData(EventType.MESSAGE_PINNED, pinnedJson))
        // `false` is a written value, not an absent one: a decoder that fell back
        // to a default would turn a deliberate unpin into a silent pin.
        assertEquals(unpinned, EventCodec.decodeData(EventType.MESSAGE_PINNED, unpinnedJson))

        // Omitting the flag is corruption, not "unpinned".
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decodeData(EventType.MESSAGE_PINNED, """{"messageId":"m1"}""")
        }
    }

    @Test
    fun messageDeletedRoundTripsItsMessageId() {
        val data = EventData.MessageDeleted(MessageId("m1"))
        val json = EventCodec.encodeData(data)

        assertEquals("""{"messageId":"m1"}""", json)
        assertEquals(data, EventCodec.decodeData(EventType.MESSAGE_DELETED, json))
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decodeData(EventType.MESSAGE_DELETED, "{}")
        }
    }

    @Test
    fun decodingRejectsAPayloadThatDoesNotMatchTheType() {
        val systemJson = EventCodec.encodeData(EventData.SystemMessage("system"))
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decodeData(EventType.USER_MESSAGE, systemJson)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decodeData(EventType.SESSION_TITLE, "{}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EventCodec.decodeData(EventType.ASSISTANT_MESSAGE, """{"messageId":"a","finishReason":"BOGUS"}""")
        }
    }

    @Test
    fun turnEndKeepsUsageAndDuration() {
        val data = EventData.TurnEnd(
            messageId = MessageId("a1"),
            finishReason = FinishReason.TOOL_CALLS,
            usage = richUsage(),
            durationMs = 12_345L,
        )
        assertEquals(data, EventCodec.decodeData(EventType.TURN_END, EventCodec.encodeData(data)))
    }

    private fun sampleByType(): Map<EventType, EventData> = mapOf(
        EventType.SYSTEM_MESSAGE to EventData.SystemMessage("you are helpful"),
        EventType.DEVELOPER_MESSAGE to EventData.DeveloperMessage("context block", "context"),
        EventType.USER_MESSAGE to EventData.UserMessage(
            MessageId("u1"),
            null,
            listOf(MessagePart.Text("hi"), MessagePart.Image(AttachmentId("img"))),
        ),
        EventType.ASSISTANT_MESSAGE to EventData.AssistantMessage(
            messageId = MessageId("a1"),
            parentId = MessageId("u1"),
            parts = listOf(MessagePart.Text("hello"), MessagePart.Reasoning("thought", "sig")),
            modelInfo = richModelInfo(),
            usage = richUsage(),
            finishReason = FinishReason.STOP,
            citations = listOf(SearchHit("t", "https://u", "s")),
        ),
        EventType.TOOL_RESULT to EventData.ToolResult("call-1", "content", isError = true),
        EventType.TURN_START to EventData.TurnStart(MessageId("a2"), MessageId("u1"), richModelInfo()),
        EventType.TURN_END to EventData.TurnEnd(
            MessageId("a2"),
            FinishReason.CANCELLED,
            richUsage(),
            1000L,
        ),
        EventType.TURN_INTERRUPTED to EventData.TurnInterrupted(
            MessageId("a3"),
            MessageId("u2"),
            "partial",
            "process death",
        ),
        EventType.COMPACTION_START to EventData.CompactionStart(
            CompactionId("cp"),
            "confirm-compaction",
            2,
            8,
            900,
        ),
        EventType.COMPACTION_END to EventData.CompactionEnd(CompactionId("cp"), 9, 120),
        EventType.COMPACTION_PRUNE to EventData.CompactionPrune(CompactionId("cp"), listOf(3L, 4L)),
        EventType.IMAGE_OFFLOAD to EventData.ImageOffload(
            MessageId("a1"),
            listOf(AttachmentId("img-1"), AttachmentId("img-2")),
            "[image removed]",
        ),
        EventType.MESSAGE_PINNED to EventData.MessagePinned(MessageId("u1"), pinned = true),
        EventType.MESSAGE_DELETED to EventData.MessageDeleted(MessageId("a1")),
        EventType.LEAF_CHANGED to EventData.LeafChanged(MessageId("a1")),
        EventType.MODEL_SELECTION to EventData.ModelSelection(
            ModelRef(ProviderId("prov"), ModelId("model")),
            SamplingParams(temperature = 0.3, topP = 0.8, maxTokens = 512, stop = listOf("STOP")),
            ThinkingLevel.MAX,
        ),
        EventType.SESSION_TITLE to EventData.SessionTitle("A title", "auto"),
    )

    private fun richModelInfo(): ModelInfo = ModelInfo(
        ref = ModelRef(ProviderId("prov"), ModelId("model-x")),
        params = SamplingParams(temperature = 0.7, topP = 0.95, maxTokens = 4096, stop = listOf("\n\n")),
        thinkingLevel = ThinkingLevel.HIGH,
    )

    private fun richUsage(): Usage = Usage(
        promptTokens = 123,
        completionTokens = 45,
        reasoningTokens = 7,
        cachedPromptTokens = 89,
        cost = 0.0123,
    )
}
