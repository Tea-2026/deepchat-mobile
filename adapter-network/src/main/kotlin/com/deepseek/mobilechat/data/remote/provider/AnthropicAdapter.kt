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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Adapter for the Anthropic Messages API (`POST {baseUrl}/v1/messages`).
 *
 * Extended thinking is a first-class feature here: the budget is mapped from
 * [ThinkingLevel], `temperature` is omitted while thinking is on, and assistant
 * `Reasoning(text, signature)` parts are replayed as `thinking` blocks with the
 * signature preserved verbatim. Blocks without a signature cannot be replayed
 * and are dropped (the API rejects unsigned thinking blocks).
 */
class AnthropicAdapter(
    private val http: HttpCaller,
    private val resolveAttachment: AttachmentResolver = { null },
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
            url = joinUrl(profile.baseUrl, "v1/messages"),
            headers = anthropicHeaders(profile, apiKey),
            body = buildAnthropicBody(request, resolveAttachment),
        )
        val parser = AnthropicStreamParser()
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

internal fun anthropicHeaders(profile: ProviderProfile, apiKey: String): Map<String, String> = buildMap {
    put("Content-Type", "application/json")
    put("Accept", "text/event-stream")
    put("x-api-key", apiKey)
    put("anthropic-version", "2023-06-01")
    profile.extraHeaders.forEach { (name, value) -> put(name, value) }
}

/** Extended-thinking budget per level; null means "thinking disabled, omit the field". */
internal fun anthropicThinkingBudget(level: ThinkingLevel): Int? = when (level) {
    ThinkingLevel.OFF -> null
    ThinkingLevel.LOW -> 1024
    ThinkingLevel.HIGH -> 4096
    ThinkingLevel.MAX -> 8192
}

internal fun buildAnthropicBody(
    request: GenerationRequest,
    resolveAttachment: AttachmentResolver = { null },
): String {
    val budget = anthropicThinkingBudget(request.thinkingLevel)
    val requestedMax = request.params.maxTokens ?: DEFAULT_MAX_TOKENS
    // The API requires max_tokens > budget_tokens whenever thinking is enabled.
    val maxTokens = if (budget != null) maxOf(requestedMax, budget + 1024) else requestedMax

    val systemText = buildList {
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let(::add)
        request.messages
            .filter { it.role == MessageRole.SYSTEM }
            .mapNotNull { prompt -> prompt.parts.visibleText().takeIf { it.isNotBlank() } }
            .forEach(::add)
    }.joinToString("\n\n")

    val messages = AnthropicMessagesBuilder()
    request.messages
        .filter { it.role != MessageRole.SYSTEM }
        .forEach { prompt -> messages.add(anthropicRole(prompt), anthropicContentBlocks(prompt, resolveAttachment)) }

    val body = buildJsonObject {
        put("model", request.modelRef.modelId.raw)
        put("max_tokens", maxTokens)
        if (systemText.isNotBlank()) put("system", systemText)
        put("messages", messages.build())
        put("stream", true)
        // Anthropic rejects a modified temperature while extended thinking is on.
        if (budget == null) {
            request.params.temperature?.let { put("temperature", it) }
            request.params.topP?.let { put("top_p", it) }
        }
        val stop = request.params.stop.filter { it.isNotBlank() }
        if (stop.isNotEmpty()) put("stop_sequences", buildJsonArray { stop.forEach { add(it) } })
        if (budget != null) {
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", budget)
            })
        }
        if (request.tools.isNotEmpty()) put("tools", anthropicTools(request.tools))
    }
    return body.toString()
}

private const val DEFAULT_MAX_TOKENS = 4096

internal fun anthropicRole(prompt: PromptMessage): String =
    if (prompt.role == MessageRole.ASSISTANT) "assistant" else "user"

internal fun anthropicTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
    tools.forEach { tool ->
        add(buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("input_schema", parseArgumentsElement(tool.parametersJson))
        })
    }
}

