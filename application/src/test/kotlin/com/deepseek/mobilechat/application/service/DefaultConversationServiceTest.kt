package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.service.testutil.FakeAttachmentStore
import com.deepseek.mobilechat.application.service.testutil.FakeChatCompletion
import com.deepseek.mobilechat.application.service.testutil.FakeClock
import com.deepseek.mobilechat.application.service.testutil.FakeCompactionStore
import com.deepseek.mobilechat.application.service.testutil.FakeConversationStore
import com.deepseek.mobilechat.application.service.testutil.FakeIdPort
import com.deepseek.mobilechat.application.service.testutil.FakeInjectionStore
import com.deepseek.mobilechat.application.service.testutil.FakeMessageStore
import com.deepseek.mobilechat.application.service.testutil.FakePresetStore
import com.deepseek.mobilechat.application.service.testutil.FakeProviderProfileStore
import com.deepseek.mobilechat.application.service.testutil.assistantMessage
import com.deepseek.mobilechat.application.service.testutil.attachment
import com.deepseek.mobilechat.application.service.testutil.conversation
import com.deepseek.mobilechat.application.service.testutil.preset
import com.deepseek.mobilechat.application.service.testutil.providerProfile
import com.deepseek.mobilechat.application.service.testutil.requireErr
import com.deepseek.mobilechat.application.service.testutil.requireOk
import com.deepseek.mobilechat.application.service.testutil.userMessage
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.Compaction
import com.deepseek.mobilechat.domain.CompactionId
import com.deepseek.mobilechat.domain.CompactionKind
import com.deepseek.mobilechat.domain.ContextInjection
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.FolderTagLimits
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.InjectionId
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.TagId
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultConversationServiceTest {

    private val conversations = FakeConversationStore()
    private val messages = FakeMessageStore()
    private val injections = FakeInjectionStore()
    private val compactions = FakeCompactionStore()
    private val attachments = FakeAttachmentStore()
    private val presets = FakePresetStore()
    private val profiles = FakeProviderProfileStore()
    private val chat = FakeChatCompletion()
    private val clock = FakeClock()
    private val ids = FakeIdPort("conv")

    private val service = DefaultConversationService(
        conversationStore = conversations,
        messageStore = messages,
        injectionStore = injections,
        compactionStore = compactions,
        attachmentStore = attachments,
        presetStore = presets,
        providerProfileStore = profiles,
        chatCompletion = chat,
        clock = clock,
        idPort = ids,
    )

    @Test
    fun `create defaults to the neutral title`() = runTest {
        val created = service.create("   ").requireOk()

        assertEquals("新对话", created.title)
        assertNull(created.activeLeafId)
        assertEquals(1, conversations.state.value.size)
    }

    @Test
    fun `rename trims and rejects an empty title`() = runTest {
        val created = service.create("old").requireOk()

        service.rename(created.id, "  new title  ").requireOk()
        assertEquals("new title", conversations.get(created.id)!!.title)

        val error = service.rename(created.id, "   ").requireErr()
        assertTrue(error is AppError.Validation)
        assertEquals("new title", conversations.get(created.id)!!.title)
    }

    @Test
    fun `delete cascades through messages injections compactions and attachments`() = runTest {
        val created = service.create("chat").requireOk()
        conversations.upsert(created.copy(activeLeafId = MessageId("a1")))
        messages.put(
            userMessage("u1", conversationId = created.id.raw, seq = 1),
            assistantMessage("a1", conversationId = created.id.raw, seq = 1, parentId = "u1"),
        )
        injections.add(
            ContextInjection(
                id = InjectionId("i1"),
                conversationId = created.id,
                anchorMessageId = null,
                scope = InjectionScope.PERSISTENT,
                position = 0,
                content = "injected",
                createdAt = 0L,
            ),
        )
        compactions.add(
            Compaction(
                id = CompactionId("cp1"),
                conversationId = created.id,
                fromMessageId = MessageId("u1"),
                toMessageId = MessageId("a1"),
                summary = "s",
                kind = CompactionKind.SUMMARY,
                createdAt = 0L,
            ),
        )
        attachments.put(attachment("at1", AttachmentKind.IMAGE))

        service.delete(created.id).requireOk()

        assertNull(conversations.get(created.id))
        assertTrue(messages.list(created.id).isEmpty())
        assertTrue(injections.state.value.isEmpty())
        assertTrue(compactions.state.value.all { it.undoneAt != null })
        assertNull(attachments.get(AttachmentId("at1")))
    }

    @Test
    fun `delete reports a missing conversation`() = runTest {
        val error = service.delete(ConversationId("ghost")).requireErr()
        assertTrue(error is AppError.Validation)
    }

    @Test
    fun `setTags rejects more than the documented maximum`() = runTest {
        val created = service.create("chat").requireOk()
        val tooMany = (0..FolderTagLimits.MAX_TAGS_PER_CONVERSATION)
            .map { TagId("t$it") }
            .toSet()

        val error = service.setTags(created.id, tooMany).requireErr()

        assertTrue(error is AppError.Validation)
        assertTrue(conversations.get(created.id)!!.tagIds.isEmpty())
    }

    @Test
    fun `applyPreset binds the preset and clears explicit model overrides`() = runTest {
        val created = service.create("chat").requireOk()
        conversations.upsert(
            created.copy(
                overrides = ConversationOverrides(
                    presetId = PresetId("old"),
                    modelRef = ModelRef(ProviderId("p"), ModelId("m")),
                    params = SamplingParams(temperature = 0.9),
                    thinkingLevel = ThinkingLevel.MAX,
                    systemPrompt = "custom",
                ),
            ),
        )

        service.applyPreset(created.id, PresetId("new")).requireOk()

        val overrides = conversations.get(created.id)!!.overrides
        assertEquals(PresetId("new"), overrides.presetId)
        assertNull(overrides.modelRef)
        assertNull(overrides.params)
        assertNull(overrides.thinkingLevel)
        assertNull(overrides.systemPrompt)
    }

    @Test
    fun `ensureTitle uses the model answer`() = runTest {
        val created = service.create("新对话").requireOk()
        messages.put(userMessage("u1", conversationId = created.id.raw, text = "带我去日本旅行", seq = 1))
        profiles.put(providerProfile())
        chat.events = listOf(
            GenerationEvent.TextDelta("「日本旅行」\n"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        service.ensureTitle(created.id).requireOk()

        assertEquals("日本旅行", conversations.get(created.id)!!.title)
        assertEquals(1, chat.requests.size)
    }

    @Test
    fun `ensureTitle degrades to the first sixteen characters when the model fails`() = runTest {
        val created = service.create("新对话").requireOk()
        val text = "  请帮我规划一次去日本的旅行，时间是一周  "
        messages.put(userMessage("u1", conversationId = created.id.raw, text = text, seq = 1))
        profiles.put(providerProfile())
        chat.events = listOf(GenerationEvent.Failed(AppError.ProviderUnavailable("down")))

        service.ensureTitle(created.id).requireOk()

        assertEquals(text.trim().take(16), conversations.get(created.id)!!.title)
    }

    @Test
    fun `ensureTitle never throws when the provider blows up`() = runTest {
        val created = service.create("").requireOk()
        val text = "another long first message about databases"
        messages.put(userMessage("u1", conversationId = created.id.raw, text = text, seq = 1))
        profiles.put(providerProfile())
        chat.throwOnStream = IllegalStateException("network exploded")

        service.ensureTitle(created.id).requireOk()

        assertEquals(text.take(16), conversations.get(created.id)!!.title)
    }

    @Test
    fun `ensureTitle leaves a real title alone`() = runTest {
        val created = service.create("我的旅行").requireOk()
        messages.put(userMessage("u1", conversationId = created.id.raw, text = "hi", seq = 1))
        profiles.put(providerProfile())

        service.ensureTitle(created.id).requireOk()

        assertEquals("我的旅行", conversations.get(created.id)!!.title)
        assertTrue(chat.requests.isEmpty())
    }

    @Test
    fun `ensureTitle upgrades the provisional title once the model can answer`() = runTest {
        val text = "请帮我规划一次去日本的旅行"
        val created = service.create(text.take(20)).requireOk()
        messages.put(userMessage("u1", conversationId = created.id.raw, text = text, seq = 1))
        presets.put(preset("p1", isDefault = true, modelRef = ModelRef(ProviderId("provider-1"), ModelId("model-1"))))
        chat.events = listOf(
            GenerationEvent.TextDelta("日本旅行规划"),
            GenerationEvent.Finished(FinishReason.STOP),
        )

        service.ensureTitle(created.id).requireOk()

        assertEquals("日本旅行规划", conversations.get(created.id)!!.title)
    }

    @Test
    fun `search delegates to the store`() = runTest {
        conversations.put(conversation("c1", title = "kotlin notes"))
        conversations.put(conversation("c2", title = "gardening"))

        val hits = service.search("kotlin").first()

        assertEquals(listOf(ConversationId("c1")), hits.map { it.id })
    }

    @Test
    fun `moveToFolder and setModelOverride persist the fields`() = runTest {
        val created = service.create("chat").requireOk()

        service.moveToFolder(created.id, FolderId("f1")).requireOk()
        service.setModelOverride(
            conversationId = created.id,
            modelRef = ModelRef(ProviderId("provider-1"), ModelId("model-2")),
            params = SamplingParams(temperature = 0.2),
            thinkingLevel = ThinkingLevel.LOW,
        ).requireOk()

        val stored = conversations.get(created.id)!!
        assertEquals("f1", stored.folderId!!.raw)
        assertEquals(ModelId("model-2"), stored.overrides.modelRef!!.modelId)
        assertEquals(0.2, stored.overrides.params!!.temperature!!, 1e-9)
        assertEquals(ThinkingLevel.LOW, stored.overrides.thinkingLevel)
        assertNotNull(stored.overrides)
    }
}
