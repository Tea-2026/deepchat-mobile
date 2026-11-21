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
 * Adapter for the Gemini `streamGenerateContent` endpoint.
 *
 * The API key travels in the query string (as the REST API requires), thinking
 * is mapped through `generationConfig.thinkingConfig.thinkingBudget`, and
 * images are sent as `inline_data` (base64), which keeps the adapter free of
 * the Files API upload dance.
 */
class GeminiAdapter(
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
            url = geminiStreamUrl(profile.baseUrl, request.modelRef.modelId.raw, apiKey),
            headers = geminiHeaders(profile),
            body = buildGeminiBody(request, resolveAttachment),
        )
        val parser = GeminiStreamParser()
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

internal fun geminiHeaders(profile: ProviderProfile): Map<String, String> = buildMap {
    put("Content-Type", "application/json")
    put("Accept", "text/event-stream")
    profile.extraHeaders.forEach { (name, value) -> put(name, value) }
}

/** Completions live under `{base}/v1beta`; a base URL that already carries it is accepted. */
internal fun geminiApiBase(baseUrl: String): String = baseUrl.trimEnd('/').removeSuffix("/v1beta")

internal fun geminiStreamUrl(baseUrl: String, modelId: String, apiKey: String): String {
    val model = modelId.removePrefix("models/")
    val key = java.net.URLEncoder.encode(apiKey, "UTF-8").replace("+", "%20")
    return joinUrl(geminiApiBase(baseUrl), "v1beta/models/$model:streamGenerateContent?alt=sse&key=$key")
}

/** Gemini thinking budgets (tokens); null means the field is omitted. */
internal fun geminiThinkingBudget(level: ThinkingLevel): Int? = when (level) {
    ThinkingLevel.OFF -> null
    ThinkingLevel.LOW -> 1024
    ThinkingLevel.HIGH -> 8192
    ThinkingLevel.MAX -> 24576
}

internal fun buildGeminiBody(
    request: GenerationRequest,
    resolveAttachment: AttachmentResolver = { null },
): String {
    val contents = buildJsonArray {
        request.messages
            .replayableHistory()
            .filter { it.role != MessageRole.SYSTEM }
            .forEach { prompt ->
                val content = geminiContent(prompt, resolveAttachment)
                if (content != null) add(content)
            }
    }

    val systemText = buildList {
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let(::add)
        request.messages
            .filter { it.role == MessageRole.SYSTEM }
            .mapNotNull { prompt -> prompt.parts.visibleText().takeIf { it.isNotBlank() } }
            .forEach(::add)
    }.joinToString("\n\n")

    val body = buildJsonObject {
        put("contents", contents)
        if (systemText.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", systemText) }) })
            })
        }
        put("generationConfig", geminiGenerationConfig(request))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("functionDeclarations", geminiFunctionDeclarations(request.tools)) })
            })
        }
    }
    return body.toString()
}

internal fun geminiGenerationConfig(request: GenerationRequest): JsonObject {
    val config = buildJsonObject {
        request.params.temperature?.let { put("temperature", it) }
        request.params.topP?.let { put("topP", it) }
        request.params.maxTokens?.let { put("maxOutputTokens", it) }
        val stop = request.params.stop.filter { it.isNotBlank() }
        if (stop.isNotEmpty()) put("stopSequences", buildJsonArray { stop.forEach { add(it) } })
        geminiThinkingBudget(request.thinkingLevel)?.let { budget ->
            put("thinkingConfig", buildJsonObject { put("thinkingBudget", budget) })
        }
    }
    return config
}

/** Gemini calls the assistant role `model`; everything else is user data. */
internal fun geminiRole(prompt: PromptMessage): String =
    if (prompt.role == MessageRole.ASSISTANT) "model" else "user"

internal fun geminiContent(
    prompt: PromptMessage,
    resolveAttachment: AttachmentResolver = { null },
): JsonElement? {
    val parts = buildJsonArray {
        prompt.parts.forEach { part ->
            when (part) {
                is MessagePart.Image -> {
                    val payload = resolveAttachment.resolveImage(part.attachmentId)
                    if (payload != null) {
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", payload.mimeType)
                                put("data", payload.base64Data)
                            })
                        })
                    } else {
                        add(buildJsonObject { put("text", "[image attachment unavailable]") })
                    }
                }

                // Gemini has no signature-backed thinking replay in our model:
                // history reasoning is not sent back (documented limitation).
                is MessagePart.Reasoning -> Unit

                is MessagePart.ToolCall -> {
                    if (prompt.role == MessageRole.ASSISTANT) {
                        add(buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", part.name)
                                put("args", parseArgumentsElement(part.argumentsJson))
                            })
                        })
                    }
                }

                // A functionResponse needs the function *name*, which ToolResult
                // does not carry, so the result is replayed as text.
                is MessagePart.ToolResult -> add(buildJsonObject {
                    put("text", "[tool result ${part.callId}] ${part.content}")
                })

                else -> part.asPromptText()?.takeIf { it.isNotBlank() }?.let { text ->
                    add(buildJsonObject { put("text", text) })
                }
            }
        }
    }
    if (parts.isEmpty()) return null
    return buildJsonObject {
        put("role", geminiRole(prompt))
        put("parts", parts)
    }
}