/**
 * Converts a prompt message to Anthropic content blocks.
 *
 * Thinking blocks are emitted first: the API requires assistant messages to
 * start with them. Unsigned reasoning is dropped because it cannot be replayed.
 */
internal fun anthropicContentBlocks(
    prompt: PromptMessage,
    resolveAttachment: AttachmentResolver = { null },
): List<JsonElement> {
    val thinkingBlocks = mutableListOf<JsonElement>()
    val blocks = mutableListOf<JsonElement>()

    if (prompt.role == MessageRole.TOOL) {
        prompt.parts.filterIsInstance<MessagePart.ToolResult>().forEach { result ->
            blocks += buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", result.callId)
                put("content", result.content)
                if (!result.ok) put("is_error", true)
            }
        }
        if (blocks.isNotEmpty()) return blocks
    }

    prompt.parts.forEach { part ->
        when (part) {
            is MessagePart.Reasoning -> {
                val signature = part.signature
                if (prompt.role == MessageRole.ASSISTANT && !signature.isNullOrBlank()) {
                    thinkingBlocks += buildJsonObject {
                        put("type", "thinking")
                        put("thinking", part.text)
                        put("signature", signature)
                    }
                }
            }

            is MessagePart.Image -> {
                val payload = resolveAttachment.resolveImage(part.attachmentId)
                if (payload != null) {
                    blocks += buildJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", payload.mimeType)
                            put("data", payload.base64Data)
                        })
                    }
                } else {
                    blocks += textBlock("[image attachment unavailable]")
                }
            }

            is MessagePart.ToolCall -> blocks += buildJsonObject {
                put("type", "tool_use")
                put("id", part.callId)
                put("name", part.name)
                put("input", parseArgumentsElement(part.argumentsJson))
            }

            is MessagePart.ToolResult -> blocks += buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", part.callId)
                put("content", part.content)
                if (!part.ok) put("is_error", true)
            }

            else -> part.asPromptText()?.takeIf { it.isNotBlank() }?.let { text -> blocks += textBlock(text) }
        }
    }

    val merged = thinkingBlocks + blocks
    return merged.ifEmpty { listOf(textBlock("")) }
}

private fun textBlock(text: String): JsonElement = buildJsonObject {
    put("type", "text")
    put("text", text)
}

/** Anthropic requires alternating roles; consecutive same-role messages are merged. */
internal class AnthropicMessagesBuilder {
    private val roles = mutableListOf<String>()
    private val blocks = mutableListOf<MutableList<JsonElement>>()

    fun add(role: String, content: List<JsonElement>) {
        val last = roles.lastOrNull()
        if (last == role) {
            blocks.last().addAll(content)
        } else {
            roles += role
            blocks += content.toMutableList()
        }
    }

