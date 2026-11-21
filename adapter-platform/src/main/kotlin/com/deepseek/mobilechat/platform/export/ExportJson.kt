package com.deepseek.mobilechat.platform.export

import com.deepseek.mobilechat.application.ExportBundle
import com.deepseek.mobilechat.application.ImportedBundle
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.Citation
import com.deepseek.mobilechat.domain.ContextStrategyIds
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageStatus
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelInfo
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.SearchHit
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.Usage
import java.util.Base64

/**
 * Result of decoding an export document.
 *
 * [ImportedBundle] (the frozen application contract) can only carry
 * conversations / messages / attachments / embedded attachment bytes plus
 * presets, folders and tags. [toImportedBundle] exposes all of it through the
 * application contract.
 */
data class DecodedExport(
    val conversations: List<Conversation>,
    val messages: List<Message>,
    val attachments: List<Attachment>,
    val presets: List<Preset> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val attachmentBytes: Map<AttachmentId, ByteArray> = emptyMap(),
) {
    /**
     * The application contract now carries conversations, messages,
     * attachments + bytes, presets, folders and tags. All ids were reallocated
     * during decode and every cross-reference
     * (`folderId` / `tagIds` / `overrides.presetId`) already points at the
     * corresponding entity returned by this bundle.
     *
     * [attachmentBytes] keys are the freshly allocated [AttachmentId]s from
     * [attachments], never the ids read from the export file.
     */
    fun toImportedBundle(): ImportedBundle = ImportedBundle(
        conversations = conversations,
        messages = messages,
        attachments = attachments,
        attachmentBytes = attachmentBytes,
        presets = presets,
        folders = folders,
        tags = tags,
    )
}

/**
 * Pure JSON codec for the export format. No Android APIs, no I/O: the same
 * functions run in JVM unit tests and inside the platform adapter.
 *
 * Schema (schemaVersion = 1):
 * ```
 * {
 *   "schemaVersion": 1,
 *   "exportedAt": 1700000000000,
 *   "includeAttachmentBytes": false,
 *   "conversations": [...], "messages": [...], "presets": [...],
 *   "folders": [...], "tags": [...], "attachments": [...]
 * }
 * ```
 * `MessagePart` is encoded with an explicit `type` discriminator so every
 * variant (including `Reasoning.signature`) round-trips losslessly.
 * Import always allocates fresh ids through [newId]; nothing is overwritten.
 */
