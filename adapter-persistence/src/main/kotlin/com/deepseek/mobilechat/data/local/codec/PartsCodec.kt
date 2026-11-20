package com.deepseek.mobilechat.data.local.codec

import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.Citation
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lossless JSON codec for [MessagePart] lists (`messages.parts_json`).
 *
 * The encoding is an explicit tagged union:
 * ```
 * {"v":1,"parts":[{"type":"text","text":"…"}, …]}
 * ```
 * Each part maps to one object with a `type` discriminator. Decoding throws
 * [IllegalArgumentException] for an unknown `type` or a newer format version
 * instead of silently dropping content — losing a `Reasoning.signature` or a
 * tool call is worse than failing loudly.
 *
 * `argumentsJson` and tool payload strings are stored as JSON **strings**, never
 * re-parsed, so a provider's exact bytes round-trip unchanged.
 */
object PartsCodec {

    const val FORMAT_VERSION: Int = 1

    fun encode(parts: List<MessagePart>): String = buildJsonObject {
        put("v", FORMAT_VERSION)
        put(
            "parts",
            buildJsonArray {
                parts.forEach { add(encodePart(it)) }
            },
        )
    }.toString()

    fun decode(json: String?): List<MessagePart> {
        if (json.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("parts_json is not valid JSON", e)
        }
        return when (root) {
            is JsonArray -> root.map(::decodePart)
            is JsonObject -> {
                val version = root.intOrNull("v") ?: FORMAT_VERSION
                if (version > FORMAT_VERSION) {
                    throw IllegalArgumentException("unsupported message parts format version $version")
                }
                val parts = root.arrayOrNull("parts")
                    ?: throw IllegalArgumentException("parts_json object is missing the 'parts' array")
                parts.map(::decodePart)
            }
            else -> throw IllegalArgumentException("parts_json must be a JSON object or array")
        }
    }

    /** Mirrors `Message.text`: only text parts, concatenated, in order. */
    fun plainText(parts: List<MessagePart>): String =
        parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

    private fun encodePart(part: MessagePart): JsonObject = when (part) {
        is MessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }

        is MessagePart.Image -> buildJsonObject {
            put("type", "image")
            put("attachmentId", part.attachmentId.raw)
        }

        is MessagePart.FileRef -> buildJsonObject {
            put("type", "file_ref")
            put("attachmentId", part.attachmentId.raw)
        }

        is MessagePart.Reasoning -> buildJsonObject {
            put("type", "reasoning")
            put("text", part.text)
            put("signature", part.signature?.let { JsonPrimitive(it) } ?: JsonNull)
        }

        is MessagePart.ContextBlock -> buildJsonObject {
            put("type", "context_block")
            put("title", part.title)
            put("body", part.body)
            put("source", part.source.name)
            put(
                "citations",
                buildJsonArray {
                    part.citations.forEach { citation ->
                        add(
                            buildJsonObject {
                                put("title", citation.title)
                                put("url", citation.url)
                            },
                        )
                    }
                },
            )
        }

        is MessagePart.SearchResults -> buildJsonObject {
            put("type", "search_results")
            put("query", part.query)
            put(
                "hits",
                buildJsonArray {
                    part.hits.forEach { hit ->
                        add(
                            buildJsonObject {
                                put("title", hit.title)
                                put("url", hit.url)
                                put("snippet", hit.snippet)
                            },
                        )
                    }
                },
            )
        }

        is MessagePart.ToolCall -> buildJsonObject {
            put("type", "tool_call")
            put("callId", part.callId)
            put("name", part.name)
            put("argumentsJson", part.argumentsJson)
        }

        is MessagePart.ToolResult -> buildJsonObject {
            put("type", "tool_result")
            put("callId", part.callId)
            put("ok", part.ok)
            put("content", part.content)
        }
    }

    private fun decodePart(element: JsonElement): MessagePart {
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("message part must be a JSON object")
        val type = obj.stringOrNull("type")
            ?: throw IllegalArgumentException("message part is missing the 'type' field")
        return when (type) {
            "text" -> MessagePart.Text(obj.requiredString("text"))

            "image" -> MessagePart.Image(AttachmentId(obj.requiredString("attachmentId")))

            "file_ref" -> MessagePart.FileRef(AttachmentId(obj.requiredString("attachmentId")))

            "reasoning" -> MessagePart.Reasoning(
                text = obj.requiredString("text"),
                signature = obj.stringOrNull("signature"),
            )

            "context_block" -> MessagePart.ContextBlock(
                title = obj.requiredString("title"),
                body = obj.requiredString("body"),
                source = parseEnum<BlockSource>(obj.requiredString("source"), "source"),
                citations = obj.arrayOrNull("citations").orEmptyList { citation ->
                    Citation(
                        title = citation.requiredString("title"),
                        url = citation.requiredString("url"),
                    )
                },
            )

            "search_results" -> MessagePart.SearchResults(
                query = obj.requiredString("query"),
                hits = obj.arrayOrNull("hits").orEmptyList { hit ->
                    SearchHit(
                        title = hit.requiredString("title"),
                        url = hit.requiredString("url"),
                        snippet = hit.requiredString("snippet"),
                    )
                },
            )

            "tool_call" -> MessagePart.ToolCall(
                callId = obj.requiredString("callId"),
                name = obj.requiredString("name"),
                argumentsJson = obj.requiredString("argumentsJson"),
            )

            "tool_result" -> MessagePart.ToolResult(
                callId = obj.requiredString("callId"),
                ok = obj.booleanOrNull("ok") ?: false,
                content = obj.requiredString("content"),
            )

            else -> throw IllegalArgumentException("unknown message part type '$type'")
        }
    }

    private fun <T> JsonArray?.orEmptyList(decode: (JsonObject) -> T): List<T> =
        this?.map { element ->
            decode(element as? JsonObject ?: throw IllegalArgumentException("expected a JSON object"))
        } ?: emptyList()

    private inline fun <reified E : Enum<E>> parseEnum(value: String, field: String): E =
        try {
            enumValueOf<E>(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("unknown $field value '$value'", e)
        }
}
