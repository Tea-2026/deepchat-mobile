package com.deepseek.mobilechat.data.local.codec

import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Hand-written codecs for the small JSON columns next to `parts_json`:
 * sampling params, model refs/info, usage, conversation overrides and provider
 * extra headers. All fields are nullable-safe; `null` columns decode to the
 * domain defaults instead of throwing.
 */
object JsonCodecs {

    fun encodeParams(params: SamplingParams): String = buildJsonObject {
        put("temperature", params.temperature.jsonNumber())
        put("topP", params.topP.jsonNumber())
        put("maxTokens", params.maxTokens?.let { JsonPrimitive(it) } ?: JsonNull)
        put("stop", buildJsonArray { params.stop.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    fun decodeParams(json: String?): SamplingParams {
        val obj = json?.takeIf { it.isNotBlank() }?.let(::parseJsonObject) ?: return SamplingParams()
        return SamplingParams(
            temperature = obj.doubleOrNull("temperature"),
            topP = obj.doubleOrNull("topP"),
            maxTokens = obj.intOrNull("maxTokens"),
            stop = obj.arrayOrNull("stop")?.strings() ?: emptyList(),
        )
    }

    fun encodeModelRef(ref: ModelRef): String = buildJsonObject {
        put("providerId", ref.providerId.raw)
        put("modelId", ref.modelId.raw)
    }.toString()

    fun decodeModelRef(json: String?): ModelRef? {
        if (json.isNullOrBlank()) return null
        val obj = parseJsonObject(json)
        return ModelRef(
            providerId = ProviderId(obj.requiredString("providerId")),
            modelId = ModelId(obj.requiredString("modelId")),
        )
    }

    fun encodeModelInfo(info: ModelInfo): String = buildJsonObject {
        put("ref", parseJsonObject(encodeModelRef(info.ref)))
        put("params", parseJsonObject(encodeParams(info.params)))
        put("thinkingLevel", info.thinkingLevel.name)
    }.toString()

    fun decodeModelInfo(json: String?): ModelInfo? {
        if (json.isNullOrBlank()) return null
        val obj = parseJsonObject(json)
        val refJson = obj.objectOrNull("ref")
            ?: throw IllegalArgumentException("model_info_json is missing the 'ref' object")
        return ModelInfo(
            ref = ModelRef(
                providerId = ProviderId(refJson.requiredString("providerId")),
                modelId = ModelId(refJson.requiredString("modelId")),
            ),
            params = obj.objectOrNull("params")?.let { decodeParams(it.toString()) } ?: SamplingParams(),
            thinkingLevel = obj.stringOrNull("thinkingLevel")
                ?.let { ThinkingLevel.valueOf(it) }
                ?: ThinkingLevel.OFF,
        )
    }

    fun encodeUsage(usage: Usage): String = buildJsonObject {
        put("promptTokens", usage.promptTokens)
        put("completionTokens", usage.completionTokens)
        put("reasoningTokens", usage.reasoningTokens)
        put("cachedPromptTokens", usage.cachedPromptTokens)
        put("cost", usage.cost.jsonNumber())
    }.toString()

    fun decodeUsage(json: String?): Usage? {
        if (json.isNullOrBlank()) return null
        val obj = parseJsonObject(json)
        return Usage(
            promptTokens = obj.intOrNull("promptTokens") ?: 0,
            completionTokens = obj.intOrNull("completionTokens") ?: 0,
            reasoningTokens = obj.intOrNull("reasoningTokens") ?: 0,
            cachedPromptTokens = obj.intOrNull("cachedPromptTokens") ?: 0,
            // Rows written before 0.2.0 stored a USD estimate under "costUsd".
            // They were converted wrong anyway (the book had no DeepSeek prices),
            // so they decode as CNY without re-conversion and age out naturally.
            cost = obj.doubleOrNull("cost") ?: obj.doubleOrNull("costUsd"),
        )
    }

    fun encodeOverrides(overrides: ConversationOverrides): String = buildJsonObject {
        put("presetId", overrides.presetId?.raw?.let { JsonPrimitive(it) } ?: JsonNull)
        put(
            "modelRef",
            overrides.modelRef?.let { parseJsonObject(encodeModelRef(it)) } ?: JsonNull,
        )
        put(
            "params",
            overrides.params?.let { parseJsonObject(encodeParams(it)) } ?: JsonNull,
        )
        put("thinkingLevel", overrides.thinkingLevel?.name?.let { JsonPrimitive(it) } ?: JsonNull)
        put("systemPrompt", overrides.systemPrompt?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    fun decodeOverrides(json: String?): ConversationOverrides {
        if (json.isNullOrBlank()) return ConversationOverrides()
        val obj = parseJsonObject(json)
        return ConversationOverrides(
            presetId = obj.stringOrNull("presetId")?.let(::PresetId),
            modelRef = obj.objectOrNull("modelRef")?.let { decodeModelRef(it.toString()) },
            params = obj.objectOrNull("params")?.let { decodeParams(it.toString()) },
            thinkingLevel = obj.stringOrNull("thinkingLevel")?.let { ThinkingLevel.valueOf(it) },
            systemPrompt = obj.stringOrNull("systemPrompt"),
        )
    }

    fun encodeHeaders(headers: Map<String, String>): String = buildJsonObject {
        headers.forEach { (key, value) -> put(key, value) }
    }.toString()

    fun decodeHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return parseJsonObject(json).entries
            .mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
            }
            .toMap()
    }

    private fun Double?.jsonNumber(): JsonPrimitive = this?.let { JsonPrimitive(it) } ?: JsonNull
}
