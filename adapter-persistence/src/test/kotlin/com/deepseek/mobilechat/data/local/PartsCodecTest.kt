package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.data.local.codec.PartsCodec
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.Citation
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsCodecTest {

    private val allParts: List<MessagePart> = listOf(
        MessagePart.Text("plain text with \"quotes\", a backslash \\ and\nnewlines — 中文 🙂"),
        MessagePart.Image(AttachmentId("att-image")),
        MessagePart.FileRef(AttachmentId("att-file")),
        MessagePart.Reasoning("thinking block", "sig-abc-123"),
        MessagePart.Reasoning("thinking without signature", null),
        MessagePart.ContextBlock(
            title = "Injected",
            body = "body\nwith lines",
            source = BlockSource.SEARCH,
            citations = listOf(
                Citation("Example", "https://example.com/a?b=1&c=2"),
                Citation("Other", "https://example.org"),
            ),
        ),
        MessagePart.ContextBlock("No citations", "empty list", BlockSource.USER_INJECTED),
        MessagePart.SearchResults(
            query = "kotlin sqlite",
            hits = listOf(
                SearchHit("Kotlin", "https://kotlinlang.org", "snippet with \"quotes\""),
                SearchHit("SQLite", "https://sqlite.org", "another snippet"),
            ),
        ),
        MessagePart.ToolCall(
            callId = "call-1",
            name = "web_search",
            argumentsJson = """{"query":"deepseek \"chat\"","max":5}""",
        ),
        MessagePart.ToolResult(callId = "call-1", ok = false, content = "errored: timeout\nretry?"),
    )

    @Test
    fun roundTripsEveryPartTypeLosslessly() {
        val encoded = PartsCodec.encode(allParts)
        val decoded = PartsCodec.decode(encoded)
        assertEquals(allParts, decoded)

        // A second pass must be byte-identical: no reordering, no lost fields.
        assertEquals(encoded, PartsCodec.encode(decoded))
    }

    @Test
    fun keepsReasoningSignatureVerbatim() {
        val parts = listOf(MessagePart.Reasoning("t", "signed-\"value\""))
        val decoded = PartsCodec.decode(PartsCodec.encode(parts))
        assertEquals("signed-\"value\"", (decoded.single() as MessagePart.Reasoning).signature)
    }

    @Test
    fun keepsToolArgumentsAsRawJsonString() {
        val raw = """{"nested":{"array":[1,2,3]},"s":"a\nb"}"""
        val parts = listOf(MessagePart.ToolCall("c", "tool", raw))
        val decoded = PartsCodec.decode(PartsCodec.encode(parts))
        assertEquals(raw, (decoded.single() as MessagePart.ToolCall).argumentsJson)
    }

    @Test
    fun emptyListRoundTrips() {
        val encoded = PartsCodec.encode(emptyList())
        assertTrue(encoded.contains("\"parts\":[]"))
        assertEquals(emptyList<MessagePart>(), PartsCodec.decode(encoded))
    }

    @Test
    fun decodesBareJsonArrayAndNull() {
        val bare = """[{"type":"text","text":"legacy"}]"""
        assertEquals(listOf(MessagePart.Text("legacy")), PartsCodec.decode(bare))
        assertEquals(emptyList<MessagePart>(), PartsCodec.decode(null))
        assertEquals(emptyList<MessagePart>(), PartsCodec.decode(""))
    }

    @Test
    fun plainTextIgnoresNonTextParts() {
        assertEquals("ab", PartsCodec.plainText(listOf(MessagePart.Text("a"), MessagePart.Reasoning("x"), MessagePart.Text("b"))))
        assertEquals("", PartsCodec.plainText(listOf(MessagePart.Image(AttachmentId("i")))))
    }

    @Test
    fun rejectsUnknownPartType() {
        val json = """{"v":1,"parts":[{"type":"video","url":"x"}]}"""
        val error = assertThrows(IllegalArgumentException::class.java) { PartsCodec.decode(json) }
        assertTrue(error.message!!.contains("video"))
    }

    @Test
    fun rejectsNewerFormatVersion() {
        val json = """{"v":99,"parts":[]}"""
        assertThrows(IllegalArgumentException::class.java) { PartsCodec.decode(json) }
    }

    @Test
    fun rejectsMalformedJson() {
        assertThrows(IllegalArgumentException::class.java) { PartsCodec.decode("{not json") }
    }

    @Test
    fun rejectsMissingRequiredField() {
        assertThrows(IllegalArgumentException::class.java) {
            PartsCodec.decode("""[{"type":"text"}]""")
        }
    }
}