    fun build(): JsonArray = buildJsonArray {
        roles.indices.forEach { index ->
            add(buildJsonObject {
                put("role", roles[index])
                put("content", JsonArray(blocks[index]))
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Stream parsing
// ---------------------------------------------------------------------------

/**
 * Parses Anthropic's typed SSE events. State is limited to token counters and
 * the `content_block_start` -> tool id map (deltas only carry the block index).
 *
 * Usage is emitted once, at `message_stop` (input tokens arrive in
 * `message_start`, output tokens in `message_delta`), so consumers always see a
 * complete [Usage].
 */
internal class AnthropicStreamParser {

    private var inputTokens = 0
    private var outputTokens = 0
    private var sawUsage = false
    private var emittedUsage = false
    private var finishedEmitted = false
    private var stopReason: FinishReason? = null
    private val toolUseIds = mutableMapOf<Int, String>()

    fun onEvent(event: SseEvent): List<GenerationEvent> {
        val payload = parseJsonObject(event.data) ?: run {
            finishedEmitted = true
            return listOf(
                GenerationEvent.Failed(
                    AppError.ProviderProtocol("malformed stream event: ${event.data.take(200)}"),
                ),
            )
        }
        val type = event.event?.takeIf { it.isNotBlank() } ?: payload.str("type").orEmpty()

        return when (type) {
            "message_start" -> {
                payload.obj("message")?.obj("usage")?.let { usage ->
                    inputTokens = usage.int("input_tokens") ?: 0
                    sawUsage = true
                }
                emptyList()
            }

            "content_block_start" -> {
                val index = payload.int("index") ?: 0
                val block = payload.obj("content_block")
                if (block?.str("type") == "tool_use") {
                    val id = block.str("id").orEmpty()
                    toolUseIds[index] = id
                    listOf(GenerationEvent.ToolCallDelta(id, block.str("name"), ""))
                } else {
                    emptyList()
                }
            }

            "content_block_delta" -> {
                val index = payload.int("index") ?: 0
                when (payload.obj("delta")?.str("type")) {
                    "text_delta" -> listOfNotNull(
                        payload.obj("delta")?.str("text")?.takeIf { it.isNotEmpty() }
                            ?.let { GenerationEvent.TextDelta(it) },
                    )
                    "thinking_delta" -> listOfNotNull(
                        payload.obj("delta")?.str("thinking")?.takeIf { it.isNotEmpty() }
                            ?.let { GenerationEvent.ReasoningDelta(it) },
                    )
                    "signature_delta" -> listOfNotNull(
                        payload.obj("delta")?.str("signature")?.takeIf { it.isNotEmpty() }
                            ?.let { GenerationEvent.ReasoningSignature(it) },
                    )
                    "input_json_delta" -> listOf(
                        GenerationEvent.ToolCallDelta(
                            callId = toolUseIds[index].orEmpty(),
                            name = null,
                            argumentsDelta = payload.obj("delta")?.str("partial_json").orEmpty(),
                        ),
                    )
                    else -> emptyList()
                }
            }

            "message_delta" -> {
                payload.obj("delta")?.str("stop_reason")?.let { stopReason = mapAnthropicStopReason(it) }
                payload.obj("usage")?.int("output_tokens")?.let { tokens ->
                    outputTokens = tokens
                    sawUsage = true
                }
                emptyList()
            }

            "message_stop" -> finish()

            "error" -> {
                finishedEmitted = true
                listOf(
                    GenerationEvent.Failed(
                        AppError.ProviderProtocol(
                            payload.obj("error")?.str("message") ?: "anthropic stream error",
                        ),
                    ),
                )
            }

            else -> emptyList() // ping / content_block_stop / unknown
        }
    }

    /** Emits the deferred usage report plus a terminal event (idempotent). */
    fun finish(): List<GenerationEvent> {
        val events = mutableListOf<GenerationEvent>()
        if (sawUsage && !emittedUsage) {
            emittedUsage = true
            events += GenerationEvent.UsageReport(Usage(promptTokens = inputTokens, completionTokens = outputTokens))
        }
        if (!finishedEmitted) {
            finishedEmitted = true
            events += GenerationEvent.Finished(stopReason ?: FinishReason.STOP)
        }
        return events
    }
}

internal fun mapAnthropicStopReason(raw: String?): FinishReason = when (raw?.lowercase()) {
    "end_turn", "stop_sequence", "pause_turn" -> FinishReason.STOP
    "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH
    "tool_use" -> FinishReason.TOOL_CALLS
    "refusal" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.STOP
}

/** Test-friendly entry point: parses a recorded Anthropic SSE document. */
internal fun parseAnthropicStream(text: String): List<GenerationEvent> {
    val parser = AnthropicStreamParser()
    val out = mutableListOf<GenerationEvent>()
    for (event in parseSse(text)) {
        if (event.isDone) break
        out += parser.onEvent(event)
    }
    if (out.none { it is GenerationEvent.Finished || it is GenerationEvent.Failed }) out += parser.finish()
    return out
}
