package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.data.remote.http.HttpErrors
import com.deepseek.mobilechat.data.remote.http.HttpResponse
import com.deepseek.mobilechat.data.remote.http.ProviderHttpException
import com.deepseek.mobilechat.data.remote.sse.SseEvent
import com.deepseek.mobilechat.data.remote.sse.SseParser
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLConnection
import java.util.Base64

/**
 * Reads the raw bytes of a local attachment.
 *
 * The adapters never touch the file system themselves: the composition root
 * wires this to the attachment store. Returning null means "attachment not
 * available" and the part is skipped (text fallback) instead of failing the
 * whole request.
 */
typealias AttachmentResolver = (AttachmentId) -> ByteArray?

/** Shared, lenient JSON codec: provider payloads gain fields all the time. */
internal val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * How an assistant's `Reasoning` parts are echoed back on OpenAI-compatible
 * endpoints. OpenAI itself has no field for prior reasoning, so the safe
 * default is [NONE] (only `Text` is replayed); DeepSeek/OpenRouter style
 * endpoints accept [REASONING_CONTENT_FIELD].
 */
enum class ReasoningEcho {
    /** Drop reasoning on the wire; replay only visible text. */
    NONE,

    /** Send `reasoning_content` on assistant messages (DeepSeek, OpenRouter, vLLM). */
    REASONING_CONTENT_FIELD,

    /** Prepend the reasoning text to the assistant content block. */
    CONTENT_PREFIX,
}

// ---------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------

internal data class ImagePayload(val mimeType: String, val base64Data: String)

/** Byte-signature sniffing; falls back to the file name, then to PNG. */
internal fun guessImageMimeType(attachmentId: AttachmentId, bytes: ByteArray): String =
    sniffImageMimeType(bytes)
        ?: URLConnection.guessContentTypeFromName(attachmentId.raw)?.takeIf { it.startsWith("image/") }
        ?: "image/png"

internal fun sniffImageMimeType(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"

    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte() -> "image/jpeg"

    bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() -> "image/gif"

    bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"

    bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "image/bmp"

    else -> null
}

internal fun AttachmentResolver.resolveImage(id: AttachmentId): ImagePayload? {
    val bytes = this(id) ?: return null
    if (bytes.isEmpty()) return null
    return ImagePayload(guessImageMimeType(id, bytes), Base64.getEncoder().encodeToString(bytes))
}

internal fun ImagePayload.asDataUrl(): String = "data:$mimeType;base64,$base64Data"

// ---------------------------------------------------------------------------
// Parts -> plain text
// ---------------------------------------------------------------------------

/**
 * Renders a part that has no native representation on the wire into text.
 * Returns null for parts handled structurally (images, reasoning, tool calls).
 */
internal fun MessagePart.asPromptText(): String? = when (this) {
    is MessagePart.Text -> text
    is MessagePart.ContextBlock -> buildString {
        if (title.isNotBlank()) append(title).append('\n')
        append(body)
        citations.forEach { append("\n- ").append(it.title).append(": ").append(it.url) }
    }
    is MessagePart.SearchResults -> buildString {
        append("Web search results for \"").append(query).append("\":")
        hits.forEach { append("\n- ").append(it.title).append(" (").append(it.url).append("): ").append(it.snippet) }
    }
    is MessagePart.FileRef -> "[file attachment ${attachmentId.raw}]"
    is MessagePart.Image -> null
    is MessagePart.Reasoning -> null
    is MessagePart.ToolCall -> null
    is MessagePart.ToolResult -> null
}

internal fun List<MessagePart>.visibleText(): String =
    mapNotNull { it.asPromptText() }.joinToString("\n").trim()

// ---------------------------------------------------------------------------
// History replay invariant
// ---------------------------------------------------------------------------

/**
 * History replays only assistant turns the protocol can validate.
 *
 * This app persists [MessagePart.ToolCall] parts but has no tool loop: nothing
 * ever executes a call, so no [MessagePart.ToolResult] is ever written. OpenAI
 * compatible history requires one `role:"tool"` message per `tool_call_id` and
 * Anthropic requires a matching `tool_result` block, so replaying an unanswered
 * assistant tool-call turn makes the provider reject the whole request with
 * `HTTP 400: Invalid assistant message: content or tool_calls must be set` —
 * the exact failure behind "regenerate is broken". The same strict validation
 * rejects an assistant turn with neither content nor tool calls, which is what
 * an interrupted generation leaves behind before its first delta.
 *
 * Repair: unanswered tool calls are stripped from the outgoing turn, keeping
 * its visible text so the model still sees what it already said. A turn with
 * nothing replayable left (tool calls only, or an empty placeholder) is omitted
 * entirely. The stored message is never mutated; only the request is repaired.
 * The rule lives here, next to the wire builders, so OpenAI-compatible,
 * Anthropic and Gemini all apply exactly the same invariant.
 */
internal fun List<PromptMessage>.replayableHistory(): List<PromptMessage> {
    val answeredCallIds = buildSet {
        this@replayableHistory.forEach { prompt ->
            prompt.parts.filterIsInstance<MessagePart.ToolResult>().forEach { add(it.callId) }
        }
    }
    return mapNotNull { prompt ->
        if (prompt.role != MessageRole.ASSISTANT) prompt else prompt.replayableCopy(answeredCallIds)
    }
}

