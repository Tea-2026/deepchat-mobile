package com.deepseek.mobilechat.data.remote.sse

/**
 * A single Server-Sent Event.
 *
 * [event] is the optional `event:` field, [data] is the concatenation of all
 * `data:` lines of the event (joined with `\n`, as required by the SSE spec).
 *
 * Contains no OkHttp or coroutine types: the parser only deals in `String`.
 */
data class SseEvent(
    val event: String?,
    val data: String,
) {
    /** True for the OpenAI-compatible stream terminator `data: [DONE]`. */
    val isDone: Boolean
        get() = data.trim() == DONE_MARKER

    companion object {
        const val DONE_MARKER = "[DONE]"
    }
}

/**
 * Incremental, allocation-light SSE parser.
 *
 * Feed it one line at a time; an event is returned when the blank line that
 * terminates it is fed. Lines are treated exactly as the SSE specification
 * describes:
 *  - a blank line dispatches the buffered event (nothing is dispatched when no
 *    `data:` line was seen, so keep-alive blank lines are harmless);
 *  - `data:` values are joined with a newline;
 *  - a single leading space after the colon is stripped;
 *  - `:` comment lines, `id:`, `retry:` and unknown fields are ignored;
 *  - a field line without a colon is a field with an empty value.
 *
 * Call [flush] once the input is exhausted to dispatch an event that was not
 * followed by a trailing blank line. The parser is not thread-safe and holds
 * no reference to any transport.
 */
class SseParser {
    private val data = StringBuilder()
    private var eventName: String? = null
    private var hasData = false

    /** Feeds one line (without its line terminator); returns a completed event, if any. */
    fun feed(line: String): SseEvent? {
        val normalized = if (line.endsWith("\r")) line.dropLast(1) else line
        if (normalized.isEmpty()) return flush()
        if (normalized.startsWith(":")) return null

        val colon = normalized.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = normalized
            value = ""
        } else {
            field = normalized.substring(0, colon)
            value = normalized.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }

        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // "id" / "retry" / unknown fields carry no meaning for our use.
            else -> Unit
        }
        return null
    }

    /** Dispatches whatever is buffered (used at end of stream). */
    fun flush(): SseEvent? {
        if (!hasData) {
            eventName = null
            return null
        }
        val event = SseEvent(eventName, data.toString())
        data.setLength(0)
        eventName = null
        hasData = false
        return event
    }
}

/** Parses a whole SSE stream from a sequence of lines. */
fun parseSse(lines: Sequence<String>): List<SseEvent> {
    val parser = SseParser()
    val out = ArrayList<SseEvent>()
    for (line in lines) parser.feed(line)?.let(out::add)
    parser.flush()?.let(out::add)
    return out
}

/** Convenience overload for recorded fixtures: parses an SSE document held in one string. */
fun parseSse(text: String): List<SseEvent> = parseSse(text.lines().asSequence())
