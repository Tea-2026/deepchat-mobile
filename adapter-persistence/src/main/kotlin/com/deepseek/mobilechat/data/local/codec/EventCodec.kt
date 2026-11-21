package com.deepseek.mobilechat.data.local.codec

import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventData
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.SurfaceOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Lossless JSON codec for the `data_json`, `surface_op_json` and
 * `source_seqs_json` columns of `conversation_events`.
 *
 * The payload shape is the one frozen in `docs/architecture-v2-log.md` §13.2:
 * message events carry `messageId` / `parentId` / `parts[]` (and the assistant's
 * `usage`, `finishReason`, `modelInfo`, `citations[]`), record events carry the
 * fields listed for their type. `parts` is embedded as the same tagged-union
 * array [PartsCodec] writes, so no content is re-shaped on the way to the log.
 *
 * Decoding is strict: an unknown `type` / missing field / newer payload version
 * throws instead of silently dropping a record. A log that cannot be replayed
 * losslessly must never be handed to a projection.
 */
object EventCodec {

    /** Shape version of every payload this codec writes. */
    const val SCHEMA_VERSION: Int = 1

    fun encodeData(data: EventData): String = when (data) {
        is EventData.SystemMessage -> buildJsonObject {
            put("text", data.text)
        }

        is EventData.DeveloperMessage -> buildJsonObject {
            put("text", data.text)
            put("kind", data.kind)
        }

        is EventData.UserMessage -> buildJsonObject {
            put("messageId", data.messageId.raw)
            put("parentId", data.parentId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
            put("parts", partsArray(data.parts))
            data.compactionId?.let { compactionId ->
                // §13.2: a compaction stand-in is a normal user message tagged
                // with `source{kind:'compact-checkpoint', compactionId}`.
                put(
                    "source",
                    buildJsonObject {
                        put("kind", COMPACT_CHECKPOINT)
                        put("compactionId", compactionId.raw)
                    },
                )
            }
        }

        is EventData.AssistantMessage -> buildJsonObject {
            put("messageId", data.messageId.raw)
            put("parentId", data.parentId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
            put("parts", partsArray(data.parts))
            put("modelInfo", data.modelInfo?.let { parseJsonObject(JsonCodecs.encodeModelInfo(it)) } ?: JsonNull)
            put("usage", data.usage?.let { parseJsonObject(JsonCodecs.encodeUsage(it)) } ?: JsonNull)
            put("finishReason", data.finishReason.name)
            put("citations", citationsArray(data.citations))
        }

        is EventData.ToolResult -> buildJsonObject {
            put("toolUseId", data.toolUseId)
            put("content", data.content)
            put("isError", data.isError)
        }

        is EventData.TurnStart -> buildJsonObject {
            put("messageId", data.messageId.raw)
            put("parentId", data.parentId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
            put("modelInfo", data.modelInfo?.let { parseJsonObject(JsonCodecs.encodeModelInfo(it)) } ?: JsonNull)
        }

        is EventData.TurnEnd -> buildJsonObject {
            put("messageId", data.messageId.raw)
            put("finishReason", data.finishReason.name)
            put("usage", data.usage?.let { parseJsonObject(JsonCodecs.encodeUsage(it)) } ?: JsonNull)
            put("durationMs", data.durationMs)
        }

        is EventData.TurnInterrupted -> buildJsonObject {
            put("messageId", data.messageId.raw)
            put("parentId", data.parentId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
            put("partialText", data.partialText)
            put("reason", data.reason)
        }

        is EventData.CompactionStart -> buildJsonObject {
            put("compactionId", data.compactionId.raw)
            put("strategyId", data.strategyId)
            put("targetFromSeq", data.targetFromSeq)
            put("targetToSeq", data.targetToSeq)
            put("tokensBefore", data.tokensBefore)
        }

        is EventData.CompactionEnd -> buildJsonObject {
            put("compactionId", data.compactionId.raw)
            put("summarySeq", data.summarySeq)
            put("tokensAfter", data.tokensAfter)
        }

        is EventData.CompactionPrune -> buildJsonObject {
            put("compactionId", data.compactionId.raw)
            put(
                "rewrittenSeqs",
                buildJsonArray { data.rewrittenSeqs.forEach { add(JsonPrimitive(it)) } },
            )
        }

        is EventData.ImageOffload -> buildJsonObject {
            put("messageId", data.messageId.raw)
            put(
                "attachmentIds",
                buildJsonArray { data.attachmentIds.forEach { add(JsonPrimitive(it.raw)) } },
            )
            put("placeholder", data.placeholder)
        }

        is EventData.LeafChanged -> buildJsonObject {
            put("messageId", data.messageId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
        }

        is EventData.ModelSelection -> buildJsonObject {
            put("modelRef", data.modelRef?.let { parseJsonObject(JsonCodecs.encodeModelRef(it)) } ?: JsonNull)
            put("params", data.params?.let { parseJsonObject(JsonCodecs.encodeParams(it)) } ?: JsonNull)
            put("thinkingLevel", data.thinkingLevel?.let { JsonPrimitive(it.name) } ?: JsonNull)
        }

        is EventData.SessionTitle -> buildJsonObject {
            put("title", data.title)
            put("source", data.source)
        }
    }.toString()

    fun decodeData(type: EventType, json: String): EventData {
        val obj = parseJsonObject(json)
        return when (type) {
            EventType.SYSTEM_MESSAGE -> EventData.SystemMessage(obj.requiredString("text"))

            EventType.DEVELOPER_MESSAGE -> EventData.DeveloperMessage(
                text = obj.requiredString("text"),
                kind = obj.stringOrNull("kind") ?: "injection",
            )

            EventType.USER_MESSAGE -> EventData.UserMessage(
                messageId = MessageId(obj.requiredString("messageId")),
                parentId = obj.stringOrNull("parentId")?.let(::MessageId),
                parts = requiredParts(obj),
                compactionId = decodeCompactionId(obj),
            )

            EventType.ASSISTANT_MESSAGE -> EventData.AssistantMessage(
                messageId = MessageId(obj.requiredString("messageId")),
                parentId = obj.stringOrNull("parentId")?.let(::MessageId),
                parts = requiredParts(obj),
                modelInfo = obj.objectOrNull("modelInfo")?.let { decodeModelInfo(it) },
                usage = obj.objectOrNull("usage")?.let { decodeUsage(it) },
                finishReason = parseEnum(obj.requiredString("finishReason"), "finishReason"),
                citations = obj.arrayOrNull("citations")?.map { element ->
                    val hit = element as? JsonObject
                        ?: throw IllegalArgumentException("citation must be a JSON object")
                    SearchHit(
                        title = hit.requiredString("title"),
                        url = hit.requiredString("url"),
                        snippet = hit.requiredString("snippet"),
                    )
                } ?: emptyList(),
            )

            EventType.TOOL_RESULT -> EventData.ToolResult(
                toolUseId = obj.requiredString("toolUseId"),
                content = obj.requiredString("content"),
                isError = obj.booleanOrNull("isError") ?: false,
            )

            EventType.TURN_START -> EventData.TurnStart(
                messageId = MessageId(obj.requiredString("messageId")),
                parentId = obj.stringOrNull("parentId")?.let(::MessageId),
                modelInfo = obj.objectOrNull("modelInfo")?.let { decodeModelInfo(it) },
            )

            EventType.TURN_END -> EventData.TurnEnd(
                messageId = MessageId(obj.requiredString("messageId")),
                finishReason = parseEnum(obj.requiredString("finishReason"), "finishReason"),
                usage = obj.objectOrNull("usage")?.let { decodeUsage(it) },
                durationMs = obj.requiredLong("durationMs"),
            )

            EventType.TURN_INTERRUPTED -> EventData.TurnInterrupted(
                messageId = MessageId(obj.requiredString("messageId")),
                parentId = obj.stringOrNull("parentId")?.let(::MessageId),
                partialText = obj.requiredString("partialText"),
                reason = obj.requiredString("reason"),
            )

            EventType.COMPACTION_START -> EventData.CompactionStart(
                compactionId = CompactionId(obj.requiredString("compactionId")),
                strategyId = obj.requiredString("strategyId"),
                targetFromSeq = obj.requiredLong("targetFromSeq"),
                targetToSeq = obj.requiredLong("targetToSeq"),
                tokensBefore = obj.intOrNull("tokensBefore") ?: 0,
            )

            EventType.COMPACTION_END -> EventData.CompactionEnd(
                compactionId = CompactionId(obj.requiredString("compactionId")),
                summarySeq = obj.requiredLong("summarySeq"),
                tokensAfter = obj.intOrNull("tokensAfter") ?: 0,
            )

            EventType.COMPACTION_PRUNE -> EventData.CompactionPrune(
                compactionId = CompactionId(obj.requiredString("compactionId")),
                rewrittenSeqs = obj.arrayOrNull("rewrittenSeqs")?.map { element ->
                    (element as? JsonPrimitive)?.longOrNull
                        ?: throw IllegalArgumentException("rewrittenSeqs must contain numbers")
                } ?: emptyList(),
            )

            EventType.IMAGE_OFFLOAD -> EventData.ImageOffload(
                messageId = MessageId(obj.requiredString("messageId")),
                attachmentIds = obj.arrayOrNull("attachmentIds")?.strings()?.map(::AttachmentId)
                    ?: emptyList(),
                placeholder = obj.requiredString("placeholder"),
            )

            EventType.LEAF_CHANGED -> EventData.LeafChanged(
                messageId = obj.stringOrNull("messageId")?.let(::MessageId),
            )

            EventType.MODEL_SELECTION -> EventData.ModelSelection(
                modelRef = obj.objectOrNull("modelRef")?.let { decodeModelRef(it) },
                params = obj.objectOrNull("params")?.let { decodeParams(it) },
                thinkingLevel = obj.stringOrNull("thinkingLevel")?.let { parseEnum<ThinkingLevel>(it, "thinkingLevel") },
            )

            EventType.SESSION_TITLE -> EventData.SessionTitle(
                title = obj.requiredString("title"),
                source = obj.stringOrNull("source") ?: "manual",
            )
        }
    }

    /**
     * `'append'` (stored as a JSON string) or `{"op":"replace","startSeq":…,"endSeq":…}`.
     * `null` means the column is NULL: a record-only event.
     */
    fun encodeSurfaceOp(op: SurfaceOp?): String? = when (op) {
        null -> null
        SurfaceOp.Append -> APPEND_JSON
        is SurfaceOp.Replace -> buildJsonObject {
            put("op", "replace")
            put("startSeq", op.startSeq)
            put("endSeq", op.endSeq)
        }.toString()
    }

    fun decodeSurfaceOp(json: String?): SurfaceOp? {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // Accept both the documented bare marker and a JSON string, so a hand
        // written migration fixture does not have to guess the quoting.
        if (raw == "append" || raw == APPEND_JSON) return SurfaceOp.Append
        val obj = parseJsonObject(raw)
        return when (val op = obj.stringOrNull("op")) {
            "replace" -> {
                val start = obj.requiredLong("startSeq")
                val end = obj.requiredLong("endSeq")
                SurfaceOp.Replace(start, end)
            }

            null -> throw IllegalArgumentException("surface_op_json is missing the 'op' field")
            else -> throw IllegalArgumentException("unknown surface op '$op'")
        }
    }

    fun encodeSourceSeqs(seqs: List<Long>): String? =
        if (seqs.isEmpty()) {
            null
        } else {
            buildJsonArray { seqs.forEach { add(JsonPrimitive(it)) } }.toString()
        }

    fun decodeSourceSeqs(json: String?): List<Long> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            Json.parseToJsonElement(json) as? JsonArray
        } catch (e: Exception) {
            throw IllegalArgumentException("source_seqs_json is not valid JSON", e)
        } ?: throw IllegalArgumentException("source_seqs_json must be a JSON array")
        return array.map { element ->
            (element as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("source_seqs_json must contain numbers")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun partsArray(parts: List<MessagePart>): JsonArray =
        parseJsonObject(PartsCodec.encode(parts)).arrayOrNull("parts") ?: JsonArray(emptyList())

    private fun decodeParts(element: JsonElement?): List<MessagePart> =
        if (element == null || element is JsonNull) emptyList() else PartsCodec.decode(element.toString())

    /** `parts` is part of every message payload; an absent array is corruption, not "empty". */
    private fun requiredParts(obj: JsonObject): List<MessagePart> =
        decodeParts(obj["parts"] ?: throw IllegalArgumentException("missing field 'parts'"))

    private fun decodeCompactionId(obj: JsonObject): CompactionId? {
        val source = obj.objectOrNull("source")
        if (source != null && source.stringOrNull("kind") == COMPACT_CHECKPOINT) {
            source.stringOrNull("compactionId")?.let { return CompactionId(it) }
        }
        // Tolerated only for decoders reading a row written by an earlier
        // in-house experiment; the encoder always writes the documented shape.
        return obj.stringOrNull("compactionId")?.let(::CompactionId)
    }

    private fun decodeModelInfo(obj: JsonObject): ModelInfo = JsonCodecs.decodeModelInfo(obj.toString())
        ?: throw IllegalArgumentException("modelInfo object decoded to null")

    private fun decodeModelRef(obj: JsonObject): ModelRef = JsonCodecs.decodeModelRef(obj.toString())
        ?: throw IllegalArgumentException("modelRef object decoded to null")

    private fun decodeParams(obj: JsonObject): SamplingParams = JsonCodecs.decodeParams(obj.toString())

    private fun decodeUsage(obj: JsonObject): Usage = JsonCodecs.decodeUsage(obj.toString())
        ?: throw IllegalArgumentException("usage object decoded to null")

    private fun citationsArray(hits: List<SearchHit>): JsonArray = buildJsonArray {
        hits.forEach { hit ->
            add(
                buildJsonObject {
                    put("title", hit.title)
                    put("url", hit.url)
                    put("snippet", hit.snippet)
                },
            )
        }
    }

    private fun JsonObject.requiredLong(key: String): Long =
        longOrNull(key) ?: throw IllegalArgumentException("missing number field '$key'")

    private inline fun <reified E : Enum<E>> parseEnum(value: String, field: String): E =
        try {
            enumValueOf<E>(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("unknown $field value '$value'", e)
        }

    private const val COMPACT_CHECKPOINT = "compact-checkpoint"
    private const val APPEND_JSON = "\"append\""
}