private fun PromptMessage.replayableCopy(answeredCallIds: Set<String>): PromptMessage? {
    val toolCalls = parts.filterIsInstance<MessagePart.ToolCall>()
    if (toolCalls.isEmpty()) return takeIf { it.hasReplayableContent() }

    val unansweredCallIds = toolCalls
        .filter { it.callId !in answeredCallIds }
        .mapTo(HashSet()) { it.callId }
    if (unansweredCallIds.isEmpty()) return this

    // Keep visible/answered content, drop only the calls no tool result answers.
    val cleaned = copy(
        parts = parts.filterNot { part ->
            part is MessagePart.ToolCall && part.callId in unansweredCallIds
        },
    )
    val hasAnsweredToolCall = cleaned.parts.any { it is MessagePart.ToolCall }
    return cleaned.takeIf { hasAnsweredToolCall || it.hasReplayableContent() }
}

private fun PromptMessage.hasReplayableContent(): Boolean = parts.any { part ->
    when (part) {
        is MessagePart.Text -> part.text.isNotBlank()
        is MessagePart.ContextBlock -> part.title.isNotBlank() || part.body.isNotBlank()
        is MessagePart.SearchResults -> part.hits.isNotEmpty()
        is MessagePart.Image, is MessagePart.FileRef -> true
        // Reasoning is not content on the OpenAI wire by default, and unsigned
        // thinking is dropped by Anthropic as well.
        is MessagePart.Reasoning -> false
        is MessagePart.ToolCall, is MessagePart.ToolResult -> false
    }
}

// ---------------------------------------------------------------------------
// JSON helpers (manual parsing; no provider model classes)
// ---------------------------------------------------------------------------

internal fun parseJsonObject(text: String): JsonObject? =
    runCatching { ProviderJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

internal fun JsonObject?.obj(key: String): JsonObject? = this?.get(key).objOrNull()

internal fun JsonObject?.arr(key: String): JsonArray? = this?.get(key).arrOrNull()

internal fun JsonObject?.str(key: String): String? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return primitive.contentOrNull
}

internal fun JsonObject?.int(key: String): Int? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()?.toInt()
}

internal fun JsonObject?.double(key: String): Double? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()
}

internal fun JsonObject?.long(key: String): Long? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()?.toLong()
}

internal fun JsonObject?.bool(key: String): Boolean? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    return primitive.content.toBooleanStrictOrNull()
}

/** Parses a tool argument string; providers want an object, not a JSON string. */
internal fun parseArgumentsElement(argumentsJson: String): JsonElement =
    runCatching { ProviderJson.parseToJsonElement(argumentsJson) }.getOrNull()
        ?: JsonObject(emptyMap())

// ---------------------------------------------------------------------------
// Time
// ---------------------------------------------------------------------------

typealias NowMillis = () -> Long

internal val SystemNowMillis: NowMillis = { System.currentTimeMillis() }

// ---------------------------------------------------------------------------
// Transport plumbing shared by the three adapters
// ---------------------------------------------------------------------------

internal fun joinUrl(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')

/**
 * Reads a secret for a request, distinguishing "not configured" from "the
 * keystore failed". Keeps `suspend` call sites free of try/catch noise.
 */
internal suspend fun readSecret(secrets: SecretStore, ref: String, label: String): Outcome<String> =
    try {
        val value = secrets.get(ref)
        if (value.isNullOrBlank()) {
            Outcome.Err(AppError.SecretMissing("$label is not configured ($ref)"))
        } else {
            Outcome.Ok(value)
        }
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        Outcome.Err(HttpErrors.unknown(t))
    }

/** Normalizes anything an adapter can throw into the frozen [AppError] set. */
internal fun Throwable.toAppError(): AppError = when (this) {
    is ProviderHttpException -> error
    is java.net.SocketTimeoutException -> HttpErrors.ioError(this)
    is java.io.IOException -> HttpErrors.ioError(this)
    else -> HttpErrors.unknown(this)
}

/**
 * Emits a batch of parsed events; returns true when the batch contained a
 * terminal [GenerationEvent.Finished] so callers can synthesize a fallback.
 */
internal suspend fun FlowCollector<GenerationEvent>.emitParsed(
    events: List<GenerationEvent>,
): Boolean {
    var sawFinish = false
    for (event in events) {
        if (event is GenerationEvent.Finished) sawFinish = true
        emit(event)
    }
    return sawFinish
}

/**
 * Consumes the SSE lines of [response], feeding each completed event to
 * [parse], and closes the response (also on cancellation or early exit).
 *
 * Stops at the first terminal event (`Finished` or `Failed`) so a provider
 * error aborts the stream instead of being followed by a synthetic finish.
 */
internal suspend fun FlowCollector<GenerationEvent>.pumpSse(
    response: HttpResponse,
    parse: (SseEvent) -> List<GenerationEvent>,
): Boolean {
    val sse = SseParser()
    var finished = false
    var terminated = false
    response.use { stream ->
        for (line in stream.bodyLines) {
            val event = sse.feed(line) ?: continue
            if (event.isDone) {
                terminated = true
                break
            }
            val parsed = parse(event)
            finished = emitParsed(parsed) || finished
            if (finished || parsed.any { it is GenerationEvent.Failed }) {
                terminated = true
                break
            }
        }
        if (!terminated) {
            sse.flush()?.let { pending -> finished = emitParsed(parse(pending)) || finished }
        }
    }
    return finished
}
