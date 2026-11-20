package com.deepseek.mobilechat.data.remote.provider

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ToolSpec
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.sse.SseEvent
import com.deepseek.mobilechat.data.remote.sse.parseSse
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Adapter for the OpenAI `/chat/completions` protocol and every compatible
 * variant (DeepSeek, OpenRouter, Groq, vLLM, LM Studio, ...).
 *
 * Responsibilities: build the request body, stream SSE chunks, and translate
 * provider JSON into the frozen [GenerationEvent] set. All parsing helpers are
 * pure functions so they can be tested against recorded payloads.
 */
class OpenAiCompatibleAdapter(
    private val http: HttpCaller,
    private val resolveAttachment: AttachmentResolver = { null },
    private val reasoningEcho: ReasoningEcho = ReasoningEcho.NONE,
    /** Ask for a final usage chunk. Off for minimal servers that reject `stream_options`. */
    private val includeUsage: Boolean = true,
    private val now: NowMillis = SystemNowMillis,
) {

    fun stream(
        request: GenerationRequest,
        profile: ProviderProfile,
        apiKey: String,
    ): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started(now()))
        val response = http.execute(
            method = "POST",
            url = joinUrl(profile.baseUrl, "chat/completions"),
            headers = openAiHeaders(profile, apiKey),
            body = buildOpenAiBody(request, resolveAttachment, reasoningEcho, includeUsage),
        )
        val parser = OpenAiStreamParser()
        pumpSse(response) { event -> parser.onEvent(event) }
        emitParsed(parser.finish())
    }
        .catch { t ->
            if (t is CancellationException) throw t
            emit(GenerationEvent.Failed(t.toAppError()))
        }
        .flowOn(Dispatchers.IO)
}

// ---------------------------------------------------------------------------
// Request building
// ---------------------------------------------------------------------------

internal fun openAiHeaders(profile: ProviderProfile, apiKey: String): Map<String, String> = buildMap {
    put("Content-Type", "application/json")
    put("Accept", "text/event-stream")
    put("Authorization", "Bearer $apiKey")
    profile.extraHeaders.forEach { (name, value) -> put(name, value) }
}

/** [ThinkingLevel] -> `reasoning_effort`; OFF means the field is omitted. */
internal fun openAiReasoningEffort(level: ThinkingLevel): String? = when (level) {
    ThinkingLevel.OFF -> null
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.HIGH -> "high"
    ThinkingLevel.MAX -> "max"
}

internal fun buildOpenAiBody(
    request: GenerationRequest,
    resolveAttachment: AttachmentResolver = { null },
    reasoningEcho: ReasoningEcho = ReasoningEcho.NONE,
    includeUsage: Boolean = true,
): String {
    val messages = buildJsonArray {
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
            add(buildJsonObject {
                put("role", "system")
                put("content", system)
            })
        }
        request.messages.forEach { prompt ->
            addAll(openAiMessagesFor(prompt, resolveAttachment, reasoningEcho))
        }
    }

    val body = buildJsonObject {
        put("model", request.modelRef.modelId.raw)
        put("messages", messages)
        put("stream", true)
        if (includeUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
        request.params.temperature?.let { put("temperature", it) }
        request.params.topP?.let { put("top_p", it) }
        request.params.maxTokens?.let { put("max_tokens", it) }
        val stop = request.params.stop.filter { it.isNotBlank() }
        if (stop.isNotEmpty()) put("stop", buildJsonArray { stop.forEach { add(it) } })
        openAiReasoningEffort(request.thinkingLevel)?.let { put("reasoning_effort", it) }
        if (request.tools.isNotEmpty()) put("tools", openAiTools(request.tools))
    }
    return body.toString()
}

internal fun openAiRole(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "system"
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.TOOL -> "tool"
}

internal fun openAiTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
    tools.forEach { tool ->
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", parseArgumentsElement(tool.parametersJson))
            })
        })
    }
}

