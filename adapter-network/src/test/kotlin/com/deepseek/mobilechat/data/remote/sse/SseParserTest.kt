package com.deepseek.mobilechat.data.remote.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `multi-line data is joined with newlines and keeps the event name`() {
        val events = parseSse("event: delta\ndata: first\ndata: second\n\n")

        assertEquals(1, events.size)
        assertEquals("delta", events.single().event)
        assertEquals("first\nsecond", events.single().data)
    }

    @Test
    fun `comment lines are ignored`() {
        val events = parseSse(": keep-alive\n\ndata: payload\n\n")

        assertEquals(1, events.size)
        assertEquals("payload", events.single().data)
        assertNull(events.single().event)
    }

    @Test
    fun `done marker is surfaced and recognized`() {
        val events = parseSse("data: [DONE]\n\n")

        assertEquals(1, events.size)
        assertTrue(events.single().isDone)
        assertEquals(SseEvent.DONE_MARKER, events.single().data)
    }

    @Test
    fun `blank lines alone produce no events`() {
        assertTrue(parseSse("\n\n\n").isEmpty())
        assertTrue(parseSse("").isEmpty())
    }

    @Test
    fun `interleaved blank lines do not split or duplicate events`() {
        val events = parseSse("data: one\n\n\n\ndata: two\n\n")

        assertEquals(listOf("one", "two"), events.map { it.data })
    }

    @Test
    fun `only a single space after the colon is stripped`() {
        val events = parseSse("data:no-space\ndata:  two-spaces\n\n")

        assertEquals("no-space\n two-spaces", events.single().data)
    }

    @Test
    fun `unknown fields and colon-less fields are tolerated`() {
        val events = parseSse("id: 42\nretry: 1000\ncomment-ish\ndata: value\n\n")

        assertEquals(1, events.size)
        assertEquals("value", events.single().data)
    }

    @Test
    fun `pending event is flushed when the stream ends without a blank line`() {
        val events = parseSse("data: tail")

        assertEquals("tail", events.single().data)
    }

    @Test
    fun `crlf line endings are handled`() {
        val events = parseSse("event: e\r\ndata: a\r\n\r\n")

        assertEquals(1, events.size)
        assertEquals("e", events.single().event)
        assertEquals("a", events.single().data)
    }

    @Test
    fun `incremental feeding dispatches only at the blank line`() {
        val parser = SseParser()

        assertNull(parser.feed("event: message_start"))
        assertNull(parser.feed("data: {\"type\":\"message_start\"}"))
        val event = parser.feed("")

        assertEquals("message_start", event?.event)
        assertEquals("{\"type\":\"message_start\"}", event?.data)
        assertNull(parser.flush())
    }

    @Test
    fun `event name does not leak into the next event`() {
        val events = parseSse("event: first\ndata: a\n\ndata: b\n\n")

        assertEquals(listOf("first", null), events.map { it.event })
        assertEquals(listOf("a", "b"), events.map { it.data })
    }

    @Test
    fun `empty data field still dispatches an empty payload`() {
        val events = parseSse("data:\n\n")

        assertEquals(1, events.size)
        assertEquals("", events.single().data)
    }
}
