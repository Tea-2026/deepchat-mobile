package com.deepseek.mobilechat.platform.export

/**
 * A tiny, dependency-free JSON model, parser and pretty writer.
 *
 * Why not kotlinx.serialization? `:adapter-platform`'s build file is part of the
 * frozen contract and does not put the serialization runtime/plugin on the
 * classpath. Keeping the codec here as pure Kotlin means the export logic can be
 * unit-tested on the JVM without an Android runtime, and the format stays fully
 * under our control (schemaVersion gate, readable output).
 *
 * This is intentionally not a general-purpose JSON library: it supports exactly
 * the subset needed by the export format and rejects malformed input loudly.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    /**
     * Numbers keep their original textual form so 64-bit values (timestamps,
     * byte counts, message seq) survive a round-trip without going through a
     * Double.
     */
    data class Num(val raw: String) : JsonValue {
        constructor(value: Long) : this(value.toString())
        constructor(value: Int) : this(value.toString())
        constructor(value: Double) : this(value.toString())

        fun toLongOrNull(): Long? = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()

        fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
    }

    data class Str(val value: String) : JsonValue

    data class Arr(val values: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
    }
}

class JsonFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Builds an object while preserving field insertion order (readable output). */
class JsonObjectBuilder {
    private val fields = LinkedHashMap<String, JsonValue>()

    fun put(key: String, value: JsonValue?) {
        fields[key] = value ?: JsonValue.Null
    }

    fun put(key: String, value: String?) {
        fields[key] = value?.let { JsonValue.Str(it) } ?: JsonValue.Null
    }

    fun put(key: String, value: Boolean) {
        fields[key] = JsonValue.Bool(value)
    }

    fun put(key: String, value: Int?) {
        fields[key] = value?.let { JsonValue.Num(it) } ?: JsonValue.Null
    }

    fun put(key: String, value: Long?) {
        fields[key] = value?.let { JsonValue.Num(it) } ?: JsonValue.Null
    }

    fun put(key: String, value: Double?) {
        fields[key] = if (value == null || !value.isFinite()) {
            JsonValue.Null
        } else {
            JsonValue.Num(value)
        }
    }

    fun build(): JsonValue.Obj = JsonValue.Obj(fields)
}

fun jsonObject(block: JsonObjectBuilder.() -> Unit): JsonValue.Obj =
    JsonObjectBuilder().apply(block).build()

fun jsonArray(values: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(values)

fun List<String>.toJsonArray(): JsonValue.Arr = jsonArray(map(JsonValue::Str))

// ---------------------------------------------------------------------------
// Reading helpers (strict by default: a malformed payload is an import error)
// ---------------------------------------------------------------------------

fun JsonValue.Obj.stringOrNull(key: String): String? = (fields[key] as? JsonValue.Str)?.value

fun JsonValue.Obj.string(key: String): String =
    stringOrNull(key) ?: throw JsonFormatException("expected string field '$key'")

fun JsonValue.Obj.longOrNull(key: String): Long? = (fields[key] as? JsonValue.Num)?.toLongOrNull()

fun JsonValue.Obj.long(key: String): Long =
    longOrNull(key) ?: throw JsonFormatException("expected integer field '$key'")

fun JsonValue.Obj.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

fun JsonValue.Obj.int(key: String): Int =
    intOrNull(key) ?: throw JsonFormatException("expected integer field '$key'")

fun JsonValue.Obj.doubleOrNull(key: String): Double? = (fields[key] as? JsonValue.Num)?.toDoubleOrNull()

fun JsonValue.Obj.boolOrNull(key: String): Boolean? = (fields[key] as? JsonValue.Bool)?.value

fun JsonValue.Obj.bool(key: String): Boolean =
    boolOrNull(key) ?: throw JsonFormatException("expected boolean field '$key'")

fun JsonValue.Obj.arrayOrNull(key: String): List<JsonValue>? = (fields[key] as? JsonValue.Arr)?.values

fun JsonValue.Obj.array(key: String): List<JsonValue> =
    arrayOrNull(key) ?: throw JsonFormatException("expected array field '$key'")

fun JsonValue.Obj.objectOrNull(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

fun JsonValue.Obj.requireObject(key: String): JsonValue.Obj =
    objectOrNull(key) ?: throw JsonFormatException("expected object field '$key'")

/** String elements; non-string elements are rejected rather than silently dropped. */
fun List<JsonValue>.stringValues(field: String): List<String> = map {
    (it as? JsonValue.Str)?.value
        ?: throw JsonFormatException("expected string element in '$field'")
}

object Json {
    fun parse(text: String): JsonValue = JsonParser(text).parseDocument()

    fun write(value: JsonValue, pretty: Boolean = true): String = buildString {
        writeValue(value, depth = 0, pretty = pretty, out = this)
    }

    private fun writeValue(value: JsonValue, depth: Int, pretty: Boolean, out: StringBuilder) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                if (value.values.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                value.values.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    if (pretty) {
                        out.append('\n')
                        writeIndent(out, depth + 1)
                    }
                    writeValue(element, depth + 1, pretty, out)
                }
                if (pretty) {
                    out.append('\n')
                    writeIndent(out, depth)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                if (value.fields.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append('{')
                var first = true
                value.fields.forEach { (key, fieldValue) ->
                    if (!first) out.append(',')
                    first = false
                    if (pretty) {
                        out.append('\n')
                        writeIndent(out, depth + 1)
                    }
                    writeString(key, out)
                    out.append(':')
                    if (pretty) out.append(' ')
                    writeValue(fieldValue, depth + 1, pretty, out)
                }
                if (pretty) {
                    out.append('\n')
                    writeIndent(out, depth)
                }
                out.append('}')
            }
        }
    }

    private fun writeIndent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') {
                    out.append("\\u")
                    out.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }
}