internal fun openAiToolCalls(calls: List<MessagePart.ToolCall>): JsonArray = buildJsonArray {
    calls.forEach { call ->
        add(buildJsonObject {
            put("id", call.callId)
            put("type", "function")
            put("function", buildJsonObject {
                put("name", call.name)
                put("arguments", call.argumentsJson)
            })
        })
    }
}

/**
 * One [PromptMessage] becomes one wire message, except tool results which the
 * OpenAI protocol requires as one `role: tool` message per `tool_call_id`.
 */
internal fun openAiMessagesFor(
    prompt: PromptMessage,
    resolveAttachment: AttachmentResolver = { null },
    reasoningEcho: ReasoningEcho = ReasoningEcho.NONE,
): List<JsonElement> {
    if (prompt.role == MessageRole.TOOL) {
        val results = prompt.parts.filterIsInstance<MessagePart.ToolResult>()
        if (results.isNotEmpty()) {
            return results.map { result ->
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.callId)
                    put("content", result.content)
                }
            }
        }
    }

    val contentParts = mutableListOf<JsonElement>()
    val toolCalls = mutableListOf<MessagePart.ToolCall>()
    val reasoning = StringBuilder()
    var hasImage = false

    prompt.parts.forEach { part ->
        when (part) {
            is MessagePart.Reasoning ->
                if (prompt.role == MessageRole.ASSISTANT) reasoning.append(part.text)

            is MessagePart.Image -> {
                val payload = resolveAttachment.resolveImage(part.attachmentId)
                if (payload != null) {
                    hasImage = true
                    contentParts += buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", payload.asDataUrl()) })
                    }
                } else {
                    contentParts += buildJsonObject {
                        put("type", "text")
                        put("text", "[image attachment unavailable]")
                    }
                }
            }

            is MessagePart.ToolCall ->
                if (prompt.role == MessageRole.ASSISTANT) toolCalls += part

            else -> part.asPromptText()?.takeIf { it.isNotBlank() }?.let { text ->
                contentParts += buildJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }
    }

    val reasoningText = reasoning.toString()
    if (reasoningEcho == ReasoningEcho.CONTENT_PREFIX &&
        reasoningText.isNotBlank() &&
        prompt.role == MessageRole.ASSISTANT
    ) {
        contentParts.add(0, buildJsonObject { put("type", "text"); put("text", reasoningText) })
    }

    val content: JsonElement = when {
        contentParts.isEmpty() -> JsonNull
        // Text-only messages stay a plain string: the widest provider support.
        !hasImage -> JsonPrimitive(contentParts.joinToString("\n") { (it as JsonObject).str("text").orEmpty() })
        else -> JsonArray(contentParts)
    }

    val role = if (prompt.role == MessageRole.TOOL) "user" else openAiRole(prompt.role)
    val message = buildJsonObject {
        put("role", role)
        put("content", content)
        if (reasoningEcho == ReasoningEcho.REASONING_CONTENT_FIELD &&
            reasoningText.isNotBlank() &&
            prompt.role == MessageRole.ASSISTANT
        ) {
            put("reasoning_content", reasoningText)
        }
        if (toolCalls.isNotEmpty()) put("tool_calls", openAiToolCalls(toolCalls))
    }
    return listOf(message)
}

// ---------------------------------------------------------------------------
// Stream parsing
// ---------------------------------------------------------------------------

/**
 * Stateful (but transport-free) chunk parser: remembers tool-call ids that the
 * protocol only sends on the first delta of each call.
 */
internal class OpenAiStreamParser {

    private val toolCallIds = mutableMapOf<Int, String>()

    var finishReason: FinishReason? = null
        private set

    private var finishedEmitted = false

    fun onEvent(event: SseEvent): List<GenerationEvent> {
        if (event.isDone) return emptyList()
        return parse(event.data)
    }

