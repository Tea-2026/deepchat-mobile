package com.deepseek.mobilechat.data.local.codec

import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import com.deepseek.mobilechat.domain.log.EventType
import com.deepseek.mobilechat.domain.log.Surface
import com.deepseek.mobilechat.domain.log.SurfaceContent
import com.deepseek.mobilechat.domain.log.SurfaceNode
import com.deepseek.mobilechat.domain.log.Transcript
import com.deepseek.mobilechat.domain.log.TranscriptNode
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
 * JSON shape for the two projection columns of `conversation_snapshots`.
 *
 * These bytes are a cache, never a source of truth: if they do not decode, the
 * row is simply ignored and the caller folds the log again. That is why this
 * codec is allowed to be totally mechanical — there is no compatibility promise
 * beyond the current process, and the payload carries its own version.
 */
object SnapshotCodec {

    private const val FORMAT_VERSION = 1

    fun encodeSurface(surface: Surface): String = buildJsonObject {
        put("v", FORMAT_VERSION)
        put(
            "nodes",
            buildJsonArray {
                surface.nodes.forEach { node ->
                    add(
                        buildJsonObject {
                            put("seq", node.seq)
                            put("type", node.type.wire)
                            put("content", encodeSurfaceContent(node.content))
                            node.compactionId?.let { put("compactionId", it.raw) }
                        },
                    )
                }
            },
        )
    }.toString()

    fun decodeSurface(json: String): Surface {
        val root = parseJsonObject(json)
        requireVersion(root, "surface")
        val nodes = root.arrayOrNull("nodes")?.map { element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException("surface node must be an object")
            val type = EventType.fromWire(obj.requiredString("type"))
                ?: throw IllegalArgumentException("unknown surface node type '${obj.stringOrNull("type")}'")
            SurfaceNode(
                seq = obj.requiredLong("seq"),
                type = type,
                content = decodeSurfaceContent(
                    obj.objectOrNull("content")
                        ?: throw IllegalArgumentException("surface node is missing 'content'"),
                ),
                compactionId = obj.stringOrNull("compactionId")?.let(::CompactionId),
            )
        } ?: emptyList()
        return Surface(nodes)
    }