object ExportJson {
    const val SCHEMA_VERSION: Int = 1

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    fun encode(
        bundle: ExportBundle,
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
        exportedAtMillis: Long = System.currentTimeMillis(),
    ): String {
        val messages = bundle.messages.sortedWith(
            compareBy({ it.conversationId.raw }, { it.seq }, { it.createdAt }, { it.id.raw }),
        )
        val inlineBytes = if (bundle.includeAttachmentBytes) attachmentBytes else emptyMap()
        val root = jsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAt", exportedAtMillis)
            put("includeAttachmentBytes", bundle.includeAttachmentBytes)
            put("conversations", jsonArray(bundle.conversations.map(::encodeConversation)))
            put("messages", jsonArray(messages.map(::encodeMessage)))
            put("presets", jsonArray(bundle.presets.map(::encodePreset)))
            put("folders", jsonArray(bundle.folders.map(::encodeFolder)))
            put("tags", jsonArray(bundle.tags.map(::encodeTag)))
            put(
                "attachments",
                jsonArray(bundle.attachments.map { encodeAttachment(it, inlineBytes[it.id.raw]) }),
            )
        }
        return Json.write(root, pretty = true)
    }

    private fun encodeConversation(conversation: Conversation): JsonValue = jsonObject {
        put("id", conversation.id.raw)
        put("title", conversation.title)
        put("activeLeafId", conversation.activeLeafId?.raw)
        put("folderId", conversation.folderId?.raw)
        put(
            "tagIds",
            jsonArray(conversation.tagIds.sortedBy { it.raw }.map { JsonValue.Str(it.raw) }),
        )
        put("overrides", encodeOverrides(conversation.overrides))
        put("createdAt", conversation.createdAt)
        put("updatedAt", conversation.updatedAt)
        put("pinned", conversation.pinned)
    }

    private fun encodeOverrides(overrides: ConversationOverrides): JsonValue = jsonObject {
        put("presetId", overrides.presetId?.raw)
        put("modelRef", encodeModelRef(overrides.modelRef))
        put("params", overrides.params?.let(::encodeParams))
        put("thinkingLevel", overrides.thinkingLevel?.name)
        put("systemPrompt", overrides.systemPrompt)
    }

    private fun encodeMessage(message: Message): JsonValue = jsonObject {
        put("id", message.id.raw)
        put("conversationId", message.conversationId.raw)
        put("parentId", message.parentId?.raw)
        put("seq", message.seq)
        put("role", message.role.name)
        put("parts", jsonArray(message.parts.map(::encodePart)))
        put("status", message.status.name)
        put("createdAt", message.createdAt)
        put("editedAt", message.editedAt)
        put("pinned", message.pinned)
        put("modelInfo", message.modelInfo?.let(::encodeModelInfo))
        put("usage", message.usage?.let(::encodeUsage))
        put("finishReason", message.finishReason?.name)
        put("error", message.error)
    }

    private fun encodeModelInfo(info: ModelInfo): JsonValue = jsonObject {
        put("ref", encodeModelRef(info.ref))
        put("params", encodeParams(info.params))
        put("thinkingLevel", info.thinkingLevel.name)
    }

    private fun encodeModelRef(ref: ModelRef?): JsonValue {
        if (ref == null) return JsonValue.Null
        return jsonObject {
            put("providerId", ref.providerId.raw)
            put("modelId", ref.modelId.raw)
        }
    }

    private fun encodeParams(params: SamplingParams): JsonValue = jsonObject {
        put("temperature", params.temperature)
        put("topP", params.topP)
        put("maxTokens", params.maxTokens)
        put("stop", params.stop.toJsonArray())
    }

    private fun encodeUsage(usage: Usage): JsonValue = jsonObject {
        put("promptTokens", usage.promptTokens)
        put("completionTokens", usage.completionTokens)
        put("reasoningTokens", usage.reasoningTokens)
        put("cachedPromptTokens", usage.cachedPromptTokens)
        put("cost", usage.cost)
    }

    private fun encodePart(part: MessagePart): JsonValue = when (part) {
        is MessagePart.Text -> jsonObject {
            put("type", "text")
            put("text", part.text)
        }

        is MessagePart.Image -> jsonObject {
            put("type", "image")
            put("attachmentId", part.attachmentId.raw)
        }

        is MessagePart.FileRef -> jsonObject {
            put("type", "file_ref")
            put("attachmentId", part.attachmentId.raw)
        }

        is MessagePart.Reasoning -> jsonObject {
            put("type", "reasoning")
            put("text", part.text)
            put("signature", part.signature)
        }

        is MessagePart.ContextBlock -> jsonObject {
            put("type", "context_block")
            put("title", part.title)
            put("body", part.body)
            put("source", part.source.name)
            put("citations", jsonArray(part.citations.map(::encodeCitation)))
        }

        is MessagePart.SearchResults -> jsonObject {
            put("type", "search_results")
            put("query", part.query)
            put("hits", jsonArray(part.hits.map(::encodeSearchHit)))
        }

        is MessagePart.ToolCall -> jsonObject {
            put("type", "tool_call")
            put("callId", part.callId)
            put("name", part.name)
            put("argumentsJson", part.argumentsJson)
        }

        is MessagePart.ToolResult -> jsonObject {
            put("type", "tool_result")
            put("callId", part.callId)
            put("ok", part.ok)
            put("content", part.content)
        }
    }

    private fun encodeCitation(citation: Citation): JsonValue = jsonObject {
        put("title", citation.title)
        put("url", citation.url)
    }

    private fun encodeSearchHit(hit: SearchHit): JsonValue = jsonObject {
        put("title", hit.title)
        put("url", hit.url)
        put("snippet", hit.snippet)
    }

    private fun encodePreset(preset: Preset): JsonValue = jsonObject {
        put("id", preset.id.raw)
        put("name", preset.name)
        put("systemPrompt", preset.systemPrompt)
        put("modelRef", encodeModelRef(preset.modelRef))
        put("params", encodeParams(preset.params))
        put("thinkingLevel", preset.thinkingLevel.name)
        put("contextStrategyId", preset.contextStrategyId)
        put("isDefault", preset.isDefault)
    }

    private fun encodeFolder(folder: Folder): JsonValue = jsonObject {
        put("id", folder.id.raw)
        put("name", folder.name)
        put("order", folder.order)
    }

    private fun encodeTag(tag: Tag): JsonValue = jsonObject {
        put("id", tag.id.raw)
        put("name", tag.name)
        put("color", tag.color)
    }

    private fun encodeAttachment(attachment: Attachment, bytes: ByteArray?): JsonValue = jsonObject {
        put("id", attachment.id.raw)
        put("fileName", attachment.fileName)
        put("mimeType", attachment.mimeType)
        put("sizeBytes", attachment.sizeBytes)
        put("localPath", attachment.localPath)
        put("sha256", attachment.sha256)
        put("kind", attachment.kind.name)
        put("extractedText", attachment.extractedText)
        put("width", attachment.width)
        put("height", attachment.height)
        put("createdAt", attachment.createdAt)
        put("bytesBase64", bytes?.let { Base64.getEncoder().encodeToString(it) })
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    fun decode(text: String, newId: () -> String): DecodedExport {
        val parsed = try {
            Json.parse(text)
        } catch (error: JsonFormatException) {
            throw JsonFormatException("invalid JSON: ${error.message}", error)
        }
        val root = parsed as? JsonValue.Obj
            ?: throw JsonFormatException("top-level JSON value must be an object")

        val schemaVersion = root.intOrNull("schemaVersion")
            ?: throw JsonFormatException("missing required field 'schemaVersion'")
        if (schemaVersion != SCHEMA_VERSION) {
            throw JsonFormatException(
                "unsupported schemaVersion $schemaVersion (this build reads $SCHEMA_VERSION)",
            )
        }

        val conversationIds = MappedIds { ConversationId(newId()) }
        val messageIds = MappedIds { MessageId(newId()) }
        val attachmentIds = MappedIds { AttachmentId(newId()) }
        val presetIds = MappedIds { PresetId(newId()) }
        val folderIds = MappedIds { FolderId(newId()) }
        val tagIds = MappedIds { TagId(newId()) }

        val attachmentBytes = LinkedHashMap<AttachmentId, ByteArray>()
        val attachments = root.objects("attachments").map { obj ->
            val attachment = decodeAttachment(obj, attachmentIds, attachmentBytes)
            attachment
        }
        val presets = root.objects("presets").map { decodePreset(it, presetIds) }
        val folders = root.objects("folders").map { decodeFolder(it, folderIds) }
        val tags = root.objects("tags").map { decodeTag(it, tagIds) }
        val conversations = root.objects("conversations").map {
            decodeConversation(it, conversationIds, messageIds, folderIds, tagIds, presetIds)
        }
        val messages = root.objects("messages").map {
            decodeMessage(it, conversationIds, messageIds, attachmentIds)
        }

        return DecodedExport(
            conversations = conversations,
            messages = parentsFirst(messages),
            attachments = attachments,
            presets = presets,
            folders = folders,
            tags = tags,
            attachmentBytes = attachmentBytes,
        )
    }

    /**
     * Returns messages in parent-before-child order.
     *
     * The persistence layer inserts rows one by one against a
     * `parent_id REFERENCES messages(id)` foreign key, so a stable topological
     * order is required even though the source order is not. Cycles are rejected:
     * they could never satisfy the foreign key anyway.
     */
    private fun parentsFirst(messages: List<Message>): List<Message> {
        val byId = messages.associateBy { it.id }
        val placed = LinkedHashSet<MessageId>()
        val output = ArrayList<Message>(messages.size)
        val visiting = HashSet<MessageId>()

        fun visit(message: Message) {
            if (message.id in placed) return
            if (!visiting.add(message.id)) {
                throw JsonFormatException("message tree contains a cycle at '${message.id.raw}'")
            }
            val parent = message.parentId?.let { byId[it] }
            if (parent != null) visit(parent)
            visiting.remove(message.id)
            if (placed.add(message.id)) output += message
        }

        messages.forEach { visit(it) }
        return output
    }

    private fun decodeConversation(
        obj: JsonValue.Obj,
        conversationIds: MappedIds<ConversationId>,
        messageIds: MappedIds<MessageId>,
        folderIds: MappedIds<FolderId>,
        tagIds: MappedIds<TagId>,
        presetIds: MappedIds<PresetId>,
    ): Conversation {
        val createdAt = obj.long("createdAt")
        return Conversation(
            id = conversationIds.required(obj.string("id"), "conversations[].id"),
            title = obj.stringOrNull("title").orEmpty(),
            activeLeafId = messageIds.of(obj.stringOrNull("activeLeafId")),
            folderId = folderIds.of(obj.stringOrNull("folderId")),
            tagIds = obj.arrayOrNull("tagIds")
                ?.stringValues("tagIds")
                ?.map { tagIds.required(it, "conversations[].tagIds") }
                ?.toSet()
                .orEmpty(),
            overrides = decodeOverrides(obj.objectOrNull("overrides"), presetIds),
            createdAt = createdAt,
            updatedAt = obj.longOrNull("updatedAt") ?: createdAt,
            pinned = obj.boolOrNull("pinned") ?: false,
        )
    }

    private fun decodeOverrides(
        obj: JsonValue.Obj?,
        presetIds: MappedIds<PresetId>,
    ): ConversationOverrides {
        if (obj == null) return ConversationOverrides()
        return ConversationOverrides(
            presetId = presetIds.of(obj.stringOrNull("presetId")),
            modelRef = decodeModelRef(obj.objectOrNull("modelRef")),
            params = obj.objectOrNull("params")?.let(::decodeParams),
            thinkingLevel = obj.stringOrNull("thinkingLevel")?.let {
                enumValue<ThinkingLevel>(it, "overrides.thinkingLevel")
            },
            systemPrompt = obj.stringOrNull("systemPrompt"),
        )
    }

    private fun decodeMessage(
        obj: JsonValue.Obj,
        conversationIds: MappedIds<ConversationId>,
        messageIds: MappedIds<MessageId>,
        attachmentIds: MappedIds<AttachmentId>,
    ): Message = Message(
        id = messageIds.required(obj.string("id"), "messages[].id"),
        conversationId = conversationIds.required(
            obj.string("conversationId"),
            "messages[].conversationId",
        ),
        parentId = messageIds.of(obj.stringOrNull("parentId")),
        seq = obj.long("seq"),
        role = enumValue<MessageRole>(obj.string("role"), "messages[].role"),
        parts = obj.array("parts").map { decodePart(it, attachmentIds) },
        status = obj.stringOrNull("status")?.let { enumValue<MessageStatus>(it, "messages[].status") }
            ?: MessageStatus.COMPLETE,
        createdAt = obj.long("createdAt"),
        editedAt = obj.longOrNull("editedAt"),
        pinned = obj.boolOrNull("pinned") ?: false,
        modelInfo = obj.objectOrNull("modelInfo")?.let(::decodeModelInfo),
        usage = obj.objectOrNull("usage")?.let(::decodeUsage),
        finishReason = obj.stringOrNull("finishReason")?.let {
            enumValue<FinishReason>(it, "messages[].finishReason")
        },
        error = obj.stringOrNull("error"),
    )

    private fun decodeModelInfo(obj: JsonValue.Obj): ModelInfo = ModelInfo(
        ref = decodeModelRef(obj.requireObject("ref"))
            ?: throw JsonFormatException("modelInfo.ref must not be null"),
        params = obj.objectOrNull("params")?.let(::decodeParams) ?: SamplingParams(),
        thinkingLevel = obj.stringOrNull("thinkingLevel")?.let {
            enumValue<ThinkingLevel>(it, "modelInfo.thinkingLevel")
        } ?: ThinkingLevel.OFF,
    )

    private fun decodeModelRef(obj: JsonValue.Obj?): ModelRef? {
        if (obj == null) return null
        return ModelRef(
            providerId = ProviderId(obj.string("providerId")),
            modelId = ModelId(obj.string("modelId")),
        )
    }

    private fun decodeParams(obj: JsonValue.Obj): SamplingParams = SamplingParams(
        temperature = obj.doubleOrNull("temperature"),
        topP = obj.doubleOrNull("topP"),
        maxTokens = obj.intOrNull("maxTokens"),
        stop = obj.arrayOrNull("stop")?.stringValues("params.stop").orEmpty(),
    )

    private fun decodeUsage(obj: JsonValue.Obj): Usage = Usage(
        promptTokens = obj.intOrNull("promptTokens") ?: 0,
        completionTokens = obj.intOrNull("completionTokens") ?: 0,
        reasoningTokens = obj.intOrNull("reasoningTokens") ?: 0,
        cachedPromptTokens = obj.intOrNull("cachedPromptTokens") ?: 0,
        // Accepts bundles exported before 0.2.0, when the key was "costUsd".
        cost = obj.doubleOrNull("cost") ?: obj.doubleOrNull("costUsd"),
    )

    private fun decodePart(value: JsonValue, attachmentIds: MappedIds<AttachmentId>): MessagePart {
        val obj = value as? JsonValue.Obj
            ?: throw JsonFormatException("message part must be an object")
        return when (val type = obj.string("type")) {
            "text" -> MessagePart.Text(obj.string("text"))
            "image" -> MessagePart.Image(
                attachmentIds.required(obj.string("attachmentId"), "part.attachmentId"),
            )
            "file_ref" -> MessagePart.FileRef(
                attachmentIds.required(obj.string("attachmentId"), "part.attachmentId"),
            )
            "reasoning" -> MessagePart.Reasoning(
                text = obj.string("text"),
                signature = obj.stringOrNull("signature"),
            )
            "context_block" -> MessagePart.ContextBlock(
                title = obj.string("title"),
                body = obj.string("body"),
                source = enumValue<BlockSource>(obj.string("source"), "part.source"),
                citations = obj.arrayOrNull("citations")
                    ?.objectValues("part.citations")
                    ?.map { Citation(title = it.string("title"), url = it.string("url")) }
                    .orEmpty(),
            )
            "search_results" -> MessagePart.SearchResults(
                query = obj.string("query"),
                hits = obj.arrayOrNull("hits")
                    ?.objectValues("part.hits")
                    ?.map {
                        SearchHit(
                            title = it.string("title"),
                            url = it.string("url"),
                            snippet = it.string("snippet"),
                        )
                    }
                    .orEmpty(),
            )
            "tool_call" -> MessagePart.ToolCall(
                callId = obj.string("callId"),
                name = obj.string("name"),
                argumentsJson = obj.string("argumentsJson"),
            )
            "tool_result" -> MessagePart.ToolResult(
                callId = obj.string("callId"),
                ok = obj.bool("ok"),
                content = obj.string("content"),
            )
            else -> throw JsonFormatException("unknown message part type '$type'")
        }
    }

    private fun decodePreset(obj: JsonValue.Obj, presetIds: MappedIds<PresetId>): Preset = Preset(
        id = presetIds.required(obj.string("id"), "presets[].id"),
        name = obj.string("name"),
        systemPrompt = obj.stringOrNull("systemPrompt").orEmpty(),
        modelRef = decodeModelRef(obj.objectOrNull("modelRef")),
        params = obj.objectOrNull("params")?.let(::decodeParams) ?: SamplingParams(),
        thinkingLevel = obj.stringOrNull("thinkingLevel")?.let {
            enumValue<ThinkingLevel>(it, "presets[].thinkingLevel")
        } ?: ThinkingLevel.OFF,
        contextStrategyId = obj.stringOrNull("contextStrategyId")
            ?: ContextStrategyIds.CONFIRM_COMPACTION,
        isDefault = obj.boolOrNull("isDefault") ?: false,
    )

    private fun decodeFolder(obj: JsonValue.Obj, folderIds: MappedIds<FolderId>): Folder = Folder(
        id = folderIds.required(obj.string("id"), "folders[].id"),
        name = obj.string("name"),
        order = obj.intOrNull("order") ?: 0,
    )

    private fun decodeTag(obj: JsonValue.Obj, tagIds: MappedIds<TagId>): Tag = Tag(
        id = tagIds.required(obj.string("id"), "tags[].id"),
        name = obj.string("name"),
        color = obj.longOrNull("color") ?: DEFAULT_TAG_COLOR,
    )

    private fun decodeAttachment(
        obj: JsonValue.Obj,
        attachmentIds: MappedIds<AttachmentId>,
        bytesSink: MutableMap<AttachmentId, ByteArray>,
    ): Attachment {
        val id = attachmentIds.required(obj.string("id"), "attachments[].id")
        val bytes = obj.stringOrNull("bytesBase64")?.let { encoded ->
            try {
                Base64.getDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw JsonFormatException("attachments[].bytesBase64 is not valid base64", error)
            }
        }
        if (bytes != null) bytesSink[id] = bytes
        return Attachment(
            id = id,
            fileName = obj.stringOrNull("fileName").orEmpty(),
            mimeType = obj.stringOrNull("mimeType") ?: "application/octet-stream",
            sizeBytes = obj.longOrNull("sizeBytes") ?: (bytes?.size?.toLong() ?: 0L),
            localPath = obj.stringOrNull("localPath").orEmpty(),
            sha256 = obj.stringOrNull("sha256").orEmpty(),
            kind = obj.stringOrNull("kind")?.let { enumValue<AttachmentKind>(it, "attachments[].kind") }
                ?: AttachmentKind.OTHER,
            extractedText = obj.stringOrNull("extractedText"),
            width = obj.intOrNull("width"),
            height = obj.intOrNull("height"),
            createdAt = obj.longOrNull("createdAt") ?: 0L,
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun JsonValue.Obj.objects(field: String): List<JsonValue.Obj> =
        arrayOrNull(field)?.objectValues(field).orEmpty()

    private fun List<JsonValue>.objectValues(field: String): List<JsonValue.Obj> =
        map {
            it as? JsonValue.Obj
                ?: throw JsonFormatException("'$field' must be an array of objects")
        }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == raw }
            ?: throw JsonFormatException("unknown $field '$raw'")

    private const val DEFAULT_TAG_COLOR: Long = 0xFF6B7280

    private class MappedIds<T>(private val factory: () -> T) {
        private val values = LinkedHashMap<String, T>()

        fun of(raw: String?): T? = raw?.let { values.getOrPut(it) { factory() } }

        fun required(raw: String, field: String): T =
            of(raw) ?: throw JsonFormatException("missing $field")
    }
}