    fun parse(data: String): List<GenerationEvent> {
        val root = parseJsonObject(data) ?: return fail(
            AppError.ProviderProtocol("malformed stream chunk: ${data.take(200)}"),
        )

        root.obj("error")?.let { error ->
            return fail(AppError.ProviderProtocol(error.str("message") ?: "provider error"))
        }

        val events = mutableListOf<GenerationEvent>()
        root.obj("usage")?.let { usage -> events += GenerationEvent.UsageReport(usageFromOpenAi(usage)) }

        val choice = root.arr("choices")?.firstOrNull().objOrNull() ?: return events
        val delta = choice.obj("delta")

        val reasoning = delta.str("reasoning_content") ?: delta.str("reasoning")
        reasoning?.takeIf { it.isNotEmpty() }?.let { events += GenerationEvent.ReasoningDelta(it) }

        delta.contentText()?.takeIf { it.isNotEmpty() }?.let { events += GenerationEvent.TextDelta(it) }

        delta.arr("tool_calls")?.forEach { element ->
            val call = element.objOrNull() ?: return@forEach
            val index = call.int("index") ?: 0
            val explicitId = call.str("id")?.takeIf { it.isNotEmpty() }
            if (explicitId != null) toolCallIds[index] = explicitId
            val callId = explicitId ?: toolCallIds[index] ?: "call_$index"
            val function = call.obj("function")
            events += GenerationEvent.ToolCallDelta(
                callId = callId,
                name = function?.str("name"),
                argumentsDelta = function?.str("arguments").orEmpty(),
            )
        }

        choice.str("finish_reason")?.let { raw ->
            val reason = mapOpenAiFinishReason(raw)
            finishReason = reason
            if (!finishedEmitted) {
                finishedEmitted = true
                events += GenerationEvent.Finished(reason)
            }
        }
        return events
    }

    /** Emits a terminal event when the stream ended without `finish_reason` (idempotent). */
    fun finish(): List<GenerationEvent> {
        if (finishedEmitted) return emptyList()
        finishedEmitted = true
        return listOf(GenerationEvent.Finished(finishReason ?: FinishReason.STOP))
    }

    /** A provider error chunk terminates the stream; no synthetic finish follows. */
    private fun fail(error: AppError): List<GenerationEvent> {
        finishedEmitted = true
        return listOf(GenerationEvent.Failed(error))
    }
}

/** `delta.content` is a string for OpenAI, but some compatible servers send part arrays. */
private fun JsonObject?.contentText(): String? {
    val content = this?.get("content") ?: return null
    (content as? JsonPrimitive)?.contentOrNull?.let { return it }
    val parts = content as? JsonArray ?: return null
    return parts.mapNotNull { element ->
        val part = element.objOrNull() ?: return@mapNotNull null
        part.str("text") ?: part.str("content")
    }.joinToString("").ifEmpty { null }
}

internal fun mapOpenAiFinishReason(raw: String?): FinishReason = when (raw?.lowercase()) {
    "stop", "end_turn" -> FinishReason.STOP
    "length" -> FinishReason.LENGTH
    "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
    "content_filter" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.STOP
}

internal fun usageFromOpenAi(usage: JsonObject): Usage = Usage(
    promptTokens = usage.int("prompt_tokens") ?: 0,
    completionTokens = usage.int("completion_tokens") ?: 0,
    reasoningTokens = usage.obj("completion_tokens_details")?.int("reasoning_tokens") ?: 0,
    costUsd = null,
)

/** Test-friendly entry point: parses a recorded SSE document into events. */
internal fun parseOpenAiStream(text: String): List<GenerationEvent> {
    val parser = OpenAiStreamParser()
    val out = mutableListOf<GenerationEvent>()
    for (event in parseSse(text)) {
        if (event.isDone) break
        out += parser.onEvent(event)
    }
    if (out.none { it is GenerationEvent.Finished || it is GenerationEvent.Failed }) {
        out += parser.finish()
    }
    return out
}