    fun encodeTranscript(transcript: Transcript): String = buildJsonObject {
        put("v", FORMAT_VERSION)
        put("conversationId", transcript.conversationId.raw)
        put("title", transcript.title)
        put(
            "nodes",
            buildJsonArray {
                transcript.nodes.forEach { node ->
                    add(
                        when (node) {
                            is TranscriptNode.MessageNode -> buildJsonObject {
                                put("kind", "message")
                                put("seq", node.seq)
                                put("message", encodeMessage(node.message))
                            }

                            is TranscriptNode.CompactionBar -> buildJsonObject {
                                put("kind", "compaction")
                                put("compactionId", node.compactionId.raw)
                                put("startSeq", node.startSeq)
                                put("endSeq", node.endSeq)
                                put("summary", node.summary)
                                node.tokensBefore?.let { put("tokensBefore", it) }
                                node.tokensAfter?.let { put("tokensAfter", it) }
                            }
                        },
                    )
                }
            },
        )
        put("messages", buildJsonArray { transcript.messages.forEach { add(encodeMessage(it)) } })
        put("activeLeafId", transcript.activeLeafId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
        put("modelRef", transcript.modelRef?.let { parseJsonObject(JsonCodecs.encodeModelRef(it)) } ?: JsonNull)
        put("params", transcript.params?.let { parseJsonObject(JsonCodecs.encodeParams(it)) } ?: JsonNull)
        put("thinkingLevel", transcript.thinkingLevel?.let { JsonPrimitive(it.name) } ?: JsonNull)
    }.toString()

    fun decodeTranscript(json: String): Transcript {
        val root = parseJsonObject(json)
        requireVersion(root, "transcript")
        val nodes = root.arrayOrNull("nodes")?.map { element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException("transcript node must be an object")
            when (val kind = obj.requiredString("kind")) {
                "message" -> TranscriptNode.MessageNode(
                    seq = obj.requiredLong("seq"),
                    message = decodeMessage(
                        obj.objectOrNull("message")
                            ?: throw IllegalArgumentException("message node is missing 'message'"),
                    ),
                )

                "compaction" -> TranscriptNode.CompactionBar(
                    compactionId = CompactionId(obj.requiredString("compactionId")),
                    startSeq = obj.requiredLong("startSeq"),
                    endSeq = obj.requiredLong("endSeq"),
                    summary = obj.requiredString("summary"),
                    tokensBefore = obj.intOrNull("tokensBefore"),
                    tokensAfter = obj.intOrNull("tokensAfter"),
                )

                else -> throw IllegalArgumentException("unknown transcript node kind '$kind'")
            }
        } ?: emptyList()
        return Transcript(
            conversationId = ConversationId(root.requiredString("conversationId")),
            title = root.stringOrNull("title") ?: "",
            nodes = nodes,
            messages = root.arrayOrNull("messages")?.map { element ->
                decodeMessage(element as? JsonObject ?: throw IllegalArgumentException("message must be an object"))
            } ?: emptyList(),
            activeLeafId = root.stringOrNull("activeLeafId")?.let(::MessageId),
            modelRef = root.objectOrNull("modelRef")?.let { JsonCodecs.decodeModelRef(it.toString()) },
            params = root.objectOrNull("params")?.let { JsonCodecs.decodeParams(it.toString()) },
            thinkingLevel = root.stringOrNull("thinkingLevel")?.let { ThinkingLevel.valueOf(it) },
        )
    }

    // ----------------------------------------------------------- surface parts

    private fun encodeSurfaceContent(content: SurfaceContent): JsonObject = when (content) {
        is SurfaceContent.System -> buildJsonObject {
            put("kind", "system")
            put("text", content.text)
        }

        is SurfaceContent.Developer -> buildJsonObject {
            put("kind", "developer")
            put("text", content.text)
            put("developerKind", content.kind)
        }

        is SurfaceContent.User -> buildJsonObject {
            put("kind", "user")
            put("message", encodeMessage(content.message))
        }

        is SurfaceContent.Assistant -> buildJsonObject {
            put("kind", "assistant")
            put("message", encodeMessage(content.message))
        }

        is SurfaceContent.Tool -> buildJsonObject {
            put("kind", "tool")
            put("toolUseId", content.toolUseId)
            put("content", content.content)
            put("isError", content.isError)
        }
    }

    private fun decodeSurfaceContent(obj: JsonObject): SurfaceContent =
        when (val kind = obj.requiredString("kind")) {
            "system" -> SurfaceContent.System(obj.requiredString("text"))

            "developer" -> SurfaceContent.Developer(
                text = obj.requiredString("text"),
                kind = obj.stringOrNull("developerKind") ?: "injection",
            )

            "user" -> SurfaceContent.User(
                decodeMessage(
                    obj.objectOrNull("message")
                        ?: throw IllegalArgumentException("user content is missing 'message'"),
                ),
            )

            "assistant" -> SurfaceContent.Assistant(
                decodeMessage(
                    obj.objectOrNull("message")
                        ?: throw IllegalArgumentException("assistant content is missing 'message'"),
                ),
            )

            "tool" -> SurfaceContent.Tool(
                toolUseId = obj.requiredString("toolUseId"),
                content = obj.requiredString("content"),
                isError = obj.booleanOrNull("isError") ?: false,
            )

            else -> throw IllegalArgumentException("unknown surface content kind '$kind'")
        }

    // ----------------------------------------------------------------- message

    private fun encodeMessage(message: Message): JsonObject = buildJsonObject {
        put("id", message.id.raw)
        put("conversationId", message.conversationId.raw)
        put("parentId", message.parentId?.let { JsonPrimitive(it.raw) } ?: JsonNull)
        put("seq", message.seq)
        put("role", message.role.name)
        put(
            "parts",
            parseJsonObject(PartsCodec.encode(message.parts)).arrayOrNull("parts") ?: JsonArray(emptyList()),
        )
        put("status", message.status.name)
        put("createdAt", message.createdAt)
        put("editedAt", message.editedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("pinned", message.pinned)
        put("modelInfo", message.modelInfo?.let { parseJsonObject(JsonCodecs.encodeModelInfo(it)) } ?: JsonNull)
        put("usage", message.usage?.let { parseJsonObject(JsonCodecs.encodeUsage(it)) } ?: JsonNull)
        put("finishReason", message.finishReason?.let { JsonPrimitive(it.name) } ?: JsonNull)
        put("error", message.error?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun decodeMessage(obj: JsonObject): Message = Message(
        id = MessageId(obj.requiredString("id")),
        conversationId = ConversationId(obj.requiredString("conversationId")),
        parentId = obj.stringOrNull("parentId")?.let(::MessageId),
        seq = obj.requiredLong("seq"),
        role = parseEnum(obj.requiredString("role"), "role"),
        parts = decodeParts(obj["parts"]),
        status = parseEnum(obj.requiredString("status"), "status"),
        createdAt = obj.requiredLong("createdAt"),
        editedAt = obj.longOrNull("editedAt"),
        pinned = obj.booleanOrNull("pinned") ?: false,
        modelInfo = obj.objectOrNull("modelInfo")?.let { info ->
            decodeModelInfo(info)
        },
        usage = obj.objectOrNull("usage")?.let { usage ->
            decodeUsage(usage)
        },
        finishReason = obj.stringOrNull("finishReason")?.let { parseEnum<FinishReason>(it, "finishReason") },
        error = obj.stringOrNull("error"),
    )

    private fun decodeParts(element: JsonElement?): List<MessagePart> =
        if (element == null || element is JsonNull) emptyList() else PartsCodec.decode(element.toString())

    private fun decodeModelInfo(obj: JsonObject): ModelInfo =
        JsonCodecs.decodeModelInfo(obj.toString())
            ?: throw IllegalArgumentException("modelInfo decoded to null")

    private fun decodeUsage(obj: JsonObject): Usage =
        JsonCodecs.decodeUsage(obj.toString())
            ?: throw IllegalArgumentException("usage decoded to null")

    // ---------------------------------------------------------------- helpers

    private fun requireVersion(root: JsonObject, what: String) {
        val version = root.intOrNull("v") ?: FORMAT_VERSION
        if (version > FORMAT_VERSION) {
            throw IllegalArgumentException("unsupported $what snapshot version $version")
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
}
