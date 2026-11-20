package com.deepseek.mobilechat.data.remote

import com.deepseek.mobilechat.application.GenerationRequest
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.ProviderProfileStore
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.application.SecretStore
import com.deepseek.mobilechat.application.ToolSpec
import com.deepseek.mobilechat.data.remote.http.HttpCaller
import com.deepseek.mobilechat.data.remote.http.HttpResponse
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Port-boundary fakes only: the object under test is always the real adapter.

class FakeSecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {
    val values: MutableMap<String, String> = initial.toMutableMap()

    override suspend fun put(ref: String, value: String) {
        values[ref] = value
    }

    override suspend fun get(ref: String): String? = values[ref]

    override suspend fun delete(ref: String) {
        values.remove(ref)
    }
}

class FakeProviderProfileStore(vararg profiles: ProviderProfile) : ProviderProfileStore {
    val byId: MutableMap<ProviderId, ProviderProfile> = profiles.associateBy { it.id }.toMutableMap()

    override fun observeAll(): Flow<List<ProviderProfile>> = flowOf(byId.values.toList())

    override suspend fun get(id: ProviderId): ProviderProfile? = byId[id]

    override suspend fun upsert(profile: ProviderProfile) {
        byId[profile.id] = profile
    }

    override suspend fun delete(id: ProviderId) {
        byId.remove(id)
    }
}

/** Records every request and defers the response to [responder]. */
class RecordingHttpCaller(
    private val responder: (HttpCall) -> HttpResponse,
) : HttpCaller {

    data class HttpCall(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val calls: MutableList<HttpCall> = mutableListOf()

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse {
        val call = HttpCall(method, url, headers, body)
        calls += call
        return responder(call)
    }
}

fun sseResponse(text: String, status: Int = 200): HttpResponse = HttpResponse(
    status = status,
    headers = mapOf("content-type" to "text/event-stream"),
    bodyLines = text.lines().asSequence(),
)

fun testProfile(
    protocol: ProviderProtocol,
    baseUrl: String = "https://api.example.test",
    secretRef: String = "provider.test",
    id: String = "p1",
): ProviderProfile = ProviderProfile(
    id = ProviderId(id),
    name = "Test $protocol",
    protocol = protocol,
    baseUrl = baseUrl,
    secretRef = secretRef,
    defaultModelId = ModelId("test-model"),
    createdAt = 0L,
)

fun testRequest(
    providerId: String = "p1",
    modelId: String = "test-model",
    systemPrompt: String? = null,
    messages: List<PromptMessage> = listOf(
        PromptMessage(MessageRole.USER, listOf(MessagePart.Text("hi"))),
    ),
    params: SamplingParams = SamplingParams(),
    thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    tools: List<ToolSpec> = emptyList(),
): GenerationRequest = GenerationRequest(
    modelRef = ModelRef(ProviderId(providerId), ModelId(modelId)),
    systemPrompt = systemPrompt,
    messages = messages,
    params = params,
    thinkingLevel = thinkingLevel,
    tools = tools,
)
