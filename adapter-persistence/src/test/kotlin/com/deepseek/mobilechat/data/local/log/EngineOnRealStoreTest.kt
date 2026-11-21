package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.application.AppSettings
import com.deepseek.mobilechat.application.ChatCompletionPort
import com.deepseek.mobilechat.application.GenerationEvent
import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PresetStore
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.application.SearchOptions
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.application.SettingsStore
import com.deepseek.mobilechat.application.log.ChatGateway
import com.deepseek.mobilechat.application.log.DefaultChatGateway
import com.deepseek.mobilechat.data.local.TestEnv
import com.deepseek.mobilechat.data.local.conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.ThinkingLevel
import com.deepseek.mobilechat.domain.log.EventType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam that the unit tests cannot cover: the real `DefaultChatGateway` on top
 * of the real SQLite log, projections and draft store.
 *
 * Everything below the network socket is production code; only the completion
 * port, the settings store and the search port are fakes (port boundaries).
 */
class EngineOnRealStoreTest {

    private val env = TestEnv()
    private val conversationId = ConversationId("c-e2e")

    @After
    fun tearDown() = env.close()

    // ---- fakes at port boundaries ----------------------------------------

    private class ScriptedCompletion(
        private val reply: List<String>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ChatCompletionPort {
        val requests = mutableListOf<GenerationRequest>()

        override fun stream(request: GenerationRequest): Flow<GenerationEvent> = flow {
            requests += request
            emit(GenerationEvent.Started(0L))
            reply.forEachIndexed { index, chunk ->
                emit(GenerationEvent.TextDelta(chunk))
                if (index == 0 && gate != null) gate.await()
            }
            emit(GenerationEvent.UsageReport(com.deepseek.mobilechat.domain.Usage(promptTokens = 10, completionTokens = 5)))
            emit(GenerationEvent.Finished(FinishReason.STOP))
        }
    }

    private class FakeSettings : SettingsStore {
        private val state = MutableStateFlow(AppSettings(streamingCheckpointMillis = 0L))
        override fun observe(): Flow<AppSettings> = state
        override suspend fun update(settings: AppSettings) {
            state.value = settings
        }
    }

    private class FakeProviders(private val profile: ProviderProfile) : ProviderProfileStore {
        override fun observeAll(): Flow<List<ProviderProfile>> = flow { emit(listOf(profile)) }
        override suspend fun get(id: ProviderId): ProviderProfile? = profile.takeIf { it.id == id }
        override suspend fun upsert(profile: ProviderProfile) = Unit
        override suspend fun delete(id: ProviderId) = Unit
    }

    private class FakePresets(private val preset: Preset) : PresetStore {
        override fun observeAll(): Flow<List<Preset>> = flow { emit(listOf(preset)) }
        override suspend fun get(id: PresetId): Preset? = preset.takeIf { it.id == id }
        override suspend fun upsert(preset: Preset) = Unit
        override suspend fun delete(id: PresetId) = Unit
    }

    private object NoSearch : SearchPort {
        override suspend fun isConfigured(): Boolean = false
        override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
            Outcome.Err(com.deepseek.mobilechat.application.AppError.Unsupported("no search in this test"))
    }

    private fun gateway(completion: ChatCompletionPort): ChatGateway {
        val provider = ProviderProfile(
            id = ProviderId("provider-1"),
            name = "Test",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://example.test/v1",
            secretRef = "provider.provider-1",
            defaultModelId = ModelId("model-1"),
            createdAt = 1L,
        )
        val preset = Preset(
            id = PresetId("preset-1"),
            name = "Default",
            systemPrompt = "你是测试助手。",
            modelRef = ModelRef(ProviderId("provider-1"), ModelId("model-1")),
            thinkingLevel = ThinkingLevel.OFF,
            isDefault = true,
        )
        return DefaultChatGateway(
            logStore = env.log,
            draftStore = env.drafts,
            conversationStore = env.conversations,
            presetStore = FakePresets(preset),
            providerProfileStore = FakeProviders(provider),
            attachmentStore = env.attachments,
            chatCompletion = completion,
            searchPort = NoSearch,
            settingsStore = FakeSettings(),
            clock = env.clock,
            idPort = env.ids,
            reconcileOnStart = false,
        )
    }

    private suspend fun seedConversation() {
        env.conversations.upsert(conversation(conversationId.raw))
    }

    // ---- tests ------------------------------------------------------------

    @Test
    fun `a real send writes one atomic turn and the snapshot shows it`() = runBlocking {
        seedConversation()
        val completion = ScriptedCompletion(listOf("你好", "，世界"))
        val gateway = gateway(completion)

        val sent = gateway.send(conversationId, "冒烟测试")
        assertTrue("send should succeed: $sent", sent is Outcome.Ok)

        // Wait for the *settled* turn: a snapshot with two rows can already be
        // the mid-stream one (user + synthetic streaming row).
        val snapshot = withTimeout(15_000) {
            gateway.snapshots(conversationId).first { candidate ->
                candidate.overlay.streaming == null &&
                    candidate.messages.any { it.role == MessageRole.ASSISTANT && it.text == "你好，世界" }
            }
        }

        val user = snapshot.messages.first { it.id == snapshot.messages.first().id }
        assertEquals("冒烟测试", user.text)
        val assistant = snapshot.messages.last()
        assertEquals("你好，世界", assistant.text)
        assertNull("overlay must be settled once the turn completes", snapshot.overlay.streaming)
        assertNull("no command may stay busy", snapshot.overlay.busy)

        // The log really is the source: fold it and look at the surface.
        val log = env.log.log(conversationId)
        val types = log.events.map { it.type }
        assertTrue(types.contains(EventType.USER_MESSAGE))
        assertTrue(types.contains(EventType.TURN_START))
        assertTrue(types.contains(EventType.ASSISTANT_MESSAGE))
        assertTrue(types.contains(EventType.TURN_END))
        assertTrue(types.contains(EventType.LEAF_CHANGED))
        assertEquals("every seq is distinct", log.events.size, log.events.map { it.seq }.distinct().size)
        assertEquals("seqs are 1..n", log.events.size.toLong(), log.events.last().seq)

        val surface = com.deepseek.mobilechat.domain.log.SurfaceFolder.fold(log)
        assertTrue("surface must contain the answer", surface.nodes.size >= 2)

        // One request, and it carried the conversation, not a synthetic history.
        assertEquals(1, completion.requests.size)
        assertTrue(completion.requests.single().messages.isNotEmpty())
    }

    @Test
    fun `regenerate keeps the previous answer on the active path until the new one lands`() = runBlocking {
        seedConversation()
        // First turn completes immediately.
        val first = gateway(ScriptedCompletion(listOf("旧回答")))
        assertTrue(first.send(conversationId, "问题") is Outcome.Ok)
        val settled = withTimeout(15_000) {
            first.snapshots(conversationId).first { candidate ->
                candidate.overlay.streaming == null &&
                    candidate.messages.any { it.role == MessageRole.ASSISTANT && it.text == "旧回答" }
            }
        }
        val oldAnswer = settled.messages.last()
        assertEquals("旧回答", oldAnswer.text)

        // Second turn stops after the first chunk so we can look mid-flight.
        val gate = CompletableDeferred<Unit>()
        val slow = gateway(ScriptedCompletion(listOf("新", "回答"), gate))
        assertTrue(slow.regenerate(oldAnswer.id) is Outcome.Ok)

        val midFlight = withTimeout(15_000) {
            slow.snapshots(conversationId).first { it.overlay.streaming != null }
        }
        assertNotNull("old answer must still be visible while regenerating", midFlight.messages.firstOrNull { it.id == oldAnswer.id })
        assertEquals(
            "the active path should follow the streaming reply",
            midFlight.overlay.streaming?.messageId,
            midFlight.activeLeafId,
        )
        assertTrue("streaming text should be in the row", midFlight.messages.any { it.status == com.deepseek.mobilechat.domain.MessageStatus.STREAMING })

        gate.complete(Unit)

        val done = withTimeout(15_000) {
            slow.snapshots(conversationId).first { snapshot ->
                snapshot.overlay.streaming == null && snapshot.messages.any { it.text == "新回答" }
            }
        }
        assertNull(done.overlay.busy)
        assertTrue("both answers stay in history", done.messages.count { it.role == com.deepseek.mobilechat.domain.MessageRole.ASSISTANT } == 2)
        assertEquals("the new answer becomes the leaf", "新回答", done.messages.first { it.id == done.activeLeafId }.text)
    }
}
