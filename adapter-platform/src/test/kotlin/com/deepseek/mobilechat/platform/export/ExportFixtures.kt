package com.deepseek.mobilechat.platform.export

import com.deepseek.mobilechat.application.ExportBundle
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

/**
 * Shared pure fixtures. No Android imports: these tests must stay runnable on a
 * plain JVM (the project explicitly does not use Robolectric).
 */
internal object ExportFixtures {
    const val T0: Long = 1_700_000_000_000L

    val conversationId = ConversationId("conv-1")
    val folderId = FolderId("folder-1")
    val tagIdImportant = TagId("tag-1")
    val tagIdDraft = TagId("tag-2")
    val presetId = PresetId("preset-1")
    val providerId = ProviderId("provider-1")
    val modelId = ModelId("model-a")
    val imageAttachmentId = AttachmentId("att-1")
    val textAttachmentId = AttachmentId("att-2")

    fun bundle(includeAttachmentBytes: Boolean = true): ExportBundle {
        val image = Attachment(
            id = imageAttachmentId,
            fileName = "diagram.png",
            mimeType = "image/png",
            sizeBytes = 3,
            localPath = "/data/attachments/diagram.png",
            sha256 = "sha-image",
            kind = AttachmentKind.IMAGE,
            extractedText = null,
            width = 640,
            height = 480,
            createdAt = T0 + 10,
        )
        val note = Attachment(
            id = textAttachmentId,
            fileName = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 5,
            localPath = "/data/attachments/notes.txt",
            sha256 = "sha-text",
            kind = AttachmentKind.TEXT,
            extractedText = "hello",
            width = null,
            height = null,
            createdAt = T0 + 20,
        )

        val firstUser = Message(
            id = MessageId("m-1"),
            conversationId = conversationId,
            parentId = null,
            seq = 1,
            role = MessageRole.USER,
            parts = listOf(
                MessagePart.Text("Hello \"world\"\nline 2 ✓ 🚀"),
                MessagePart.Image(imageAttachmentId),
            ),
            status = MessageStatus.COMPLETE,
            createdAt = T0 + 100,
            pinned = true,
        )
        val firstAnswer = Message(
            id = MessageId("m-2"),
            conversationId = conversationId,
            parentId = firstUser.id,
            seq = 1,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                MessagePart.Text("First answer"),
                MessagePart.Reasoning("Deliberately verbose reasoning", "sig-abc"),
                MessagePart.ContextBlock(
                    title = "Search context",
                    body = "Context body\nwith two lines",
                    source = BlockSource.SEARCH,
                    citations = listOf(Citation("Example", "https://example.com/a")),
                ),
                MessagePart.SearchResults(
                    query = "kotlin",
                    hits = listOf(SearchHit("Kotlin", "https://kotlinlang.org", "language docs")),
                ),
                MessagePart.ToolCall("call-1", "lookup", "{\"q\":\"x\"}"),
                MessagePart.ToolResult("call-1", true, "tool output"),
                MessagePart.FileRef(textAttachmentId),
            ),
            status = MessageStatus.COMPLETE,
            createdAt = T0 + 200,
            editedAt = T0 + 250,
            modelInfo = ModelInfo(
                ref = ModelRef(providerId, modelId),
                params = SamplingParams(0.5, 0.9, 2048, listOf("STOP")),
                thinkingLevel = ThinkingLevel.MAX,
            ),
            usage = Usage(
                promptTokens = 10,
                completionTokens = 20,
                reasoningTokens = 5,
                cost = 0.0012,
            ),
            finishReason = FinishReason.STOP,
        )
        val activeAnswer = Message(
            id = MessageId("m-3"),
            conversationId = conversationId,
            parentId = firstUser.id,
            seq = 2,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                MessagePart.Text("Second answer"),
                MessagePart.Reasoning("second thought", null),
            ),
            status = MessageStatus.COMPLETE,
            createdAt = T0 + 300,
            finishReason = FinishReason.STOP,
        )
        val abandonedAnswer = Message(
            id = MessageId("m-4"),
            conversationId = conversationId,
            parentId = firstAnswer.id,
            seq = 1,
            role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text("Inactive branch answer")),
            status = MessageStatus.CANCELLED,
            createdAt = T0 + 400,
            error = "cancelled by user",
        )

        val conversation = Conversation(
            id = conversationId,
            title = "Branching",
            activeLeafId = activeAnswer.id,
            folderId = folderId,
            tagIds = setOf(tagIdImportant, tagIdDraft),
            overrides = ConversationOverrides(
                presetId = presetId,
                modelRef = ModelRef(providerId, modelId),
                params = SamplingParams(0.7, 0.95, 4096, listOf("END")),
                thinkingLevel = ThinkingLevel.MAX,
                systemPrompt = "You are terse.",
            ),
            createdAt = T0,
            updatedAt = T0 + 500,
            pinned = true,
        )

        val preset = Preset(
            id = presetId,
            name = "Terse",
            systemPrompt = "Be terse.",
            modelRef = ModelRef(providerId, modelId),
            params = SamplingParams(0.2, 0.8, 1024, listOf("stop-1", "stop-2")),
            thinkingLevel = ThinkingLevel.HIGH,
            contextStrategyId = ContextStrategyIds.DROP_OLDEST,
            isDefault = true,
        )
        val folder = Folder(id = folderId, name = "Work", order = 2)
        val important = Tag(id = tagIdImportant, name = "important", color = 0xFFFF0000)
        val draft = Tag(id = tagIdDraft, name = "draft", color = 0xFF6B7280)

        return ExportBundle(
            conversations = listOf(conversation),
            messages = listOf(firstUser, firstAnswer, activeAnswer, abandonedAnswer),
            attachments = listOf(image, note),
            presets = listOf(preset),
            folders = listOf(folder),
            tags = listOf(important, draft),
            includeAttachmentBytes = includeAttachmentBytes,
        )
    }

    fun attachmentBytes(): Map<String, ByteArray> = mapOf(
        imageAttachmentId.raw to byteArrayOf(1, 2, 3),
        textAttachmentId.raw to "hello".toByteArray(Charsets.UTF_8),
    )
}
