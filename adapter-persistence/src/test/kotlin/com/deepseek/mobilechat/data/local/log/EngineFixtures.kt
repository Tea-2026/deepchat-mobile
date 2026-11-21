package com.deepseek.mobilechat.data.local.log

import com.deepseek.mobilechat.application.AppError
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
import com.deepseek.mobilechat.domain.FinishReason
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Fakes for the engine-on-real-store tests: everything below the network socket is
 * production code (`TestEnv` wires the real SQLite stores), and only the three
 * ports that would leave the process are substituted.
 */

internal class ScriptedCompletion(
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
        emit(
            GenerationEvent.UsageReport(
                com.deepseek.mobilechat.domain.Usage(promptTokens = 10, completionTokens = 5),
            ),
        )
        emit(GenerationEvent.Finished(FinishReason.STOP))
    }
}

internal class FakeSettings : SettingsStore {
    private val state = MutableStateFlow(AppSettings(streamingCheckpointMillis = 0L))

    override fun observe(): Flow<AppSettings> = state

    override suspend fun update(settings: AppSettings) {
        state.value = settings
    }
}

internal class FakeProviders(private val profile: ProviderProfile) : ProviderProfileStore {
    override fun observeAll(): Flow<List<ProviderProfile>> = flow { emit(listOf(profile)) }

    override suspend fun get(id: ProviderId): ProviderProfile? = profile.takeIf { it.id == id }

    override suspend fun upsert(profile: ProviderProfile) = Unit

    override suspend fun delete(id: ProviderId) = Unit
}

internal class FakePresets(private val preset: Preset) : PresetStore {
    override fun observeAll(): Flow<List<Preset>> = flow { emit(listOf(preset)) }

    override suspend fun get(id: PresetId): Preset? = preset.takeIf { it.id == id }

    override suspend fun upsert(preset: Preset) = Unit

    override suspend fun delete(id: PresetId) = Unit
}

internal object NoSearch : SearchPort {
    override suspend fun isConfigured(): Boolean = false

    override suspend fun search(query: String, options: SearchOptions): Outcome<SearchResponse> =
        Outcome.Err(AppError.Unsupported("search is not part of this test"))
}

/** The production gateway over the real stores from [env]. */
internal fun engineGateway(
    env: TestEnv,
    completion: ChatCompletionPort,
    reconcileOnStart: Boolean = false,
): ChatGateway {
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
        reconcileOnStart = reconcileOnStart,
    )
}