private class JsonParser(private val text: String) {
    private var position = 0
    private var depth = 0

    fun parseDocument(): JsonValue {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        if (position != text.length) fail("unexpected trailing content at offset $position")
        return value
    }

    private fun readValue(): JsonValue {
        if (position >= text.length) fail("unexpected end of input")
        return when (val char = text[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            else -> if (char == '-' || char in '0'..'9') readNumber() else fail("unexpected '$char' at offset $position")
        }
    }

    private fun readObject(): JsonValue.Obj {
        enterContainer()
        expect('{')
        skipWhitespace()
        val fields = LinkedHashMap<String, JsonValue>()
        if (peek() == '}') {
            position++
            leaveContainer()
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                '}' -> {
                    position++
                    leaveContainer()
                    return JsonValue.Obj(fields)
                }
                else -> fail("expected ',' or '}' at offset $position")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        enterContainer()
        expect('[')
        skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (peek() == ']') {
            position++
            leaveContainer()
            return JsonValue.Arr(values)
        }
        while (true) {
            skipWhitespace()
            values += readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                ']' -> {
                    position++
                    leaveContainer()
                    return JsonValue.Arr(values)
                }
                else -> fail("expected ',' or ']' at offset $position")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (position < text.length) {
            val char = text[position++]
            when {
                char == '"' -> return out.toString()
                char == '\\' -> {
                    if (position >= text.length) fail("unterminated escape sequence")
                    when (val escaped = text[position++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (position + 4 > text.length) fail("truncated unicode escape")
                            val hex = text.substring(position, position + 4)
                            val code = hex.toIntOrNull(16)
                                ?: fail("invalid unicode escape '\\u$hex'")
                            position += 4
                            out.append(code.toChar())
                        }
                        else -> fail("invalid escape '\\$escaped'")
                    }
                }
                char < ' ' -> fail("unescaped control character in string")
                else -> out.append(char)
            }
        }
        fail("unterminated string")
    }

    private fun readNumber(): JsonValue.Num {
        val start = position
        if (peek() == '-') position++
        if (position >= text.length || text[position] !in '0'..'9') fail("invalid number at offset $start")
        while (position < text.length && text[position] in '0'..'9') position++
        if (position < text.length && text[position] == '.') {
            position++
            if (position >= text.length || text[position] !in '0'..'9') fail("invalid fraction at offset $start")
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
            position++
            if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
            if (position >= text.length || text[position] !in '0'..'9') fail("invalid exponent at offset $start")
            while (position < text.length && text[position] in '0'..'9') position++
        }
        return JsonValue.Num(text.substring(start, position))
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, position)) fail("invalid literal at offset $position")
        position += literal.length
        return value
    }

    private fun enterContainer() {
        depth++
        if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH levels")
    }

    private fun leaveContainer() {
        depth--
    }

    private fun skipWhitespace() {
        while (position < text.length && text[position] in WHITESPACE) position++
    }

    private fun peek(): Char {
        if (position >= text.length) fail("unexpected end of input")
        return text[position]
    }

    private fun expect(char: Char) {
        if (position >= text.length || text[position] != char) {
            fail("expected '$char' at offset $position")
        }
        position++
    }

    private fun fail(message: String): Nothing = throw JsonFormatException(message)

    private companion object {
        const val MAX_DEPTH = 200
        val WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
    }
}
