package com.deepseek.mobilechat.platform.export

import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.MessageTree
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ThinkingLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportJsonTest {

    private val bundle = ExportFixtures.bundle()

    @Test
    fun `round trip preserves tree shape branch and ids are all new`() {
        val json = ExportJson.encode(bundle, ExportFixtures.attachmentBytes(), ExportFixtures.T0)
        assertTrue(json.contains("\n  \"schemaVersion\": 1"))
        assertTrue(json.contains("\"includeAttachmentBytes\": true"))

        val decoded = decode(json)
        assertEquals(1, decoded.conversations.size)
        assertEquals(4, decoded.messages.size)

        val conversation = decoded.conversations.single()
        assertNotEquals("conv-1", conversation.id.raw)
        assertEquals("Branching", conversation.title)
        assertTrue(conversation.pinned)
        assertTrue(decoded.messages.all { it.conversationId == conversation.id })
        assertTrue(decoded.messages.none { it.id.raw == "m-1" || it.id.raw == "m-2" })

        val tree = MessageTree.from(decoded.messages)
        assertTrue(tree.validate(conversation.id).isEmpty())

        val path = tree.pathTo(conversation.activeLeafId)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), path.map { it.role })
        assertEquals("Hello \"world\"\nline 2 ✓ 🚀", path[0].text)
        assertEquals("Second answer", path[1].text)

        // The inactive branch still exists and hangs off the first answer.
        val firstAnswer = decoded.messages.first { it.text == "First answer" }
        val abandoned = decoded.messages.first { it.text == "Inactive branch answer" }
        assertEquals(path[1].parentId, path[0].id)
        assertEquals(path[0].id, firstAnswer.parentId)
        assertEquals(firstAnswer.id, abandoned.parentId)
        assertEquals(2, tree.childrenOf(path[0].id).size)
        assertTrue(tree.isLeaf(abandoned.id))
        assertEquals("cancelled by user", abandoned.error)

        // Conversation metadata is remapped consistently with the decoded entities.
        assertEquals(decoded.folders.single().id, conversation.folderId)
        assertEquals(decoded.tags.map { it.id }.toSet(), conversation.tagIds)
        assertEquals(decoded.presets.single().id, conversation.overrides.presetId)

        val modelRef = conversation.overrides.modelRef
        assertEquals(ModelRef(ProviderId("provider-1"), ModelId("model-a")), modelRef)
        assertEquals(ThinkingLevel.MAX, conversation.overrides.thinkingLevel)
        assertEquals("You are terse.", conversation.overrides.systemPrompt)
        assertEquals(0.7, conversation.overrides.params?.temperature ?: -1.0, 0.0001)
        assertEquals(listOf("END"), conversation.overrides.params?.stop)

        assertEquals(1, decoded.folders.size)
        assertEquals(2, decoded.tags.size)
        assertEquals(1, decoded.presets.size)
        assertEquals("/data/attachments/notes.txt", decoded.attachments.first { it.fileName == "notes.txt" }.localPath)

        val image = decoded.attachments.first { it.fileName == "diagram.png" }
        assertEquals("image/png", image.mimeType)
        assertEquals(com.deepseek.mobilechat.domain.AttachmentKind.IMAGE, image.kind)
        assertEquals(640, image.width)
        assertEquals(480, image.height)
        assertEquals("sha-image", image.sha256)
        assertEquals(3L, image.sizeBytes)
        assertEquals(ExportFixtures.T0 + 10, image.createdAt)

        val note = decoded.attachments.first { it.fileName == "notes.txt" }
        assertEquals("hello", note.extractedText)
        assertNull(note.width)
        assertNull(note.height)

        assertEquals(2, decoded.folders.single().order)
        assertEquals(0xFFFF0000, decoded.tags.first { it.name == "important" }.color)

        val preset = decoded.presets.single()
        assertEquals("Terse", preset.name)
        assertEquals("Be terse.", preset.systemPrompt)
        assertEquals(com.deepseek.mobilechat.domain.ContextStrategyIds.DROP_OLDEST, preset.contextStrategyId)
        assertEquals(ThinkingLevel.HIGH, preset.thinkingLevel)
        assertEquals(listOf("stop-1", "stop-2"), preset.params.stop)
        assertTrue(preset.isDefault)
    }

    @Test
    fun `round trip preserves every message part variant and model metadata`() {
        val decoded = decode(ExportJson.encode(bundle, ExportFixtures.attachmentBytes(), ExportFixtures.T0))
        val exportedFirstAnswer = bundle.messages.first { it.id.raw == "m-2" }
        val decodedFirstAnswer = decoded.messages.first { it.text == "First answer" }

        val attachmentIds = bundle.attachments.associate { old ->
            old.id to decoded.attachments.first { it.fileName == old.fileName }.id
        }
        val expectedParts = exportedFirstAnswer.parts.map { part ->
            when (part) {
                is MessagePart.Image -> part.copy(attachmentId = attachmentIds.getValue(part.attachmentId))
                is MessagePart.FileRef -> part.copy(attachmentId = attachmentIds.getValue(part.attachmentId))
                else -> part
            }
        }
        assertEquals(expectedParts, decodedFirstAnswer.parts)

        val reasoning = decodedFirstAnswer.parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertEquals("Deliberately verbose reasoning", reasoning.text)
        assertEquals("sig-abc", reasoning.signature)

        val secondAnswer = decoded.messages.first { it.text == "Second answer" }
        assertNull(secondAnswer.parts.filterIsInstance<MessagePart.Reasoning>().single().signature)

        assertEquals(exportedFirstAnswer.modelInfo, decodedFirstAnswer.modelInfo)
        assertEquals(exportedFirstAnswer.usage, decodedFirstAnswer.usage)
        assertEquals(exportedFirstAnswer.finishReason, decodedFirstAnswer.finishReason)
        assertEquals(exportedFirstAnswer.seq, decodedFirstAnswer.seq)
        assertEquals(exportedFirstAnswer.editedAt, decodedFirstAnswer.editedAt)
        assertEquals(exportedFirstAnswer.createdAt, decodedFirstAnswer.createdAt)
        assertEquals(exportedFirstAnswer.pinned, decodedFirstAnswer.pinned)
    }

    @Test
    fun `attachment bytes toggle embeds base64 only when requested`() {
        val withBytes = ExportJson.encode(
            bundle.copy(includeAttachmentBytes = true),
            ExportFixtures.attachmentBytes(),
            ExportFixtures.T0,
        )
        val withoutBytes = ExportJson.encode(
            bundle.copy(includeAttachmentBytes = false),
            emptyMap(),
            ExportFixtures.T0,
        )

        assertEquals(2, withBytes.countOccurrences("\"bytesBase64\": \""))
        assertFalse(withoutBytes.contains("\"bytesBase64\": \""))

        val decodedWith = decode(withBytes)
        assertEquals(2, decodedWith.attachmentBytes.size)
        val image = decodedWith.attachments.first { it.fileName == "diagram.png" }
        assertArrayEquals(byteArrayOf(1, 2, 3), decodedWith.attachmentBytes.getValue(image.id))
        val note = decodedWith.attachments.first { it.fileName == "notes.txt" }
        assertArrayEquals("hello".toByteArray(Charsets.UTF_8), decodedWith.attachmentBytes.getValue(note.id))

        val decodedWithout = decode(withoutBytes)
        assertTrue(decodedWithout.attachmentBytes.isEmpty())
        assertEquals(2, decodedWithout.attachments.size)
    }

    @Test
    fun `decode rejects unsupported schema version`() {
        val error = assertThrows(JsonFormatException::class.java) {
            ExportJson.decode(
                """{"schemaVersion": 2, "conversations": [], "messages": [], "attachments": []}""",
            ) { "new-id" }
        }
        assertTrue(error.message.orEmpty().contains("schemaVersion"))
    }

    @Test
    fun `decode rejects malformed json`() {
        assertThrows(JsonFormatException::class.java) {
            ExportJson.decode("""{"schemaVersion": 1, "conversations": [""") { "new-id" }
        }
    }

    @Test
    fun `imported bundle carries attachment bytes under remapped ids`() {
        val decoded = decode(ExportJson.encode(bundle, ExportFixtures.attachmentBytes(), ExportFixtures.T0))
        val imported = decoded.toImportedBundle()

        assertEquals(2, imported.attachmentBytes.size)
        assertEquals(
            imported.attachments.map { it.id }.toSet(),
            imported.attachmentBytes.keys,
        )
        assertTrue(imported.attachmentBytes.keys.none { it.raw == "att-1" || it.raw == "att-2" })

        val image = imported.attachments.first { it.fileName == "diagram.png" }
        assertArrayEquals(byteArrayOf(1, 2, 3), imported.attachmentBytes.getValue(image.id))
        val note = imported.attachments.first { it.fileName == "notes.txt" }
        assertArrayEquals("hello".toByteArray(Charsets.UTF_8), imported.attachmentBytes.getValue(note.id))

        // The same export path with the toggle off yields no payload at all.
        val withoutBytes = decode(
            ExportJson.encode(
                bundle.copy(includeAttachmentBytes = false),
                emptyMap(),
                ExportFixtures.T0,
            ),
        ).toImportedBundle()
        assertTrue(withoutBytes.attachmentBytes.isEmpty())
    }

    @Test
    fun `imported bundle carries folders tags presets and resolves references`() {
        val decoded = decode(ExportJson.encode(bundle, emptyMap(), ExportFixtures.T0))
        val imported = decoded.toImportedBundle()
        val conversation = imported.conversations.single()

        assertEquals(1, imported.folders.size)
        assertEquals(2, imported.tags.size)
        assertEquals(1, imported.presets.size)

        assertNotNull(conversation.folderId)
        assertEquals(conversation.folderId, imported.folders.single().id)
        assertEquals("Work", imported.folders.single().name)

        assertTrue(conversation.tagIds.isNotEmpty())
        assertTrue(conversation.tagIds.all { tagId -> imported.tags.any { it.id == tagId } })
        assertEquals(setOf("important", "draft"), imported.tags.map { it.name }.toSet())

        assertNotNull(conversation.overrides.presetId)
        assertEquals(conversation.overrides.presetId, imported.presets.single().id)
        assertEquals("Terse", imported.presets.single().name)
        assertEquals("Branching", conversation.title)
    }

    @Test
    fun `decoded messages are ordered parent before child`() {
        val parent = bundle.messages.first { it.id.raw == "m-1" }.copy(seq = 50)
        val child = bundle.messages.first { it.id.raw == "m-2" }.copy(seq = 1)
        val grandChild = bundle.messages.first { it.id.raw == "m-4" }.copy(seq = 1)
        val unsorted = bundle.copy(
            messages = listOf(
                child,
                grandChild,
                parent,
                bundle.messages.first { it.id.raw == "m-3" },
            ),
        )

        val decoded = decode(ExportJson.encode(unsorted, emptyMap(), ExportFixtures.T0))
        assertParentsPrecedeChildren(decoded.messages)
        // ImportedBundle must preserve that property: it inserts rows one by one.
        assertParentsPrecedeChildren(decoded.toImportedBundle().messages)
    }

    private fun assertParentsPrecedeChildren(messages: List<com.deepseek.mobilechat.domain.Message>) {
        val positions = messages.withIndex().associate { (index, message) -> message.id.raw to index }
        messages.forEach { message ->
            val parentId = message.parentId ?: return@forEach
            assertTrue(
                "parent ${parentId.raw} must precede ${message.id.raw}",
                positions.getValue(parentId.raw) < positions.getValue(message.id.raw),
            )
        }
    }

    @Test
    fun `64-bit timestamps round trip without double precision loss`() {
        val twoTo53PlusOne = 9_007_199_254_740_993L
        val message = bundle.messages.first().copy(
            createdAt = twoTo53PlusOne,
            editedAt = Long.MAX_VALUE,
        )
        val json = ExportJson.encode(
            bundle.copy(messages = listOf(message)),
            emptyMap(),
            twoTo53PlusOne,
        )
        val decoded = decode(json).messages.single()
        assertEquals(twoTo53PlusOne, decoded.createdAt)
        assertEquals(Long.MAX_VALUE, decoded.editedAt)
    }

    @Test
    fun `json strings escape quotes newlines and emoji`() {
        val original = "quote \" backslash \\ newline \n tab \t control \u0001 emoji 🚀"
        val written = Json.write(JsonValue.Str(original))
        val parsed = Json.parse(written) as JsonValue.Str
        assertEquals(original, parsed.value)
    }

    private fun decode(json: String): DecodedExport {
        var counter = 0
        return ExportJson.decode(json) { "new-${++counter}" }
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}