internal fun geminiFunctionDeclarations(tools: List<ToolSpec>): JsonArray = buildJsonArray {
    tools.forEach { tool ->
        add(buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", parseArgumentsElement(tool.parametersJson))
        })
    }
}

// ---------------------------------------------------------------------------
// Stream parsing
// ---------------------------------------------------------------------------

internal class GeminiStreamParser {

    private var finishReason: FinishReason? = null
    private var finishedEmitted = false
    private var pendingUsage: Usage? = null
    private var usageEmitted = false

    fun onEvent(event: SseEvent): List<GenerationEvent> {
        if (event.isDone) return emptyList()
        return parse(event.data)
    }

    fun parse(data: String): List<GenerationEvent> {
        val root = parseJsonObject(data) ?: run {
            finishedEmitted = true
            return listOf(
                GenerationEvent.Failed(
                    AppError.ProviderProtocol("malformed stream chunk: ${data.take(200)}"),
                ),
            )
        }

        root.obj("error")?.let { error ->
            finishedEmitted = true
            return listOf(
                GenerationEvent.Failed(
                    AppError.ProviderProtocol(error.str("message") ?: "gemini error"),
                ),
            )
        }

        val events = mutableListOf<GenerationEvent>()

        root.arr("candidates")?.forEach { element ->
            val candidate = element.objOrNull() ?: return@forEach
            candidate.obj("content")?.arr("parts")?.forEach { partElement ->
                val part = partElement.objOrNull() ?: return@forEach
                val text = part.str("text")
                if (!text.isNullOrEmpty()) {
                    if (part.bool("thought") == true) {
                        events += GenerationEvent.ReasoningDelta(text)
                    } else {
                        events += GenerationEvent.TextDelta(text)
                    }
                }
                part.obj("functionCall")?.let { call ->
                    events += GenerationEvent.ToolCallDelta(
                        callId = call.str("id") ?: call.str("name").orEmpty(),
                        name = call.str("name"),
                        argumentsDelta = call.obj("args")?.toString().orEmpty(),
                    )
                }
            }
            candidate.str("finishReason")?.let { raw -> finishReason = mapGeminiFinishReason(raw) }
        }

        root.obj("usageMetadata")?.let { metadata ->
            pendingUsage = Usage(
                promptTokens = metadata.int("promptTokenCount") ?: 0,
                completionTokens = metadata.int("candidatesTokenCount") ?: 0,
                reasoningTokens = metadata.int("thoughtsTokenCount") ?: 0,
                // Gemini bills cached prompt tokens at a discount and reports
                // them separately from the prompt total.
                cachedPromptTokens = metadata.int("cachedContentTokenCount") ?: 0,
            )
        }

        root.obj("promptFeedback")?.str("blockReason")?.let { blockReason ->
            finishReason = FinishReason.CONTENT_FILTER
            finishedEmitted = true
            events += GenerationEvent.Failed(AppError.ProviderProtocol("prompt blocked: $blockReason"))
            events += GenerationEvent.Finished(FinishReason.CONTENT_FILTER)
        }

        if (finishReason != null && !finishedEmitted) {
            events += drainTerminal()
        }
        return events
    }

    /** Emits usage/finish events still pending (idempotent). */
    fun finish(): List<GenerationEvent> = drainTerminal()

    private fun drainTerminal(): List<GenerationEvent> {
        val events = mutableListOf<GenerationEvent>()
        if (!finishedEmitted) {
            finishedEmitted = true
            if (pendingUsage != null && !usageEmitted) {
                usageEmitted = true
                events += GenerationEvent.UsageReport(pendingUsage!!)
            }
            events += GenerationEvent.Finished(finishReason ?: FinishReason.STOP)
        } else if (pendingUsage != null && !usageEmitted) {
            usageEmitted = true
            events += GenerationEvent.UsageReport(pendingUsage!!)
        }
        return events
    }
}

internal fun mapGeminiFinishReason(raw: String?): FinishReason = when (raw?.uppercase()) {
    "STOP" -> FinishReason.STOP
    "MAX_TOKENS" -> FinishReason.LENGTH
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
        FinishReason.CONTENT_FILTER
    "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "OTHER" -> FinishReason.ERROR
    else -> FinishReason.STOP
}

/** Test-friendly entry point: parses a recorded Gemini SSE document. */
internal fun parseGeminiStream(text: String): List<GenerationEvent> {
    val parser = GeminiStreamParser()
    val out = mutableListOf<GenerationEvent>()
    for (event in parseSse(text)) {
        if (event.isDone) break
        out += parser.onEvent(event)
    }
    out += parser.finish()
    return out
}
