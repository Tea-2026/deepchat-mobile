package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ExportFormat
import com.deepseek.mobilechat.application.ImportedBundle
import com.deepseek.mobilechat.application.service.testutil.FakeAttachmentStore
import com.deepseek.mobilechat.application.service.testutil.FakeConversationStore
import com.deepseek.mobilechat.application.service.testutil.FakeExportPort
import com.deepseek.mobilechat.application.service.testutil.FakeFolderTagStore
import com.deepseek.mobilechat.application.service.testutil.FakeIdPort
import com.deepseek.mobilechat.application.service.testutil.FakeMessageStore
import com.deepseek.mobilechat.application.service.testutil.FakePresetStore
import com.deepseek.mobilechat.application.service.testutil.assistantMessage
import com.deepseek.mobilechat.application.service.testutil.attachment
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.preset
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.application.service.testutil.userMessage
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Message
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTransferServiceTest {

    private val conversations = FakeConversationStore()
    private val messages = FakeMessageStore()
    private val attachments = FakeAttachmentStore()
    private val presets = FakePresetStore()
    private val folderTags = FakeFolderTagStore()
    private val exportPort = FakeExportPort()
    private val ids = FakeIdPort("imported")

    private val service = DefaultTransferService(
        conversationStore = conversations,
        messageStore = messages,
        attachmentStore = attachments,
        presetStore = presets,
        folderTagStore = folderTags,
        exportPort = exportPort,
        idPort = ids,
    )

    @Test
    fun `export collects the whole graph for the requested conversations`() = runTest {
        conversations.put(conversation("c1", title = "one"))
        conversations.put(conversation("c2", title = "two"))
        messages.put(
            userMessage("u1", conversationId = "c1", seq = 1),
            assistantMessage("a1", conversationId = "c1", seq = 1, parentId = "u1"),
        )
        attachments.put(attachment("at1", AttachmentKind.IMAGE))
        presets.put(preset("p1"))
        folderTags.upsertFolder(Folder(FolderId("f1"), "work"))
        folderTags.upsertTag(Tag(TagId("t1"), "idea"))

        service.exportConversations(
            conversationIds = listOf(ConversationId("c1")),
            format = ExportFormat.MARKDOWN,
            targetUri = "content://target",
            includeAttachments = true,
        ).requireOk()

        val bundle = exportPort.exported!!
        assertEquals(listOf(ConversationId("c1")), bundle.conversations.map { it.id })
        assertEquals(2, bundle.messages.size)
        assertEquals(1, bundle.attachments.size)
        assertEquals(1, bundle.presets.size)
        assertEquals(1, bundle.folders.size)
        assertEquals(1, bundle.tags.size)
        assertTrue(bundle.includeAttachmentBytes)
        assertEquals(ExportFormat.MARKDOWN, exportPort.exportedFormat)
        assertEquals("content://target", exportPort.exportedTarget)
    }

    @Test
    fun `export rejects an empty selection and unknown conversations`() = runTest {
        assertTrue(
            service.exportConversations(emptyList(), ExportFormat.JSON, "content://x", false)
                .requireErr() is AppError.Validation,
        )
        assertTrue(
            service.exportConversations(
                listOf(ConversationId("ghost")),
                ExportFormat.JSON,
                "content://x",
                false,
            ).requireErr() is AppError.Validation,
        )
    }

    @Test
    fun `import reassigns every id and rewires the tree`() = runTest {
        val sourceConversation = conversation("c-old", title = "imported", activeLeafId = "a-old")
        val sourceUser = userMessage("u-old", conversationId = "c-old", text = "hi", seq = 1)
        val sourceAssistant = assistantMessage(
            id = "a-old",
            conversationId = "c-old",
            text = "hello",
            seq = 1,
            parentId = "u-old",
            parts = listOf(
                MessagePart.Text("hello"),
                MessagePart.Image(AttachmentId("at-old")),
            ),
        )
        exportPort.importedBundle = ImportedBundle(
            conversations = listOf(sourceConversation),
            messages = listOf(sourceUser, sourceAssistant),
            attachments = listOf(attachment("at-old", AttachmentKind.IMAGE)),
        )

        val imported = service.importConversations("content://source").requireOk()

        assertEquals(1, imported)
        val storedConversation: Conversation = conversations.state.value.values.single()
        assertNotEquals(sourceConversation.id, storedConversation.id)
        assertEquals("imported", storedConversation.title)

        val storedMessages: List<Message> = messages.state.value.values.toList()
        assertEquals(2, storedMessages.size)
        val newUser = storedMessages.first { it.role == com.deepseek.mobilechat.domain.MessageRole.USER }
        val newAssistant = storedMessages.first { it.role == com.deepseek.mobilechat.domain.MessageRole.ASSISTANT }
        assertNotEquals(sourceUser.id, newUser.id)
        assertNotEquals(sourceAssistant.id, newAssistant.id)
        assertEquals(storedConversation.id, newUser.conversationId)
        assertEquals(newUser.id, newAssistant.parentId)
        assertEquals(newAssistant.id, storedConversation.activeLeafId)

        val image = newAssistant.parts.filterIsInstance<MessagePart.Image>().single()
        assertNotEquals(AttachmentId("at-old"), image.attachmentId)
    }

    @Test
    fun `import of an empty bundle reports zero`() = runTest {
        exportPort.importedBundle = ImportedBundle(emptyList(), emptyList(), emptyList())

        assertEquals(0, service.importConversations("content://empty").requireOk())
    }

    @Test
    fun `import drops messages whose conversation is missing`() = runTest {
        exportPort.importedBundle = ImportedBundle(
            conversations = emptyList(),
            messages = listOf(userMessage("u-old", conversationId = "c-old", seq = 1)),
            attachments = emptyList(),
        )

        assertEquals(0, service.importConversations("content://x").requireOk())
        assertTrue(messages.state.value.isEmpty())
    }

    @Test
    fun `import restores attachment bytes and rewires organisation ids`() = runTest {
        val sourceConversation = conversation("c-old", title = "trip", activeLeafId = "u-old")
            .copy(
                folderId = FolderId("f-old"),
                tagIds = setOf(TagId("t-old")),
                overrides = ConversationOverrides(presetId = PresetId("p-old")),
            )
        val sourceUser = userMessage(
            id = "u-old",
            conversationId = "c-old",
            text = "photo",
            seq = 1,
            parts = listOf(
                MessagePart.Text("photo"),
                MessagePart.Image(AttachmentId("at-old")),
            ),
        )
        exportPort.importedBundle = ImportedBundle(
            conversations = listOf(sourceConversation),
            messages = listOf(sourceUser),
            attachments = listOf(attachment("at-old", AttachmentKind.IMAGE)),
            attachmentBytes = mapOf(AttachmentId("at-old") to byteArrayOf(1, 2, 3)),
            presets = listOf(preset("p-old", isDefault = true)),
            folders = listOf(Folder(FolderId("f-old"), "work")),
            tags = listOf(Tag(TagId("t-old"), "idea")),
        )

        service.importConversations("content://source").requireOk()

        val storedConversation = conversations.state.value.values.single()
        val storedFolder = folderTags.folders.value.single()
        val storedTag = folderTags.tags.value.single()
        val storedPreset = presets.state.value.single()
        assertNotEquals(FolderId("f-old"), storedFolder.id)
        assertNotEquals(TagId("t-old"), storedTag.id)
        assertNotEquals(PresetId("p-old"), storedPreset.id)
        assertEquals(storedFolder.id, storedConversation.folderId)
        assertEquals(setOf(storedTag.id), storedConversation.tagIds)
        assertEquals(storedPreset.id, storedConversation.overrides.presetId)
        assertTrue("imported presets must not become the default", !storedPreset.isDefault)

        val storedMessage = messages.state.value.values.single()
        val image = storedMessage.parts.filterIsInstance<MessagePart.Image>().single()
        val restored = attachments.get(image.attachmentId)
        assertNotEquals(AttachmentId("at-old"), image.attachmentId)
        assertNotNull(restored)
        assertEquals(3, restored!!.sizeBytes)
    }

    @Test
    fun `import keeps a synthetic attachment id when the bundle carries no bytes`() = runTest {
        val sourceUser = userMessage(
            id = "u-old",
            conversationId = "c-old",
            seq = 1,
            parts = listOf(
                MessagePart.Text("doc"),
                MessagePart.FileRef(AttachmentId("at-old")),
            ),
        )
        exportPort.importedBundle = ImportedBundle(
            conversations = listOf(conversation("c-old", activeLeafId = "u-old")),
            messages = listOf(sourceUser),
            attachments = listOf(attachment("at-old", AttachmentKind.TEXT)),
        )

        service.importConversations("content://source").requireOk()

        val storedMessage = messages.state.value.values.single()
        val fileRef = storedMessage.parts.filterIsInstance<MessagePart.FileRef>().single()
        assertNotEquals(AttachmentId("at-old"), fileRef.attachmentId)
        assertNull(attachments.get(fileRef.attachmentId))
    }
}
